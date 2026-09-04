<template>
  <el-drawer :model-value="modelValue" size="520px"
             @update:model-value="(v: boolean) => emit('update:modelValue', v)"
             @open="load">
    <template #header>
      <div class="ref-head">
        <b>临床资料引用</b>
        <span class="ref-sub">点条目「插入正文」把资料抄进病历；已签名病历不可再插入</span>
      </div>
    </template>

    <el-tabs :model-value="tab" @tab-change="onTabChange">
      <el-tab-pane label="基本资料" name="BASIC" />
      <el-tab-pane label="检验" name="LAB" />
      <el-tab-pane label="检查" name="EXAM" />
      <el-tab-pane label="历史病历" name="HISTORY" />
    </el-tabs>

    <el-alert v-if="error" type="warning" show-icon :closable="false" :title="error" style="margin-bottom: 8px" />

    <div v-loading="loading">
      <div v-if="seg" class="ref-bar">
        <span class="ref-count">共 {{ seg.count }} 条</span>
        <el-button size="small" type="primary" :disabled="disabled || !seg.count"
                   @click="insert(String(seg.snippet))">
          全部插入正文
        </el-button>
      </div>

      <el-alert v-if="seg && seg.truncated" type="info" show-icon :closable="false" style="margin-bottom: 8px"
                :title="`命中超过 ${seg.limit} 条，仅显示前 ${seg.limit} 条——请从更近的资料里挑，本抽屉不提供翻页`" />

      <!-- 诚实边界：住院医嘱的检验检查在本平台尚无结果落地表，这里只能引到门诊侧的结果 -->
      <el-alert v-if="(tab === 'LAB' || tab === 'EXAM') && !!admissionId" type="info" show-icon :closable="false"
                style="margin-bottom: 8px"
                title="本平台的检验结果与检查报告只挂在门诊医嘱上，此处引用到的是该患者门诊侧的结果；住院医嘱开出的检验检查尚无结果落地表，引用不到。" />

      <el-empty v-if="seg && !seg.count" :image-size="60" :description="emptyText" />

      <div v-for="it in items" :key="String(it.refId)" class="ref-row">
        <div class="ref-row-head">
          <span class="ref-title">{{ it.title }}</span>
          <el-tag v-if="it.abnormal" type="danger" size="small">异常</el-tag>
          <el-tag v-if="it.critical" type="danger" size="small">危急值</el-tag>
          <el-tag v-if="it.currentVisit" type="success" size="small">本次</el-tag>
          <el-button link type="primary" size="small" :disabled="disabled"
                     @click="insert(String(it.text))">插入正文</el-button>
        </div>
        <p class="ref-text">{{ it.text }}</p>
      </div>
    </div>
  </el-drawer>
</template>

<script lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../api/client'

/** 复制伴随状态：记下"这段文字是从哪个患者的病历里复制的"（只落本地，不上送服务端） */
interface CopySource {
  patientId: number | null
  patientName: string
  text: string
  at: number
}

const COPY_SRC_KEY = 'hip_emr_copy_src'
/** 伴随状态里存的原文上限：只用于比对来源，存全本没必要也会撑爆 localStorage */
const COPY_TEXT_MAX = 20000

function readSource(): CopySource | null {
  try {
    const raw = localStorage.getItem(COPY_SRC_KEY)
    return raw ? (JSON.parse(raw) as CopySource) : null
  } catch {
    return null   // 隐私模式/被清空：识别不了来源，按外部来源处理
  }
}

function writeSource(src: CopySource) {
  try {
    localStorage.setItem(COPY_SRC_KEY, JSON.stringify(src))
  } catch {
    /* 写不进去就退化成"识别不了来源"，不影响医生录入 */
  }
}

function norm(s: string) {
  return s.replace(/\s+/g, ' ').trim()
}

/** 粘贴内容与记录下的复制内容是否"同一段"（整段相等，或一方包含另一方且都够长） */
function sameContent(pasted: string, copied: string) {
  const a = norm(pasted)
  const b = norm(copied)
  if (!a || !b) return false
  if (a === b) return true
  return a.length >= 8 && b.length >= 8 && (a.includes(b) || b.includes(a))
}

function selectedTextOf(target: EventTarget | null): string {
  if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
    // textarea/input 内的选区取不到 window.getSelection()（Chrome 返回空），必须走 selectionStart
    return target.value.slice(target.selectionStart ?? 0, target.selectionEnd ?? 0)
  }
  return String(window.getSelection() ?? '')
}

/**
 * v45 车道J：跨患者病历复制粘贴管控（偏离表 1082★）。
 *
 * <p><b>诚实边界（务必读）</b>：这只能管住<b>本系统内</b>的复制粘贴——复制时把来源患者
 * 记进本地伴随状态，粘贴时比对内容是否就是那一段。<b>从外部编辑器、浏览器、聊天窗口或
 * 纸质材料粘贴进来的内容识别不到来源，本管控识别不到，也不拦截。</b>
 * 不要把它当成"全面防止病历复制"。
 *
 * <p><b>零写路径</b>：三态由 sys_config `emr.copy.cross_patient` 控制（off 放行 /
 * warn 弹确认 / block 拒绝，<b>默认 warn</b>），<b>block 档也只在前端拒绝粘贴动作</b>——
 * 病历保存端点一行未改，服务端不做任何拦截。
 *
 * <p>用法：把 `onCopy` / `onPaste` 绑在病历正文输入区的<b>外层容器</b>上
 * （copy/cut/paste 事件从内部 input/textarea 冒泡上来，一处绑定覆盖全部正文框）。
 */
export function useEmrPasteGuard(patient: () => { patientId: number | null; patientName: string }) {
  const mode = ref<'off' | 'warn' | 'block'>('warn')
  const scopeNote = ref('')

  async function loadPolicy() {
    try {
      const resp = await client.get('/outpatient/emr-ref/copy-policy')
      const d = resp.data.data as { mode?: string; scopeNote?: string }
      if (d.mode === 'off' || d.mode === 'warn' || d.mode === 'block') mode.value = d.mode
      scopeNote.value = d.scopeNote ?? ''
    } catch {
      /* 读不到配置就维持默认 warn：管控不因接口抖动而静默关闭 */
    }
  }

  function onCopy(e: ClipboardEvent) {
    const text = selectedTextOf(e.target)
    if (!text.trim()) return
    const p = patient()
    writeSource({
      patientId: p.patientId,
      patientName: p.patientName,
      text: text.slice(0, COPY_TEXT_MAX),
      at: Date.now(),
    })
  }

  function onPaste(e: ClipboardEvent) {
    if (mode.value === 'off') return
    const pasted = e.clipboardData?.getData('text/plain') ?? ''
    if (!pasted.trim()) return
    const src = readSource()
    // 识别不到来源 = 外部来源（外部编辑器/浏览器/纸质），本管控管不到，如实放行
    if (!src || !sameContent(pasted, src.text)) return
    const p = patient()
    // 同患者续写、或任一侧患者未知：放行（1082 管的是"不同病人之间"）
    if (src.patientId == null || p.patientId == null || src.patientId === p.patientId) return

    e.preventDefault()

    if (mode.value === 'block') {
      ElMessage.error(`这段内容来自患者「${src.patientName || '其他患者'}」，`
        + '按院内参数设置（emr.copy.cross_patient=block）禁止跨患者粘贴病历内容')
      return
    }

    // warn：先同步记下光标位置（弹框会夺焦点、选区随之丢失），医生确认后再代为写入
    const target = e.target
    const el = target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement ? target : null
    const start = el?.selectionStart ?? 0
    const end = el?.selectionEnd ?? start
    ElMessageBox.confirm(
      `这段内容来自患者「${src.patientName || '其他患者'}」，不是当前患者「${p.patientName}」的病历。`
      + '跨患者复制病历是病历质量与病案安全的高风险操作，确认粘贴？',
      '跨患者粘贴确认',
      { type: 'warning', confirmButtonText: '确认粘贴', cancelButtonText: '不粘贴' })
      .then(() => {
        if (!el) {
          ElMessage.warning('已确认，但当前输入框不支持自动写入，请重新粘贴一次')
          return
        }
        // 不绕过 Vue 直接改 value：写完派发原生 input 事件，让 v-model 跟上
        el.value = el.value.slice(0, start) + pasted + el.value.slice(end)
        el.dispatchEvent(new Event('input', { bubbles: true }))
        const caret = start + pasted.length
        el.focus()
        el.setSelectionRange(caret, caret)
      })
      .catch(() => { /* 医生取消：什么都不做 */ })
  }

  return { mode, scopeNote, loadPolicy, onCopy, onPaste }
}
</script>

<script setup lang="ts">
/**
 * v45 车道J：临床资料引用抽屉（偏离表 992★ 引用患者基本资料/检验/检查/历史病历）。
 *
 * <p>四个页签各调一次只读聚合端点 `GET /api/outpatient/emr-ref`，返回体里
 * `items[].text` 就是<b>可直接插入正文的片段</b>，本组件不做二次拼装、不改一个字。
 * 插入动作只 `emit('insert', text)`，<b>写不写进病历由父组件决定</b>——
 * 本组件自身不调用任何写接口。
 */
const props = withDefaults(defineProps<{
  modelValue: boolean
  registrationId?: number | null
  admissionId?: number | null
  /** 病历已签名冻结时禁用插入 */
  disabled?: boolean
}>(), { registrationId: null, admissionId: null, disabled: false })

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'insert', text: string): void
}>()

const tab = ref('BASIC')
const loading = ref(false)
const error = ref('')
const seg = ref<Record<string, unknown> | null>(null)

const items = computed(() => (seg.value?.items ?? []) as Record<string, unknown>[])

const emptyText = computed(() => ({
  BASIC: '该就诊无可引用的基本资料',
  LAB: '该患者暂无可引用的检验结果',
  EXAM: '该患者暂无可引用的检查报告',
  HISTORY: '该患者暂无既往病历（本次就诊自身不计入）',
}[tab.value] ?? '暂无可引用的资料'))

async function load() {
  if (!props.registrationId && !props.admissionId) return
  loading.value = true
  error.value = ''
  seg.value = null
  try {
    const resp = await client.get('/outpatient/emr-ref', {
      params: {
        registrationId: props.registrationId ?? undefined,
        admissionId: props.admissionId ?? undefined,
        kind: tab.value,
      },
    })
    seg.value = resp.data.data
  } catch (e) {
    // 4029 越权 / 4000 参数：全局拦截器已弹红字，这里只在抽屉里留一条可读原因
    error.value = (e as Error).message || '引用资料加载失败'
  } finally {
    loading.value = false
  }
}

function onTabChange(name: unknown) {
  tab.value = String(name)
  void load()
}

function insert(text: string) {
  if (!text.trim()) return
  emit('insert', text)
}

// 切患者时清空，避免上一位的资料留在抽屉里（切患者串号是病历事故的常见来源）
watch(() => [props.registrationId, props.admissionId], () => {
  seg.value = null
  error.value = ''
  if (props.modelValue) void load()
})
</script>

<style scoped>
.ref-head { display: flex; flex-direction: column; gap: 2px; }
.ref-sub { color: var(--el-text-color-placeholder); font-size: 12px; font-weight: 400; }
.ref-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.ref-count { color: var(--el-text-color-secondary); font-size: 12px; flex: 1; }
.ref-row { padding: 6px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.ref-row-head { display: flex; align-items: center; gap: 6px; }
.ref-title { flex: 1; font-size: 13px; color: var(--el-text-color-regular); }
.ref-text { margin: 4px 0 0; font-size: 13px; color: var(--el-text-color-primary); white-space: pre-wrap; }
</style>
