<template>
  <div v-loading="loading">
    <el-row :gutter="12" class="mb">
      <el-col :span="4" v-for="card in cards" :key="card.label">
        <el-card>
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-row :gutter="12">
      <el-col :span="8">
        <el-card class="mb">
          <h4>案件状态分布</h4>
          <el-table :data="d.caseByStatus" size="small" border>
            <el-table-column label="状态">
              <template #default="{ row }">{{ CASE_STATUS[row.status] || row.status }}</template>
            </el-table-column>
            <el-table-column prop="cnt" label="件数" width="80" align="right" />
          </el-table>
        </el-card>
        <el-card>
          <h4>当事人类别分布</h4>
          <el-table :data="d.byPartyType" size="small" border>
            <el-table-column label="类别">
              <template #default="{ row }">{{ PARTY_TYPE[row.party_type] || row.party_type }}</template>
            </el-table-column>
            <el-table-column prop="cnt" label="件数" width="80" align="right" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <h4>高频案由 TOP10</h4>
          <el-table :data="d.byCause" size="small" border>
            <el-table-column prop="category" label="案由" show-overflow-tooltip />
            <el-table-column prop="cnt" label="件数" width="80" align="right" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <h4>近12个月立案趋势</h4>
          <el-table :data="d.monthlyFiled" size="small" border>
            <el-table-column prop="ym" label="月份" width="100" />
            <el-table-column prop="cnt" label="立案数" align="right" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import client from '../../api/client'
import { CASE_STATUS, PARTY_TYPE } from './labels'

const d = ref<any>({})
const loading = ref(false)

const cards = computed(() => [
  { label: '线索总数', value: d.value.clueTotal ?? 0, color: '#16417c' },
  { label: '核查中线索', value: d.value.cluePending ?? 0, color: '#b7791f' },
  { label: '案件总数', value: d.value.caseTotal ?? 0, color: '#16417c' },
  { label: '决定罚款(元)', value: d.value.fineDecided ?? 0, color: '#c0392b' },
  { label: '决定追回基金(元)', value: d.value.recoupDecided ?? 0, color: '#c0392b' },
  { label: '已收缴/追回(元)', value: Number(d.value.fineCollected ?? 0) + Number(d.value.recoupCollected ?? 0), color: '#1d7a5f' },
])

onMounted(async () => {
  loading.value = true
  try {
    const resp = await client.get('/bureau/stats/overview')
    d.value = resp.data.data
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.stat-label { font-size: 12px; color: #999; }
.stat-value { font-size: 26px; font-weight: 600; margin-top: 4px; }
h4 { margin: 0 0 8px; }
</style>
