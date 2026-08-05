<template>
  <el-card>
    <el-descriptions v-if="aud" :column="3" border size="small" style="margin-bottom: 10px">
      <el-descriptions-item label="抗菌药 DDDs">{{ aud.ddds }}</el-descriptions-item>
      <el-descriptions-item label="出院床日数">{{ aud.bedDays }}</el-descriptions-item>
      <el-descriptions-item label="使用强度 AUD">
        <b :style="{ color: Number(aud.aud) > 40 ? '#dc2626' : '#16a34a' }">{{ aud.aud }}</b>
        <span class="muted">（国考参考 ≤40）</span>
      </el-descriptions-item>
    </el-descriptions>

    <el-tabs v-model="tab">
      <el-tab-pane label="使用汇总 TOP30" name="usage">
        <el-table :data="usage" size="small" border>
          <el-table-column prop="item_name" label="药品" show-overflow-tooltip />
          <el-table-column label="类别" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.drug_class === 'C' ? 'warning' : 'info'">
                {{ row.drug_class === 'C' ? '中成药' : '西药' }}</el-tag>
              <el-tag v-if="row.antibiotic" size="small" type="danger" style="margin-left: 3px">抗</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="total_qty" label="数量" width="90" />
          <el-table-column prop="total_amount" label="金额" width="110" />
          <el-table-column prop="visits" label="人次" width="90" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="中西药构成" name="class">
        <el-table :data="classSummary" size="small" border>
          <el-table-column prop="drug_class" label="类别" width="110" />
          <el-table-column prop="varieties" label="品种数" width="100" />
          <el-table-column prop="total_qty" label="数量" width="100" />
          <el-table-column prop="total_amount" label="金额" width="120" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="经费调查表" name="fund">
        <el-table :data="monthlyFund" size="small" border>
          <el-table-column prop="month" label="月份" width="100" />
          <el-table-column prop="western" label="西药" width="110" />
          <el-table-column prop="chinese" label="中成药" width="110" />
          <el-table-column prop="antibiotic" label="其中抗菌药" width="110" />
          <el-table-column prop="total" label="合计" width="110" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import client from '../../api/client'

const tab = ref('usage')
const aud = ref<Record<string, unknown> | null>(null)
const usage = ref<Record<string, unknown>[]>([])
const classSummary = ref<Record<string, unknown>[]>([])
const monthlyFund = ref<Record<string, unknown>[]>([])

async function load() {
  aud.value = (await client.get('/drug-analysis/aud')).data.data
  usage.value = (await client.get('/drug-analysis/usage')).data.data
}
watch(tab, async (t) => {
  if (t === 'class') classSummary.value = (await client.get('/drug-analysis/class-summary')).data.data
  if (t === 'fund') monthlyFund.value = (await client.get('/drug-analysis/monthly-fund')).data.data
})
onMounted(load)
</script>

<style scoped>
.muted { color: #909399; font-size: 12px; margin-left: 4px; }
</style>
