<template>
  <div class="inp-doctor">
    <el-card class="list">
      <template #header>在院患者</template>
      <el-table :data="admissions" highlight-current-row height="calc(100vh - 220px)" @current-change="open">
        <el-table-column prop="bedNo" label="床" width="50" />
        <el-table-column prop="patientName" label="姓名" width="80" />
        <el-table-column prop="admitDiagName" label="诊断" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-card v-if="current" class="workspace">
      <template #header>
        <b>{{ current.patientName }}</b>（{{ current.admissionNo }} · {{ current.wardName }} {{ current.bedNo }}床）
        <el-button size="small" style="margin-left: 8px" @click="openTransfer">转科</el-button>
        <span class="fees">
          费用 ¥{{ totalAmount }} / 押金 ¥{{ depositAmount }} /
          <span :class="{ owed: account?.owed }">余额 ¥{{ account ? account.balance : '-' }}</span>
        </span>
      </template>
      <el-tabs v-model="tab">
        <el-tab-pane label="医嘱" name="orders">
      <!-- 收尾环·阻塞1：押金/余额条，余额为负标红提醒（不硬拦开单，医疗行为不因欠费停摆） -->
      <el-alert v-if="account?.owed" type="error" show-icon :closable="false" style="margin-bottom: 8px"
                :title="`欠费 ¥${Math.abs(Number(account?.balance)).toFixed(2)}，请提醒患者续交押金`" />
      <div class="add-row">
        <el-select v-model="drugId" filterable remote :remote-method="searchDrugs" placeholder="药品" style="width: 240px">
          <el-option v-for="d in drugOptions" :key="d.id as number"
                     :label="`${d.name}（¥${d.price}，存${d.stock}）`" :value="d.id as number" />
        </el-select>
        <el-input v-model="dose" placeholder="单次量" style="width: 80px" />
        <el-select v-model="freq" style="width: 80px">
          <el-option v-for="f in ['qd', 'bid', 'tid', 'q8h', 'st']" :key="f" :label="f" :value="f" />
        </el-select>
        <el-select v-model="route" style="width: 90px">
          <el-option v-for="u in ['口服', '静滴', '肌注']" :key="u" :label="u" :value="u" />
        </el-select>
        <el-input-number v-model="qty" :min="1" :max="999" style="width: 90px" />
        <el-button type="primary" @click="addDrug">开药</el-button>
        <el-select v-model="itemId" filterable remote :remote-method="searchItems" placeholder="检查/检验/治疗"
                   style="width: 220px">
          <el-option v-for="c in itemOptions" :key="c.id as number" :label="`${c.name}（¥${c.price}）`"
                     :value="c.id as number" />
        </el-select>
        <el-button type="primary" @click="addItem">开申请</el-button>
      </div>
      <el-table :data="orders" size="small" height="calc(100vh - 330px)">
        <el-table-column prop="groupNo" label="医嘱号" width="140" />
        <el-table-column label="类型" width="60">
          <template #default="{ row }">{{ { DRUG: '药', LAB: '验', EXAM: '查', TREAT: '治' }[row.orderType as string] }}</template>
        </el-table-column>
        <el-table-column prop="itemName" label="项目" />
        <el-table-column label="用法" width="150">
          <template #default="{ row }">
            <span v-if="row.orderType === 'DRUG'">{{ row.usageRoute }} {{ row.dosePerTime }} {{ row.frequency }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="qty" label="量" width="50" />
        <el-table-column prop="amount" label="金额" width="80" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="{ CREATED: 'warning', EXECUTED: 'success', CANCELLED: 'info' }[row.status as string]">
              {{ { CREATED: '未执行', EXECUTED: '已执行', CANCELLED: '作废' }[row.status as string] }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
        </el-tab-pane>

        <el-tab-pane label="病历" name="records">
          <el-form inline>
            <el-form-item>
              <el-select v-model="recordType" style="width: 130px">
                <el-option label="入院记录" value="ADMISSION" />
                <el-option label="病程记录" value="PROGRESS" />
                <el-option label="出院小结" value="DISCHARGE" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-input v-model="recordTitle" placeholder="标题（可空）" style="width: 180px" />
            </el-form-item>
          </el-form>
          <el-input v-model="recordContent" type="textarea" :rows="4" placeholder="病历内容" />
          <el-button type="primary" style="margin-top: 8px" @click="addRecord">保存记录</el-button>
          <el-timeline style="margin-top: 16px">
            <el-timeline-item v-for="r in records" :key="r.id as number"
                              :timestamp="`${String(r.createdAt).slice(0, 16).replace('T', ' ')} · ${recordTypeNames[r.recordType as string]}`">
              <b>{{ r.title }}</b>
              <el-tag v-if="r.signature" size="small" type="success" style="margin-left: 6px">已签名</el-tag>
              <el-button v-else size="small" link type="primary" style="margin-left: 6px"
                         @click="signRecord(r)">签名</el-button>
              <!-- 阻塞4：签名冻结病历只能追加补正，不能改原文 -->
              <el-button v-if="r.signature" size="small" link type="warning" style="margin-left: 6px"
                         @click="openAmend(r)">补正</el-button>
              <p class="record-content">{{ r.content }}</p>
            </el-timeline-item>
          </el-timeline>
        </el-tab-pane>

        <el-tab-pane label="体征" name="vitals">
          <VitalsChart :vitals="vitals" />
          <el-table :data="vitals" size="small" height="calc(100vh - 560px)">
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ String(row.measuredAt).slice(0, 16).replace('T', ' ') }}</template>
            </el-table-column>
            <el-table-column prop="temperature" label="体温℃" width="80" />
            <el-table-column prop="pulse" label="脉搏" width="70" />
            <el-table-column prop="respiration" label="呼吸" width="70" />
            <el-table-column label="血压" width="100">
              <template #default="{ row }">
                <span v-if="row.sbp">{{ row.sbp }}/{{ row.dbp }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="spo2" label="SpO₂%" width="80" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
    <el-empty v-else class="workspace" description="选择在院患者" />

    <!-- 收尾环·阻塞3：转科转床（选目标科室 + 空床 + 原因，调已有 transfer 接口） -->
    <el-dialog v-model="transferVisible" title="转科转床" width="600px">
      <el-form label-width="90px">
        <el-form-item label="目标科室" required>
          <el-select v-model="tf.toDeptId" placeholder="选择收治科室" style="width: 100%">
            <el-option v-for="d in clinicalDepts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标病区" required>
          <el-select v-model="tfWardId" placeholder="选择病区后挑选空床" style="width: 100%" @change="loadTransferBeds">
            <el-option v-for="w in wards" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标床位" required>
          <el-radio-group v-model="tf.toBedId">
            <el-radio v-for="b in transferBeds" :key="b.id as number" :value="b.id as number"
                      :disabled="b.status !== 'FREE'" border style="margin: 2px">
              {{ b.bedNo }}{{ b.status !== 'FREE' ? `(${b.patientName ?? '占'})` : '' }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="转科原因">
          <el-input v-model="tf.reason" type="textarea" :rows="2" placeholder="如：病情变化需专科处理" />
        </el-form-item>
      </el-form>
      <el-divider>转科历史</el-divider>
      <el-table :data="transferHistory" size="small" height="160" empty-text="暂无转科记录">
        <el-table-column label="时间" width="140">
          <template #default="{ row }">{{ String(row.created_at).slice(0, 16).replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column label="由">
          <template #default="{ row }">{{ row.from_dept_name }} {{ row.from_bed_no }}床</template>
        </el-table-column>
        <el-table-column label="至">
          <template #default="{ row }">{{ row.to_dept_name }} {{ row.to_bed_no }}床</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" :loading="transferring" @click="doTransfer">确认转科</el-button>
      </template>
    </el-dialog>

    <!-- 阻塞4：住院病历补正（签名冻结病历追加法定留痕，不改原文） -->
    <el-dialog v-model="amendVisible" title="病历补正" width="560px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 10px"
                :title="`《${amendTarget?.title ?? ''}》已签名冻结，原文保留，追加补正记录留痕可追溯`" />
      <el-form label-width="80px">
        <el-form-item label="补正内容" required>
          <el-input v-model="amendForm.amendText" type="textarea" :rows="3" placeholder="正确的表述/更正说明" />
        </el-form-item>
        <el-form-item label="补正原因" required>
          <el-input v-model="amendForm.reason" placeholder="如：录入笔误、诊断补充" />
        </el-form-item>
      </el-form>
      <el-divider>补正历史</el-divider>
      <el-timeline v-if="recordAmendments.length">
        <el-timeline-item v-for="a in recordAmendments" :key="a.id as number"
                          :timestamp="`${String(a.amended_at).slice(0, 16).replace('T', ' ')} · ${a.amended_by_name ?? ('用户' + a.amended_by)}`">
          <b>补正：</b>{{ a.amend_text }}
          <div class="record-content">原因：{{ a.reason }}</div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="暂无补正记录" :image-size="60" />
      <template #footer>
        <el-button @click="amendVisible = false">关闭</el-button>
        <el-button type="warning" :loading="amending" @click="submitAmend">提交补正</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import VitalsChart from '../../components/VitalsChart.vue'

const admissions = ref<Record<string, unknown>[]>([])
const current = ref<Record<string, unknown> | null>(null)
const orders = ref<Record<string, unknown>[]>([])
const totalAmount = ref(0)
const depositAmount = ref(0)
// 收尾环·阻塞1：住院账户实时状态（押金/已发生费用/余额/是否欠费）
const account = ref<{ balance: number; owed: boolean } | null>(null)
const drugOptions = ref<Record<string, unknown>[]>([])
const itemOptions = ref<Record<string, unknown>[]>([])
const drugId = ref<number | null>(null)
const itemId = ref<number | null>(null)
const dose = ref('1粒')
const freq = ref('bid')
const route = ref('口服')
const qty = ref(1)
const tab = ref('orders')
const records = ref<Record<string, unknown>[]>([])
const vitals = ref<Record<string, unknown>[]>([])
const recordType = ref('PROGRESS')
const recordTitle = ref('')
const recordContent = ref('')
const recordTypeNames: Record<string, string> = { ADMISSION: '入院记录', PROGRESS: '病程记录', DISCHARGE: '出院小结' }

// 转科转床（收尾环·阻塞3）
const depts = ref<{ id: number; name: string; type: string }[]>([])
const clinicalDepts = computed(() => depts.value.filter((d) => d.type === 'CLINICAL'))
const wards = computed(() => depts.value.filter((d) => d.type === 'NURSING'))
const transferVisible = ref(false)
const transferring = ref(false)
const tfWardId = ref<number | null>(null)
const transferBeds = ref<{ id: number; bedNo: string; status: string; patientName?: string }[]>([])
const transferHistory = ref<Record<string, unknown>[]>([])
const tf = reactive({ toDeptId: null as number | null, toBedId: null as number | null, reason: '' })

// 病历补正（阻塞4）
const amendVisible = ref(false)
const amending = ref(false)
const amendTarget = ref<Record<string, unknown> | null>(null)
const amendForm = reactive({ amendText: '', reason: '' })
const recordAmendments = ref<Record<string, unknown>[]>([])

async function loadList() {
  const resp = await client.get('/inpatient/admissions')
  admissions.value = resp.data.data
}

async function loadAccount(id: unknown) {
  account.value = (await client.get(`/inpatient/admissions/${id}/account`)).data.data
}

async function open(row: Record<string, unknown> | null) {
  current.value = row
  account.value = null
  if (!row) return
  const [ws, rec, vit] = await Promise.all([
    client.get(`/inpatient/admissions/${row.id}/workspace`),
    client.get(`/inpatient/admissions/${row.id}/records`),
    client.get(`/inpatient/admissions/${row.id}/vitals`),
    loadAccount(row.id),
  ])
  orders.value = ws.data.data.orders
  totalAmount.value = ws.data.data.totalAmount
  depositAmount.value = ws.data.data.depositAmount
  records.value = rec.data.data
  vitals.value = vit.data.data
}

function openTransfer() {
  if (!current.value) return
  tf.toDeptId = null
  tf.toBedId = null
  tf.reason = ''
  tfWardId.value = null
  transferBeds.value = []
  loadTransferHistory()
  transferVisible.value = true
}

async function loadTransferHistory() {
  if (!current.value) return
  transferHistory.value = (await client.get(`/inpatient/admissions/${current.value.id}/transfers`)).data.data
}

async function loadTransferBeds() {
  if (!tfWardId.value) return
  tf.toBedId = null
  transferBeds.value = (await client.get('/inpatient/beds', { params: { wardId: tfWardId.value } })).data.data
}

async function doTransfer() {
  if (!current.value) return
  if (!tf.toDeptId || !tf.toBedId) {
    ElMessage.warning('请选择目标科室与空床')
    return
  }
  transferring.value = true
  try {
    await client.post(`/inpatient/admissions/${current.value.id}/transfer`, {
      toDeptId: tf.toDeptId, toBedId: tf.toBedId, reason: tf.reason || null,
    })
    ElMessage.success('转科成功')
    transferVisible.value = false
    await loadList()
    // 转科改了科室/床位，刷新当前工作区表头
    const updated = admissions.value.find((a) => a.id === current.value?.id) ?? null
    await open(updated)
  } finally {
    transferring.value = false
  }
}

async function addRecord() {
  if (!current.value || !recordContent.value) {
    ElMessage.warning('请填写病历内容')
    return
  }
  await client.post(`/inpatient/admissions/${current.value.id}/records`, {
    recordType: recordType.value,
    title: recordTitle.value || recordTypeNames[recordType.value],
    content: recordContent.value,
  })
  ElMessage.success('病历已保存')
  recordContent.value = ''
  recordTitle.value = ''
  await open(current.value)
}

// 1.0.4：病历 CA 签名（签名后冻结标识）
async function signRecord(r: Record<string, unknown>) {
  if (!current.value) return
  await client.post(`/inpatient/admissions/${current.value.id}/records/${r.id}/sign`)
  ElMessage.success('已签名')
  await open(current.value)
}

// 阻塞4：签名冻结病历追加补正记录（原文保留，法定留痕）
async function openAmend(r: Record<string, unknown>) {
  amendTarget.value = r
  amendForm.amendText = ''
  amendForm.reason = ''
  recordAmendments.value = await loadRecordAmendments(r.id as number)
  amendVisible.value = true
}

async function loadRecordAmendments(recordId: number) {
  if (!current.value) return []
  const resp = await client.get(`/inpatient/admissions/${current.value.id}/records/${recordId}/amendments`)
  return (resp.data.data ?? []) as Record<string, unknown>[]
}

async function submitAmend() {
  if (!current.value || !amendTarget.value) return
  if (!amendForm.amendText.trim() || !amendForm.reason.trim()) {
    ElMessage.warning('补正内容与补正原因均须填写')
    return
  }
  amending.value = true
  try {
    const resp = await client.post(
      `/inpatient/admissions/${current.value.id}/records/${amendTarget.value.id}/amend`,
      { amendText: amendForm.amendText, reason: amendForm.reason },
    )
    if (resp.data.code !== 0) {
      ElMessage.error(resp.data.message)
      return
    }
    ElMessage.success('补正已留痕')
    amendForm.amendText = ''
    amendForm.reason = ''
    recordAmendments.value = await loadRecordAmendments(amendTarget.value.id as number)
  } finally {
    amending.value = false
  }
}

async function searchDrugs(kw: string) {
  const resp = await client.get('/masterdata/drugs', { params: { keyword: kw } })
  drugOptions.value = resp.data.data
}

async function searchItems(kw: string) {
  const resp = await client.get('/masterdata/charge-items', { params: { keyword: kw } })
  itemOptions.value = resp.data.data
}

async function addDrug() {
  if (!current.value || !drugId.value) return
  await client.post(`/inpatient/admissions/${current.value.id}/orders`, {
    lines: [{ orderType: 'DRUG', itemId: drugId.value, qty: qty.value, usageRoute: route.value, frequency: freq.value, dosePerTime: dose.value }],
  })
  ElMessage.success('医嘱已开立')
  drugId.value = null
  await open(current.value)
}

async function addItem() {
  if (!current.value || !itemId.value) return
  const item = itemOptions.value.find((c) => c.id === itemId.value)
  await client.post(`/inpatient/admissions/${current.value.id}/orders`, {
    lines: [{ orderType: item?.category ?? 'TREAT', itemId: itemId.value, qty: 1 }],
  })
  ElMessage.success('申请已开立')
  itemId.value = null
  await open(current.value)
}

onMounted(async () => {
  depts.value = (await client.get('/system/depts')).data.data
  await loadList()
})
</script>

<style scoped>
.inp-doctor { display: grid; grid-template-columns: 300px 1fr; gap: 12px; }
.add-row { display: flex; gap: 6px; align-items: center; margin-bottom: 8px; flex-wrap: wrap; }
.fees { float: right; color: #909399; font-size: 13px; }
.owed { color: #d03050; font-weight: 700; }
.record-content { white-space: pre-wrap; color: #555; margin: 4px 0 0; }
</style>
