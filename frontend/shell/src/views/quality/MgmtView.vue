<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="护理排班" name="shift">
        <el-form inline size="small">
          <el-form-item><el-input v-model="shift.deptId" placeholder="科室ID" style="width: 90px" /></el-form-item>
          <el-form-item><el-input v-model="shift.nurseName" placeholder="护士姓名" style="width: 120px" /></el-form-item>
          <el-form-item>
            <el-date-picker v-model="shift.shiftDate" type="date" value-format="YYYY-MM-DD" style="width: 140px" />
          </el-form-item>
          <el-form-item>
            <el-select v-model="shift.shiftType" style="width: 100px">
              <el-option label="白班" value="DAY" /><el-option label="中班" value="MID" /><el-option label="夜班" value="NIGHT" />
            </el-select>
          </el-form-item>
          <el-button type="primary" size="small" @click="addShift" :loading="addShiftLoading">排班</el-button>
          <el-date-picker v-model="shiftQueryDate" type="date" value-format="YYYY-MM-DD" size="small"
                          style="width: 140px; margin-left: 16px" @change="loadShifts" />
        </el-form>
        <el-table :data="shifts" size="small" border>
          <el-table-column prop="dept_name" label="科室" width="130" />
          <el-table-column prop="nurse_name" label="护士" width="110" />
          <el-table-column prop="shift_date" label="日期" width="120" />
          <el-table-column label="班次" width="90">
            <template #default="{ row }">{{ shiftText(row.shift_type) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="护理质控评分" name="score">
        <el-form inline size="small">
          <el-form-item><el-input v-model="score.deptId" placeholder="科室ID" style="width: 90px" /></el-form-item>
          <el-form-item><el-input v-model="score.item" placeholder="检查项目（如 基础护理）" style="width: 180px" /></el-form-item>
          <el-form-item><el-input-number v-model="score.score" :min="0" :max="100" /></el-form-item>
          <el-button type="primary" size="small" @click="addScore" :loading="addScoreLoading">登记评分</el-button>
        </el-form>
        <h4>科室对比</h4>
        <el-table :data="scoreSummary" size="small" border>
          <el-table-column prop="dept_name" label="科室" width="150" />
          <el-table-column prop="avg_score" label="平均分" width="100" />
          <el-table-column prop="checks" label="检查次数" width="100" />
        </el-table>
        <h4>明细</h4>
        <el-table :data="scores" size="small" border>
          <el-table-column prop="dept_name" label="科室" width="130" />
          <el-table-column prop="item" label="项目" />
          <el-table-column prop="score" label="评分" width="80" />
          <el-table-column prop="checker" label="检查人" width="100" />
          <el-table-column prop="check_date" label="日期" width="110" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="医务台账" name="credential">
        <el-form inline size="small">
          <el-form-item><el-input v-model="cred.staffName" placeholder="医师姓名" style="width: 110px" /></el-form-item>
          <el-form-item>
            <el-select v-model="cred.certType" style="width: 130px">
              <el-option v-for="t in ['执业证', '定期考核', '处方权', '手术授权', '麻醉处方权']" :key="t" :label="t" :value="t" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="cred.certNo" placeholder="证书编号" style="width: 140px" /></el-form-item>
          <el-form-item>
            <el-date-picker v-model="cred.expireDate" type="date" value-format="YYYY-MM-DD" placeholder="到期日"
                            style="width: 140px" />
          </el-form-item>
          <el-button type="primary" size="small" @click="addCred" :loading="addCredLoading">登记</el-button>
        </el-form>
        <el-table :data="credentials" size="small" border>
          <el-table-column prop="staff_name" label="姓名" width="100" />
          <el-table-column prop="cert_type" label="类型" width="110" />
          <el-table-column prop="cert_no" label="编号" width="150" />
          <el-table-column prop="expire_date" label="到期日" width="120" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.validity === 'VALID' ? 'success' : row.validity === 'EXPIRING' ? 'warning' : 'danger'"
                      size="small">
                {{ row.validity === 'VALID' ? '有效' : row.validity === 'EXPIRING' ? '即将到期' : '已过期' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="备注" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="抗菌处方权" name="abx">
        <el-row :gutter="16">
          <el-col :span="14">
            <h4>医师授权（1 非限制 / 2 限制 / 3 特殊）</h4>
            <el-table :data="privileges" size="small" border>
              <el-table-column prop="username" label="账号" width="110" />
              <el-table-column prop="real_name" label="姓名" width="100" />
              <el-table-column prop="level" label="当前级别" width="90" />
              <el-table-column label="调整" width="200">
                <template #default="{ row }">
                  <el-button v-for="l in [1, 2, 3]" :key="l" link :type="row.level === l ? 'info' : 'primary'"
                             size="small" :loading="busyId === row.user_id" @click="grant(row, l)">{{ l }}级</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-col>
          <el-col :span="10">
            <h4>分级药品目录</h4>
            <el-table :data="abxDrugs" size="small" border>
              <el-table-column prop="name" label="药品" />
              <el-table-column label="级别" width="90">
                <template #default="{ row }">
                  <el-tag :type="row.abx_level >= 3 ? 'danger' : row.abx_level === 2 ? 'warning' : 'info'" size="small">
                    {{ row.abx_level }}级</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-col>
        </el-row>
      </el-tab-pane>

      <el-tab-pane label="消毒供应" name="cssd">
        <el-form inline size="small">
          <el-form-item><el-input v-model="cssdName" placeholder="包名（如 剖宫产器械包）" style="width: 200px" /></el-form-item>
          <el-button type="primary" size="small" @click="pack" :loading="packLoading">打包生成条码</el-button>
        </el-form>
        <el-table :data="cssdPackages" size="small" border>
          <el-table-column prop="pkg_no" label="包条码" width="130" />
          <el-table-column prop="name" label="包名" show-overflow-tooltip />
          <el-table-column prop="sterile_batch" label="灭菌批次" width="120" />
          <el-table-column prop="dest_dept_name" label="发放科室" width="110" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'USED' ? 'success' : 'info'">{{ cssdText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="流转" width="220">
            <template #default="{ row }">
              <el-button v-if="row.status === 'PACKED'" link type="primary" size="small"
                         :loading="busyId === row.pkg_no" @click="sterilize(row)">
                灭菌</el-button>
              <el-button v-if="row.status === 'STERILIZED'" link type="warning" size="small"
                         :loading="busyId === row.pkg_no" @click="issueCssd(row)">
                发放</el-button>
              <el-button v-if="row.status === 'ISSUED'" link type="success" size="small"
                         :loading="busyId === row.pkg_no" @click="useCssd(row)">
                使用登记</el-button>
              <el-button link size="small" @click="showTrace(row)">追溯</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="预防保健" name="phc">
        <el-form inline size="small">
          <el-form-item><el-input v-model="phc.patientId" placeholder="患者ID" style="width: 100px" /></el-form-item>
          <el-form-item>
            <el-select v-model="phc.recordType" style="width: 130px">
              <el-option label="疫苗接种" value="VACCINATION" />
              <el-option label="健康体检" value="PHYSICAL" />
              <el-option label="健康宣教" value="EDUCATION" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="phc.content" placeholder="内容（如 流感疫苗第1剂）" style="width: 220px" /></el-form-item>
          <el-button type="primary" size="small" @click="addPhc" :loading="addPhcLoading">登记</el-button>
        </el-form>
        <el-table :data="phcRecords" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">{{ phcText(row.record_type) }}</template>
          </el-table-column>
          <el-table-column prop="content" label="内容" show-overflow-tooltip />
          <el-table-column prop="occurred_date" label="日期" width="120" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { todayLocal } from '../../utils/date'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('shift')
const today = todayLocal()
const shifts = ref<Record<string, unknown>[]>([])
const scores = ref<Record<string, unknown>[]>([])
const scoreSummary = ref<Record<string, unknown>[]>([])
const credentials = ref<Record<string, unknown>[]>([])
const privileges = ref<Record<string, unknown>[]>([])
const abxDrugs = ref<Record<string, unknown>[]>([])
const cssdPackages = ref<Record<string, unknown>[]>([])
const phcRecords = ref<Record<string, unknown>[]>([])
const shiftQueryDate = ref(today)
const cssdName = ref('')
const addShiftLoading = ref(false)
const addScoreLoading = ref(false)
const addCredLoading = ref(false)
const packLoading = ref(false)
const addPhcLoading = ref(false)
const busyId = ref<unknown>(null)
const shift = reactive({ deptId: '', nurseName: '', shiftDate: today, shiftType: 'DAY' })
const score = reactive({ deptId: '', item: '', score: 90 })
const cred = reactive({ staffName: '', certType: '定期考核', certNo: '', expireDate: '' })
const phc = reactive({ patientId: '', recordType: 'VACCINATION', content: '' })

const shiftText = (s: string) => ({ DAY: '白班', MID: '中班', NIGHT: '夜班' }[s] ?? s)
const cssdText = (s: string) => ({ PACKED: '已打包', STERILIZED: '已灭菌', ISSUED: '已发放', USED: '已使用' }[s] ?? s)
const phcText = (s: string) => ({ VACCINATION: '疫苗接种', PHYSICAL: '健康体检', EDUCATION: '健康宣教' }[s] ?? s)

async function loadShifts() { shifts.value = (await client.get('/nursing/shifts', { params: { date: shiftQueryDate.value } })).data.data }
async function loadScores() {
  scores.value = (await client.get('/nursing/qc-scores')).data.data
  scoreSummary.value = (await client.get('/nursing/qc-scores/summary')).data.data
}
async function loadCreds() { credentials.value = (await client.get('/hrp/credentials')).data.data }
async function loadAbx() {
  privileges.value = (await client.get('/outpatient/abx-privileges')).data.data
  abxDrugs.value = (await client.get('/outpatient/abx-privileges/drugs')).data.data
}
async function loadCssd() { cssdPackages.value = (await client.get('/cssd/packages')).data.data }
async function loadPhc() { phcRecords.value = (await client.get('/phc/records')).data.data }

async function addShift() {
  if (!shift.deptId || !shift.nurseName) { ElMessage.warning('请填写完整'); return }
  addShiftLoading.value = true
  try {
    await client.post('/nursing/shifts', { ...shift, deptId: Number(shift.deptId) })
    shiftQueryDate.value = shift.shiftDate
    await loadShifts()
  } finally { addShiftLoading.value = false }
}
async function addScore() {
  if (!score.deptId || !score.item) { ElMessage.warning('请填写完整'); return }
  addScoreLoading.value = true
  try {
    await client.post('/nursing/qc-scores', { ...score, deptId: Number(score.deptId) })
    score.item = ''
    await loadScores()
  } finally { addScoreLoading.value = false }
}
async function addCred() {
  if (!cred.staffName || !cred.expireDate) { ElMessage.warning('请填写完整'); return }
  addCredLoading.value = true
  try {
    await client.post('/hrp/credentials', cred)
    cred.staffName = ''
    await loadCreds()
  } finally { addCredLoading.value = false }
}
async function grant(row: Record<string, unknown>, level: number) {
  busyId.value = row.user_id
  try {
    await client.put(`/outpatient/abx-privileges/${row.user_id}`, null, { params: { level } })
    ElMessage.success('已调整')
    await loadAbx()
  } finally { busyId.value = null }
}
async function pack() {
  if (!cssdName.value) { ElMessage.warning('请填写包名'); return }
  packLoading.value = true
  try {
    const resp = await client.post('/cssd/packages', { name: cssdName.value })
    ElMessage.success(`已打包，条码 ${resp.data.data.pkgNo}`)
    cssdName.value = ''
    await loadCssd()
  } finally { packLoading.value = false }
}
async function sterilize(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('灭菌批次号', '灭菌', { inputValue: 'MJ-' + today }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.pkg_no
  try {
    await client.put(`/cssd/packages/${row.pkg_no}/sterilize`, null, { params: { batch: value } })
    await loadCssd()
  } finally { busyId.value = null }
}
async function issueCssd(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('发放科室ID', '发放', { inputValue: '2' }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.pkg_no
  try {
    await client.put(`/cssd/packages/${row.pkg_no}/issue`, null, { params: { deptId: value } })
    await loadCssd()
  } finally { busyId.value = null }
}
async function useCssd(row: Record<string, unknown>) {
  busyId.value = row.pkg_no
  try {
    await client.put(`/cssd/packages/${row.pkg_no}/use`)
    await loadCssd()
  } finally { busyId.value = null }
}
async function showTrace(row: Record<string, unknown>) {
  const chain = (await client.get(`/cssd/packages/${row.pkg_no}/trace`)).data.data as
    { action: string; at: string }[]
  ElMessageBox.alert(chain.map((c) => `${c.action} @ ${c.at}`).join('\n'), `追溯链 ${row.pkg_no}`)
}
async function addPhc() {
  if (!phc.patientId || !phc.content) { ElMessage.warning('请填写完整'); return }
  addPhcLoading.value = true
  try {
    await client.post('/phc/records', { ...phc, patientId: Number(phc.patientId) })
    phc.content = ''
    await loadPhc()
  } finally { addPhcLoading.value = false }
}

watch(tab, (t) => {
  if (t === 'score') loadScores()
  if (t === 'credential') loadCreds()
  if (t === 'abx') loadAbx()
  if (t === 'cssd') loadCssd()
  if (t === 'phc') loadPhc()
})
onMounted(loadShifts)
</script>
