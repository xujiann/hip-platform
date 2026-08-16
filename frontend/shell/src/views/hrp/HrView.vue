<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="员工档案" name="emp">
        <el-form inline size="small">
          <el-form-item><el-input v-model="emp.empNo" placeholder="工号" style="width: 100px" /></el-form-item>
          <el-form-item><el-input v-model="emp.name" placeholder="姓名" style="width: 100px" /></el-form-item>
          <el-form-item><el-input v-model="emp.deptId" placeholder="科室ID" style="width: 90px" /></el-form-item>
          <el-form-item><el-input v-model="emp.title" placeholder="职称/岗位" style="width: 120px" /></el-form-item>
          <el-form-item><el-input v-model="emp.phone" placeholder="手机" style="width: 130px" /></el-form-item>
          <el-button type="primary" size="small" @click="addEmp">建档</el-button>
          <el-input v-model="empKeyword" placeholder="姓名/工号检索" size="small" clearable
                    style="width: 150px; margin-left: 12px" @change="loadEmps" />
        </el-form>
        <el-table :data="emps" size="small" border>
          <el-table-column prop="emp_no" label="工号" width="100" />
          <el-table-column prop="name" label="姓名" width="100" />
          <el-table-column prop="dept_name" label="科室" width="130" />
          <el-table-column prop="title" label="职称" width="120" />
          <el-table-column prop="phone" label="手机" width="130" />
          <el-table-column prop="email" label="邮箱" show-overflow-tooltip />
          <el-table-column prop="hire_date" label="入职" width="110" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="工资" name="salary">
        <el-alert title="批量导入：粘贴「工号,基本,奖金,扣款」多行（Excel 复制即可），同员工同月覆盖"
                  type="info" show-icon :closable="false" style="margin-bottom: 6px" />
        <el-form size="small">
          <el-form-item>
            <el-input v-model="salaryMonth" placeholder="YYYY-MM" style="width: 120px" />
            <el-input v-model="salaryText" type="textarea" :rows="3"
                      placeholder="G001,8000,2000,500&#10;G002,7500,1800,300" style="margin-top: 4px" />
          </el-form-item>
          <el-button type="primary" size="small" @click="importSalary">导入</el-button>
          <el-input v-model="salaryEmpNo" placeholder="按工号查个人工资" size="small"
                    style="width: 160px; margin-left: 12px" @change="loadSalary" />
        </el-form>
        <el-table :data="salaries" size="small" border>
          <el-table-column prop="emp_name" label="姓名" width="100" />
          <el-table-column prop="month" label="月份" width="100" />
          <el-table-column prop="base_pay" label="基本" width="100" />
          <el-table-column prop="bonus" label="奖金" width="100" />
          <el-table-column prop="deduction" label="扣款" width="100" />
          <el-table-column prop="total" label="实发" width="110" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="培训/招聘" name="train">
        <el-form inline size="small">
          <el-form-item><el-input v-model="tr.employeeId" placeholder="员工ID" style="width: 90px" /></el-form-item>
          <el-form-item><el-input v-model="tr.category" placeholder="培训类别" style="width: 130px" /></el-form-item>
          <el-form-item><el-input v-model="tr.certName" placeholder="证书名称" style="width: 150px" /></el-form-item>
          <el-button type="primary" size="small" @click="addTraining">登记培训</el-button>
        </el-form>
        <el-table :data="trainings" size="small" border>
          <el-table-column prop="emp_name" label="员工" width="100" />
          <el-table-column prop="category" label="类别" width="130" />
          <el-table-column prop="org" label="机构" width="140" />
          <el-table-column prop="cert_name" label="证书" show-overflow-tooltip />
        </el-table>
        <h4>招聘面试
          <el-button link type="primary" size="small" @click="addRecruit">登记面试</el-button>
        </h4>
        <el-table :data="recruits" size="small" border>
          <el-table-column prop="candidate_name" label="应聘人" width="110" />
          <el-table-column prop="position" label="岗位" width="130" />
          <el-table-column prop="interview_date" label="面试日" width="110" />
          <el-table-column label="结果" width="150">
            <template #default="{ row }">
              <template v-if="row.result === 'PENDING'">
                <el-button link type="success" size="small" @click="recruitResult(row, 'PASS')">通过</el-button>
                <el-button link type="danger" size="small" @click="recruitResult(row, 'FAIL')">淘汰</el-button>
              </template>
              <el-tag v-else :type="row.result === 'PASS' ? 'success' : 'info'" size="small">
                {{ row.result === 'PASS' ? '通过' : '淘汰' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="继续教育/考勤" name="cme">
        <el-form inline size="small">
          <el-form-item><el-input v-model="cme.employeeId" placeholder="员工ID" style="width: 90px" /></el-form-item>
          <el-form-item><el-input v-model="cme.projectName" placeholder="继续教育项目名" style="width: 200px" /></el-form-item>
          <el-form-item label="学分"><el-input-number v-model="cme.credit" :min="0" :step="0.5" :precision="1" /></el-form-item>
          <el-form-item label="年度"><el-input v-model="cme.cmeYear" style="width: 80px" /></el-form-item>
          <el-button type="primary" size="small" @click="addCme">登记</el-button>
        </el-form>
        <el-row :gutter="16">
          <el-col :span="14">
            <el-table :data="cmeRecords" size="small" border>
              <el-table-column prop="emp_name" label="员工" width="90" />
              <el-table-column prop="project_name" label="项目" show-overflow-tooltip />
              <el-table-column prop="credit" label="学分" width="70" />
              <el-table-column prop="cme_year" label="年度" width="80" />
            </el-table>
          </el-col>
          <el-col :span="10">
            <h4 style="margin-top: 0">年度学分汇总</h4>
            <el-table :data="cmeSummary" size="small" border>
              <el-table-column prop="emp_name" label="员工" width="90" />
              <el-table-column prop="cme_year" label="年度" width="80" />
              <el-table-column prop="total_credit" label="总学分" width="90" />
            </el-table>
          </el-col>
        </el-row>
        <h4>
          考勤（打卡/补卡）
          <el-date-picker v-model="attDate" type="date" value-format="YYYY-MM-DD" size="small"
                          style="width: 140px; margin-left: 8px" @change="loadAtt" />
          <el-button link type="primary" size="small" @click="punch(false)">打卡</el-button>
          <el-button link type="warning" size="small" @click="punch(true)">补卡</el-button>
        </h4>
        <el-table :data="attendance" size="small" border>
          <el-table-column prop="emp_no" label="工号" width="90" />
          <el-table-column prop="emp_name" label="员工" width="90" />
          <el-table-column prop="check_in" label="上班" width="80" />
          <el-table-column prop="check_out" label="下班" width="80" />
          <el-table-column label="类型" width="80">
            <template #default="{ row }">
              <el-tag :type="row.att_type === 'MAKEUP' ? 'warning' : 'success'" size="small">
                {{ row.att_type === 'MAKEUP' ? '补卡' : '打卡' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="说明" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="通讯录" name="dir">
        <el-table :data="directory" size="small" border>
          <el-table-column prop="dept_name" label="科室" width="140" />
          <el-table-column prop="name" label="姓名" width="100" />
          <el-table-column prop="title" label="职称" width="120" />
          <el-table-column prop="phone" label="电话" width="140" />
          <el-table-column prop="email" label="邮箱" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="资产处置/房屋" name="asset">
        <el-form inline size="small">
          <el-form-item><el-input v-model="tf.assetId" placeholder="资产ID" style="width: 90px" /></el-form-item>
          <el-form-item><el-input v-model="tf.toDeptId" placeholder="调入科室ID" style="width: 110px" /></el-form-item>
          <el-button type="primary" size="small" @click="doTransfer">调拨</el-button>
          <el-button size="small" @click="doHandover">移交登记</el-button>
          <el-button type="danger" size="small" @click="applyScrap">报废申请</el-button>
        </el-form>
        <el-table :data="transfers" size="small" border>
          <el-table-column prop="asset_name" label="资产" width="150" />
          <el-table-column label="动作" width="80">
            <template #default="{ row }">{{ row.action === 'TRANSFER' ? '调拨' : '移交' }}</template>
          </el-table-column>
          <el-table-column label="流向" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.action === 'TRANSFER' ? `${row.from_dept ?? '-'} → ${row.to_dept ?? '-'}`
                : `${row.from_person ?? '-'} → ${row.to_person ?? '-'}` }}
            </template>
          </el-table-column>
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>
        <h4>报废审核</h4>
        <el-table :data="scraps" size="small" border>
          <el-table-column prop="asset_name" label="资产" width="150" />
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'APPROVED' ? 'success'
                : row.status === 'REJECTED' ? 'info' : 'warning'">
                {{ { APPLIED: '待审', APPROVED: '已报废', REJECTED: '已驳回' }[row.status as string] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <template v-if="row.status === 'APPLIED'">
                <el-button link type="success" size="small" @click="reviewScrap(row, true)">批准</el-button>
                <el-button link type="danger" size="small" @click="reviewScrap(row, false)">驳回</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <h4>价值调整/附件
          <el-button link type="warning" size="small" @click="addAdjust">增值/折旧补录</el-button>
          <el-button link type="primary" size="small" @click="addAssetDoc">附件登记</el-button>
        </h4>
        <el-table :data="adjusts" size="small" border>
          <el-table-column prop="asset_name" label="资产" width="150" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">{{ row.adjust_type === 'APPRECIATION' ? '增值' : '折旧补录' }}</template>
          </el-table-column>
          <el-table-column prop="amount" label="金额" width="100" />
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>
        <h4>房屋建筑台账
          <el-button link type="primary" size="small" @click="addBuilding">登记房屋</el-button>
        </h4>
        <el-table :data="buildings" size="small" border>
          <el-table-column prop="building_no" label="编号" width="100" />
          <el-table-column prop="name" label="名称" width="150" />
          <el-table-column prop="usage_type" label="用途" width="100" />
          <el-table-column prop="area_sqm" label="面积㎡" width="100" />
          <el-table-column prop="address" label="地址" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="价格/交款核查" name="finance">
        <h4>诊疗项目调价（留痕）</h4>
        <el-form inline size="small">
          <el-form-item><el-input v-model="pr.itemId" placeholder="项目ID" style="width: 90px" /></el-form-item>
          <el-form-item><el-input-number v-model="pr.newPrice" :min="0" :precision="2" /></el-form-item>
          <el-form-item><el-input v-model="pr.reason" placeholder="调价原因" style="width: 200px" /></el-form-item>
          <el-button type="primary" size="small" @click="changePrice">调价</el-button>
        </el-form>
        <el-table :data="priceLogs" size="small" border>
          <el-table-column prop="name" label="项目" width="160" />
          <el-table-column prop="old_price" label="原价" width="90" />
          <el-table-column prop="new_price" label="新价" width="90" />
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
          <el-table-column prop="changed_by" label="操作人" width="100" />
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>
        <h4>
          交款核对
          <el-date-picker v-model="reconDate" type="date" value-format="YYYY-MM-DD" size="small"
                          style="width: 140px; margin-left: 8px" @change="loadRecon" />
        </h4>
        <el-table :data="recon?.byCashier ?? []" size="small" border>
          <el-table-column prop="cashier" label="收款员" width="110" />
          <el-table-column prop="paid_cnt" label="收款笔数" width="90" />
          <el-table-column prop="paid_amount" label="收款金额" width="110" />
          <el-table-column prop="refund_cnt" label="退款笔数" width="90" />
          <el-table-column prop="refund_amount" label="退款金额" width="110" />
        </el-table>
        <h4>结账明细检索
          <el-date-picker v-model="searchFrom" type="date" value-format="YYYY-MM-DD" size="small"
                          style="width: 130px; margin-left: 8px" />
          <el-date-picker v-model="searchTo" type="date" value-format="YYYY-MM-DD" size="small"
                          style="width: 130px; margin-left: 4px" />
          <el-select v-model="searchPay" size="small" clearable placeholder="全部方式"
                     style="width: 110px; margin-left: 4px">
            <el-option label="现金" value="CASH" /><el-option label="微信" value="WECHAT" />
            <el-option label="支付宝" value="ALIPAY" /><el-option label="医保" value="YB" />
          </el-select>
          <el-select v-model="searchType" size="small" clearable placeholder="全部类型(汇总)"
                     style="width: 130px; margin-left: 4px">
            <el-option label="药品" value="DRUG" /><el-option label="检验" value="LAB" />
            <el-option label="检查" value="EXAM" /><el-option label="治疗" value="TREAT" />
            <el-option label="挂号费" value="REG" />
          </el-select>
          <el-button type="primary" size="small" style="margin-left: 4px" @click="searchCharges">检索</el-button>
        </h4>
        <el-table v-if="chargeHits.length" :data="chargeHits" size="small" border max-height="300">
          <el-table-column prop="charge_no" label="结算单号" width="170" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column v-if="searchType" prop="item_name" label="项目" show-overflow-tooltip />
          <el-table-column v-if="searchType" prop="order_type" label="类型" width="70" />
          <el-table-column v-if="searchType" prop="qty" label="量" width="55" />
          <el-table-column :prop="searchType ? 'amount' : 'total_amount'" label="金额" width="90" />
          <el-table-column prop="pay_method" label="方式" width="90" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column v-if="!searchType" prop="cashier" label="收款员" width="100" />
          <el-table-column prop="created_at" label="时间" width="170" />
        </el-table>
        <h4>异常明细（退费/作废支付单）</h4>
        <el-table :data="recon?.anomalies ?? []" size="small" border>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.kind === 'REFUND' ? 'danger' : 'info'">
                {{ row.kind === 'REFUND' ? '退费' : '作废支付' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="ref_no" label="单号" width="170" />
          <el-table-column prop="amount" label="金额" width="90" />
          <el-table-column prop="detail" label="方式" width="90" />
          <el-table-column prop="created_at" label="时间" width="170" />
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

interface Recon { byCashier: Record<string, unknown>[]; anomalies: Record<string, unknown>[] }

const tab = ref('emp')
const today = todayLocal()
const emps = ref<Record<string, unknown>[]>([])
const salaries = ref<Record<string, unknown>[]>([])
const trainings = ref<Record<string, unknown>[]>([])
const recruits = ref<Record<string, unknown>[]>([])
const directory = ref<Record<string, unknown>[]>([])
const transfers = ref<Record<string, unknown>[]>([])
const scraps = ref<Record<string, unknown>[]>([])
const buildings = ref<Record<string, unknown>[]>([])
const priceLogs = ref<Record<string, unknown>[]>([])
const recon = ref<Recon | null>(null)
const empKeyword = ref('')
const salaryMonth = ref(today.slice(0, 7))
const salaryText = ref('')
const salaryEmpNo = ref('')
const reconDate = ref(today)
const emp = reactive({ empNo: '', name: '', deptId: '', title: '', phone: '' })
const tr = reactive({ employeeId: '', category: '', certName: '' })
const tf = reactive({ assetId: '', toDeptId: '' })
const pr = reactive({ itemId: '', newPrice: 0, reason: '' })
const cmeRecords = ref<Record<string, unknown>[]>([])
const cmeSummary = ref<Record<string, unknown>[]>([])
const attendance = ref<Record<string, unknown>[]>([])
const adjusts = ref<Record<string, unknown>[]>([])
const chargeHits = ref<Record<string, unknown>[]>([])
const attDate = ref(today)
const searchFrom = ref(today)
const searchTo = ref(today)
const searchPay = ref('')
const searchType = ref('') // 1.0.1（1227）：按项目类型切明细行模式
const cme = reactive({ employeeId: '', projectName: '', credit: 5, cmeYear: today.slice(0, 4) })

async function loadCme() {
  const d = (await client.get('/hr/cme')).data.data
  cmeRecords.value = d.records
  cmeSummary.value = d.creditSummary
}
async function loadAtt() {
  attendance.value = (await client.get('/hr/attendance', { params: { date: attDate.value } })).data.data
}
async function addCme() {
  if (!cme.employeeId || !cme.projectName) return
  await client.post('/hr/cme', { employeeId: Number(cme.employeeId), projectName: cme.projectName,
    credit: cme.credit, cmeYear: Number(cme.cmeYear), organizer: '省继教平台' })
  cme.projectName = ''
  await loadCme()
}
async function punch(makeup: boolean) {
  const tip = makeup ? '员工ID,上班,下班,补卡说明（逗号分隔）' : '员工ID,上班,下班（逗号分隔）'
  const { value } = await ElMessageBox.prompt(tip, makeup ? '补卡' : '打卡',
    { inputValue: makeup ? '1,08:00,17:30,忘带工牌' : '1,08:00,17:30' })
  const [employeeId, checkIn, checkOut, note] = value.split(/[,，]/).map((s: string) => s.trim())
  await client.post('/hr/attendance', { employeeId: Number(employeeId), workDate: attDate.value,
    checkIn, checkOut, attType: makeup ? 'MAKEUP' : 'NORMAL', note })
  await loadAtt()
}
async function loadAdjusts() { adjusts.value = (await client.get('/asset-plus/value-adjusts')).data.data }
async function addAdjust() {
  const { value } = await ElMessageBox.prompt('资产ID,类型(APPRECIATION/DEP_FIX),金额,原因', '价值调整',
    { inputValue: `${tf.assetId || 1},DEP_FIX,1000,历史折旧补录` })
  const [assetId, adjustType, amount, reason] = value.split(/[,，]/).map((s: string) => s.trim())
  await client.post('/asset-plus/value-adjusts',
    { assetId: Number(assetId), adjustType, amount: Number(amount), reason })
  await loadAdjusts()
}
async function addAssetDoc() {
  const { value } = await ElMessageBox.prompt('资产ID,附件名', '附件登记',
    { inputValue: `${tf.assetId || 1},购置合同.pdf` })
  const [assetId, docName] = value.split(/[,，]/).map((s: string) => s.trim())
  await client.post('/asset-plus/docs', { assetId: Number(assetId), docName, remark: '' })
  ElMessage.success('已登记')
}
async function searchCharges() {
  chargeHits.value = (await client.get('/finance/charge-search',
    { params: { from: searchFrom.value, to: searchTo.value, payMethod: searchPay.value || undefined,
                orderType: searchType.value || undefined } })).data.data
}

async function loadEmps() {
  emps.value = (await client.get('/hr/employees', { params: { keyword: empKeyword.value || undefined } })).data.data
}
async function loadSalary() {
  if (!salaryEmpNo.value) return
  salaries.value = (await client.get('/hr/salaries', { params: { empNo: salaryEmpNo.value } })).data.data
}
async function loadTrain() {
  trainings.value = (await client.get('/hr/trainings')).data.data
  recruits.value = (await client.get('/hr/recruits')).data.data
}
async function loadDir() { directory.value = (await client.get('/hr/directory')).data.data }
async function loadAsset() {
  transfers.value = (await client.get('/asset-plus/transfers')).data.data
  scraps.value = (await client.get('/asset-plus/scraps')).data.data
  buildings.value = (await client.get('/asset-plus/buildings')).data.data
}
async function loadFinance() {
  priceLogs.value = (await client.get('/price/change-logs')).data.data
  await loadRecon()
}
async function loadRecon() {
  recon.value = (await client.get('/finance/reconciliation', { params: { date: reconDate.value } })).data.data
}

async function addEmp() {
  if (!emp.empNo || !emp.name) return
  await client.post('/hr/employees', { ...emp, deptId: emp.deptId ? Number(emp.deptId) : null, hireDate: today })
  ElMessage.success('已建档')
  await loadEmps()
}
async function importSalary() {
  const rows = salaryText.value.split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
    const [empNo, basePay, bonus, deduction] = l.split(/[,，\t]/).map((s) => s.trim())
    return { empNo, basePay: Number(basePay || 0), bonus: Number(bonus || 0), deduction: Number(deduction || 0) }
  })
  if (!rows.length) return
  const r = (await client.post('/hr/salaries/import', { month: salaryMonth.value, rows })).data.data
  ElMessage.success(`导入 ${r.imported} 条，工号不存在 ${r.missing} 条`)
}
async function addTraining() {
  if (!tr.employeeId || !tr.category) return
  await client.post('/hr/trainings', { ...tr, employeeId: Number(tr.employeeId), org: '院内培训中心' })
  await loadTrain()
}
async function addRecruit() {
  const { value } = await ElMessageBox.prompt('应聘人,岗位（逗号分隔）', '登记面试', { inputValue: '张应聘,护士' })
  const [candidateName, position] = value.split(/[,，]/).map((s: string) => s.trim())
  await client.post('/hr/recruits', { candidateName, position, interviewDate: today, note: '' })
  await loadTrain()
}
async function recruitResult(row: Record<string, unknown>, result: string) {
  await client.put(`/hr/recruits/${row.id}/result`, null, { params: { result } })
  await loadTrain()
}
async function doTransfer() {
  if (!tf.assetId || !tf.toDeptId) return
  await client.post('/asset-plus/transfers',
    { assetId: Number(tf.assetId), toDeptId: Number(tf.toDeptId), note: '科室调拨' })
  ElMessage.success('已调拨')
  await loadAsset()
}
async function doHandover() {
  const { value } = await ElMessageBox.prompt('资产ID,移交人,接收人（逗号分隔）', '移交登记',
    { inputValue: `${tf.assetId || 1},张三,李四` })
  const [assetId, fromPerson, toPerson] = value.split(/[,，]/).map((s: string) => s.trim())
  await client.post('/asset-plus/handovers', { assetId: Number(assetId), fromPerson, toPerson, note: '' })
  await loadAsset()
}
async function applyScrap() {
  const { value } = await ElMessageBox.prompt('报废原因', '报废申请', { inputValue: '超使用年限，维修不经济' })
  await client.post('/asset-plus/scraps', null, { params: { assetId: Number(tf.assetId), reason: value } })
  await loadAsset()
}
async function reviewScrap(row: Record<string, unknown>, approve: boolean) {
  await client.put(`/asset-plus/scraps/${row.id}/review`, null, { params: { approve, note: approve ? '同意' : '不同意' } })
  await loadAsset()
}
async function addBuilding() {
  const { value } = await ElMessageBox.prompt('编号,名称,用途,面积（逗号分隔）', '登记房屋',
    { inputValue: 'F01,门诊综合楼,门诊,12000' })
  const [buildingNo, name, usageType, areaSqm] = value.split(/[,，]/).map((s: string) => s.trim())
  await client.post('/asset-plus/buildings', { buildingNo, name, usageType, areaSqm: Number(areaSqm), address: '' })
  await loadAsset()
}
async function changePrice() {
  if (!pr.itemId || !pr.reason) return
  await client.put(`/price/charge-items/${pr.itemId}`, { newPrice: pr.newPrice, reason: pr.reason })
  ElMessage.success('已调价并留痕')
  await loadFinance()
}

watch(tab, (t) => {
  if (t === 'train') loadTrain()
  if (t === 'cme') { loadCme(); loadAtt() }
  if (t === 'dir') loadDir()
  if (t === 'asset') { loadAsset(); loadAdjusts() }
  if (t === 'finance') loadFinance()
})
onMounted(loadEmps)
</script>
