<template>
  <div ref="rootRef" class="sf-form">
    <div class="sf-bar no-print">
      <span class="sf-title">结构化录入</span>
      <span class="sf-hint">回车跳到下一元素（Shift+回车换行）</span>
      <span class="sf-spacer" />
      <el-button size="small" :disabled="disabled" @click="focusNextRequired">下一个必填项</el-button>
    </div>

    <el-alert v-if="missingRequired.length" type="warning" :closable="false" show-icon
              style="margin-bottom: 8px"
              :title="`还有 ${missingRequired.length} 个必填元素未填：${missingRequired.map((f) => f.label).join('、')}`" />

    <!-- 正文由**保存端点**渲染（车道 I 纪律：先渲染成正文、再存侧车），这里只做所见即所得的预览，
         不在前端二次写入正文——两边都写会把同一段内容写两遍 -->
    <div v-if="preview" class="sf-preview">
      <span class="sf-preview-label">{{ previewLabel }}：</span>{{ preview }}
    </div>

    <el-form label-width="110px" class="sf-grid">
      <el-form-item v-for="(f, i) in sorted" :key="f.fieldCode" :required="f.required">
        <template #label>
          {{ f.label }}<span v-if="f.unit" class="sf-unit">（{{ f.unit }}）</span>
        </template>
        <div class="sf-cell" :data-field="f.fieldCode">
          <!-- 六型（1075★ 明文列举，一个不多一个不少）：文本·数值·复选·单选·多选·日期 -->
          <el-input v-if="f.datatype === 'TEXT'" :model-value="asText(f)" :disabled="disabled"
                    :placeholder="f.placeholder ?? ''" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }"
                    @update:model-value="(v: string) => set(f, v)"
                    @keydown.enter.exact.prevent="focusAt(i + 1)" />

          <el-input-number v-else-if="f.datatype === 'NUMBER'" :model-value="asNumber(f)" :disabled="disabled"
                           :controls="false" :placeholder="f.placeholder ?? ''" style="width: 180px"
                           @update:model-value="(v: unknown) => set(f, toNumberOrNull(v))"
                           @keydown.enter="focusAt(i + 1)" />

          <el-checkbox v-else-if="f.datatype === 'CHECKBOX'" :model-value="asBool(f)" :disabled="disabled"
                       @update:model-value="(v: unknown) => set(f, v === true)">
            {{ f.placeholder || '是' }}
          </el-checkbox>

          <el-radio-group v-else-if="f.datatype === 'RADIO'" :model-value="asText(f)" :disabled="disabled"
                          @update:model-value="(v: unknown) => set(f, v == null ? '' : String(v))">
            <el-radio v-for="opt in options(f)" :key="opt" :value="opt" border size="small">{{ opt }}</el-radio>
          </el-radio-group>

          <el-select v-else-if="f.datatype === 'MULTI'" :model-value="asArray(f)" :disabled="disabled"
                     multiple collapse-tags collapse-tags-tooltip style="width: 320px"
                     :placeholder="f.placeholder || '可多选'"
                     @update:model-value="(v: unknown) => set(f, toStringArray(v))">
            <el-option v-for="opt in options(f)" :key="opt" :label="opt" :value="opt" />
          </el-select>

          <el-date-picker v-else-if="f.datatype === 'DATE'" :model-value="asText(f)" :disabled="disabled"
                          type="date" value-format="YYYY-MM-DD" :placeholder="f.placeholder || '选择日期'"
                          @update:model-value="(v: unknown) => set(f, v == null ? '' : String(v))" />

          <!-- 六型之外的取值只可能来自数据异常：如实显示，不猜、不静默丢弃 -->
          <el-tag v-else type="danger" size="small">
            未知元素类型 {{ f.datatype }}（本平台只支持 文本/数值/复选/单选/多选/日期）
          </el-tag>
        </div>
      </el-form-item>
    </el-form>
  </div>
</template>

<script lang="ts">
/**
 * 车道 I `GET /api/emr/templates/{templateId}/fields` 的返回体形态
 * （{@code EmrFieldController.toDto}，字段定义表 `emr_template_field` / V139 迁移）。
 *
 * <p>`valueSet` 在库里是 text 存的 JSON 数组，但<b>出接口时车道 I 已解析成数组</b>——
 * 这里按数组收；同时兼容 JSON 字符串，免得哪天上游改口径就整块表单渲染不出来。
 */
export interface EmrTemplateField {
  id?: number
  templateId?: number
  fieldCode: string
  label: string
  /** TEXT 文本 / NUMBER 数值 / CHECKBOX 复选 / RADIO 单选 / MULTI 多选 / DATE 日期 */
  datatype: string
  required?: boolean
  sortNo?: number
  valueSet?: string[] | string | null
  placeholder?: string | null
  unit?: string | null
  enabled?: boolean
}
</script>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'

/**
 * v45 车道J：结构化字段动态表单（偏离表 989★ 结构化书写与元素间快速跳转 / 1075★ 六型元素）。
 *
 * <p><b>这是表单控件，不是富文本控件。</b>规划文档「范围外」明确不引入任何富文本编辑器依赖——
 * 富文本会把病历正文变成不可检索的 HTML 泥团，与 1098「检索结构化元素内容」直接冲突。
 * 989 要的"类 WORD 所见即所得"用结构化元素表单实现，这正是 1075 的"最小结构化元素"。
 *
 * <p><b>取值形态</b>（与车道 I 的 `content_json` 扁平对象一致，供其直接落侧车列）：
 * TEXT/DATE/RADIO → 字符串；NUMBER → 数值；CHECKBOX → 布尔；MULTI → 字符串数组。
 *
 * <p><b>本组件不落库、也不写正文</b>：取值经父组件随病历保存端点的可空 `fields` 参数上送，
 * 由车道 I 在服务端<b>先渲染进正文、再存 content_json 侧车</b>（这是它迁移里写死的顺序）。
 * 前端这里只按同样的规则做一份<b>所见即所得的预览</b>——若前端也往正文里写一遍，
 * 同一段内容会被写两次。
 *
 * <p><b>CHECKBOX 的"必填"口径</b>：未勾选<b>不</b>算未填——false 是一个明确答案
 * （"有无咳嗽：否"），把它算成未填会让"下一个必填项"永远停在一个已作答的复选框上。
 * 若后端 4025 的口径与此不同，以后端为准；本处只是录入导航辅助，<b>不做任何阻断</b>。
 */
const props = withDefaults(defineProps<{
  fields: EmrTemplateField[]
  modelValue: Record<string, unknown>
  disabled?: boolean
  /** 预览行的前缀，写明这段将被渲染到哪儿（门诊是现病史，住院是病历正文） */
  previewLabel?: string
}>(), { disabled: false, previewLabel: '保存后写入病历正文' })

const emit = defineEmits<{
  (e: 'update:modelValue', v: Record<string, unknown>): void
}>()

const rootRef = ref<HTMLElement | null>(null)

/** 停用的字段不渲染（历史病历里它的值仍解释得通，但不该再录新值）；排序即 989 的跳转序 */
const sorted = computed(() =>
  props.fields
    .filter((f) => f.enabled !== false)
    .slice()
    .sort((a, b) => (a.sortNo ?? 0) - (b.sortNo ?? 0) || a.fieldCode.localeCompare(b.fieldCode)))

/** RADIO/MULTI 的候选值；写坏了就当作无候选，不抛错打断录入 */
function options(f: EmrTemplateField): string[] {
  const vs = f.valueSet
  if (!vs) return []
  if (Array.isArray(vs)) return vs.map((v) => String(v))
  try {
    const parsed: unknown = JSON.parse(vs)
    return Array.isArray(parsed) ? parsed.map((v) => String(v)) : []
  } catch {
    return []
  }
}

function raw(f: EmrTemplateField): unknown {
  return props.modelValue[f.fieldCode]
}

function asText(f: EmrTemplateField): string {
  const v = raw(f)
  return v == null ? '' : String(v)
}

function asNumber(f: EmrTemplateField): number | undefined {
  const v = raw(f)
  if (typeof v === 'number') return v
  if (v == null || String(v).trim() === '') return undefined
  const n = Number(v)
  return Number.isNaN(n) ? undefined : n
}

function asBool(f: EmrTemplateField): boolean {
  return raw(f) === true
}

function asArray(f: EmrTemplateField): string[] {
  const v = raw(f)
  return Array.isArray(v) ? v.map((x) => String(x)) : []
}

function toNumberOrNull(v: unknown): number | null {
  if (typeof v === 'number' && !Number.isNaN(v)) return v
  if (v == null || String(v).trim() === '') return null
  const n = Number(v)
  return Number.isNaN(n) ? null : n
}

function toStringArray(v: unknown): string[] {
  return Array.isArray(v) ? v.map((x) => String(x)) : []
}

function set(f: EmrTemplateField, v: unknown) {
  emit('update:modelValue', { ...props.modelValue, [f.fieldCode]: v })
}

/** 已作答判定：复选框永远算已作答（false 也是答案），其余按空值判 */
function answered(f: EmrTemplateField): boolean {
  if (f.datatype === 'CHECKBOX') return true
  const v = raw(f)
  if (v == null) return false
  if (Array.isArray(v)) return v.length > 0
  return String(v).trim().length > 0
}

const missingRequired = computed(() => sorted.value.filter((f) => f.required && !answered(f)))

// ===== 989★ 元素间快速跳转：回车逐项前进 + 一个「下一个必填项」按钮 =====

function focusCode(code: string) {
  const host = rootRef.value?.querySelector<HTMLElement>(`[data-field="${CSS.escape(code)}"]`)
  const el = host?.querySelector<HTMLElement>('input, textarea, [tabindex]')
  el?.focus()
  host?.scrollIntoView({ block: 'nearest' })
}

function focusAt(index: number) {
  const f = sorted.value[index]
  if (f) focusCode(f.fieldCode)
}

function focusNextRequired() {
  const next = missingRequired.value[0]
  if (!next) {
    ElMessage.success('必填元素已填齐')
    return
  }
  focusCode(next.fieldCode)
}

// ===== 正文预览（与服务端渲染同规则；真正写正文的是保存端点，前端只预览不写） =====

function renderValue(f: EmrTemplateField): string {
  const v = raw(f)
  if (f.datatype === 'CHECKBOX') return v === true ? '是' : '否'
  if (Array.isArray(v)) return v.join('、')
  return v == null ? '' : String(v).trim()
}

/** `label（unit）：value`，按跳转序以「；」相连；空值跳过（不生成"体温：" 这种空壳） */
const preview = computed(() =>
  sorted.value
    .map((f) => ({ f, v: renderValue(f) }))
    .filter((x) => x.v !== '')
    .map((x) => `${x.f.label}${x.f.unit ? '（' + x.f.unit + '）' : ''}：${x.v}`)
    .join('；'))

defineExpose({ focusNextRequired })
</script>

<style scoped>
.sf-form { border: 1px dashed var(--el-border-color); border-radius: 4px; padding: 10px; margin-bottom: 12px; }
.sf-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.sf-title { font-weight: 600; }
.sf-hint { color: var(--el-text-color-placeholder); font-size: 12px; }
.sf-spacer { flex: 1; }
.sf-unit { color: var(--el-text-color-placeholder); }
.sf-cell { width: 100%; }
.sf-grid :deep(.el-form-item) { margin-bottom: 10px; }
.sf-preview {
  margin-bottom: 8px;
  padding: 6px 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-radius: 3px;
  white-space: pre-wrap;
}
.sf-preview-label { color: var(--el-text-color-placeholder); }
</style>
