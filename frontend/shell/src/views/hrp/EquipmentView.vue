<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="维修工单" name="repair">
        <el-form inline size="small">
          <el-form-item><el-input v-model="rep.assetId" placeholder="资产ID" style="width: 90px" /></el-form-item>
          <el-form-item><el-input v-model="rep.faultDesc" placeholder="故障描述" style="width: 220px" /></el-form-item>
          <el-form-item>
            <el-select v-model="rep.repairType" style="width: 110px">
              <el-option label="院内维修" value="IN" /><el-option label="院外送修" value="OUT" />
            </el-select>
          </el-form-item>
          <el-button type="primary" size="small" :loading="reportLoading" @click="report">报修</el-button>
          <el-input v-model="repKeyword" placeholder="台账检索" size="small" clearable
                    style="width: 160px; margin-left: 12px" @change="loadRepairs" />
        </el-form>
        <el-table :data="repairs" size="small" border>
          <el-table-column prop="asset_name" label="设备" width="150" />
          <el-table-column prop="fault_desc" label="故障" show-overflow-tooltip />
          <el-table-column label="方式" width="80">
            <template #default="{ row }">{{ row.repair_type === 'IN' ? '院内' : '院外' }}</template>
          </el-table-column>
          <el-table-column prop="assignee" label="维修人" width="90" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'DONE' ? 'success' : row.status === 'ASSIGNED' ? 'warning' : 'danger'">
                {{ { REPORTED: '待派工', ASSIGNED: '维修中', DONE: '已完成' }[row.status as string] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button v-if="row.status === 'REPORTED'" link type="primary" size="small" :loading="busyId === row.id" @click="assign(row)">派工</el-button>
              <el-button v-if="row.status === 'ASSIGNED'" link type="success" size="small" :loading="busyId === row.id" @click="doneRepair(row)">完成</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="保养与计量" name="maintain">
        <el-form inline size="small">
          <el-form-item><el-input v-model="plan.assetId" placeholder="资产ID" style="width: 90px" /></el-form-item>
          <el-form-item label="周期(天)"><el-input-number v-model="plan.cycleDays" :min="7" :step="7" /></el-form-item>
          <el-form-item>
            <el-date-picker v-model="plan.nextDate" type="date" value-format="YYYY-MM-DD" placeholder="下次保养日" style="width: 140px" />
          </el-form-item>
          <el-button type="primary" size="small" :loading="addPlanLoading" @click="addPlan">建保养计划</el-button>
        </el-form>
        <el-table :data="plans" size="small" border>
          <el-table-column prop="asset_name" label="设备" width="160" />
          <el-table-column prop="cycle_days" label="周期(天)" width="90" />
          <el-table-column prop="next_date" label="下次保养" width="120" />
          <el-table-column label="到期" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.due" type="danger" size="small">到期</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="success" size="small" :loading="busyId === row.id" @click="execute(row)">执行保养</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4>计量台账
          <el-button link type="primary" size="small" :loading="addMetrologyLoading" @click="addMetrologyDlg">登记检定</el-button>
        </h4>
        <el-table :data="metrology" size="small" border>
          <el-table-column prop="asset_name" label="设备" width="160" />
          <el-table-column prop="cert_no" label="检定证书" width="140" />
          <el-table-column prop="valid_until" label="有效期至" width="120" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.validity === 'VALID' ? 'success' : row.validity === 'EXPIRING' ? 'warning' : 'danger'">
                {{ { VALID: '有效', EXPIRING: '即将到期', EXPIRED: '已过期' }[row.validity as string] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="agency" label="检定机构" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="采购单据" name="purchase">
        <el-form inline size="small">
          <el-form-item>
            <el-select v-model="doc.docType" style="width: 100px">
              <el-option label="备货单" value="STOCK" /><el-option label="退货单" value="RETURN" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="doc.supplierId" placeholder="供应商ID" style="width: 100px" /></el-form-item>
          <el-form-item><el-input v-model="doc.items" placeholder="品目摘要" style="width: 200px" /></el-form-item>
          <el-form-item><el-input-number v-model="doc.amount" :min="0" :step="100" /></el-form-item>
          <el-button type="primary" size="small" :loading="createDocLoading" @click="createDoc">开单</el-button>
        </el-form>
        <el-table :data="docs" size="small" border>
          <el-table-column prop="doc_no" label="单号" width="130" />
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ row.doc_type === 'STOCK' ? '备货' : '退货' }}</template>
          </el-table-column>
          <el-table-column prop="supplier_name" label="供应商" width="140" />
          <el-table-column prop="amount" label="金额" width="90" />
          <el-table-column prop="invoice_no" label="发票号" width="120" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'RECEIVED' ? 'success' : row.status === 'CANCELLED' ? 'info' : 'warning'">
                {{ { DRAFT: '草稿', APPROVED: '已审核', RECEIVED: '已验收', CANCELLED: '已作废' }[row.status as string] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small" :loading="busyId === row.doc_no" @click="docAction(row, 'approve')">审核</el-button>
              <el-button v-if="row.status === 'DRAFT'" link type="danger" size="small" :loading="busyId === row.doc_no" @click="docAction(row, 'cancel')">作废</el-button>
              <el-button v-if="['APPROVED', 'RECEIVED'].includes(String(row.status))" link type="success" size="small"
                         :loading="busyId === row.doc_no" @click="receiveDoc(row)">{{ row.status === 'RECEIVED' ? '改发票' : '验收' }}</el-button>
              <el-button v-if="row.status === 'CANCELLED'" link size="small" :loading="busyId === row.doc_no" @click="docAction(row, 'restore')">还原</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="供应商证照" name="certs">
        <el-form inline size="small">
          <el-form-item><el-input v-model="cert.supplierId" placeholder="供应商ID" style="width: 100px" /></el-form-item>
          <el-form-item>
            <el-select v-model="cert.certType" style="width: 130px">
              <el-option v-for="c in ['营业执照', '经营许可证', '生产许可证', '授权书']" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="cert.certNo" placeholder="证照号" style="width: 140px" /></el-form-item>
          <el-form-item>
            <el-date-picker v-model="cert.expireDate" type="date" value-format="YYYY-MM-DD" placeholder="到期日" style="width: 140px" />
          </el-form-item>
          <el-button type="primary" size="small" :loading="addCertLoading" @click="addCert">登记</el-button>
        </el-form>
        <el-table :data="certs" size="small" border>
          <el-table-column prop="supplier_name" label="供应商" width="150" />
          <el-table-column prop="cert_type" label="证照类型" width="120" />
          <el-table-column prop="cert_no" label="证照号" width="150" />
          <el-table-column prop="expire_date" label="到期日" width="120" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.validity === 'VALID' ? 'success' : row.validity === 'EXPIRING' ? 'warning' : 'danger'">
                {{ { VALID: '有效', EXPIRING: '即将到期', EXPIRED: '已过期' }[row.validity as string] }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="制度文件库" name="doclib">
        <el-form size="small">
          <el-form-item>
            <el-input v-model="fileForm.category" placeholder="分类（如 医疗核心制度）" style="width: 200px" />
            <el-input v-model="fileForm.title" placeholder="标题" style="width: 260px; margin-left: 8px" />
          </el-form-item>
          <el-form-item>
            <el-input v-model="fileForm.content" type="textarea" :rows="2" placeholder="制度正文（在线预览内容）" />
          </el-form-item>
          <el-button type="primary" size="small" :loading="uploadDocLoading" @click="uploadDoc">上传制度</el-button>
          <el-input v-model="docKeyword" placeholder="检索" size="small" clearable
                    style="width: 160px; margin-left: 12px" @change="loadDoclib" />
        </el-form>
        <el-table :data="doclib" size="small" border>
          <el-table-column prop="category" label="分类" width="140" />
          <el-table-column prop="title" label="标题" show-overflow-tooltip />
          <el-table-column prop="version" label="版本" width="80" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="preview(row)">预览</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('repair')
const repairs = ref<Record<string, unknown>[]>([])
const plans = ref<Record<string, unknown>[]>([])
const metrology = ref<Record<string, unknown>[]>([])
const docs = ref<Record<string, unknown>[]>([])
const certs = ref<Record<string, unknown>[]>([])
const doclib = ref<Record<string, unknown>[]>([])
const repKeyword = ref('')
const docKeyword = ref('')
const rep = reactive({ assetId: '', faultDesc: '', repairType: 'IN' })
const plan = reactive({ assetId: '', cycleDays: 30, nextDate: '' })
const doc = reactive({ docType: 'STOCK', supplierId: '', items: '', amount: 1000 })
const cert = reactive({ supplierId: '', certType: '营业执照', certNo: '', expireDate: '' })
const fileForm = reactive({ category: '', title: '', content: '' })
const busyId = ref<unknown>(null)
const reportLoading = ref(false)
const addPlanLoading = ref(false)
const addMetrologyLoading = ref(false)
const createDocLoading = ref(false)
const addCertLoading = ref(false)
const uploadDocLoading = ref(false)

async function loadRepairs() {
  repairs.value = (await client.get('/equipment/repairs', { params: { keyword: repKeyword.value || undefined } })).data.data
}
async function loadMaintain() {
  plans.value = (await client.get('/equipment/maintain-plans')).data.data
  metrology.value = (await client.get('/equipment/metrology')).data.data
}
async function loadDocs() { docs.value = (await client.get('/purchase/docs')).data.data }
async function loadCerts() { certs.value = (await client.get('/purchase/supplier-certs')).data.data }
async function loadDoclib() {
  doclib.value = (await client.get('/doclib', { params: { keyword: docKeyword.value || undefined } })).data.data
}

async function report() {
  if (!rep.assetId || !rep.faultDesc) { ElMessage.warning('请填写完整'); return }
  reportLoading.value = true
  try {
    await client.post('/equipment/repairs', { ...rep, assetId: Number(rep.assetId) })
    rep.faultDesc = ''
    await loadRepairs()
  } finally {
    reportLoading.value = false
  }
}
async function assign(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('维修人', '派工', { inputValue: '设备科-维修组' }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.id
  try {
    await client.put(`/equipment/repairs/${row.id}/assign`, null, { params: { assignee: value } })
    await loadRepairs()
  } finally {
    busyId.value = null
  }
}
async function doneRepair(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('维修结果', '完成维修', { inputValue: '更换配件后恢复正常' }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.id
  try {
    await client.put(`/equipment/repairs/${row.id}/done`, null, { params: { note: value } })
    await loadRepairs()
  } finally {
    busyId.value = null
  }
}
async function addPlan() {
  if (!plan.assetId || !plan.nextDate) { ElMessage.warning('请填写完整'); return }
  addPlanLoading.value = true
  try {
    await client.post('/equipment/maintain-plans', { ...plan, assetId: Number(plan.assetId) })
    await loadMaintain()
  } finally {
    addPlanLoading.value = false
  }
}
async function execute(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('保养内容', '执行保养', { inputValue: '例行保养，运行正常' }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.id
  try {
    await client.post(`/equipment/maintain-plans/${row.id}/execute`, null, { params: { content: value } })
    ElMessage.success('保养单已生成，下次保养日已顺延')
    await loadMaintain()
  } finally {
    busyId.value = null
  }
}
async function addMetrologyDlg() {
  const res = await ElMessageBox.prompt('资产ID,证书号,检定日,有效期至（逗号分隔）', '登记检定',
    { inputValue: '1,JD-2026-001,2026-08-01,2027-08-01' }).catch(() => null)
  if (!res) return
  const { value } = res
  const [assetId, certNo, checkedAt, validUntil] = value.split(/[,，]/).map((s: string) => s.trim())
  addMetrologyLoading.value = true
  try {
    await client.post('/equipment/metrology', { assetId: Number(assetId), certNo, checkedAt, validUntil, agency: '市计量院' })
    await loadMaintain()
  } finally {
    addMetrologyLoading.value = false
  }
}
async function createDoc() {
  if (!doc.supplierId || !doc.items) { ElMessage.warning('请填写完整'); return }
  createDocLoading.value = true
  try {
    await client.post('/purchase/docs', { ...doc, supplierId: Number(doc.supplierId) })
    await loadDocs()
  } finally {
    createDocLoading.value = false
  }
}
async function docAction(row: Record<string, unknown>, action: string) {
  busyId.value = row.doc_no
  try {
    await client.put(`/purchase/docs/${row.doc_no}/${action}`)
    await loadDocs()
  } finally {
    busyId.value = null
  }
}
async function receiveDoc(row: Record<string, unknown>) {
  const res = await ElMessageBox.prompt('发票号（可补录/调整）', '财务验收', { inputValue: String(row.invoice_no ?? 'FP-') }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.doc_no
  try {
    await client.put(`/purchase/docs/${row.doc_no}/receive`, null, { params: { invoiceNo: value } })
    await loadDocs()
  } finally {
    busyId.value = null
  }
}
async function addCert() {
  if (!cert.supplierId || !cert.expireDate) { ElMessage.warning('请填写完整'); return }
  addCertLoading.value = true
  try {
    await client.post('/purchase/supplier-certs',
      { ...cert, supplierId: Number(cert.supplierId), attachmentName: cert.certNo + '.pdf' })
    await loadCerts()
  } finally {
    addCertLoading.value = false
  }
}
async function uploadDoc() {
  if (!fileForm.title || !fileForm.content) { ElMessage.warning('请填写完整'); return }
  uploadDocLoading.value = true
  try {
    await client.post('/doclib', fileForm)
    ElMessage.success('已上传')
    fileForm.title = ''
    fileForm.content = ''
    await loadDoclib()
  } finally {
    uploadDocLoading.value = false
  }
}
async function preview(row: Record<string, unknown>) {
  const d = (await client.get(`/doclib/${row.id}`)).data.data
  ElMessageBox.alert(String(d.content), `${d.title}（${d.version}）`)
}

watch(tab, (t) => {
  if (t === 'maintain') loadMaintain()
  if (t === 'purchase') loadDocs()
  if (t === 'certs') loadCerts()
  if (t === 'doclib') loadDoclib()
})
onMounted(loadRepairs)
</script>
