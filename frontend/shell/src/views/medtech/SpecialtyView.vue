<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="心电/内镜报告" name="ecg">
        <el-radio-group v-model="modality" size="small" @change="loadRis">
          <el-radio-button value="ECG">心电</el-radio-button>
          <el-radio-button value="ENDO">内镜</el-radio-button>
          <el-radio-button value="US">超声</el-radio-button>
        </el-radio-group>
        <el-table :data="risList" size="small" border style="margin-top: 10px">
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="item_name" label="项目" show-overflow-tooltip />
          <el-table-column prop="modality" label="模态" width="90" />
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button v-if="row.status !== 'VERIFIED'" link type="primary" size="small" :loading="busyId === row.id" @click="writeReport(row)">
                书写报告</el-button>
              <el-button v-if="row.status === 'REPORTED'" link type="success" size="small" :loading="busyId === row.id" @click="verify(row)">
                审核发布</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="病理" name="path">
        <h4>待取材 <el-button link type="primary" size="small" @click="loadPathPending">刷新</el-button></h4>
        <el-table :data="pathPending" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="item_name" label="项目" show-overflow-tooltip />
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button link type="primary" size="small" :loading="collectBusy === row.order_id" @click="collect(row)">取材打码</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4>标本流转</h4>
        <el-table :data="specimens" size="small" border>
          <el-table-column prop="barcode" label="条码" width="130" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="item_name" label="项目" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column prop="diagnosis" label="诊断" show-overflow-tooltip />
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button v-if="row.status === 'COLLECTED'" link type="primary" size="small" :loading="busyId === row.id" @click="receive(row)">
                核收</el-button>
              <el-button v-if="row.status === 'RECEIVED'" link type="success" size="small" :loading="busyId === row.id" @click="diagnose(row)">
                诊断报告</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="急诊留观" name="er">
        <el-form inline size="small">
          <el-form-item><el-input v-model="er.triageId" placeholder="分诊ID" style="width: 110px" /></el-form-item>
          <el-form-item><el-input v-model="er.bedNo" placeholder="留观床号（如 L01）" style="width: 150px" /></el-form-item>
          <el-button type="primary" size="small" @click="startEr" :loading="startErLoading">入留观</el-button>
        </el-form>
        <el-table :data="observations" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="level" label="分级" width="60" />
          <el-table-column prop="chief_complaint" label="主诉" show-overflow-tooltip />
          <el-table-column prop="bed_no" label="床号" width="70" />
          <el-table-column prop="notes" label="观察记录" width="90" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'IN' ? 'warning' : 'success'" size="small">
                {{ row.status === 'IN' ? '留观中' : outcomeText(row.outcome) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="310">
            <template #default="{ row }">
              <template v-if="row.status === 'IN'">
                <el-button link type="primary" size="small" :loading="busyId === row.id" @click="addErNote(row)">记观察</el-button>
                <el-button link type="success" size="small" :loading="busyId === row.id" @click="endEr(row, 'DISCHARGED')">离院</el-button>
                <el-button link type="warning" size="small" :loading="busyId === row.id" @click="endEr(row, 'ADMITTED')">收住院</el-button>
              </template>
              <!-- 车道B 收尾③：抢救记录录入/查看（危重可随时记，不限留观中，故置于状态判断外） -->
              <el-button link type="danger" size="small" @click="openRescue(row)">抢救记录</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="麻醉记录" name="anes">
        <el-form inline size="small">
          <el-form-item><el-input v-model="anes.surgeryId" placeholder="手术ID" style="width: 100px" /></el-form-item>
          <el-form-item>
            <el-select v-model="anes.phase" style="width: 110px">
              <el-option label="术中" value="INTRA" /><el-option label="复苏 PACU" value="PACU" />
            </el-select>
          </el-form-item>
          <el-form-item label="HR"><el-input v-model="anes.hr" style="width: 70px" /></el-form-item>
          <el-form-item label="BP"><el-input v-model="anes.sbp" style="width: 65px" />/<el-input v-model="anes.dbp" style="width: 65px" /></el-form-item>
          <el-form-item label="SpO2"><el-input v-model="anes.spo2" style="width: 65px" /></el-form-item>
          <el-form-item v-if="anes.phase === 'PACU'" label="Steward">
            <el-input v-model="anes.stewardScore" style="width: 65px" />
          </el-form-item>
          <el-button type="primary" size="small" @click="recordAnes" :loading="recordAnesLoading">记录</el-button>
          <el-button size="small" @click="loadAnes">查询</el-button>
        </el-form>
        <el-alert v-if="pacu" :type="pacu.canLeave ? 'success' : 'warning'" show-icon :closable="false"
                  :title="pacu.latestScore < 0 ? '暂无 PACU 苏醒评分'
                    : `最近 Steward 评分 ${pacu.latestScore} 分，${pacu.canLeave ? '≥4 可出复苏室' : '<4 继续观察'}`"
                  style="margin: 8px 0" />
        <el-table :data="anesRecords" size="small" border>
          <el-table-column prop="recorded_at" label="时间" width="170" />
          <el-table-column label="阶段" width="90">
            <template #default="{ row }">
              <el-tag :type="row.phase === 'PACU' ? 'warning' : 'info'" size="small">
                {{ row.phase === 'PACU' ? '复苏' : '术中' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="hr" label="HR" width="70" />
          <el-table-column label="BP" width="90">
            <template #default="{ row }">{{ row.sbp }}/{{ row.dbp }}</template>
          </el-table-column>
          <el-table-column prop="spo2" label="SpO2" width="70" />
          <el-table-column prop="steward_score" label="Steward" width="80" />
          <el-table-column prop="note" label="备注" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="ICU 记录" name="icu">
        <el-form inline size="small">
          <el-form-item><el-input v-model="icu.admissionId" placeholder="住院ID" style="width: 100px" /></el-form-item>
          <el-form-item label="T"><el-input v-model="icu.temperature" style="width: 70px" /></el-form-item>
          <el-form-item label="P"><el-input v-model="icu.pulse" style="width: 70px" /></el-form-item>
          <el-form-item label="R"><el-input v-model="icu.respiration" style="width: 70px" /></el-form-item>
          <el-form-item label="BP"><el-input v-model="icu.sbp" style="width: 65px" />/<el-input v-model="icu.dbp" style="width: 65px" /></el-form-item>
          <el-form-item label="SpO2"><el-input v-model="icu.spo2" style="width: 65px" /></el-form-item>
          <el-form-item label="GCS"><el-input v-model="icu.gcs" style="width: 65px" /></el-form-item>
          <el-form-item label="入量"><el-input v-model="icu.intakeMl" style="width: 75px" /></el-form-item>
          <el-form-item label="出量"><el-input v-model="icu.outputMl" style="width: 75px" /></el-form-item>
          <el-form-item><el-checkbox v-model="icu.ventilator">呼吸机</el-checkbox></el-form-item>
          <el-button type="primary" size="small" @click="recordIcu" :loading="recordIcuLoading">记录</el-button>
          <el-button size="small" @click="loadIcu">查询</el-button>
        </el-form>
        <el-descriptions v-if="balance" :column="3" border size="small" style="margin: 8px 0">
          <el-descriptions-item label="24h 入量">{{ balance.intake }} ml</el-descriptions-item>
          <el-descriptions-item label="24h 出量">{{ balance.output }} ml</el-descriptions-item>
          <el-descriptions-item label="平衡">{{ balance.balance }} ml</el-descriptions-item>
        </el-descriptions>
        <el-table :data="icuRecords" size="small" border>
          <el-table-column prop="recorded_at" label="时间" width="170" />
          <el-table-column prop="temperature" label="T" width="60" />
          <el-table-column prop="pulse" label="P" width="60" />
          <el-table-column prop="respiration" label="R" width="60" />
          <el-table-column label="BP" width="90">
            <template #default="{ row }">{{ row.sbp }}/{{ row.dbp }}</template>
          </el-table-column>
          <el-table-column prop="spo2" label="SpO2" width="70" />
          <el-table-column prop="gcs" label="GCS" width="60" />
          <el-table-column prop="intake_ml" label="入量" width="70" />
          <el-table-column prop="output_ml" label="出量" width="70" />
          <el-table-column label="呼吸机" width="70">
            <template #default="{ row }"><el-tag v-if="row.ventilator" type="danger" size="small">机</el-tag></template>
          </el-table-column>
          <el-table-column prop="note" label="备注" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <!-- 车道B 收尾③：急诊抢救记录录入 + 查看（独立于住院 ICU） -->
  <el-dialog v-model="rescueDialog" :title="`抢救记录 - ${rescueTarget?.patient_name ?? ''}`" width="820px">
    <el-form inline size="small">
      <el-form-item label="T"><el-input v-model="rescue.temperature" style="width: 70px" /></el-form-item>
      <el-form-item label="P"><el-input v-model="rescue.pulse" style="width: 70px" /></el-form-item>
      <el-form-item label="R"><el-input v-model="rescue.respiration" style="width: 70px" /></el-form-item>
      <el-form-item label="BP"><el-input v-model="rescue.sbp" style="width: 60px" />/<el-input v-model="rescue.dbp" style="width: 60px" /></el-form-item>
      <el-form-item label="SpO2"><el-input v-model="rescue.spo2" style="width: 60px" /></el-form-item>
      <el-form-item label="GCS"><el-input v-model="rescue.gcs" style="width: 60px" /></el-form-item>
      <el-form-item label="入量"><el-input v-model="rescue.intakeMl" style="width: 70px" /></el-form-item>
      <el-form-item label="出量"><el-input v-model="rescue.outputMl" style="width: 70px" /></el-form-item>
    </el-form>
    <el-form size="small">
      <el-form-item label="抢救措施">
        <el-input v-model="rescue.measures" type="textarea" :rows="2"
                  placeholder="如：心肺复苏、气管插管、电除颤、肾上腺素静推…" />
      </el-form-item>
      <el-form-item label="参与人员">
        <el-input v-model="rescue.participants" placeholder="参与抢救的医生/护士（法定留痕）" />
      </el-form-item>
      <el-form-item>
        <el-button type="danger" size="small" :loading="rescueSaving" @click="saveRescue">记录抢救</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="rescues" size="small" border height="240">
      <el-table-column prop="rescue_start" label="开始" width="150">
        <template #default="{ row }">{{ String(row.rescue_start).slice(0, 19).replace('T', ' ') }}</template>
      </el-table-column>
      <el-table-column label="体征" width="150">
        <template #default="{ row }">T{{ row.temperature ?? '-' }} P{{ row.pulse ?? '-' }} GCS{{ row.gcs ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="measures" label="抢救措施" show-overflow-tooltip />
      <el-table-column prop="participants" label="参与人员" width="130" />
      <el-table-column label="转归" width="150">
        <template #default="{ row }">
          <el-tag v-if="row.rescue_end" :type="row.outcome === 'DEATH' ? 'danger' : 'success'" size="small">
            {{ rescueOutcomeText(row.outcome) }}</el-tag>
          <span v-else>
            <el-button link type="success" size="small" @click="endRescue(row, 'SUCCESS')">成功</el-button>
            <el-button link type="danger" size="small" @click="endRescue(row, 'DEATH')">死亡</el-button>
            <el-button link type="warning" size="small" @click="endRescue(row, 'TRANSFERRED')">转院</el-button>
          </span>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('ecg')
const modality = ref('ECG')
const risList = ref<Record<string, unknown>[]>([])
const pathPending = ref<Record<string, unknown>[]>([])
const specimens = ref<Record<string, unknown>[]>([])
const observations = ref<Record<string, unknown>[]>([])
const icuRecords = ref<Record<string, unknown>[]>([])
const balance = ref<Record<string, unknown> | null>(null)
const er = reactive({ triageId: '', bedNo: '' })
const icu = reactive({ admissionId: '', temperature: '', pulse: '', respiration: '', sbp: '', dbp: '',
  spo2: '', gcs: '', intakeMl: '', outputMl: '', ventilator: false })
const anesRecords = ref<Record<string, unknown>[]>([])
const pacu = ref<{ latestScore: number; canLeave: boolean } | null>(null)
const anes = reactive({ surgeryId: '', phase: 'INTRA', hr: '', sbp: '', dbp: '', spo2: '', stewardScore: '' })
const busyId = ref<unknown>(null)
const collectBusy = ref<unknown>(null)
const startErLoading = ref(false)
const recordAnesLoading = ref(false)
const recordIcuLoading = ref(false)

// 车道B 收尾③：急诊抢救记录
const rescueDialog = ref(false)
const rescueTarget = ref<Record<string, unknown> | null>(null)
const rescues = ref<Record<string, unknown>[]>([])
const rescueSaving = ref(false)
const rescue = reactive({ temperature: '', pulse: '', respiration: '', sbp: '', dbp: '',
  spo2: '', gcs: '', intakeMl: '', outputMl: '', measures: '', participants: '' })
const rescueOutcomeText = (o: string) =>
  ({ ONGOING: '进行中', SUCCESS: '抢救成功', DEATH: '死亡', TRANSFERRED: '转院' }[o] ?? o)

async function loadAnes() {
  if (!anes.surgeryId) return
  // /api/anes 归「手术麻醉」开关，而本页菜单是 /specialty 不随开关消失——
  // 关闭手麻后此 tab 的请求会 404（v27-B：此前直接弹全局错误，这里给出可读原因）
  try {
    anesRecords.value = (await client.get('/anes/records', { params: { surgeryId: anes.surgeryId } })).data.data
    pacu.value = (await client.get('/anes/records/pacu-status', { params: { surgeryId: anes.surgeryId } })).data.data
  } catch {
    anesRecords.value = []
    pacu.value = null
    ElMessage.warning('手术麻醉模块未启用或暂不可用')
  }
}
async function recordAnes() {
  if (!anes.surgeryId) { ElMessage.warning('请填写手术ID'); return }
  const num = (v: string) => (v === '' ? null : Number(v))
  recordAnesLoading.value = true
  try {
    await client.post('/anes/records', {
      surgeryId: Number(anes.surgeryId), phase: anes.phase, hr: num(anes.hr), sbp: num(anes.sbp),
      dbp: num(anes.dbp), spo2: num(anes.spo2), stewardScore: num(anes.stewardScore),
    })
    ElMessage.success('已记录')
    await loadAnes()
  } finally { recordAnesLoading.value = false }
}

const outcomeText = (o: string) => ({ DISCHARGED: '已离院', ADMITTED: '已住院', TRANSFERRED: '已转院' }[o] ?? o)

async function loadRis() { risList.value = (await client.get('/ris/worklist', { params: { modality: modality.value } })).data.data }
async function loadPathPending() { pathPending.value = (await client.get('/pathology/pending')).data.data }
async function loadSpecimens() { specimens.value = (await client.get('/pathology/specimens')).data.data }
async function loadEr() { observations.value = (await client.get('/outpatient/er-observation')).data.data }
async function loadIcu() {
  if (!icu.admissionId) return
  icuRecords.value = (await client.get('/inpatient/icu-records', { params: { admissionId: icu.admissionId } })).data.data
  balance.value = (await client.get('/inpatient/icu-records/balance', { params: { admissionId: icu.admissionId } })).data.data
}

async function writeReport(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('检查所见 | 诊断意见（用 | 分隔）', '书写报告',
    { inputValue: '窦性心律，未见明显异常 | 正常心电图' }).catch(() => null)
  if (!res) return
  const { value } = res
  const [findings, impression] = value.split('|').map((s: string) => s.trim())
  busyId.value = row.id
  try {
    await client.put(`/ris/exams/${row.id}/report`, { findings, impression: impression ?? findings })
    await loadRis()
  } finally { busyId.value = null }
}
async function verify(row: Record<string, unknown>) {
  busyId.value = row.id
  try {
    await client.put(`/ris/exams/${row.id}/verify`)
    ElMessage.success('已发布')
    await loadRis()
  } finally { busyId.value = null }
}
async function collect(row: Record<string, unknown>) {
  collectBusy.value = row.order_id
  try {
    const resp = await client.post('/pathology/specimens', null,
      { params: { orderId: row.order_id, specimenDesc: '手术切除标本' } })
    ElMessage.success(`取材成功，条码 ${resp.data.data.barcode}`)
    await Promise.all([loadPathPending(), loadSpecimens()])
  } finally { collectBusy.value = null }
}
async function receive(row: Record<string, unknown>) {
  busyId.value = row.id
  try {
    await client.put(`/pathology/specimens/${row.barcode}/receive`)
    await loadSpecimens()
  } finally { busyId.value = null }
}
async function diagnose(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('病理诊断', '诊断报告', { inputValue: '慢性炎症，未见恶性证据' }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.id
  try {
    await client.put(`/pathology/specimens/${row.barcode}/diagnose`,
      { grossFinding: '灰白组织一块', microFinding: '镜下见慢性炎细胞浸润', diagnosis: value })
    ElMessage.success('已发布')
    await loadSpecimens()
  } finally { busyId.value = null }
}
async function startEr() {
  if (!er.triageId || !er.bedNo) { ElMessage.warning('请填写分诊ID与留观床号'); return }
  startErLoading.value = true
  try {
    await client.post('/outpatient/er-observation', { triageId: Number(er.triageId), bedNo: er.bedNo })
    ElMessage.success('已入留观')
    await loadEr()
  } finally { startErLoading.value = false }
}
async function addErNote(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('观察情况', '病情观察', { inputValue: '神志清，生命体征平稳' }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.id
  try {
    await client.post(`/outpatient/er-observation/${row.id}/notes`, null, { params: { note: value } })
    await loadEr()
  } finally { busyId.value = null }
}
async function endEr(row: Record<string, unknown>, outcome: string) {
  busyId.value = row.id
  try {
    await client.put(`/outpatient/er-observation/${row.id}/end`, null, { params: { outcome } })
    await loadEr()
  } finally { busyId.value = null }
}
async function recordIcu() {
  if (!icu.admissionId) { ElMessage.warning('请填写住院ID'); return }
  const num = (v: string) => (v === '' ? null : Number(v))
  recordIcuLoading.value = true
  try {
    await client.post('/inpatient/icu-records', {
      admissionId: Number(icu.admissionId), temperature: num(icu.temperature), pulse: num(icu.pulse),
      respiration: num(icu.respiration), sbp: num(icu.sbp), dbp: num(icu.dbp), spo2: num(icu.spo2),
      gcs: num(icu.gcs), intakeMl: num(icu.intakeMl), outputMl: num(icu.outputMl), ventilator: icu.ventilator,
    })
    ElMessage.success('已记录')
    await loadIcu()
  } finally { recordIcuLoading.value = false }
}

// 车道B 收尾③：抢救记录——以留观记录 observationId 为入口，后端反查分诊/患者名
async function openRescue(row: Record<string, unknown>) {
  rescueTarget.value = row
  Object.assign(rescue, { temperature: '', pulse: '', respiration: '', sbp: '', dbp: '',
    spo2: '', gcs: '', intakeMl: '', outputMl: '', measures: '', participants: '' })
  await loadRescues()
  rescueDialog.value = true
}
async function loadRescues() {
  if (!rescueTarget.value) { rescues.value = []; return }
  rescues.value = (await client.get('/outpatient/er-rescue',
    { params: { observationId: rescueTarget.value.id } })).data.data
}
async function saveRescue() {
  if (!rescueTarget.value) return
  const num = (v: string) => (v === '' ? null : Number(v))
  rescueSaving.value = true
  try {
    // 业务错误（4567 GCS 越界等）由拦截器统一红字提示
    await client.post('/outpatient/er-rescue', {
      observationId: rescueTarget.value.id,
      temperature: num(rescue.temperature), pulse: num(rescue.pulse), respiration: num(rescue.respiration),
      sbp: num(rescue.sbp), dbp: num(rescue.dbp), spo2: num(rescue.spo2), gcs: num(rescue.gcs),
      intakeMl: num(rescue.intakeMl), outputMl: num(rescue.outputMl),
      measures: rescue.measures || null, participants: rescue.participants || null,
    })
    ElMessage.success('抢救记录已录入')
    await loadRescues()
  } finally { rescueSaving.value = false }
}
async function endRescue(row: Record<string, unknown>, outcome: string) {
  await client.put(`/outpatient/er-rescue/${row.id}/end`, { outcome })
  ElMessage.success('抢救已结束')
  await loadRescues()
}

watch(tab, (t) => {
  if (t === 'path') { loadPathPending(); loadSpecimens() }
  if (t === 'er') loadEr()
})
onMounted(loadRis)
</script>
