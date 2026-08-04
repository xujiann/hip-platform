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
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('followup')
const patients = ref<Record<string, unknown>[]>([])
const followups = ref<Record<string, unknown>[]>([])
const sat = ref<Record<string, unknown>>({})
const consults = ref<Record<string, unknown>[]>([])
const examRecords = ref<Record<string, unknown>[]>([])
const fu = reactive({ patientId: null as number | null, topic: '', dueDate: '' })

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

onMounted(loadAll)
</script>

<style scoped>
.opinion { color: #67c23a; font-size: 12px; }
</style>
