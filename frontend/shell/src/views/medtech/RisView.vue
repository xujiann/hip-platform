<template>
  <el-card>
    <div class="toolbar">
      <h3>RIS 检查报告</h3>
      <el-button link type="primary" @click="load">刷新</el-button>
      <!-- v74 复核补入口：PACS 调阅地址端点此前前端零调用，偏离表 7 行却写着「已提供阅片地址跳转」。
           它是全局配置（影像浏览器地址），不是按检查定位，所以放工具栏而不是行内；未配置时不显示。 -->
      <el-button v-if="pacs.configured" link type="success" @click="openPacs">影像浏览器</el-button>
    </div>
    <el-table :data="records" size="small" border>
      <el-table-column prop="group_no" label="申请单号" width="150" />
      <el-table-column prop="patient_name" label="患者" width="90" />
      <el-table-column prop="item_name" label="检查项目" width="160" />
      <!-- v74 复核（1014★ 审计打回点）：检查目的/临床摘要/注意事项/加急后端已随工作列表下发，技师屏幕此前看不到——只在医生那张纸上。 -->
      <el-table-column label="申请信息" min-width="240">
        <template #default="{ row }">
          <el-tag v-if="row.urgent" type="danger" size="small" style="margin-right: 4px">加急</el-tag>
          <span v-if="row.diagnosis">诊断：{{ row.diagnosis }}</span>
          <span v-if="row.exam_purpose" :style="row.diagnosis ? 'margin-left: 6px' : ''">目的：{{ row.exam_purpose }}</span>
          <span v-if="row.clinical_summary" style="margin-left: 6px">摘要：{{ row.clinical_summary }}</span>
          <span v-if="row.notice" style="margin-left: 6px; color: #c00">注意：{{ row.notice }}</span>
          <span v-if="!row.urgent && !row.diagnosis && !row.exam_purpose && !row.clinical_summary && !row.notice">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">{{ { REGISTERED: '已登记', ARRIVED: '已到检', REPORTED: '已报告' }[row.status as string] }}</template>
      </el-table-column>
      <el-table-column label="源医嘱状态" width="110">
        <template #default="{ row }">{{ orderStatusNames[row.order_status as string] || row.order_status || '—' }}</template>
      </el-table-column>
      <el-table-column prop="impression" label="诊断意见" show-overflow-tooltip />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button v-if="canArrive(row)" link size="small" :disabled="writing" :loading="busyId === row.id" @click="arrive(row)">到检</el-button>
          <el-button v-if="canReport(row)" link type="primary" size="small" @click="openReport(row)">
            {{ row.status === 'REPORTED' ? '修改报告' : '书写报告' }}
          </el-button>
          <el-button v-if="canVerify(row)" link type="warning" size="small" :disabled="writing" @click="markCritical(row)">标记危急</el-button>
          <el-button v-if="canVerify(row)" link type="success" size="small" :disabled="writing" :loading="busyId === row.id" @click="verify(row)">审核发布</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="`报告：${current?.item_name}`" width="560px">
      <el-form label-width="80px">
        <!-- v38 结构化模板：按 RIS 类型套模板到影像所见 -->
        <el-form-item label="套模板">
          <el-select v-model="templateId" clearable placeholder="选择报告模板" style="width:260px" @change="applyTemplate">
            <el-option v-for="t in templates" :key="t.id as number" :label="String(t.name)" :value="t.id as number" />
          </el-select>
        </el-form-item>
        <el-form-item label="影像所见">
          <el-input v-model="findings" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="诊断意见">
          <el-input v-model="impression" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeReport">取消</el-button>
        <el-button type="primary" @click="saveReport" :loading="saveReportLoading" :disabled="writing || !current || !canReport(current)">保存报告</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const records = ref<Record<string, unknown>[]>([])
const orderStatusNames: Record<string, string> = { CREATED: '未收费', CHARGED: '已收费', EXECUTED: '已执行', CANCELLED: '已作废', DISPENSED: '已发药' }
const dialogVisible = ref(false)
const current = ref<Record<string, unknown> | null>(null)
const findings = ref('')
const impression = ref('')
const busyId = ref<unknown>(null)
const saveReportLoading = ref(false)
const templates = ref<Record<string, unknown>[]>([])
const templateId = ref<number | null>(null)
const canWrite = computed(() => auth.user?.roles.some(role => ['ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP', 'NURSE'].includes(role)) ?? false)
const canShare = computed(() => auth.user?.roles.some(role => ['ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP'].includes(role)) ?? false)
const writing = computed(() => busyId.value != null || saveReportLoading.value)
let active = true
let viewVersion = 0
let readVersion = 0
let modalVersion = 0
let pacsReadVersion = 0

async function load() {
  if (!active) return
  ++viewVersion
  closeReport()
  records.value = []
  templates.value = []
  await refreshRecords()
}

/** 写后刷新不关闭新打开的另一份草稿；列表与模板仅接纳本轮最新读取。 */
async function refreshRecords() {
  if (!active) return
  const version = ++readVersion
  const [worklist, reportTemplates] = await Promise.all([
    client.get('/ris/worklist'), client.get('/emr-templates', { params: { type: 'RIS' } }),
  ])
  if (!active || version !== readVersion) return
  records.value = worklist.data.data
  templates.value = reportTemplates.data.data
}

function canOperate(row: Record<string, unknown>, states: string[]) {
  const source = records.value.find(record => record.id === row.id)
  return active && canWrite.value && source != null && states.includes(String(source.status)) && ['CHARGED', 'EXECUTED'].includes(String(source.order_status))
}
function canArrive(row: Record<string, unknown>) {
  return canOperate(row, ['REGISTERED'])
}
function canReport(row: Record<string, unknown>) {
  return canOperate(row, ['REGISTERED', 'ARRIVED', 'REPORTED'])
}
function canVerify(row: Record<string, unknown>) {
  return canOperate(row, ['REPORTED'])
}

/** 对象状态拒绝后回读真实源状态；只关闭仍属于原提交的失效草稿。 */
async function recoverWrite(error: unknown, code: number, sameView: () => boolean, sameDialog?: () => boolean) {
  if ((error as { bizCode?: number }).bizCode !== code) throw error
  if (!sameView()) return
  if (sameDialog?.()) closeReport()
  await refreshRecords()
}

async function arrive(row: Record<string, unknown>) {
  if (!active || writing.value || !records.value.includes(row) || !canArrive(row)) return
  const id = row.id
  const version = viewVersion
  const dialogVersion = modalVersion
  const sameView = () => active && version === viewVersion
  busyId.value = id
  try {
    await client.put(`/ris/exams/${id}/arrive`)
    if (!sameView()) return
    if (dialogVersion === modalVersion) ElMessage.success('已到检登记')
    await refreshRecords()
  } catch (error) {
    await recoverWrite(error, 9944, sameView)
  } finally { busyId.value = null }
}

function applyTemplate() {
  if (!active || !dialogVisible.value || !current.value || !canReport(current.value)) return
  const t = templates.value.find(x => x.id === templateId.value)
  if (t) findings.value = String(t.content ?? '')
}

function closeReport() {
  ++modalVersion
  dialogVisible.value = false
  current.value = null
  findings.value = ''
  impression.value = ''
  templateId.value = null
}

function openReport(row: Record<string, unknown>) {
  if (!active || !records.value.includes(row) || !canReport(row)) return
  ++modalVersion
  current.value = { ...row }
  findings.value = String(row.findings ?? '')
  impression.value = String(row.impression ?? '')
  templateId.value = null
  dialogVisible.value = true
}

async function saveReport() {
  if (!active || writing.value || !dialogVisible.value || !current.value || !canReport(current.value)) return
  const id = current.value.id
  const body = { findings: findings.value, impression: impression.value }
  const version = viewVersion
  const dialogVersion = modalVersion
  const sameView = () => active && version === viewVersion
  const sameDialog = () => sameView() && dialogVersion === modalVersion && dialogVisible.value && current.value?.id === id
  saveReportLoading.value = true
  try {
    await client.put(`/ris/exams/${id}/report`, body)
    if (!sameView()) return
    if (sameDialog()) {
      closeReport()
      ElMessage.success('报告已保存')
    }
    await refreshRecords()
  } catch (error) {
    await recoverWrite(error, 9944, sameView, sameDialog)
  } finally { saveReportLoading.value = false }
}

async function markCritical(row: Record<string, unknown>) {
  if (!active || writing.value || !records.value.includes(row) || !canVerify(row)) return
  const id = row.id
  const version = viewVersion
  const dialogVersion = modalVersion
  const sameView = () => active && version === viewVersion
  const sameInteraction = () => sameView() && dialogVersion === modalVersion
  busyId.value = id
  // 影像危急值（气胸/夹层/颅内出血等）：上报后走与检验同一条确认闭环，通知开单医师限时确认
  try {
    const res = await ElMessageBox.prompt('危急描述（必填，如：右侧气胸，肺压缩约 40%）', '影像危急值上报',
      { inputPlaceholder: '危急征象描述' }).catch(() => null)
    if (!res || !sameInteraction() || !records.value.includes(row) || !canVerify(row)) return
    const note = (res.value || '').trim()
    if (!note) { ElMessage.warning('危急描述必填'); return }
    await client.put(`/ris/exams/${id}/critical`, { note })
    if (!sameView()) return
    if (sameInteraction()) ElMessage.success('已上报危急值，已通知开单医师确认')
    await refreshRecords()
  } catch (error) {
    await recoverWrite(error, 9945, sameView)
  } finally { busyId.value = null }
}

async function verify(row: Record<string, unknown>) {
  if (!active || writing.value || !records.value.includes(row) || !canVerify(row)) return
  const id = row.id
  const version = viewVersion
  const dialogVersion = modalVersion
  const sameView = () => active && version === viewVersion
  const sameInteraction = () => sameView() && dialogVersion === modalVersion
  busyId.value = id
  try {
    await client.put(`/ris/exams/${id}/verify`)
    if (!sameView()) return
    if (sameInteraction()) ElMessage.success('已审核发布，医嘱转已执行')
    await refreshRecords()
    // 护士保留审核岗位；分享沿用该接口原有的另三岗位，且只在原交互仍有效时展示。
    if (!sameInteraction() || !canShare.value) return
    const share = (await client.post(`/ris/exams/${id}/share`)).data.data
    if (!sameInteraction() || !canShare.value) return
    await ElMessageBox.alert(`<div style="word-break:break-all">匿名访问链接（72 小时有效，姓名已脱敏）：
<b>${location.origin}${share.url}</b></div>`,
      '报告分享')
  } catch (error) {
    await recoverWrite(error, 9945, sameView)
  } finally { busyId.value = null }
}

/** v74：PACS 调阅入口。后端 /api/ris/pacs-info 回 {configured, viewerUrl}；读不到就当未配置，不打断报告页。 */
const pacs = ref<{ configured: boolean; viewerUrl: string }>({ configured: false, viewerUrl: '' })
async function loadPacs() {
  const version = ++pacsReadVersion
  try {
    const d = (await client.get('/ris/pacs-info')).data.data ?? {}
    if (!active || version !== pacsReadVersion) return
    pacs.value = { configured: Boolean(d.configured) && Boolean(d.viewerUrl), viewerUrl: String(d.viewerUrl ?? '') }
  } catch {
    if (!active || version !== pacsReadVersion) return
    pacs.value = { configured: false, viewerUrl: '' }
  }
}
function openPacs() {
  if (active && pacs.value.viewerUrl) window.open(pacs.value.viewerUrl, '_blank')
}

onMounted(() => { load(); loadPacs() })
onUnmounted(() => {
  active = false
  ++viewVersion
  ++readVersion
  ++modalVersion
  ++pacsReadVersion
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
</style>
