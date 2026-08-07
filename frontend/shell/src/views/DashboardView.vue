<template>
  <div>
    <el-row :gutter="12">
      <el-col v-for="c in cards" :key="c.label" :span="4">
        <el-card class="stat-card">
          <div class="stat-value" :class="{ warn: c.warn && Number(c.value) > 0 }">{{ c.value }}</div>
          <div class="stat-label">{{ c.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" style="margin-top: 12px">
      <el-col :span="14">
        <el-card>
          <template #header>
            近 7 日门诊趋势
            <el-switch v-model="showTable" active-text="表格" style="float: right" size="small" />
          </template>
          <template v-if="!showTable">
            <div ref="regChartEl" class="chart" />
            <div ref="revChartEl" class="chart" />
            <div ref="deptChartEl" class="chart chart-tall" />
          </template>
          <el-table v-else :data="daily" size="small">
            <el-table-column label="日期" width="120">
              <template #default="{ row }">{{ String(row.day).slice(0, 10) }}</template>
            </el-table-column>
            <el-table-column prop="registrations" label="挂号量" width="90" />
            <el-table-column prop="revenue" label="门诊收入 ¥" width="110" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card style="margin-bottom: 12px">
          <template #header>运营指标</template>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="药占比">{{ op.drugRatio }}%</el-descriptions-item>
            <el-descriptions-item label="门诊均次费用">¥{{ op.avgOutpCost }}</el-descriptions-item>
            <el-descriptions-item label="出院人次">{{ op.dischargedCount }}</el-descriptions-item>
            <el-descriptions-item label="平均住院日">{{ op.avgInpDays }} 天</el-descriptions-item>
            <el-descriptions-item label="住院均次费用">¥{{ op.avgInpCost }}</el-descriptions-item>
            <el-descriptions-item label="病组数">{{ (op.diagnosisGroups as unknown[])?.length ?? 0 }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
        <el-card>
          <template #header>待办工作量</template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="待收费（门诊）">{{ overview.pendingCharges }} 人</el-descriptions-item>
            <el-descriptions-item label="待发药">{{ overview.pendingDispense }} 人</el-descriptions-item>
            <el-descriptions-item label="待执行（医技）">{{ overview.pendingExec }} 项</el-descriptions-item>
            <el-descriptions-item label="待执行（住院医嘱）">{{ overview.pendingInpOrders }} 条</el-descriptions-item>
            <el-descriptions-item label="低库存药品">{{ overview.lowStockDrugs }} 种</el-descriptions-item>
            <el-descriptions-item label="危急值待处理">
              <el-tag v-if="Number(overview.pendingCriticalAlerts) > 0" type="danger" size="small">
                {{ overview.pendingCriticalAlerts }} 条
              </el-tag>
              <span v-else>0 条</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
// echarts/core 按需注册（仅折线/柱状 + 网格/提示框 + Canvas，全量引入曾占 1.1MB chunk）
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, TitleComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import client from '../api/client'

echarts.use([BarChart, LineChart, GridComponent, TitleComponent, TooltipComponent, CanvasRenderer])

const overview = ref<Record<string, unknown>>({})
const daily = ref<Record<string, unknown>[]>([])
const op = ref<Record<string, unknown>>({})
const showTable = ref(false)
const regChartEl = ref<HTMLElement>()
const revChartEl = ref<HTMLElement>()
const deptChartEl = ref<HTMLElement>()

// 配色经 dataviz 校验（蓝/水绿，对比度 WARN 由表格视图承接）；单序列图无图例，标题即命名
const C = { blue: '#2a78d6', aqua: '#1baf7a', grid: '#ebeef0', text: '#52514e' }

function baseAxis(days: string[]) {
  return {
    animation: false,
    grid: { left: 44, right: 16, top: 30, bottom: 22 },
    xAxis: { type: 'category' as const, data: days, axisLine: { lineStyle: { color: C.grid } },
             axisTick: { show: false }, axisLabel: { color: C.text, fontSize: 11 } },
    yAxis: { type: 'value' as const, splitLine: { lineStyle: { color: C.grid } },
             axisLabel: { color: C.text, fontSize: 11 } },
    tooltip: { trigger: 'axis' as const, axisPointer: { type: 'line' as const } },
  }
}

const chartInstances: ReturnType<typeof echarts.init>[] = []
function initChart(el: HTMLElement) {
  const existing = echarts.getInstanceByDom(el)
  if (existing) return existing
  const inst = echarts.init(el)
  chartInstances.push(inst)
  return inst
}
window.addEventListener('resize', () => chartInstances.forEach((c) => c.resize()))

function renderCharts() {
  if (showTable.value || !daily.value.length) return
  const days = daily.value.map((d) => String(d.day).slice(5, 10))
  if (regChartEl.value) {
    initChart(regChartEl.value).setOption({
      ...baseAxis(days),
      title: { text: '门诊人次', left: 0, textStyle: { fontSize: 13, color: C.text } },
      series: [{ type: 'line', name: '门诊人次', data: daily.value.map((d) => d.registrations),
                 lineStyle: { width: 2, color: C.blue }, itemStyle: { color: C.blue },
                 symbol: 'circle', symbolSize: 8, showSymbol: false }],
    })
  }
  if (revChartEl.value) {
    initChart(revChartEl.value).setOption({
      ...baseAxis(days),
      title: { text: '门诊收入（¥）', left: 0, textStyle: { fontSize: 13, color: C.text } },
      series: [{ type: 'line', name: '门诊收入', data: daily.value.map((d) => d.revenue),
                 lineStyle: { width: 2, color: C.aqua }, itemStyle: { color: C.aqua },
                 symbol: 'circle', symbolSize: 8, showSymbol: false }],
    })
  }
  const groups = (op.value.diagnosisGroups as { sample_name: string; cases: number }[] | undefined) ?? []
  if (deptChartEl.value && groups.length) {
    const top = groups.slice(0, 6).reverse()
    initChart(deptChartEl.value).setOption({
      animation: false,
      title: { text: '出院病种 TOP（例数）', left: 0, textStyle: { fontSize: 13, color: C.text } },
      grid: { left: 110, right: 30, top: 30, bottom: 8 },
      xAxis: { type: 'value', splitLine: { lineStyle: { color: C.grid } },
               axisLabel: { color: C.text, fontSize: 11 } },
      yAxis: { type: 'category', data: top.map((g) => g.sample_name),
               axisLine: { lineStyle: { color: C.grid } }, axisTick: { show: false },
               axisLabel: { color: C.text, fontSize: 11 } },
      tooltip: { trigger: 'item' },
      series: [{ type: 'bar', name: '例数', data: top.map((g) => g.cases), barWidth: 14,
                 itemStyle: { color: C.blue, borderRadius: [0, 4, 4, 0] },
                 label: { show: true, position: 'right', color: C.text, fontSize: 11 } }],
    })
  }
}

watch(showTable, async (v) => {
  if (!v) {
    await nextTick()
    renderCharts()
  }
})

const cards = computed(() => [
  { label: '今日挂号', value: overview.value.todayRegistrations ?? '-' },
  { label: '今日门诊收入 ¥', value: overview.value.todayOutpRevenue ?? '-' },
  { label: '今日住院结算 ¥', value: overview.value.todayInpRevenue ?? '-' },
  { label: '在院患者', value: overview.value.inHospitalCount ?? '-' },
  {
    label: '床位使用率',
    value: overview.value.bedTotal
      ? `${Math.round((Number(overview.value.bedOccupied) / Number(overview.value.bedTotal)) * 100)}%`
      : '-',
  },
  { label: '低库存药品', value: overview.value.lowStockDrugs ?? '-', warn: true },
])

onMounted(async () => {
  const [o, d, p] = await Promise.all([
    client.get('/stats/overview'), client.get('/stats/daily?days=7'), client.get('/stats/operation'),
  ])
  overview.value = o.data.data
  daily.value = d.data.data
  op.value = p.data.data
  await nextTick()
  renderCharts()
})
</script>

<style scoped>
.stat-card { text-align: center; }
.stat-value { font-size: 26px; font-weight: 600; color: #1a6fc9; }
.stat-value.warn { color: #e6a23c; }
.stat-label { color: #888; font-size: 13px; margin-top: 4px; }
.chart { height: 150px; }
.chart-tall { height: 200px; }
</style>
