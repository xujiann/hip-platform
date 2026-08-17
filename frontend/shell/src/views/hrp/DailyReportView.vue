<template>
  <el-card>
    <div class="toolbar">
      <h3>日结报表（收费轧账）</h3>
      <div style="display: flex; gap: 8px">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="load" />
        <el-button size="small" @click="exportCsv">导出 CSV</el-button>
      </div>
    </div>
    <el-descriptions :column="3" border size="small" style="margin-bottom: 10px">
      <el-descriptions-item label="实收合计">¥{{ total.paid ?? 0 }}</el-descriptions-item>
      <el-descriptions-item label="退费合计">¥{{ total.refunded ?? 0 }}</el-descriptions-item>
      <el-descriptions-item label="票据数">{{ total.bills ?? 0 }}</el-descriptions-item>
    </el-descriptions>
    <el-table :data="byMethod" size="small" border>
      <el-table-column label="支付方式" width="110">
        <template #default="{ row }">
          {{ { CASH: '现金', WECHAT: '微信', ALIPAY: '支付宝', YB: '医保' }[row.pay_method as string] }}
        </template>
      </el-table-column>
      <el-table-column label="口径" width="100">
        <!-- 1.1.3 B-1：收款按收款日、退款按退费日各自归集（含退往日单） -->
        <template #default="{ row }">{{ row.side === 'COLLECTED' ? '当日收款' : '当日退款' }}</template>
      </el-table-column>
      <el-table-column prop="cnt" label="笔数" width="80" />
      <el-table-column prop="amount" label="金额 ¥" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { todayLocal } from '../../utils/date'
import client from '../../api/client'

const date = ref(todayLocal())
const byMethod = ref<Record<string, unknown>[]>([])
const total = ref<Record<string, unknown>>({})

async function load() {
  const d = (await client.get('/reports/daily-settlement', { params: { date: date.value } })).data.data
  byMethod.value = d.byMethod
  total.value = d.total
}

async function exportCsv() {
  const resp = await client.get('/reports/daily-settlement.csv', {
    params: { date: date.value }, responseType: 'blob',
  })
  const url = URL.createObjectURL(resp.data as Blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `日结_${date.value}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
</style>
