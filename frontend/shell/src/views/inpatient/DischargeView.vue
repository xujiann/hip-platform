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
      <div style="display: flex; gap: 8px; align-items: center; margin-bottom: 8px;">
        <b style="font-size: 13px;">出院诊断</b>
        <el-input v-model="dischargeIcd" placeholder="ICD-10" style="width: 110px" size="small" />
        <el-input v-model="dischargeName" placeholder="诊断名称" style="width: 200px" size="small" />
        <el-button size="small" @click="saveDischargeDiag">保存</el-button>
      </div>
      <div class="actions">
        <el-input-number v-model="extraDeposit" :min="0" :step="100" />
        <el-button @click="addDeposit">补交押金</el-button>
        <el-select v-model="payMethod" style="width: 110px">
          <el-option label="现金结清" value="CASH" />
          <el-option label="医保结算" value="YB" />
        </el-select>
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

      <div style="margin-top: 12px; display: flex; gap: 8px; align-items: center;">
        <b style="font-size: 13px;">每日清单</b>
        <el-date-picker v-model="dailyDate" type="date" value-format="YYYY-MM-DD" size="small"
                        style="width: 140px" @change="loadDaily" />
        <span v-if="daily" style="font-size: 13px; color: #909399;">当日合计 ¥{{ daily.total }}</span>
      </div>
      <el-table v-if="daily" :data="daily.rows" size="small" height="180" style="margin-top: 6px">
        <el-table-column prop="item_name" label="项目" />
        <el-table-column prop="qty" label="量" width="50" />
        <el-table-column prop="amount" label="金额" width="80" />
        <el-table-column prop="order_type" label="类型" width="70" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { todayLocal } from '../../utils/date'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const admissions = ref<Record<string, unknown>[]>([])
const current = ref<Record<string, unknown> | null>(null)
const detail = ref<Record<string, unknown> | null>(null)
const extraDeposit = ref(0)
const payMethod = ref('CASH')
const discharging = ref(false)

// 1.0.1（2067）：每日费用清单
const dailyDate = ref(todayLocal())
const daily = ref<{ total: string; rows: Record<string, unknown>[] } | null>(null)
async function loadDaily() {
  if (!current.value) return
  daily.value = (await client.get(`/inpatient/admissions/${current.value.id}/daily-fees`,
    { params: { date: dailyDate.value } })).data.data
}

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
  daily.value = null
  if (!row) return
  const resp = await client.get(`/inpatient/admissions/${row.id}/workspace`)
  detail.value = resp.data.data
  const adm = (detail.value as Record<string, unknown>)?.admission as Record<string, unknown> | undefined
  dischargeIcd.value = String(adm?.dischargeDiagIcd ?? '')
  dischargeName.value = String(adm?.dischargeDiagName ?? '')
  await loadDaily()
}

// 1.0.4：出院诊断补录（病案编码；DRG 入组优先取出院诊断）
const dischargeIcd = ref('')
const dischargeName = ref('')
async function saveDischargeDiag() {
  if (!current.value) return
  if (!dischargeIcd.value) {
    ElMessage.warning('请填写出院诊断 ICD 编码')
    return
  }
  await client.put(`/inpatient/admissions/${current.value.id}/discharge-diag`,
    { icd: dischargeIcd.value, name: dischargeName.value })
  ElMessage.success('出院诊断已保存')
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
  try {
    await ElMessageBox.confirm(`确认为 ${current.value.patientName} 办理出院结算？`, '出院确认')
  } catch {
    return   // 用户取消
  }
  discharging.value = true
  try {
    const resp = await client.post(`/inpatient/admissions/${current.value.id}/discharge`, null,
      { params: { payMethod: payMethod.value } })
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
