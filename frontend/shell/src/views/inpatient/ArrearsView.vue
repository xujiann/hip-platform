<template>
  <el-card>
    <div class="toolbar">
      <h3 style="margin:0">住院欠费挂账台账</h3>
      <div style="display:flex; gap:8px; align-items:center">
        <el-radio-group v-model="status" size="small" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="OPEN">待追缴</el-radio-button>
          <el-radio-button value="PARTIAL">部分补缴</el-radio-button>
          <el-radio-button value="CLEARED">已结清</el-radio-button>
          <el-radio-button value="WRITTEN_OFF">已核销</el-radio-button>
        </el-radio-group>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="summary">
      共 {{ rows.length }} 条，欠费合计 {{ sum('amount') }} 元，已补缴 {{ sum('paid_amount') }} 元，
      剩余应收 {{ sum('remain_amount') }} 元
    </div>

    <el-table :data="rows" size="small" border v-loading="loading">
      <el-table-column prop="admission_no" label="住院号" width="150" />
      <el-table-column prop="patient_name" label="患者" width="100" />
      <el-table-column prop="dept_name" label="科室" width="110" show-overflow-tooltip />
      <el-table-column prop="settle_no" label="结算单号" width="150" show-overflow-tooltip />
      <el-table-column label="欠费额" width="100" align="right">
        <template #default="{ row }">{{ money(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="已补缴" width="100" align="right">
        <template #default="{ row }">{{ money(row.paid_amount) }}</template>
      </el-table-column>
      <el-table-column label="剩余" width="100" align="right">
        <template #default="{ row }">
          <span :class="{ owe: Number(row.remain_amount) > 0 }">{{ money(row.remain_amount) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag[row.status as string]" size="small">
            {{ statusCn[row.status as string] ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="dunning_count" label="催缴次数" width="90" align="center" />
      <el-table-column label="操作" min-width="230">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">明细</el-button>
          <template v-if="row.status === 'OPEN' || row.status === 'PARTIAL'">
            <el-button link type="success" size="small" @click="openPay(row)">补缴</el-button>
            <el-button link type="warning" size="small" @click="openDun(row)">催缴登记</el-button>
            <el-button v-if="isAdmin" link type="danger" size="small" @click="openWriteOff(row)">核销</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <!-- 补缴 -->
    <el-dialog v-model="payVisible" title="欠费补缴" width="440px">
      <el-form label-width="90px" size="small">
        <el-form-item label="患者">{{ current?.patient_name }}（{{ current?.admission_no }}）</el-form-item>
        <el-form-item label="剩余欠费">{{ money(current?.remain_amount) }} 元</el-form-item>
        <el-form-item label="补缴金额" required>
          <el-input-number v-model="payForm.amount" :min="0.01" :precision="2" :step="100"
                           :max="Number(current?.remain_amount ?? 0)" style="width:100%" />
        </el-form-item>
        <el-form-item label="收款方式">
          <el-select v-model="payForm.payMethod" style="width:100%">
            <el-option label="现金" value="CASH" />
            <el-option label="银行卡" value="CARD" />
            <el-option label="微信" value="WECHAT" />
            <el-option label="支付宝" value="ALIPAY" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="hint">补缴登记为「收欠款」独立流水，不计入住院押金，不改动已出院的结算单。</div>
      <template #footer>
        <el-button @click="payVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doPay">确认收款</el-button>
      </template>
    </el-dialog>

    <!-- 催缴登记 -->
    <el-dialog v-model="dunVisible" title="催缴登记" width="440px">
      <el-form label-width="90px" size="small">
        <el-form-item label="患者">{{ current?.patient_name }}（{{ current?.admission_no }}）</el-form-item>
        <el-form-item label="催缴方式" required>
          <el-select v-model="dunForm.method" style="width:100%">
            <el-option label="电话" value="PHONE" />
            <el-option label="短信" value="SMS" />
            <el-option label="上门" value="VISIT" />
            <el-option label="书面函件" value="LETTER" />
          </el-select>
        </el-form-item>
        <el-form-item label="情况说明">
          <el-input v-model="dunForm.note" type="textarea" :rows="3" placeholder="如：联系家属，承诺月底前结清" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dunVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doDun">登记</el-button>
      </template>
    </el-dialog>

    <!-- 核销（仅 ADMIN） -->
    <el-dialog v-model="woVisible" title="欠费核销（坏账）" width="440px">
      <el-alert type="warning" :closable="false" show-icon
                title="核销后该笔欠费不再追缴，将计入坏账损失，不可撤销。" style="margin-bottom:12px" />
      <el-form label-width="90px" size="small">
        <el-form-item label="患者">{{ current?.patient_name }}（{{ current?.admission_no }}）</el-form-item>
        <el-form-item label="核销金额">{{ money(current?.remain_amount) }} 元</el-form-item>
        <el-form-item label="核销原因" required>
          <el-input v-model="woForm.reason" type="textarea" :rows="3" placeholder="如：患者死亡无遗产可执行 / 多次催缴无果，经院办批准核销" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="woVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="doWriteOff">确认核销</el-button>
      </template>
    </el-dialog>

    <!-- 明细 -->
    <el-dialog v-model="detailVisible" title="欠费明细" width="640px">
      <el-descriptions :column="2" border size="small" v-if="detail">
        <el-descriptions-item label="住院号">{{ detail.arrears?.admission_no }}</el-descriptions-item>
        <el-descriptions-item label="患者">{{ detail.arrears?.patient_name }}</el-descriptions-item>
        <el-descriptions-item label="欠费额">{{ money(detail.arrears?.amount) }} 元</el-descriptions-item>
        <el-descriptions-item label="剩余">{{ money(detail.arrears?.remain_amount) }} 元</el-descriptions-item>
        <el-descriptions-item label="状态">
          {{ statusCn[detail.arrears?.status as string] ?? detail.arrears?.status }}
        </el-descriptions-item>
        <el-descriptions-item label="核销原因">{{ detail.arrears?.write_off_reason ?? '-' }}</el-descriptions-item>
      </el-descriptions>
      <h4>补缴流水</h4>
      <el-table :data="detail?.payments ?? []" size="small" border>
        <el-table-column label="金额" width="100" align="right">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="方式" width="90">
          <template #default="{ row }">{{ payCn[row.pay_method as string] ?? row.pay_method }}</template>
        </el-table-column>
        <el-table-column prop="operator_name" label="经办人" width="100" />
        <el-table-column prop="paid_at" label="时间" />
      </el-table>
      <h4>催缴记录</h4>
      <el-table :data="detail?.dunnings ?? []" size="small" border>
        <el-table-column label="方式" width="90">
          <template #default="{ row }">{{ dunCn[row.method as string] ?? row.method }}</template>
        </el-table-column>
        <el-table-column prop="note" label="说明" show-overflow-tooltip />
        <el-table-column prop="operator_name" label="经办人" width="100" />
        <el-table-column prop="dunned_at" label="时间" width="200" />
      </el-table>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'

type Row = Record<string, unknown>

const statusCn: Record<string, string> = {
  OPEN: '待追缴', PARTIAL: '部分补缴', CLEARED: '已结清', WRITTEN_OFF: '已核销',
}
const statusTag: Record<string, string> = {
  OPEN: 'danger', PARTIAL: 'warning', CLEARED: 'success', WRITTEN_OFF: 'info',
}
const payCn: Record<string, string> = { CASH: '现金', CARD: '银行卡', WECHAT: '微信', ALIPAY: '支付宝' }
const dunCn: Record<string, string> = { PHONE: '电话', SMS: '短信', VISIT: '上门', LETTER: '书面函件' }

const auth = useAuthStore()
const isAdmin = computed(() => auth.user?.roles?.includes('ADMIN') ?? false)

const status = ref('OPEN')
const rows = ref<Row[]>([])
const loading = ref(false)
const saving = ref(false)
const current = ref<Row | null>(null)

const payVisible = ref(false)
const dunVisible = ref(false)
const woVisible = ref(false)
const detailVisible = ref(false)
const detail = ref<{ arrears?: Row; payments?: Row[]; dunnings?: Row[] } | null>(null)

const payForm = reactive({ amount: 0, payMethod: 'CASH' })
const dunForm = reactive({ method: 'PHONE', note: '' })
const woForm = reactive({ reason: '' })

function money(v: unknown): string {
  return Number(v ?? 0).toFixed(2)
}
function sum(field: string): string {
  return rows.value.reduce((acc, r) => acc + Number(r[field] ?? 0), 0).toFixed(2)
}

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/inpatient/arrears', { params: { status: status.value || undefined } })).data.data
  } finally {
    loading.value = false
  }
}

function openPay(row: Row) {
  current.value = row
  payForm.amount = Number(row.remain_amount ?? 0)
  payForm.payMethod = 'CASH'
  payVisible.value = true
}
function openDun(row: Row) {
  current.value = row
  dunForm.method = 'PHONE'
  dunForm.note = ''
  dunVisible.value = true
}
function openWriteOff(row: Row) {
  current.value = row
  woForm.reason = ''
  woVisible.value = true
}
async function openDetail(row: Row) {
  current.value = row
  detail.value = (await client.get(`/inpatient/arrears/${row.id}`)).data.data
  detailVisible.value = true
}

async function doPay() {
  if (!payForm.amount || payForm.amount <= 0) { ElMessage.warning('补缴金额须大于 0'); return }
  saving.value = true
  try {
    const r = (await client.post(`/inpatient/arrears/${current.value?.id}/payments`, { ...payForm })).data.data
    ElMessage.success(r.status === 'CLEARED' ? '已收款，该笔欠费已结清' : '已收款，剩余 ' + money(r.remainAmount) + ' 元')
    payVisible.value = false
    await load()
  } finally { saving.value = false }
}

async function doDun() {
  saving.value = true
  try {
    await client.post(`/inpatient/arrears/${current.value?.id}/dunnings`, { ...dunForm })
    ElMessage.success('催缴已登记')
    dunVisible.value = false
    await load()
  } finally { saving.value = false }
}

async function doWriteOff() {
  if (!woForm.reason.trim()) { ElMessage.warning('核销原因必填'); return }
  saving.value = true
  try {
    await client.post(`/inpatient/arrears/${current.value?.id}/write-off`, { reason: woForm.reason })
    ElMessage.success('已核销')
    woVisible.value = false
    await load()
  } finally { saving.value = false }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; gap: 12px; flex-wrap: wrap; }
.summary { margin-bottom: 8px; color: #606266; font-size: 13px; }
.hint { color: #909399; font-size: 12px; line-height: 1.6; }
.owe { color: #f56c6c; font-weight: 600; }
h4 { margin: 14px 0 6px; font-size: 14px; }
</style>
