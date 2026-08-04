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
          <template #header>近 7 日门诊趋势</template>
          <el-table :data="daily" size="small">
            <el-table-column label="日期" width="120">
              <template #default="{ row }">{{ String(row.day).slice(0, 10) }}</template>
            </el-table-column>
            <el-table-column prop="registrations" label="挂号量" width="90" />
            <el-table-column label="" >
              <template #default="{ row }">
                <div class="bar" :style="{ width: barWidth(row.registrations) }" />
              </template>
            </el-table-column>
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
import { computed, onMounted, ref } from 'vue'
import client from '../api/client'

const overview = ref<Record<string, unknown>>({})
const daily = ref<Record<string, unknown>[]>([])
const op = ref<Record<string, unknown>>({})

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

const maxReg = computed(() => Math.max(1, ...daily.value.map((d) => Number(d.registrations))))
function barWidth(v: unknown) {
  return `${Math.round((Number(v) / maxReg.value) * 100)}%`
}

onMounted(async () => {
  const [o, d, p] = await Promise.all([
    client.get('/stats/overview'), client.get('/stats/daily?days=7'), client.get('/stats/operation'),
  ])
  overview.value = o.data.data
  daily.value = d.data.data
  op.value = p.data.data
})
</script>

<style scoped>
.stat-card { text-align: center; }
.stat-value { font-size: 26px; font-weight: 600; color: #1a6fc9; }
.stat-value.warn { color: #e6a23c; }
.stat-label { color: #888; font-size: 13px; margin-top: 4px; }
.bar {
  height: 12px;
  background: #79a8d9;
  border-radius: 2px;
  min-width: 2px;
}
</style>
