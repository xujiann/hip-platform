<template>
  <el-card>
    <template #header>
      <span style="font-weight: 600">病历版本留痕</span>
      <el-tag type="info" size="small" style="margin-left: 10px">《电子病历应用管理规范》第二十四条</el-tag>
      <el-tag type="warning" size="small" style="margin-left: 6px">只回看，不回滚</el-tag>
      <el-select v-model="emrType" size="small" style="width: 190px; margin-left: 12px">
        <el-option value="OUTP" label="OUTP　门诊病历（outp_emr.id）" />
        <el-option value="INP" label="INP　住院病历（inp_medical_record.id）" />
      </el-select>
      <el-input-number v-model="emrId" :min="1" :controls="false" size="small"
                       placeholder="病历 id" style="width: 150px; margin-left: 8px" />
      <el-button type="primary" size="small" :loading="loading" style="margin-left: 8px"
                 @click="load(true)">
        查询版本
      </el-button>
    </template>

    <!-- ============ 留痕开关与运行状况：gate 能静默关掉法定留痕，必须摆在最上面 ============ -->
    <template v-if="settings">
      <el-alert v-if="!settings.complianceClaimHolds" type="error" show-icon :closable="false" class="gap"
                title="当前配置下「病历修改留痕可追溯」不成立">
        <div>
          {{ settings.gateKey }} = {{ settings.gate }}：本档位完全不落版本记录。
          下方列表里没有版本，不代表这份病历没被改过。
        </div>
      </el-alert>
      <el-alert v-if="settings.counters.failed > 0" type="warning" show-icon :closable="false" class="gap"
                title="留痕曾经失败过（警示，非本页数据问题）">
        <div class="pre-line">{{ settings.counters.note }}</div>
        <div v-if="settings.counters.lastFailure">
          最近一次失败：{{ settings.counters.lastFailure }}（{{ fmtTime(settings.counters.lastFailureAt) }}）
        </div>
      </el-alert>

      <el-descriptions :column="4" border size="small" class="gap" title="留痕配置与运行计数">
        <el-descriptions-item :label="`留痕档位（${settings.gateKey}）`">
          <el-tag :type="gateTagType" size="small">{{ settings.gate }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="`同内容去重（${settings.dedupKey}）`">
          {{ settings.dedup ? '开' : '关' }}
        </el-descriptions-item>
        <el-descriptions-item :label="`单版正文上限（${settings.maxContentCharsKey}）`">
          {{ settings.maxContentChars }} 字
        </el-descriptions-item>
        <el-descriptions-item label="合规声明是否成立">
          <el-tag :type="settings.complianceClaimHolds ? 'success' : 'danger'" size="small">
            {{ settings.complianceClaimHolds ? '成立' : '不成立' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="已落版本">{{ settings.counters.recorded }}</el-descriptions-item>
        <el-descriptions-item label="同内容去重">{{ settings.counters.deduped }}</el-descriptions-item>
        <el-descriptions-item label="留痕失败">
          <span :class="{ bad: settings.counters.failed > 0 }">{{ settings.counters.failed }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="跳过未落">{{ settings.counters.skipped }}</el-descriptions-item>
      </el-descriptions>
      <el-alert type="info" :closable="false" class="gap" title="留痕纪律（后端原文）">
        <div v-for="(n, i) in settings.notes" :key="i" class="note-line">· {{ n }}</div>
        <div class="note-line">
          · 本页据此<b>不提供「恢复到某一版」</b>：回滚会让「当前正文的责任人是谁」说不清。
          要改回旧写法，请由医师本人重新书写保存，落成新的一版。
        </div>
      </el-alert>
    </template>

    <!-- ============ 版本列表 ============ -->
    <div class="toolbar">
      <el-switch v-model="withChangedFields" size="small" active-text="额外算出每一版改了哪几个字段" />
      <span class="hint">
        开启后后端要把<b>本页每一版的正文全部读进内存</b>比对（复核实测 10 版 × 20 万字 ≈ 51MB），
        故每页条数会被压到 {{ WITH_FIELDS_MAX }} 条以内；只回字段名，不回正文。默认关闭。
      </span>
    </div>

    <template v-if="listBody">
      <!-- 空列表的 notice 必须原样上屏：没有版本是**事实**，不是系统坏了 -->
      <el-alert v-if="listBody.notice" type="warning" show-icon :closable="false" class="gap"
                title="本份病历没有任何版本记录（这是事实，不是查询失败）">
        <div class="pre-line">{{ listBody.notice }}</div>
      </el-alert>
      <el-alert v-else-if="listBody.items.length === 0" type="info" show-icon :closable="false" class="gap"
                :title="`本页没有数据：共 ${listBody.total} 版，当前偏移 ${listBody.offset} 已超出范围，请回到第 1 页`" />

      <el-alert type="info" :closable="false" class="gap"
                :title="`共 ${listBody.total} 版　当前档位 ${listBody.gate}　去重 ${listBody.dedup ? '开' : '关'}`">
        <div v-for="(n, i) in listBody.notes" :key="i" class="note-line">· {{ n }}</div>
      </el-alert>

      <el-table :data="listBody.items" size="small" border v-loading="loading" max-height="420">
        <el-table-column label="版本" width="92">
          <template #default="{ row }">
            <b>v{{ row.versionNo }}</b>
            <el-tag v-if="row.firstVersion" size="small" type="info" class="chip">初版</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="86" />
        <el-table-column label="保存人" min-width="160">
          <template #default="{ row }">{{ savedByText(row) }}</template>
        </el-table-column>
        <el-table-column label="保存时间（本地时区）" width="180">
          <template #default="{ row }">{{ fmtTime(row.savedAt) }}</template>
        </el-table-column>
        <el-table-column label="业务日" width="110">
          <template #default="{ row }">{{ row.savedOn ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="字数" width="90">
          <template #default="{ row }">{{ row.contentLen }}</template>
        </el-table-column>
        <el-table-column label="较上一版" width="150">
          <template #default="{ row }">
            <span v-if="row.firstVersion">首版，无上一版</span>
            <span v-else-if="row.deltaLen === null" class="muted">上一版不在本页，未计算</span>
            <span v-else :class="row.deltaLen >= 0 ? 'up' : 'down'">
              {{ row.deltaLen >= 0 ? '+' : '' }}{{ row.deltaLen }} 字
            </span>
          </template>
        </el-table-column>
        <el-table-column v-if="withChangedFields" label="改动字段（相对上一版）" min-width="220">
          <template #default="{ row }">
            <template v-if="row.changedFields && row.changedFields.length">
              <el-tag v-for="f in row.changedFields" :key="f" size="small" type="warning" class="chip">
                {{ fieldLabel(f) }}
              </el-tag>
            </template>
            <span v-else-if="row.changedFields" class="muted">与上一版逐字段相同</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="内容摘要" width="130">
          <template #default="{ row }">
            <el-tooltip :content="row.contentHash" placement="top">
              <span class="code">{{ String(row.contentHash).slice(0, 12) }}…</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openVersion(row.versionNo)">查看本版</el-button>
            <el-button link type="primary" size="small" @click="fromNo = row.versionNo">设为左侧</el-button>
            <el-button link type="primary" size="small" @click="toNo = row.versionNo">设为右侧</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination class="pager" size="small" background layout="total, sizes, prev, pager, next"
                     :total="listBody.total" :page-sizes="pageSizes"
                     v-model:current-page="page" v-model:page-size="limit"
                     @size-change="onSizeChange" @current-change="load(false)" />
    </template>

    <el-empty v-else-if="!loading" :image-size="60" description="请输入病历类型与 id 后查询版本" />
  </el-card>

  <!-- ============ 任意两版对比：按字段并排，改动字段显著标出 ============ -->
  <el-card v-if="listBody && listBody.items.length" class="gap-top">
    <template #header>
      <span style="font-weight: 600">版本对比</span>
      <el-select v-model="fromNo" size="small" placeholder="左侧（改前）" style="width: 240px; margin-left: 12px">
        <el-option v-for="o in versionOptions" :key="o.value" :value="o.value" :label="o.label" />
      </el-select>
      <span class="arrow">→</span>
      <el-select v-model="toNo" size="small" placeholder="右侧（改后）" style="width: 240px">
        <el-option v-for="o in versionOptions" :key="o.value" :value="o.value" :label="o.label" />
      </el-select>
      <el-button type="primary" size="small" style="margin-left: 8px" :loading="cmpLoading"
                 :disabled="fromNo === undefined || toNo === undefined" @click="doCompare">
        对比这两版
      </el-button>
      <el-button size="small" :loading="cmpLoading" :disabled="fromNo === undefined" @click="doCompareCurrent">
        左侧 ↔ 当前正文
      </el-button>
    </template>

    <el-alert type="info" :closable="false" class="gap"
              title="下拉里只列出当前页的版本；要比更早的版本请先翻页。「左侧 ↔ 当前正文」比的是数据库里的现有正文，不写库、不生成版本行。" />

    <EmrVersionDiff v-if="compareBody" :body="compareBody" />
    <el-empty v-else :image-size="60" description="选择左右两版后点击对比" />
  </el-card>

  <!-- ============ 单版查看 ============ -->
  <el-drawer v-model="drawer" size="60%" :title="detail ? `第 ${detail.versionNo} 版 · 当时的完整内容` : '单版查看'">
    <template v-if="detail">
      <el-descriptions :column="2" border size="small" class="gap">
        <el-descriptions-item label="版本号">v{{ detail.versionNo }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ detail.source }}</el-descriptions-item>
        <el-descriptions-item label="保存人">{{ savedByText(detail) }}</el-descriptions-item>
        <el-descriptions-item label="保存时间（本地时区）">{{ fmtTime(detail.savedAt) }}</el-descriptions-item>
        <el-descriptions-item label="业务日">{{ detail.savedOn ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="字数">{{ detail.contentLen }}</el-descriptions-item>
        <el-descriptions-item label="内容摘要" :span="2">
          <span class="code">{{ detail.contentHash }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div v-for="(fv, key) in detail.fields" :key="key" class="field">
        <div class="field-head">
          <span class="fname">{{ fv.label }}</span>
          <span class="meta code">{{ key }}</span>
        </div>
        <div v-if="fv.value" class="text">{{ fv.value }}</div>
        <div v-else class="empty-val">{{ fv.value === null ? '（未填写 / null）' : '（空字符串）' }}</div>
      </div>

      <el-collapse class="gap-top">
        <el-collapse-item title="原始快照（规范化 JSON，content_hash 就是按它算的）" name="raw">
          <div class="text">{{ detail.content }}</div>
        </el-collapse-item>
      </el-collapse>
    </template>
    <el-empty v-else-if="!detailLoading" :image-size="60" description="未加载到该版本" />
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../../api/client'
import EmrVersionDiff from './EmrVersionDiff.vue'
import type {
  CompareBody, VersionDetailBody, VersionListBody, VersionMeta, VersionSettings,
} from './types'
import { fieldLabel, fmtTime } from './types'

/**
 * 开了 withChangedFields 时的每页上限。
 *
 * 后端为了算「改了哪几个字段」，要把本页 + 前一版的 content 全读出来解析
 * （复核实测 10 版 × 20 万字 ≈ 51MB 进内存）。默认 50 条一页开着这个参数，
 * 等于每次翻页都把整份病历史全文拉一遍——所以开关一开就把页大小压下来，并在界面上说明原因。
 */
const WITH_FIELDS_MAX = 10

const emrType = ref<'OUTP' | 'INP'>('OUTP')
const emrId = ref<number | undefined>(undefined)

/**
 * 已生效的查询目标：翻页、单版查看、对比一律用它，**不用输入框里的 emrType/emrId**。
 * 用户改了 id 却没点查询时，屏幕上还是旧病历的版本列表；此时按输入框去查单版或对比，
 * 拿到的是另一份病历的内容，却显示在这份病历的列表下面——同 AnesQcView 的 applied 纪律。
 */
const applied = ref<{ emrType: string; emrId: number } | null>(null)

const settings = ref<VersionSettings | null>(null)
const listBody = ref<VersionListBody | null>(null)
const loading = ref(false)

const page = ref(1)
const limit = ref(50)
const withChangedFields = ref(false)
const pageSizes = computed(() => (withChangedFields.value ? [5, 10] : [20, 50, 100, 200]))

const gateTagType = computed<'success' | 'warning' | 'danger' | 'info'>(() => {
  const g = settings.value?.gate
  if (g === 'off') return 'danger'
  if (g === 'block') return 'success'
  if (g === 'warn') return 'warning'
  return 'info'
})

async function loadSettings() {
  try {
    settings.value = (await client.get('/emr/versions/settings')).data.data
  } catch {
    settings.value = null   // 拦截器已弹错；不画一份假的配置表
  }
}

/**
 * 列表请求序号：改每页条数时 el-pagination 会连着抛 size-change 与 current-change，
 * 两个请求并发在途；先发的后到就会用旧页覆盖新页。只认最后一次发出的请求的响应。
 */
let reqSeq = 0

async function load(reset: boolean) {
  if (!emrId.value || emrId.value <= 0) {
    ElMessage.warning('请输入病历 id')
    return
  }
  const target = { emrType: emrType.value, emrId: emrId.value }
  const switched = applied.value?.emrType !== target.emrType || applied.value?.emrId !== target.emrId
  if (reset || switched) page.value = 1
  const my = ++reqSeq
  loading.value = true
  try {
    const resp = await client.get(`/emr/versions/${target.emrType}/${target.emrId}`, {
      params: {
        limit: limit.value,
        offset: (page.value - 1) * limit.value,
        withChangedFields: withChangedFields.value,
      },
    })
    if (my !== reqSeq) return   // 已有更新的请求发出，这份旧响应作废
    listBody.value = resp.data.data
    applied.value = target
    if (switched) {
      // 换了病历：上一份的对比结果与选中版本不再属于当前列表，清掉而不是留在屏幕上
      compareBody.value = null
      fromNo.value = undefined
      toNo.value = undefined
      detail.value = null
    }
  } catch {
    if (my === reqSeq) listBody.value = null   // 不留上一份病历的数据在屏幕上冒充本次查询结果
  } finally {
    if (my === reqSeq) loading.value = false
  }
}

function onSizeChange() {
  page.value = 1
  if (applied.value) load(true)
}

watch(withChangedFields, (on) => {
  if (on && limit.value > WITH_FIELDS_MAX) {
    limit.value = WITH_FIELDS_MAX
    ElMessage.info(`已把每页条数压到 ${WITH_FIELDS_MAX} 条：算改动字段要读取整页正文`)
  }
  if (!on && limit.value < 20) limit.value = 50
  page.value = 1
  if (applied.value) load(true)
})

/* ---------------- 单版查看 ---------------- */
const drawer = ref(false)
const detail = ref<VersionDetailBody | null>(null)
const detailLoading = ref(false)

async function openVersion(versionNo: number) {
  if (!applied.value) return
  detail.value = null
  drawer.value = true
  detailLoading.value = true
  try {
    const { emrType: t, emrId: id } = applied.value
    detail.value = (await client.get(`/emr/versions/${t}/${id}/versions/${versionNo}`)).data.data
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

/* ---------------- 任意两版对比 ---------------- */
const fromNo = ref<number | undefined>(undefined)
const toNo = ref<number | undefined>(undefined)
const compareBody = ref<CompareBody | null>(null)
const cmpLoading = ref(false)

const versionOptions = computed(() =>
  (listBody.value?.items ?? []).map((it) => ({
    value: it.versionNo as number,
    label: `v${it.versionNo}　${it.savedByName ?? '未记录保存人'}　${fmtTime(it.savedAt)}`,
  })),
)

async function doCompare() {
  if (!applied.value || fromNo.value === undefined || toNo.value === undefined) return
  const { emrType: t, emrId: id } = applied.value
  cmpLoading.value = true
  try {
    const resp = await client.get(`/emr/versions/${t}/${id}/compare`, {
      params: { from: fromNo.value, to: toNo.value },
    })
    compareBody.value = resp.data.data
  } catch {
    compareBody.value = null
  } finally {
    cmpLoading.value = false
  }
}

async function doCompareCurrent() {
  if (!applied.value || fromNo.value === undefined) return
  const { emrType: t, emrId: id } = applied.value
  cmpLoading.value = true
  try {
    const resp = await client.get(`/emr/versions/${t}/${id}/compare-current`, {
      params: { from: fromNo.value },
    })
    compareBody.value = resp.data.data
  } catch {
    compareBody.value = null
  } finally {
    cmpLoading.value = false
  }
}

/* ---------------- 渲染小工具 ---------------- */
/** savedBy 为 null 是「没有登录上下文」，不是「查不到人」——两种情况分开说，都不猜 */
function savedByText(m: VersionMeta): string {
  if (m.savedBy === null || m.savedBy === undefined) return '未记录（本次保存无登录上下文）'
  return m.savedByName ?? `用户 #${m.savedBy}（姓名未查到）`
}

onMounted(loadSettings)
</script>

<style scoped>
.gap {
  margin-bottom: 8px;
}
.gap-top {
  margin-top: 12px;
}
.chip {
  margin-left: 4px;
}
.pre-line {
  white-space: pre-line;
  line-height: 1.6;
}
.note-line {
  line-height: 1.7;
}
.toolbar {
  margin: 12px 0 8px;
}
.toolbar .hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.arrow {
  margin: 0 8px;
  color: var(--el-text-color-secondary);
}
.pager {
  margin-top: 10px;
  justify-content: flex-end;
}
.code {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.bad {
  color: var(--el-color-danger);
  font-weight: 600;
}
.up {
  color: var(--el-color-success);
}
.down {
  color: var(--el-color-danger);
}
.field {
  margin-top: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 8px 10px;
}
.field-head {
  margin-bottom: 6px;
}
.fname {
  font-weight: 600;
  margin-right: 8px;
}
.meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.text {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.7;
  max-height: 320px;
  overflow: auto;
  padding: 6px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 3px;
  background: var(--el-bg-color-page);
}
.empty-val {
  padding: 6px 8px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 3px;
}
</style>
