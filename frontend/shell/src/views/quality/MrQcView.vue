<template>
  <el-tabs v-model="tab" class="mrqc-tabs" @tab-change="onTabChange">
    <!-- ============================ 待评队列 ============================ -->
    <el-tab-pane name="pending">
      <template #label>
        待评队列
        <el-badge v-if="pending.total" :value="pending.total" class="badge" type="warning" />
      </template>
      <el-card>
        <template #header>
          <span>待终末质控（已出院、评分单未提交）</span>
          <el-tag type="info" size="small" style="margin-left: 10px">共 {{ pending.total }} 份</el-tag>
          <el-select v-model="pendingDept" placeholder="全部科室" clearable size="small"
                     style="width: 160px; margin-left: 10px" @change="loadPending">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <el-button link type="primary" style="float: right" :loading="pendingLoading" @click="loadPending">
            刷新
          </el-button>
        </template>
        <el-alert type="info" show-icon :closable="false" class="caveat"
                  title="终末质控是出院后的事后管理评价：甲乙丙评级不作为归档/结算的准入条件，丙级病案照样归档。" />
        <el-alert v-if="pending.truncated" type="warning" show-icon :closable="false" class="caveat"
                  :title="`待评超过 ${pending.limit} 份，仅显示出院最久的 ${pending.limit} 份，请按科室专项清理历史欠账`" />
        <el-table :data="pending.items" v-loading="pendingLoading" size="small" height="calc(100vh - 300px)">
          <el-table-column prop="admission_no" label="住院号" width="150" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="dept_name" label="科室" width="110" />
          <el-table-column label="出院时间" width="150">
            <template #default="{ row }">{{ fmt(row.discharged_at) }}</template>
          </el-table-column>
          <el-table-column prop="discharged_days" label="出院天数" width="100" align="right" />
          <el-table-column label="归档" width="150">
            <template #default="{ row }">
              <el-tag v-if="!row.archived" type="info" size="small">未归档</el-tag>
              <el-tag v-else-if="row.archived_at" type="success" size="small">
                {{ fmt(row.archived_at) }}
              </el-tag>
              <el-tooltip v-else :content="pending.archivedAtCaveat" placement="top">
                <el-tag type="success" size="small">已归档（时间未知）</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="评分单" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.sheet_status === 'DRAFT'" type="warning" size="small">草稿</el-tag>
              <el-tag v-else type="info" size="small">未建单</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openSheet(row.admission_id)">去评分</el-button>
            </template>
          </el-table-column>
          <template #empty><span>无待评病案，队列已清空</span></template>
        </el-table>
      </el-card>
    </el-tab-pane>

    <!-- ============================ 评分单编辑 ============================ -->
    <el-tab-pane label="评分单" name="sheet">
      <el-empty v-if="!sheet" description="从待评队列选择一份病案，或先自动预填建单" />
      <el-card v-else>
        <template #header>
          <span>{{ sheet.admission_no }} · {{ sheet.patient_name }} · {{ sheet.dept_name }}</span>
          <el-tag :type="gradeTagType(sheet.grade)" style="margin-left: 10px">
            {{ sheet.grade ? `${sheet.grade}级` : '未定级' }}
          </el-tag>
          <el-tag :type="sheet.status === 'SUBMITTED' ? 'success' : 'warning'" size="small"
                  style="margin-left: 6px">
            {{ sheet.status === 'SUBMITTED' ? '已提交' : '草稿' }}
          </el-tag>
          <span class="score">
            {{ Number(sheet.base_score).toFixed(0) }} − {{ Number(sheet.total_deduct).toFixed(1) }}
            = <b>{{ Number(sheet.final_score).toFixed(1) }}</b> 分
          </span>
          <el-button v-if="!submitted" link type="primary" style="float: right; margin-left: 12px"
                     :loading="busy" @click="doSubmit">提交评分</el-button>
          <el-button v-if="!submitted" link style="float: right" :loading="busy" @click="doPrefill">
            重新自动预填
          </el-button>
        </template>

        <el-alert type="info" show-icon :closable="false" class="caveat"
                  title="「自动预填」= 按扣分项字典的 auto_rule 把病历完整性缺项转成 AUTO 扣分，不是病历内涵语义评审；内涵项需人工评定。" />
        <el-alert v-if="submitted" type="success" show-icon :closable="false" class="caveat"
                  :title="`已于 ${fmt(sheet.reviewed_at)} 由 ${sheet.reviewer_name ?? '—'} 提交，不可再评`" />
        <el-alert v-if="unmapped.length" type="warning" show-icon :closable="false" class="caveat"
                  :title="`${unmapped.length} 条自动判定缺项在扣分项字典中无启用的对应项，未计扣分：${unmapped.join('、')}`" />

        <div v-if="!submitted" class="adder">
          <el-select v-model="addCode" filterable placeholder="选择扣分项" style="width: 340px">
            <el-option-group v-for="(group, cat) in dictByCategory" :key="cat" :label="cat">
              <el-option v-for="d in group" :key="d.code" :value="d.code"
                         :label="`${d.name}（标准扣 ${Number(d.deduct_score).toFixed(1)} 分）`" />
            </el-option-group>
          </el-select>
          <el-input-number v-model="addScore" :min="0.5" :max="100" :step="0.5" :precision="1"
                           placeholder="扣分" style="width: 130px" controls-position="right" />
          <el-input v-model="addRemark" placeholder="扣分说明（可选）" style="width: 260px" />
          <el-button type="primary" :disabled="!addCode" :loading="busy" @click="doAddItem">加扣分项</el-button>
          <span class="hint">分值留空取字典标准分</span>
        </div>

        <el-table :data="sheet.items" size="small" style="margin-top: 10px">
          <el-table-column prop="category" label="一级项" width="110" />
          <el-table-column prop="item_name" label="扣分项" min-width="220" />
          <el-table-column prop="item_code" label="编码" width="90" />
          <el-table-column label="扣分" width="80" align="right">
            <template #default="{ row }">{{ Number(row.deduct_score).toFixed(1) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="90">
            <template #default="{ row }">
              <el-tag :type="row.source === 'AUTO' ? 'success' : 'warning'" size="small">
                {{ row.source === 'AUTO' ? '自动' : '人工' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="扣分原因/说明" min-width="220" />
          <el-table-column v-if="!submitted" label="操作" width="80" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" :loading="busy" @click="doRemoveItem(row.item_code)">移除</el-button>
            </template>
          </el-table-column>
          <template #empty><span>无扣分项，本病案得满分</span></template>
        </el-table>
      </el-card>
    </el-tab-pane>

    <!-- ============================ 统计报表 ============================ -->
    <el-tab-pane label="统计报表" name="stats">
      <el-card>
        <template #header>
          <span>终末质控统计</span>
          <el-select v-model="months" size="small" style="width: 110px; margin-left: 10px" @change="loadStats">
            <el-option :value="6" label="近 6 个月" />
            <el-option :value="12" label="近 12 个月" />
            <el-option :value="24" label="近 24 个月" />
          </el-select>
          <el-select v-model="statsDept" placeholder="全部科室" clearable size="small"
                     style="width: 150px; margin-left: 8px" @change="loadStats">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
          <el-select v-model="statsGrade" placeholder="全部评级" clearable size="small"
                     style="width: 110px; margin-left: 8px" @change="loadStats">
            <el-option value="甲" label="甲级" />
            <el-option value="乙" label="乙级" />
            <el-option value="丙" label="丙级" />
          </el-select>
          <el-button link type="primary" style="float: right; margin-left: 12px" @click="exportCsv('stats')">
            导出汇总 CSV
          </el-button>
          <el-button link type="primary" style="float: right" @click="exportCsv('deduct-items')">
            导出扣分项 CSV
          </el-button>
        </template>

        <el-alert type="warning" show-icon :closable="false" class="caveat">
          <template #title>
            <div>口径说明（与端点返回同源）：</div>
            <div>· {{ stats.monthCaveat }}</div>
            <div>· {{ stats.archivedAtCaveat }}</div>
            <div>· 「运行质控 / 终末质控」分类汇总中，<b>运行（环节）质控在本平台为在院实时现算、不落库</b>，
              无历史评分单可汇总，故其份数恒为 0——不以现算值冒充历史评分。</div>
          </template>
        </el-alert>

        <div class="totals">
          <el-statistic title="评分份数" :value="numOf(stats.totals?.sheets)" />
          <el-statistic title="甲级" :value="numOf(stats.totals?.grade_a)" />
          <el-statistic title="乙级" :value="numOf(stats.totals?.grade_b)" />
          <el-statistic title="丙级" :value="numOf(stats.totals?.grade_c)" />
          <el-statistic title="甲级率" :value="numOf(stats.totals?.grade_a_rate)" suffix="%" />
          <el-statistic title="平均分" :value="numOf(stats.totals?.avg_score)" />
        </div>

        <div class="charts">
          <div ref="trendEl" class="chart" />
          <div ref="deptEl" class="chart" />
        </div>

        <el-row :gutter="12">
          <el-col :span="12">
            <h4>扣分项 TOP10（扣分原因）</h4>
            <el-table :data="stats.topDeductItems" size="small">
              <el-table-column prop="category" label="一级项" width="100" />
              <el-table-column prop="item_name" label="扣分项" min-width="180" />
              <el-table-column prop="times" label="次数" width="70" align="right" />
              <el-table-column prop="total_deduct" label="扣分合计" width="90" align="right" />
              <template #empty><span>窗口内无扣分记录</span></template>
            </el-table>
          </el-col>
          <el-col :span="12">
            <h4>评分 TOP10 病案</h4>
            <el-table :data="stats.topSheets" size="small">
              <el-table-column prop="admission_no" label="住院号" width="140" />
              <el-table-column prop="patient_name" label="患者" width="80" />
              <el-table-column prop="dept_name" label="科室" min-width="90" />
              <el-table-column label="得分" width="70" align="right">
                <template #default="{ row }">{{ Number(row.final_score).toFixed(1) }}</template>
              </el-table-column>
              <el-table-column label="评级" width="70">
                <template #default="{ row }">
                  <el-tag :type="gradeTagType(row.grade)" size="small">{{ row.grade }}</el-tag>
                </template>
              </el-table-column>
              <template #empty><span>窗口内无已提交评分单</span></template>
            </el-table>
          </el-col>
        </el-row>

        <h4>科室排名（按平均分）</h4>
        <el-table :data="stats.deptRank" size="small">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column prop="dept_name" label="科室" min-width="120" />
          <el-table-column prop="sheets" label="评分份数" width="90" align="right" />
          <el-table-column prop="avg_score" label="平均分" width="90" align="right" />
          <el-table-column prop="grade_a" label="甲级" width="80" align="right" />
          <el-table-column prop="grade_c" label="丙级" width="80" align="right" />
          <el-table-column prop="grade_a_rate" label="甲级率(%)" width="100" align="right" />
          <template #empty><span>窗口内无已提交评分单</span></template>
        </el-table>
      </el-card>
    </el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
// echarts 按需注册（照 DashboardView：全量引入曾占 1.1MB chunk）
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import client from '../../api/client'

echarts.use([BarChart, GridComponent, LegendComponent, TitleComponent, TooltipComponent, CanvasRenderer])

type Row = Record<string, any>

interface Dept { id: number; name: string }
interface DictItem { id: number; code: string; category: string; name: string; deduct_score: number }
interface Pending {
  items: Row[]
  total: number
  limit: number
  truncated: boolean
  archivedAtCaveat: string
}
interface Stats {
  months: number
  byMonthDept: Row[]
  deptRank: Row[]
  topDeductItems: Row[]
  topSheets: Row[]
  totals: Row | null
  monthCaveat: string
  archivedAtCaveat: string
}

const tab = ref('pending')
const busy = ref(false)
const depts = ref<Dept[]>([])
const dict = ref<DictItem[]>([])

/* ---------------- 待评队列 ---------------- */
const pendingLoading = ref(false)
const pendingDept = ref<number | undefined>()
const pending = ref<Pending>({ items: [], total: 0, limit: 200, truncated: false, archivedAtCaveat: '' })

async function loadPending() {
  pendingLoading.value = true
  try {
    const resp = await client.get('/quality/mr-qc/sheets/pending',
      { params: { deptId: pendingDept.value } })
    pending.value = resp.data.data as Pending
  } finally {
    pendingLoading.value = false
  }
}

/* ---------------- 评分单 ---------------- */
const sheet = ref<Row | null>(null)
const currentId = ref<number | null>(null)
const unmapped = ref<string[]>([])
const addCode = ref<string>('')
const addScore = ref<number | undefined>()
const addRemark = ref('')

const submitted = computed(() => sheet.value?.status === 'SUBMITTED')
const dictByCategory = computed<Record<string, DictItem[]>>(() => {
  const g: Record<string, DictItem[]> = {}
  for (const d of dict.value) (g[d.category] ??= []).push(d)
  return g
})

function setSheet(data: Row | null) {
  sheet.value = data
  unmapped.value = (data?.unmappedFindings as string[] | undefined) ?? []
  addCode.value = ''
  addScore.value = undefined
  addRemark.value = ''
}

/** 打开一份病案的评分单：已有单直接读，没有则自动预填建单 */
async function openSheet(admissionId: number) {
  currentId.value = admissionId
  tab.value = 'sheet'
  busy.value = true
  try {
    const resp = await client.get(`/quality/mr-qc/sheets/${admissionId}`, { __silentCodes: [4840] })
    setSheet(resp.data.data as Row)
  } catch {
    await doPrefill()   // 4840 未建单 → 直接预填建单
  } finally {
    busy.value = false
  }
}

async function doPrefill() {
  if (!currentId.value) return
  busy.value = true
  try {
    const resp = await client.post(`/quality/mr-qc/sheets/${currentId.value}/prefill`)
    setSheet(resp.data.data as Row)
  } finally {
    busy.value = false
  }
}

async function doAddItem() {
  if (!currentId.value || !addCode.value) return
  busy.value = true
  try {
    const resp = await client.post(`/quality/mr-qc/sheets/${currentId.value}/items`,
      { itemCode: addCode.value, deductScore: addScore.value, remark: addRemark.value })
    setSheet(resp.data.data as Row)
  } finally {
    busy.value = false
  }
}

async function doRemoveItem(itemCode: string) {
  if (!currentId.value) return
  busy.value = true
  try {
    const resp = await client.delete(`/quality/mr-qc/sheets/${currentId.value}/items/${itemCode}`)
    setSheet(resp.data.data as Row)
  } finally {
    busy.value = false
  }
}

async function doSubmit() {
  if (!currentId.value) return
  busy.value = true
  try {
    const resp = await client.post(`/quality/mr-qc/sheets/${currentId.value}/submit`, { note: '' })
    setSheet(resp.data.data as Row)
    ElMessage.success(`已提交，评级 ${sheet.value?.grade ?? '—'} 级`)
    await loadPending()
  } finally {
    busy.value = false
  }
}

/* ---------------- 统计 ---------------- */
const months = ref(12)
const statsDept = ref<number | undefined>()
const statsGrade = ref<string | undefined>()
const stats = ref<Stats>({
  months: 12, byMonthDept: [], deptRank: [], topDeductItems: [], topSheets: [],
  totals: null, monthCaveat: '', archivedAtCaveat: '',
})
const trendEl = ref<HTMLElement>()
const deptEl = ref<HTMLElement>()
const charts: ReturnType<typeof echarts.init>[] = []

function statsParams() {
  return { months: months.value, deptId: statsDept.value, grade: statsGrade.value }
}

async function loadStats() {
  const resp = await client.get('/quality/mr-qc/stats', { params: statsParams() })
  stats.value = resp.data.data as Stats
  await nextTick()
  renderCharts()
}

// 配色：甲=绿 / 乙=橙 / 丙=红，与页面评级标签同语义
const C = { a: '#1baf7a', b: '#e6a23c', c: '#d95c5c', blue: '#2a78d6', grid: '#ebeef0', text: '#52514e' }

function initChart(el: HTMLElement) {
  const existing = echarts.getInstanceByDom(el)
  if (existing) return existing
  const inst = echarts.init(el)
  charts.push(inst)
  return inst
}

function renderCharts() {
  if (trendEl.value) {
    // 月份趋势：把 byMonthDept 在前端按月汇总（同一份数据既支持科室明细表也支持全院趋势）
    const byMonth = new Map<string, [number, number, number]>()
    for (const r of stats.value.byMonthDept) {
      const k = String(r.month)
      const cur = byMonth.get(k) ?? [0, 0, 0]
      byMonth.set(k, [cur[0] + Number(r.grade_a ?? 0), cur[1] + Number(r.grade_b ?? 0),
        cur[2] + Number(r.grade_c ?? 0)])
    }
    const monthKeys = [...byMonth.keys()].sort()
    initChart(trendEl.value).setOption({
      animation: false,
      title: { text: '甲乙丙份数趋势（按质控月）', left: 8, top: 4, textStyle: { fontSize: 13 } },
      tooltip: { trigger: 'axis' },
      legend: { top: 4, right: 8, data: ['甲级', '乙级', '丙级'] },
      grid: { left: 44, right: 16, top: 42, bottom: 24 },
      xAxis: { type: 'category', data: monthKeys, axisTick: { show: false },
               axisLine: { lineStyle: { color: C.grid } }, axisLabel: { color: C.text, fontSize: 11 } },
      yAxis: { type: 'value', splitLine: { lineStyle: { color: C.grid } },
               axisLabel: { color: C.text, fontSize: 11 } },
      series: [
        { name: '甲级', type: 'bar', stack: 'g', itemStyle: { color: C.a },
          data: monthKeys.map((k) => byMonth.get(k)![0]) },
        { name: '乙级', type: 'bar', stack: 'g', itemStyle: { color: C.b },
          data: monthKeys.map((k) => byMonth.get(k)![1]) },
        { name: '丙级', type: 'bar', stack: 'g', itemStyle: { color: C.c },
          data: monthKeys.map((k) => byMonth.get(k)![2]) },
      ],
    }, true)
  }
  if (deptEl.value) {
    const rank = stats.value.deptRank.slice(0, 12)
    initChart(deptEl.value).setOption({
      animation: false,
      title: { text: '科室甲级率（%）', left: 8, top: 4, textStyle: { fontSize: 13 } },
      tooltip: { trigger: 'axis' },
      grid: { left: 44, right: 16, top: 42, bottom: 40 },
      xAxis: { type: 'category', data: rank.map((r) => String(r.dept_name)), axisTick: { show: false },
               axisLine: { lineStyle: { color: C.grid } },
               axisLabel: { color: C.text, fontSize: 11, rotate: rank.length > 6 ? 30 : 0 } },
      yAxis: { type: 'value', max: 100, splitLine: { lineStyle: { color: C.grid } },
               axisLabel: { color: C.text, fontSize: 11 } },
      series: [{ type: 'bar', itemStyle: { color: C.blue }, barMaxWidth: 36,
                 data: rank.map((r) => Number(r.grade_a_rate ?? 0)) }],
    }, true)
  }
}

const onResize = () => charts.forEach((c) => c.resize())
window.addEventListener('resize', onResize)
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  charts.forEach((c) => c.dispose())
  charts.length = 0
})

async function exportCsv(which: 'stats' | 'deduct-items') {
  const resp = await client.get(`/quality/mr-qc/${which}.csv`,
    { params: statsParams(), responseType: 'blob' })
  const href = URL.createObjectURL(resp.data as Blob)
  const a = document.createElement('a')
  a.href = href
  a.download = `${which === 'stats' ? '病案终末质控汇总' : '病案质控扣分项TOP10'}_近${months.value}月.csv`
  a.click()
  URL.revokeObjectURL(href)
}

/* ---------------- 通用 ---------------- */
function fmt(v: unknown): string {
  if (!v) return ''
  return String(v).slice(0, 16).replace('T', ' ')
}
function numOf(v: unknown): number {
  return v == null ? 0 : Number(v)
}
function gradeTagType(g: unknown): 'success' | 'warning' | 'danger' | 'info' {
  if (g === '甲') return 'success'
  if (g === '乙') return 'warning'
  if (g === '丙') return 'danger'
  return 'info'
}

function onTabChange(name: string | number) {
  if (name === 'stats' && !stats.value.byMonthDept.length) loadStats()
  else if (name === 'stats') nextTick(renderCharts)
}

onMounted(async () => {
  const [d, dic] = await Promise.all([
    client.get('/system/depts'),
    client.get('/quality/mr-qc/items', { params: { enabled: true } }),
    loadPending(),
  ])
  depts.value = d.data.data as Dept[]
  dict.value = dic.data.data as DictItem[]
})
</script>

<style scoped>
.badge { margin-left: 6px; }
.caveat { margin-bottom: 10px; }
.score { margin-left: 14px; color: #606266; }
.score b { font-size: 17px; color: #2a78d6; }
.adder { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 6px; }
.adder .hint { color: #909399; font-size: 12px; }
.totals { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin: 12px 0; }
.charts { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 8px; }
.chart { height: 260px; }
h4 { margin: 14px 0 6px; padding-left: 6px; border-left: 3px solid #409eff; font-size: 14px; }
</style>
