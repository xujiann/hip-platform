<template>
  <el-card>
    <template #header>
      <span style="font-weight: 600">麻醉与手术质控指标</span>
      <el-tag type="info" size="small" style="margin-left: 10px">《麻醉专业医疗质量控制指标（2022 年版）》</el-tag>
      <el-date-picker v-model="range" type="daterange" size="small" unlink-panels
                      value-format="YYYY-MM-DD" range-separator="至"
                      start-placeholder="起始日" end-placeholder="截止日"
                      style="width: 250px; margin-left: 12px" @change="load" />
      <el-select v-model="picked" size="small" clearable placeholder="全部指标"
                 style="width: 260px; margin-left: 8px" @change="load">
        <el-option v-for="c in catalog" :key="c.code" :value="c.code"
                   :label="`${c.code}　${c.name}`" :disabled="false">
          <span>{{ c.code }}　{{ c.name }}</span>
          <el-tag v-if="!c.available" type="warning" size="small" style="margin-left: 6px">缺数据源</el-tag>
        </el-option>
      </el-select>
      <el-button type="primary" size="small" :loading="loading" style="margin-left: 8px" @click="load">
        查询
      </el-button>
    </template>

    <!-- ============ 口径三处同源之一：页面 alert（另两处是端点 javadoc 与返回体 caveat） ============ -->
    <template v-if="body">
      <el-alert type="warning" show-icon :closable="false" class="caveat"
                title="口径覆盖面（请先看这一条再看指标值）">
        <div>{{ body.timepointCaveat }}</div>
      </el-alert>
      <el-alert type="info" show-icon :closable="false" class="caveat" :title="body.anchorNote" />
      <el-alert type="info" show-icon :closable="false" class="caveat" :title="body.standardNote" />
    </template>

    <!-- ============ 字段录入覆盖率：低覆盖时指标值只代表已录入部分 ============ -->
    <el-descriptions v-if="coverage" :column="6" border size="small" class="caveat"
                     title="本时段字段录入覆盖率">
      <el-descriptions-item label="手术总台数">{{ coverage.surgeries }}</el-descriptions-item>
      <el-descriptions-item label="已录手术间">{{ coverage.with_room }}</el-descriptions-item>
      <el-descriptions-item label="已录开台时间">{{ coverage.with_start }}</el-descriptions-item>
      <el-descriptions-item label="入室出室齐全">{{ coverage.with_room_times }}</el-descriptions-item>
      <el-descriptions-item label="可判准点">{{ coverage.judgeable_ontime }}</el-descriptions-item>
      <el-descriptions-item label="已录手术级别">{{ coverage.with_level }}</el-descriptions-item>
      <el-descriptions-item label="已录 ASA">{{ coverage.with_asa }}</el-descriptions-item>
      <el-descriptions-item label="已录切口等级">{{ coverage.with_incision }}</el-descriptions-item>
      <el-descriptions-item label="已录手术类别">{{ coverage.with_kind }}</el-descriptions-item>
      <el-descriptions-item label="已标取消阶段">{{ coverage.with_cancel_stage }}</el-descriptions-item>
      <el-descriptions-item label="准点阈值">{{ body?.onTimeMinutes }} 分钟</el-descriptions-item>
      <el-descriptions-item label="统计区间">{{ body?.from }} 至 {{ body?.to }}</el-descriptions-item>
    </el-descriptions>
    <el-alert v-if="coverage" type="info" :closable="false" class="caveat" :title="coverage.note" />

    <!-- ============ 逐指标 ============ -->
    <div v-for="ind in indicators" :key="ind.code" class="indicator">
      <div class="ind-head">
        <span class="ind-code">{{ ind.code }}★</span>
        <span class="ind-name">{{ ind.name }}</span>
        <el-tag v-if="!ind.available" type="danger" size="small" style="margin-left: 8px">缺数据源</el-tag>
        <span v-if="ind.available" style="float: right">
          <el-button link type="primary" size="small" @click="openDetail(ind)">穿透明细</el-button>
          <el-button link type="primary" size="small" @click="exportCsv('indicators', ind)">导出汇总</el-button>
          <el-button link type="primary" size="small" @click="exportCsv('detail', ind)">导出明细</el-button>
        </span>
      </div>

      <!-- 缺数据源：只说"为什么没有"，绝不画一张全 0 的表 -->
      <el-alert v-if="!ind.available" type="error" show-icon :closable="false"
                title="本指标缺数据源，本平台不给近似值">
        <div>{{ ind.unavailableReason }}</div>
      </el-alert>

      <template v-else>
        <el-alert v-if="ind.note" type="info" :closable="false" class="caveat" :title="ind.note" />
        <el-descriptions v-if="ind.summary" :column="4" border size="small" class="caveat">
          <el-descriptions-item v-for="k in keysOf(ind.summary)" :key="k" :label="zh(k)">
            {{ fmt(ind.summary[k]) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-table v-if="ind.rows && ind.rows.length" :data="ind.rows" size="small" border max-height="360">
          <el-table-column v-for="c in columnsOf(ind.rows)" :key="c" :prop="c" :label="zh(c)"
                           :min-width="colWidth(c)" show-overflow-tooltip>
            <template #default="{ row }">{{ fmt(row[c]) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="该统计区间内无数据" :image-size="60" />
      </template>
    </div>

    <el-empty v-if="!loading && indicators.length === 0" description="请选择统计区间后查询" />
  </el-card>

  <!-- ============ 穿透明细（1422★1423★ 明文要求：可穿透查看指标取值明细） ============ -->
  <el-drawer v-model="drawer" size="72%" :title="`${detailCode}★ ${detailName} — 取值明细`">
    <el-alert type="info" show-icon :closable="false" class="caveat"
              title="明细与汇总同时间窗、同锚点、同过滤条件——对不上账即为缺陷，请据此核对。" />
    <el-alert v-if="detailNote" type="info" :closable="false" class="caveat" :title="detailNote" />
    <el-alert v-if="detailTruncated" type="warning" show-icon :closable="false" class="caveat"
              :title="`命中超过 ${detailLimit} 条，仅显示前 ${detailLimit} 条（不做翻页）；请缩小统计区间后再穿透`" />
    <el-table :data="detailItems" v-loading="detailLoading" size="small" border height="calc(100vh - 240px)">
      <el-table-column v-for="c in columnsOf(detailItems)" :key="c" :prop="c" :label="zh(c)"
                       :min-width="colWidth(c)" show-overflow-tooltip>
        <template #default="{ row }">{{ fmt(row[c]) }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!detailLoading && detailItems.length === 0" description="该统计区间内无明细" />
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

type Row = Record<string, unknown>

interface CatalogItem { code: string; name: string; available: boolean; unavailableReason: string | null }
interface Indicator {
  code: string
  name: string
  available: boolean
  unavailableReason?: string
  note?: string
  rows?: Row[]
  summary?: Row
}
interface Body {
  from: string
  to: string
  onTimeMinutes: number
  standardNote: string
  anchorNote: string
  timepointCaveat: string
  coverage: Row
  indicators: Indicator[]
}

function defaultRange(): [string, string] {
  const to = new Date()
  const from = new Date(to.getTime() - 29 * 86400000)
  const iso = (d: Date) => d.toISOString().slice(0, 10)
  return [iso(from), iso(to)]
}

const range = ref<[string, string]>(defaultRange())
/**
 * 已生效的统计区间：穿透与导出一律用它，<b>不用 range</b>。
 * 用户改了日期但没点查询时，页面上的数还是旧区间的——此时按 range 去穿透，
 * 拿到的明细与屏幕上的汇总不是同一个窗口，正好制造出「指标算得出但对不上账」。
 */
const applied = ref<[string, string]>(defaultRange())
const picked = ref<string>('')
const catalog = ref<CatalogItem[]>([])
const body = ref<Body | null>(null)
const loading = ref(false)

const indicators = computed<Indicator[]>(() => body.value?.indicators ?? [])
const coverage = computed<Row | null>(() => body.value?.coverage ?? null)

async function loadCatalog() {
  catalog.value = (await client.get('/anes-qc/catalog')).data.data
}

async function load() {
  if (!range.value || range.value.length !== 2) {
    ElMessage.warning('请选择统计区间')
    return
  }
  const [from, to] = range.value
  loading.value = true
  try {
    const resp = await client.get('/anes-qc/indicators', {
      params: { from, to, indicator: picked.value || undefined },
    })
    body.value = resp.data.data
    applied.value = [from, to]
  } finally {
    loading.value = false
  }
}

/* ---------------- 穿透明细 ---------------- */
const drawer = ref(false)
const detailCode = ref('')
const detailName = ref('')
const detailNote = ref('')
const detailItems = ref<Row[]>([])
const detailTruncated = ref(false)
const detailLimit = ref(200)
const detailLoading = ref(false)

async function openDetail(ind: Indicator) {
  detailCode.value = ind.code
  detailName.value = ind.name
  detailItems.value = []
  detailTruncated.value = false
  detailNote.value = ''
  drawer.value = true
  detailLoading.value = true
  try {
    const resp = await client.get('/anes-qc/detail', {
      params: { indicator: ind.code, from: applied.value[0], to: applied.value[1] },
    })
    const d = resp.data.data
    detailItems.value = d.items ?? []
    detailTruncated.value = !!d.truncated
    detailLimit.value = d.limit ?? 200
    detailNote.value = d.note ?? ''
  } finally {
    detailLoading.value = false
  }
}

/* ---------------- CSV 导出（1423★「支持导出为 EXCEL 表格」） ---------------- */
async function exportCsv(kind: 'indicators' | 'detail', ind: Indicator) {
  const resp = await client.get(`/anes-qc/${kind}.csv`, {
    params: { indicator: ind.code, from: applied.value[0], to: applied.value[1] },
    responseType: 'blob',
  })
  const href = URL.createObjectURL(resp.data as Blob)
  const a = document.createElement('a')
  a.href = href
  a.download = `麻醉质控${ind.code}_${ind.name}_${kind === 'indicators' ? '汇总' : '明细'}`
    + `_${applied.value[0]}至${applied.value[1]}.csv`
  a.click()
  URL.revokeObjectURL(href)
}

/* ---------------- 通用渲染 ---------------- */
function columnsOf(rows: Row[] | undefined): string[] {
  if (!rows || rows.length === 0) return []
  return Object.keys(rows[0])
}

function keysOf(row: Row | undefined): string[] {
  return row ? Object.keys(row) : []
}

function fmt(v: unknown): string {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'boolean') return v ? '是' : '否'
  const s = String(v)
  // 时间戳统一截到分钟：明细里同时有 date 与 timestamptz 两类
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s) ? s.slice(0, 16).replace('T', ' ') : s
}

function colWidth(col: string): number {
  if (col === 'note' || col === 'description' || col === 'detail' || col === 'cancel_reason') return 220
  if (col === 'first_case_depts' || col === 'procedure_name' || col === 'patient_name') return 140
  return 110
}

/**
 * 列名中文化 —— 与后端 AnesQcController.zh() 逐键同源（CSV 导出走后端那一份）。
 * 未登记的列名原样显示，不猜也不隐藏：漏一个键只是显示成英文，比显示成空白或猜错要好。
 */
const ZH: Record<string, string> = {
  op_day: '日期',
  first_cases: '首台量',
  on_time: '准点开台数',
  delayed: '延迟开台数',
  unjudgeable: '无法判定数',
  on_time_rate_pct: '准点开台率(%)',
  elective_first_cases: '择期首台量(国标口径)',
  elective_on_time: '择期准点开台数',
  elective_delayed: '择期延迟开台数',
  elective_on_time_rate_pct: '择期首台准点率(%,2022国标)',
  first_case_depts: '首台科室',
  cases: '台数',
  timed_cases: '有完整手术时长的台数',
  total_op_minutes: '总手术时长(分钟)',
  avg_op_minutes: '平均手术时长(分钟)',
  turnovers: '接台次数',
  total_turnover_minutes: '总接台时长(分钟)',
  avg_turnover_minutes: '平均接台时长(分钟)',
  stage_name: '取消阶段',
  cancel_stage: '阶段编码',
  cancel_reason: '取消原因',
  with_reason: '已填原因数',
  cross_day_cases: '跨日手术台数',
  cross_day_pct: '跨日占比(%)',
  avg_hours: '平均时长(小时)',
  kind_name: '手术类别',
  surgery_kind: '类别编码',
  pct: '构成比(%)',
  asa_grade: 'ASA 分级',
  deaths: '死亡例数',
  death_rate_pct: '死亡率(%)',
  anesthesia_type: '麻醉方式',
  dept_name: '科室',
  product_name: '血制品',
  product_type: '血制品编码',
  records: '记录条数',
  surgeries: '涉及手术数',
  total_ml: '总量(ml)',
  auto_records: '其中自体血记录数',
  auto_ml: '其中自体血量(ml)',
  band: '输注量分档',
  patients: '人数',
  transfused_patients: '输血患者数',
  auto_patients: '自体血患者数',
  non_auto_patients: '非自体血患者数',
  both_patients: '两类均有患者数(重叠)',
  transfused_surgeries: '输血台次数',
  planned_events: '计划',
  unplanned_events: '非计划',
  unspecified_events: '未区分',
  age_band: '年龄段',
  sex: '性别',
  added: '新增镇痛泵',
  removed: '拆泵',
  in_use: '在用量(全历史累计)',
  procedure_name: '术式',
  op_icd: '手术操作编码(自填)',
  done_cases: '已完成台数',
  procedures: '术式种数',
  with_op_icd: '已填编码台数',
  surgery_level: '手术级别',
  unplanned_reop: '非计划再次手术',
  target: '去向',
  planned_name: '计划性',
  events: '事件次数',
  event_name: '事件',
  event_type: '事件编码',
  level: '级别',
  level1: 'Ⅰ级',
  level2: 'Ⅱ级',
  level3: 'Ⅲ级',
  level4: 'Ⅳ级',
  handled: '已处置',
  surgery_id: '手术ID',
  admission_id: '住院ID',
  admission_no: '住院号',
  patient_name: '患者',
  room_no: '手术间',
  scheduled_at: '排台时间',
  in_room_at: '入室时间',
  start_at: '开台时间',
  end_at: '结束时间',
  out_room_at: '出室时间',
  status: '状态',
  delay_minutes: '较排台延迟(分钟)',
  judgement: '判定',
  op_minutes: '手术时长(分钟)',
  turnover_minutes: '接台时长(分钟)',
  died: '是否死亡',
  died_at: '死亡时间',
  transfusion_id: '输血记录ID',
  volume_ml: '输注量(ml)',
  is_auto: '是否自体血',
  transfused_at: '输注时间',
  event_id: '事件ID',
  event_time: '事件时间',
  planned: '是否计划内',
  detail: '说明',
  operator_name: '操作人',
  occurred_on: '发生日期',
  description: '描述',
  note: '口径说明',
  surgeries_total: '手术总数',
  with_room: '已录手术间',
  with_start: '已录开台时间',
  with_start_end: '开台结束齐全',
  with_room_times: '入室出室齐全',
  judgeable_ontime: '可判准点',
  with_level: '已录手术级别',
  with_asa: '已录 ASA',
  with_incision: '已录切口等级',
  with_kind: '已录手术类别',
  with_cancel_stage: '已标取消阶段',
}

function zh(col: string): string {
  return ZH[col] ?? col
}

onMounted(async () => {
  await loadCatalog()
  await load()
})
</script>

<style scoped>
.caveat {
  margin-bottom: 8px;
}
.indicator {
  margin-top: 18px;
  padding-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.ind-head {
  margin-bottom: 8px;
  line-height: 24px;
}
.ind-code {
  font-weight: 600;
  color: var(--el-color-primary);
  margin-right: 6px;
}
.ind-name {
  font-weight: 600;
}
</style>
