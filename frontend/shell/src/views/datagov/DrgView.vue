<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="病组分析" name="analysis">
        <el-button type="primary" size="small" @click="groupAll">批量入组</el-button>
        <el-button type="warning" size="small" @click="regroupAll">重新入组（规则/诊断变更后重算）</el-button>
        <el-descriptions v-if="analysis" :column="4" border size="small" style="margin: 10px 0">
          <el-descriptions-item label="入组病例">{{ analysis.cases }} 例</el-descriptions-item>
          <el-descriptions-item label="总权重">{{ analysis.totalWeight }}</el-descriptions-item>
          <el-descriptions-item label="CMI"><b style="color: #2563eb">{{ analysis.cmi }}</b></el-descriptions-item>
          <el-descriptions-item label="歧义病例(QY)">{{ analysis.ambiguous }} 例</el-descriptions-item>
          <el-descriptions-item label="费率">{{ analysis.rate }} 元/权重</el-descriptions-item>
          <el-descriptions-item label="标杆支付合计">￥{{ analysis.totalBenchmark }}</el-descriptions-item>
          <el-descriptions-item label="实际费用合计">￥{{ analysis.totalCost }}</el-descriptions-item>
          <el-descriptions-item label="模拟结余">
            <b :style="{ color: Number(analysis.totalBalance) >= 0 ? '#16a34a' : '#dc2626' }">
              ￥{{ analysis.totalBalance }}</b>
          </el-descriptions-item>
        </el-descriptions>
        <el-table v-if="analysis" :data="analysis.groups" size="small" border>
          <el-table-column prop="drg_code" label="DRG" width="70" />
          <el-table-column prop="drg_name" label="组名" show-overflow-tooltip />
          <el-table-column prop="weight" label="权重" width="80" />
          <el-table-column prop="cases" label="例数" width="70" />
          <el-table-column prop="avg_cost" label="次均费用" width="95" />
          <el-table-column prop="avg_days" label="平均住院日" width="100" />
          <el-table-column prop="benchmark_pay" label="标杆支付" width="95" />
          <el-table-column label="组内结余" width="95">
            <template #default="{ row }">
              <span v-if="row.balance != null" :style="{ color: Number(row.balance) >= 0 ? '#16a34a' : '#dc2626' }">
                {{ row.balance }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="费耗指数" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.cost_index != null" size="small"
                      :type="Number(row.cost_index) > 1 ? 'danger' : 'success'">{{ row.cost_index }}</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="时耗指数" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.time_index != null" size="small"
                      :type="Number(row.time_index) > 1 ? 'danger' : 'success'">{{ row.time_index }}</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="科室 CMI" name="dept">
        <el-table :data="deptRows" size="small" border>
          <el-table-column prop="dept_name" label="科室" width="160" />
          <el-table-column prop="cases" label="入组例数" width="100" />
          <el-table-column prop="total_weight" label="总权重" width="110" />
          <el-table-column label="CMI" width="110">
            <template #default="{ row }"><b style="color: #2563eb">{{ row.cmi }}</b></template>
          </el-table-column>
          <el-table-column prop="avg_cost" label="次均费用" width="110" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="入组明细" name="cases">
        <el-table :data="cases" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="135" />
          <el-table-column prop="patient_name" label="患者" width="85" />
          <el-table-column prop="admit_diag_icd" label="主诊断" width="80" />
          <el-table-column prop="drg_code" label="DRG" width="65" />
          <el-table-column prop="drg_name" label="病组" show-overflow-tooltip />
          <el-table-column label="严重程度" width="85">
            <template #default="{ row }">
              <el-tag v-if="row.severity === 'MCC'" type="danger" size="small">MCC</el-tag>
              <el-tag v-else-if="row.severity === 'CC'" type="warning" size="small">CC</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column prop="other_diags" label="其他诊断" width="80" />
          <el-table-column prop="weight" label="权重" width="80" />
          <el-table-column prop="total_cost" label="费用" width="80" />
          <el-table-column prop="benchmark_pay" label="标杆" width="80" />
          <el-table-column label="结余" width="80">
            <template #default="{ row }">
              <span v-if="row.balance != null" :style="{ color: Number(row.balance) >= 0 ? '#16a34a' : '#dc2626' }">
                {{ row.balance }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="倍率" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.pay_flag === 'LOW'" type="info" size="small">低倍率</el-tag>
              <el-tag v-else-if="row.pay_flag === 'HIGH'" type="danger" size="small">高倍率</el-tag>
              <span v-else-if="row.pay_flag === 'NORMAL'">正常</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="诊断补录" name="diag">
        <el-alert title="其他诊断（并发症/合并症）决定细分组尾码：命中 MCC 目录→尾码1（权重×1.3），CC→尾码3（×1.15），无→尾码5；补录后点「重新入组」生效"
                  type="info" show-icon :closable="false" style="margin-bottom: 8px" />
        <el-form inline size="small">
          <el-form-item><el-input v-model="diag.admissionId" placeholder="住院ID" style="width: 110px" /></el-form-item>
          <el-form-item><el-input v-model="diag.icd" placeholder="ICD（如 I50.9）" style="width: 130px" /></el-form-item>
          <el-form-item><el-input v-model="diag.name" placeholder="诊断名称" style="width: 180px" /></el-form-item>
          <el-button type="primary" size="small" @click="addDiag">补录其他诊断</el-button>
          <el-button size="small" @click="loadDiags">查询</el-button>
        </el-form>
        <el-table :data="diags" size="small" border>
          <el-table-column prop="icd" label="ICD" width="110" />
          <el-table-column prop="name" label="诊断" />
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="removeDiag(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4>MCC/CC 目录</h4>
        <el-table :data="ccList" size="small" border>
          <el-table-column prop="icd_prefix" label="ICD 前缀" width="100" />
          <el-table-column prop="name" label="诊断" />
          <el-table-column label="级别" width="90">
            <template #default="{ row }">
              <el-tag :type="row.level === 'MCC' ? 'danger' : 'warning'" size="small">{{ row.level }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="分组定义" name="defs">
        <el-alert title="ADRG（3位）：主诊断 ICD 前缀入组、同前缀按手术操作分列；细分组尾码由并发症实时计算。正式分组器接入后本表由官方目录替换"
                  type="info" show-icon :closable="false" style="margin-bottom: 8px" />
        <el-table :data="groups" size="small" border>
          <el-table-column prop="mdc_code" label="MDC" width="70" />
          <el-table-column prop="mdc_name" label="大类" width="130" />
          <el-table-column prop="adrg_code" label="ADRG" width="80" />
          <el-table-column prop="drg_name" label="组名" show-overflow-tooltip />
          <el-table-column prop="icd_prefixes" label="ICD 前缀" width="180" />
          <el-table-column label="手术组" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.surgical" type="danger" size="small">手术</el-tag>
              <el-tag v-else type="info" size="small">内科</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="weight" label="基础权重" width="100" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

interface Analysis {
  groups: Record<string, unknown>[]
  cases: number
  ambiguous: number
  totalWeight: string
  cmi: string
  rate: string
  totalBenchmark: string
  totalCost: string
  totalBalance: string
}

const tab = ref('analysis')
const analysis = ref<Analysis | null>(null)
const deptRows = ref<Record<string, unknown>[]>([])
const cases = ref<Record<string, unknown>[]>([])
const groups = ref<Record<string, unknown>[]>([])
const ccList = ref<Record<string, unknown>[]>([])
const diags = ref<Record<string, unknown>[]>([])
const diag = reactive({ admissionId: '', icd: '', name: '' })

async function loadAnalysis() { analysis.value = (await client.get('/drg/analysis')).data.data }
async function loadDept() { deptRows.value = (await client.get('/drg/dept-analysis')).data.data }
async function loadCases() { cases.value = (await client.get('/drg/cases')).data.data }
async function loadGroups() { groups.value = (await client.get('/drg/groups')).data.data }
async function loadCc() { ccList.value = (await client.get('/drg/cc-list')).data.data }
async function loadDiags() {
  if (!diag.admissionId) return
  diags.value = (await client.get('/inpatient/diagnoses', { params: { admissionId: diag.admissionId } })).data.data
}

async function groupAll() {
  const r = (await client.post('/drg/group-all')).data.data
  ElMessage.success(`入组完成：${r.grouped} 例入组，${r.ambiguous} 例歧义(QY)`)
  await loadAnalysis()
}
async function regroupAll() {
  const r = (await client.post('/drg/regroup-all')).data.data
  ElMessage.success(`重算完成：${r.grouped} 例入组，${r.ambiguous} 例歧义(QY)`)
  await loadAnalysis()
}
async function addDiag() {
  if (!diag.admissionId || !diag.icd || !diag.name) return
  await client.post('/inpatient/diagnoses', { admissionId: Number(diag.admissionId), icd: diag.icd, name: diag.name })
  ElMessage.success('已补录，重新入组后生效')
  diag.icd = ''
  diag.name = ''
  await loadDiags()
}
async function removeDiag(row: Record<string, unknown>) {
  await client.delete(`/inpatient/diagnoses/${row.id}`)
  await loadDiags()
}

watch(tab, (t) => {
  if (t === 'dept') loadDept()
  if (t === 'cases') loadCases()
  if (t === 'defs') loadGroups()
  if (t === 'diag') loadCc()
})
onMounted(loadAnalysis)
</script>
