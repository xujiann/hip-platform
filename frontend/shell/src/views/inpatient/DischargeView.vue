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
        <el-descriptions-item :label="Number(balance) >= 0 ? '应退' : '欠费应补'">
          <span class="balance">¥{{ Math.abs(Number(balance)).toFixed(2) }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <!-- 收尾环·阻塞1：欠费出院不硬拦（医院常规，事后追缴），但必须明确标注欠费金额 -->
      <el-alert v-if="Number(balance) < 0" type="error" show-icon :closable="false" style="margin-top: 8px"
                :title="`欠费 ¥${Math.abs(Number(balance)).toFixed(2)}，允许欠费出院但请提醒患者续交/事后追缴`" />
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
        <!-- 车道B 收尾①：住院中间结算——住院期间就已发生费用出阶段性结算单，不出院、不释放床位 -->
        <el-button type="warning" :loading="interiming" @click="interimSettle">中间结算</el-button>
        <el-button type="danger" :loading="discharging" @click="discharge">出院结算</el-button>
        <el-button @click="printSummary()">打印出院小结</el-button>
      </div>

      <!-- 历次中间结算（与出院结算口径不重复：中间结算只结已发生费用的子集，出院结算按台账现算全账单） -->
      <div v-if="interims.length" style="margin-top: 10px;">
        <b style="font-size: 13px;">历次中间结算</b>
        <el-table :data="interims" size="small" style="margin-top: 4px">
          <el-table-column prop="settle_no" label="结算单号" width="180" />
          <el-table-column prop="total_amount" label="本次结算" width="100">
            <template #default="{ row }">¥{{ Number(row.total_amount).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="balance" label="当时余额" width="100">
            <template #default="{ row }">¥{{ Number(row.balance).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="created_at" label="时间">
            <template #default="{ row }">{{ String(row.created_at).slice(0, 19).replace('T', ' ') }}</template>
          </el-table-column>
        </el-table>
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
        <el-button size="small" @click="printDaily">打印日清单</el-button>
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
const interiming = ref(false)
const interims = ref<Record<string, unknown>[]>([])

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

// 收尾环·打印：住院单据在独立打印页新开一页（与门诊 PrintView 同一入口）
function openPrint(query: Record<string, string>) {
  const qs = new URLSearchParams(query).toString()
  window.open(`/print?${qs}`, '_blank')
}
function printDaily() {
  if (!current.value) return
  openPrint({ type: 'inp-daily-fee', id: String(current.value.id), date: dailyDate.value })
}
function printSummary(admissionId?: number | string) {
  const aid = admissionId ?? current.value?.id
  if (!aid) return
  openPrint({ type: 'inp-discharge-summary', id: String(aid) })
}

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
  await Promise.all([loadDaily(), loadInterims()])
}

// 车道B 收尾①：历次中间结算
async function loadInterims() {
  if (!current.value) { interims.value = []; return }
  interims.value = (await client.get(
    `/inpatient/admissions/${current.value.id}/interim-settlements`)).data.data
}

async function interimSettle() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm(
      `确认为 ${current.value.patientName} 做住院中间结算？将就当前已发生费用出一张中间结算单（不出院、不释放床位）。`,
      '中间结算确认')
  } catch {
    return   // 用户取消
  }
  interiming.value = true
  try {
    // 业务错误（9030 已出院 / 9031 无新增费用 / 9032 医保通道）由拦截器统一红字提示并 reject
    const resp = await client.post(`/inpatient/admissions/${current.value.id}/interim-settle`)
    const s = resp.data.data
    ElMessage.success(`中间结算完成 ${s.settleNo}：本次结算 ¥${s.totalAmount}，当时余额 ¥${s.balance}`)
    await loadInterims()
  } finally {
    interiming.value = false
  }
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
  const dischargedId = current.value.id
  discharging.value = true
  try {
    const resp = await client.post(`/inpatient/admissions/${current.value.id}/discharge`, null,
      { params: { payMethod: payMethod.value } })
    const s = resp.data.data
    // 欠费出院（balance<0）：不硬拦，但结算提示明确标注"欠费应补"
    const msg = Number(s.balance) >= 0 ? `应退 ¥${s.balance}` : `欠费应补 ¥${Math.abs(Number(s.balance))}`
    ElMessage.success(`出院结算完成 ${s.settleNo}：费用 ¥${s.totalAmount}，押金 ¥${s.depositAmount}，${msg}`)
    // 结算后自动开出院小结打印页（患者离院即带走）
    printSummary(dischargedId as number)
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
