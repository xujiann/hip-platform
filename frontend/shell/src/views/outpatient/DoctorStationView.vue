<template>
  <div class="doctor-page">
    <el-card class="worklist">
      <template #header>
        接诊队列（{{ today }}）
        <el-button link type="primary" style="float: right" @click="loadWorklist">刷新</el-button>
      </template>
      <el-table :data="worklist" highlight-current-row height="calc(100vh - 220px)" @current-change="openPatient">
        <el-table-column prop="regNo" label="号" width="50" />
        <el-table-column prop="patientName" label="姓名" width="80" />
        <el-table-column label="性别/年龄" width="90">
          <template #default="{ row }">
            {{ { M: '男', F: '女', U: '?' }[row.sex as string] }}/{{ row.age ?? '?' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'REGISTERED' ? 'warning' : 'success'" size="small">
              {{ row.status === 'REGISTERED' ? '待诊' : '已诊' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="current" class="workspace">
      <template #header>
        <b>{{ current.patientName }}</b>
        （{{ current.patientNo }} · {{ { M: '男', F: '女', U: '未知' }[current.sex as string] }} · {{ current.age }} 岁）
        <el-tag v-if="current.allergyHistory" type="danger" style="margin-left: 8px">
          过敏：{{ current.allergyHistory }}
        </el-tag>
        <el-button v-if="current.status === 'REGISTERED'" type="primary" size="small" style="float: right"
                   @click="startVisit">
          接 诊
        </el-button>
      </template>

      <el-tabs v-model="tab">
        <el-tab-pane label="病历" name="emr">
          <el-form :model="emr" label-width="80px">
            <el-form-item label="主诉"><el-input v-model="emr.chiefComplaint" /></el-form-item>
            <el-form-item label="现病史"><el-input v-model="emr.presentIllness" type="textarea" :rows="3" /></el-form-item>
            <el-form-item label="既往史"><el-input v-model="emr.pastHistory" type="textarea" :rows="2" /></el-form-item>
            <el-form-item label="体格检查"><el-input v-model="emr.physicalExam" type="textarea" :rows="2" /></el-form-item>
            <el-form-item label="诊断">
              <el-select v-model="diagCodes" multiple filterable remote reserve-keyword
                         :remote-method="searchIcd" placeholder="搜索 ICD（名称/编码/拼音首字母），第一项为主诊断"
                         style="width: 100%">
                <el-option v-for="i in icdOptions" :key="i.code" :label="`${i.name} (${i.code})`" :value="i.code" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="cdssTips.length" label="CDSS">
              <el-alert v-for="(tip, i) in cdssTips" :key="i" :title="tip" type="warning" show-icon
                        :closable="false" style="margin-bottom: 4px" />
            </el-form-item>
            <el-form-item label="处理意见"><el-input v-model="emr.advice" type="textarea" :rows="2" /></el-form-item>
            <el-button type="primary" :loading="savingEmr" @click="saveEmr">保存病历</el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="处方" name="rx">
          <div class="add-row">
            <el-select v-model="rxDrugId" filterable remote :remote-method="searchDrugs"
                       placeholder="搜索药品" style="width: 260px">
              <el-option v-for="d in drugOptions" :key="d.id"
                         :label="`${d.name} ${d.spec}（¥${d.price}/​${d.unit}，存${d.stock}）`" :value="d.id" />
            </el-select>
            <el-input v-model="rxDose" placeholder="单次量" style="width: 90px" />
            <el-select v-model="rxFreq" style="width: 90px">
              <el-option v-for="f in ['qd', 'bid', 'tid', 'qid', 'q8h', 'prn']" :key="f" :label="f" :value="f" />
            </el-select>
            <el-select v-model="rxRoute" style="width: 100px">
              <el-option v-for="u in ['口服', '静滴', '肌注', '外用', '雾化']" :key="u" :label="u" :value="u" />
            </el-select>
            <el-input-number v-model="rxDays" :min="1" :max="30" style="width: 100px" /><span>天</span>
            <el-input-number v-model="rxQty" :min="1" style="width: 100px" /><span>盒/瓶</span>
            <el-button type="primary" @click="addRxLine">加入</el-button>
          </div>
          <el-table :data="rxLines" size="small">
            <el-table-column prop="itemName" label="药品" />
            <el-table-column prop="dosePerTime" label="单次量" width="80" />
            <el-table-column prop="frequency" label="频次" width="70" />
            <el-table-column prop="usageRoute" label="途径" width="70" />
            <el-table-column prop="days" label="天数" width="60" />
            <el-table-column prop="qty" label="数量" width="60" />
            <el-table-column label="" width="60">
              <template #default="{ $index }">
                <el-button link type="danger" @click="rxLines.splice($index, 1)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button type="success" :disabled="!rxLines.length" :loading="submitting" style="margin-top: 8px"
                     @click="submitOrders('rx')">
            开立处方
          </el-button>
        </el-tab-pane>

        <el-tab-pane label="检查检验" name="lab">
          <div class="add-row">
            <el-select v-model="labItemId" filterable remote :remote-method="searchChargeItems"
                       placeholder="搜索检验/检查/治疗项目" style="width: 300px">
              <el-option v-for="c in chargeItemOptions" :key="c.id"
                         :label="`[${categoryNames[c.category as string]}] ${c.name}（¥${c.price}）`" :value="c.id" />
            </el-select>
            <el-button type="primary" @click="addLabLine">加入</el-button>
          </div>
          <el-table :data="labLines" size="small">
            <el-table-column prop="itemName" label="项目" />
            <el-table-column label="类别" width="80">
              <template #default="{ row }">{{ categoryNames[row.category as string] }}</template>
            </el-table-column>
            <el-table-column label="" width="60">
              <template #default="{ $index }">
                <el-button link type="danger" @click="labLines.splice($index, 1)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button type="success" :disabled="!labLines.length" :loading="submitting" style="margin-top: 8px"
                     @click="submitOrders('lab')">
            提交申请
          </el-button>
        </el-tab-pane>

        <el-tab-pane :label="`已开医嘱(${orders.length})`" name="orders">
          <el-table :data="orders" size="small">
            <el-table-column prop="groupNo" label="单号" width="140" />
            <el-table-column label="类型" width="70">
              <template #default="{ row }">{{ typeNames[row.orderType as string] }}</template>
            </el-table-column>
            <el-table-column prop="itemName" label="项目" />
            <el-table-column prop="qty" label="量" width="50" />
            <el-table-column prop="amount" label="金额" width="80" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="orderStatusTag[row.status as string]">
                  {{ orderStatusNames[row.status as string] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="" width="70">
              <template #default="{ row }">
                <el-button v-if="row.status === 'CREATED'" link type="danger" @click="cancelOrder(row)">作废</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
    <el-empty v-else class="workspace" description="从左侧队列选择患者" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const categoryNames: Record<string, string> = { LAB: '检验', EXAM: '检查', TREAT: '治疗', MATERIAL: '材料' }
const typeNames: Record<string, string> = { DRUG: '药品', LAB: '检验', EXAM: '检查', TREAT: '治疗' }
const orderStatusNames: Record<string, string> = {
  CREATED: '已开立', CHARGED: '已收费', DISPENSED: '已发药', EXECUTED: '已执行', CANCELLED: '已作废',
}
const orderStatusTag: Record<string, string> = {
  CREATED: 'warning', CHARGED: 'primary', DISPENSED: 'success', EXECUTED: 'success', CANCELLED: 'info',
}

const today = new Date().toISOString().slice(0, 10)
const worklist = ref<Record<string, unknown>[]>([])
const current = ref<Record<string, unknown> | null>(null)
const tab = ref('emr')
const savingEmr = ref(false)
const submitting = ref(false)

const emr = reactive({ chiefComplaint: '', presentIllness: '', pastHistory: '', physicalExam: '', advice: '' })
const diagCodes = ref<string[]>([])
const cdssTips = ref<string[]>([])
const icdOptions = ref<{ code: string; name: string }[]>([])
const knownIcd = new Map<string, string>()

const drugOptions = ref<Record<string, unknown>[]>([])
const rxDrugId = ref<number | null>(null)
const rxDose = ref('1粒')
const rxFreq = ref('tid')
const rxRoute = ref('口服')
const rxDays = ref(3)
const rxQty = ref(1)
const rxLines = ref<Record<string, unknown>[]>([])

const chargeItemOptions = ref<Record<string, unknown>[]>([])
const labItemId = ref<number | null>(null)
const labLines = ref<Record<string, unknown>[]>([])

const orders = ref<Record<string, unknown>[]>([])

async function loadWorklist() {
  const resp = await client.get('/outpatient/doctor/worklist', { params: { date: today } })
  worklist.value = resp.data.data
}

async function openPatient(row: Record<string, unknown> | null) {
  current.value = row
  if (!row) return
  const resp = await client.get(`/outpatient/doctor/${row.registrationId}/workspace`)
  const ws = resp.data.data
  Object.assign(emr, {
    chiefComplaint: ws.emr?.chiefComplaint ?? '', presentIllness: ws.emr?.presentIllness ?? '',
    pastHistory: ws.emr?.pastHistory ?? '', physicalExam: ws.emr?.physicalExam ?? '', advice: ws.emr?.advice ?? '',
  })
  diagCodes.value = (ws.diagnoses ?? []).map((d: { icdCode: string; icdName: string }) => {
    knownIcd.set(d.icdCode, d.icdName)
    return d.icdCode
  })
  icdOptions.value = (ws.diagnoses ?? []).map((d: { icdCode: string; icdName: string }) => ({ code: d.icdCode, name: d.icdName }))
  orders.value = ws.orders ?? []
  rxLines.value = []
  labLines.value = []
}

async function startVisit() {
  if (!current.value) return
  const resp = await client.post(`/outpatient/doctor/${current.value.registrationId}/start`)
  current.value.status = resp.data.data.status
  ElMessage.success('已接诊')
  await loadWorklist()
}

async function searchIcd(kw: string) {
  if (!kw) return
  const resp = await client.get('/masterdata/icd10', { params: { keyword: kw } })
  icdOptions.value = resp.data.data
  resp.data.data.forEach((i: { code: string; name: string }) => knownIcd.set(i.code, i.name))
}

async function saveEmr() {
  if (!current.value) return
  savingEmr.value = true
  try {
    await client.put(`/outpatient/doctor/${current.value.registrationId}/emr`, {
      emr,
      diagnoses: diagCodes.value.map((code) => ({ icdCode: code, icdName: knownIcd.get(code) ?? code })),
    })
    ElMessage.success('病历已保存')
    await loadCdssTips()
  } finally {
    savingEmr.value = false
  }
}

// CDSS：按主诊断给出诊疗建议
async function loadCdssTips() {
  cdssTips.value = []
  const primary = diagCodes.value[0]
  if (!primary) return
  const resp = await client.get('/cdss/suggestions', { params: { icd: primary } })
  cdssTips.value = (resp.data.data as { content: string }[]).map((s) => s.content)
}

async function searchDrugs(kw: string) {
  const resp = await client.get('/masterdata/drugs', { params: { keyword: kw } })
  drugOptions.value = resp.data.data
}

function addRxLine() {
  const drug = drugOptions.value.find((d) => d.id === rxDrugId.value)
  if (!drug) return
  rxLines.value.push({
    orderType: 'DRUG', itemId: drug.id, itemName: drug.name, qty: rxQty.value,
    usageRoute: rxRoute.value, frequency: rxFreq.value, dosePerTime: rxDose.value, days: rxDays.value,
  })
  rxDrugId.value = null
}

async function searchChargeItems(kw: string) {
  const resp = await client.get('/masterdata/charge-items', { params: { keyword: kw } })
  chargeItemOptions.value = resp.data.data
}

function addLabLine() {
  const item = chargeItemOptions.value.find((c) => c.id === labItemId.value)
  if (!item) return
  labLines.value.push({ orderType: item.category, itemId: item.id, itemName: item.name, category: item.category, qty: 1 })
  labItemId.value = null
}

async function submitOrders(kind: 'rx' | 'lab') {
  if (!current.value) return
  const lines = kind === 'rx' ? rxLines.value : labLines.value
  submitting.value = true
  try {
    await client.post(`/outpatient/doctor/${current.value.registrationId}/orders`, { lines })
    ElMessage.success(kind === 'rx' ? '处方已开立' : '申请已提交')
    if (kind === 'rx') rxLines.value = []
    else labLines.value = []
    await openPatient(current.value)
    tab.value = 'orders'
  } finally {
    submitting.value = false
  }
}

async function cancelOrder(row: Record<string, unknown>) {
  await client.put(`/outpatient/doctor/orders/${row.id}/cancel`)
  ElMessage.success('已作废')
  await openPatient(current.value)
}

onMounted(loadWorklist)
</script>

<style scoped>
.doctor-page {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 12px;
}
.add-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
</style>
