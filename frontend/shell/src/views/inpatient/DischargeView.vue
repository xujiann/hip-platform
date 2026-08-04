<template>
  <div class="discharge-page">
    <el-card>
      <template #header>在院患者</template>
      <el-table :data="admissions" highlight-current-row height="400" @current-change="open">
        <el-table-column prop="admissionNo" label="住院号" width="160" />
        <el-table-column prop="patientName" label="姓名" width="90" />
        <el-table-column prop="wardName" label="病区" width="100" />
        <el-table-column prop="bedNo" label="床" width="50" />
      </el-table>
    </el-card>

    <el-card v-if="detail">
      <template #header><b>{{ current?.patientName }}</b> 费用汇总</template>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="费用总额">¥{{ detail.totalAmount }}</el-descriptions-item>
        <el-descriptions-item label="已交押金">¥{{ detail.depositAmount }}</el-descriptions-item>
        <el-descriptions-item :label="Number(balance) >= 0 ? '应退' : '应补'">
          <span class="balance">¥{{ Math.abs(Number(balance)).toFixed(2) }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <div class="actions">
        <el-input-number v-model="extraDeposit" :min="0" :step="100" />
        <el-button @click="addDeposit">补交押金</el-button>
        <el-button type="danger" :loading="discharging" @click="discharge">出院结算</el-button>
      </div>
      <el-table :data="detail.orders" size="small" height="260" style="margin-top: 12px">
        <el-table-column prop="itemName" label="项目" />
        <el-table-column prop="qty" label="量" width="50" />
        <el-table-column prop="amount" label="金额" width="80" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            {{ { CREATED: '未执行', EXECUTED: '已执行', CANCELLED: '作废' }[row.status as string] }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const admissions = ref<Record<string, unknown>[]>([])
const current = ref<Record<string, unknown> | null>(null)
const detail = ref<Record<string, unknown> | null>(null)
const extraDeposit = ref(0)
const discharging = ref(false)

const balance = computed(() =>
  detail.value ? (Number(detail.value.depositAmount) - Number(detail.value.totalAmount)).toFixed(2) : '0')

async function load() {
  const resp = await client.get('/inpatient/admissions')
  admissions.value = resp.data.data
  detail.value = null
  current.value = null
}

async function open(row: Record<string, unknown> | null) {
  current.value = row
  if (!row) return
  const resp = await client.get(`/inpatient/admissions/${row.id}/workspace`)
  detail.value = resp.data.data
}

async function addDeposit() {
  if (!current.value || !extraDeposit.value) return
  await client.post(`/inpatient/admissions/${current.value.id}/deposits`, {
    amount: extraDeposit.value, payMethod: 'CASH',
  })
  ElMessage.success('押金已补交')
  await open(current.value)
}

async function discharge() {
  if (!current.value) return
  await ElMessageBox.confirm(`确认为 ${current.value.patientName} 办理出院结算？`, '出院确认')
  discharging.value = true
  try {
    const resp = await client.post(`/inpatient/admissions/${current.value.id}/discharge`)
    const s = resp.data.data
    const msg = Number(s.balance) >= 0 ? `应退 ¥${s.balance}` : `应补 ¥${Math.abs(Number(s.balance))}`
    ElMessage.success(`出院结算完成 ${s.settleNo}：费用 ¥${s.totalAmount}，押金 ¥${s.depositAmount}，${msg}`)
    await load()
  } finally {
    discharging.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.discharge-page { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.balance { font-size: 18px; font-weight: 600; color: #e6482e; }
.actions { display: flex; gap: 8px; margin-top: 12px; align-items: center; }
</style>
