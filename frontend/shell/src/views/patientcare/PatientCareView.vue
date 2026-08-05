<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="患者随访" name="followup">
        <el-form inline size="small">
          <el-form-item>
            <el-select v-model="fu.patientId" filterable remote :remote-method="searchPatients"
                       placeholder="搜索患者" style="width: 200px">
              <el-option v-for="p in patients" :key="p.id as number"
                         :label="`${p.name}（${p.patientNo}）`" :value="p.id as number" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="fu.topic" placeholder="随访主题" style="width: 200px" /></el-form-item>
          <el-form-item>
            <el-date-picker v-model="fu.dueDate" type="date" value-format="YYYY-MM-DD" placeholder="随访日期" />
          </el-form-item>
          <el-button type="primary" size="small" @click="createFollowup">建随访计划</el-button>
        </el-form>
        <el-table :data="followups" size="small" border>
          <el-table-column prop="due_date" label="随访日期" width="110" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="phone" label="电话" width="130" />
          <el-table-column prop="topic" label="主题" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="success" size="small" @click="doneFollowup(row)">完成随访</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="满意度" name="satisfaction">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 10px">
          <el-descriptions-item label="收集份数">{{ sat.count ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="平均分">{{ sat.avgScore ?? '-' }} / 5</el-descriptions-item>
        </el-descriptions>
        <el-table :data="(sat.recent as Record<string, unknown>[]) ?? []" size="small" border>
          <el-table-column label="评分" width="140">
            <template #default="{ row }"><el-rate :model-value="Number(row.score)" disabled /></template>
          </el-table-column>
          <el-table-column prop="source" label="来源" width="90" />
          <el-table-column prop="comment" label="意见" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="院内会诊" name="consult">
        <el-table :data="consults" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="170" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="to_dept_name" label="受邀科室" width="110" />
          <el-table-column prop="question" label="会诊问题" show-overflow-tooltip />
          <el-table-column label="状态/意见" width="220">
            <template #default="{ row }">
              <el-button v-if="row.status === 'REQUESTED'" link type="primary" size="small" @click="completeConsult(row)">
                书写会诊意见
              </el-button>
              <span v-else class="opinion">{{ row.opinion }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="健康体检" name="exam">
        <el-table :data="examRecords" size="small" border>
          <el-table-column prop="patient_name" label="体检人" width="100" />
          <el-table-column prop="package_name" label="套餐" width="160" />
          <el-table-column prop="price" label="价格" width="90" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'DONE' ? 'success' : 'warning'" size="small">
                {{ row.status === 'DONE' ? '已完成' : '已登记' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="summary" label="总检结论" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="单位团检" name="group">
        <el-form inline size="small">
          <el-form-item><el-input v-model="grp.unitName" placeholder="单位名称" style="width: 160px" /></el-form-item>
          <el-form-item><el-input v-model="grp.contact" placeholder="联系人" style="width: 110px" /></el-form-item>
          <el-form-item><el-input v-model="grp.packageId" placeholder="套餐ID" style="width: 90px" /></el-form-item>
          <el-form-item>
            <el-input v-model="grp.members" placeholder="人员名单（逗号分隔）" style="width: 240px" />
          </el-form-item>
          <el-button type="primary" size="small" @click="createGroup">团检建档</el-button>
        </el-form>
        <el-table :data="groups" size="small" border @row-click="openGroup" highlight-current-row>
          <el-table-column prop="unit_name" label="单位" width="160" />
          <el-table-column prop="package_name" label="套餐" width="150" />
          <el-table-column prop="members" label="人数" width="70" />
          <el-table-column prop="done" label="已检" width="70" />
          <el-table-column prop="abnormal_cnt" label="异常" width="70" />
          <el-table-column label="异常率" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.abnormal_rate != null" size="small"
                      :type="Number(row.abnormal_rate) > 30 ? 'danger' : 'success'">{{ row.abnormal_rate }}%</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
        <template v-if="groupRecords.length">
          <h4>团检名单</h4>
          <el-table :data="groupRecords" size="small" border>
            <el-table-column prop="patient_name" label="姓名" width="110" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'DONE' ? 'success' : 'warning'" size="small">
                  {{ row.status === 'DONE' ? '已完成' : '待检' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="summary" label="结论" show-overflow-tooltip />
            <el-table-column label="操作" width="180">
              <template #default="{ row }">
                <template v-if="row.status !== 'DONE'">
                  <el-button link type="success" size="small" @click="finishExam(row, false)">正常</el-button>
                  <el-button link type="danger" size="small" @click="finishExam(row, true)">异常</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>

      <el-tab-pane label="临床路径" name="pathway">
        <h4>变异统计</h4>
        <el-table :data="varStats" size="small" border>
          <el-table-column prop="pathway_name" label="路径" width="200" />
          <el-table-column prop="enrolled" label="入径" width="70" />
          <el-table-column prop="completed" label="完成" width="70" />
          <el-table-column prop="exited" label="退出" width="70" />
          <el-table-column prop="varied" label="有变异" width="80" />
          <el-table-column label="变异率" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="Number(row.variance_rate) > 20 ? 'danger' : 'success'">
                {{ row.variance_rate }}%</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <h4>入径病例</h4>
        <el-table :data="enrollments" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="140" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="pathway_name" label="路径" show-overflow-tooltip />
          <el-table-column prop="variances" label="变异数" width="75" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'COMPLETED' ? 'success'
                : row.status === 'EXITED' ? 'danger' : 'warning'">
                {{ { ACTIVE: '在径', COMPLETED: '完成', EXITED: '退出' }[row.status as string] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <template v-if="row.status === 'ACTIVE'">
                <el-button link type="warning" size="small" @click="addVariance(row, 'DELAY')">记变异</el-button>
                <el-button link type="danger" size="small" @click="addVariance(row, 'EXIT')">退出路径</el-button>
                <el-button link type="success" size="small" @click="completePathway(row)">完成</el-button>
              </template>
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

const tab = ref('followup')
const patients = ref<Record<string, unknown>[]>([])
const followups = ref<Record<string, unknown>[]>([])
const sat = ref<Record<string, unknown>>({})
const consults = ref<Record<string, unknown>[]>([])
const examRecords = ref<Record<string, unknown>[]>([])
const fu = reactive({ patientId: null as number | null, topic: '', dueDate: '' })
const groups = ref<Record<string, unknown>[]>([])
const groupRecords = ref<Record<string, unknown>[]>([])
const enrollments = ref<Record<string, unknown>[]>([])
const varStats = ref<Record<string, unknown>[]>([])
const grp = reactive({ unitName: '', contact: '', packageId: '', members: '' })

async function loadGroups() { groups.value = (await client.get('/exam/groups')).data.data }
async function loadPathways() {
  enrollments.value = (await client.get('/pathways/enrollments')).data.data
  varStats.value = (await client.get('/pathways/variance-stats')).data.data
}
async function createGroup() {
  if (!grp.unitName || !grp.packageId || !grp.members) return
  const names = grp.members.split(/[,，、]/).map((s) => s.trim()).filter(Boolean)
  const resp = await client.post('/exam/groups',
    { unitName: grp.unitName, contact: grp.contact, packageId: Number(grp.packageId), memberNames: names })
  ElMessage.success(`团检建档成功，${resp.data.data.members} 人`)
  await loadGroups()
}
async function openGroup(row: Record<string, unknown>) {
  groupRecords.value = (await client.get(`/exam/groups/${row.id}/records`)).data.data
}
async function finishExam(row: Record<string, unknown>, abnormal: boolean) {
  const { value } = await ElMessageBox.prompt('总检结论', '完成体检',
    { inputValue: abnormal ? '血压偏高，建议心内科复查' : '各项指标正常' })
  await client.put(`/exam/records/${row.id}/complete`, null, { params: { summary: value, abnormal } })
  await loadGroups()
  groupRecords.value = groupRecords.value.map((r) =>
    r.id === row.id ? { ...r, status: 'DONE', summary: value, abnormal } : r)
}
async function addVariance(row: Record<string, unknown>, varType: string) {
  const { value } = await ElMessageBox.prompt('变异原因', varType === 'EXIT' ? '退出路径' : '变异登记',
    { inputValue: varType === 'EXIT' ? '病情变化，转出路径' : '检查延迟一天完成' })
  await client.post(`/pathways/enrollments/${row.id}/variances`, { dayNo: 1, varType, reason: value })
  await loadPathways()
}
async function completePathway(row: Record<string, unknown>) {
  await client.put(`/pathways/enrollments/${row.id}/complete`)
  await loadPathways()
}

async function searchPatients(kw: string) {
  const resp = await client.get('/patients', { params: { keyword: kw, page: 0, size: 10 } })
  patients.value = resp.data.data.records
}

async function loadAll() {
  followups.value = (await client.get('/patientcare/followups')).data.data
  sat.value = (await client.get('/patientcare/satisfaction/stats')).data.data
  consults.value = (await client.get('/inpatient/consults')).data.data
  examRecords.value = (await client.get('/exam/records')).data.data
}

async function createFollowup() {
  if (!fu.patientId || !fu.topic || !fu.dueDate) {
    ElMessage.warning('患者、主题、日期必填')
    return
  }
  await client.post('/patientcare/followups', fu)
  ElMessage.success('随访计划已建立')
  await loadAll()
}

async function doneFollowup(row: Record<string, unknown>) {
  const { value } = await ElMessageBox.prompt('随访结果', '完成随访', { inputValue: '恢复良好，无异常' })
  await client.put(`/patientcare/followups/${row.id}/done`, null, { params: { note: value } })
  await loadAll()
}

async function completeConsult(row: Record<string, unknown>) {
  const { value } = await ElMessageBox.prompt('会诊意见', '书写会诊意见')
  await client.put(`/inpatient/consults/${row.id}/complete`, null, { params: { opinion: value } })
  await loadAll()
}

watch(tab, (t) => {
  if (t === 'group') loadGroups()
  if (t === 'pathway') loadPathways()
})
onMounted(loadAll)
</script>

<style scoped>
.opinion { color: #67c23a; font-size: 12px; }
</style>
