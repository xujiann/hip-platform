<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="入组建议" name="candidates">
        <el-table :data="candidates" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="admission_no" label="住院号" width="130" />
          <el-table-column prop="admit_diag_name" label="诊断" show-overflow-tooltip />
          <el-table-column prop="admit_diag_icd" label="ICD" width="80" />
          <el-table-column prop="disease_name" label="命中病种" width="140" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="createCase(row)">建上报卡</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="上报卡" name="cases">
        <el-table :data="cases" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="disease_name" label="病种" width="140" />
          <el-table-column prop="dataset" label="数据集" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'REPORTED' ? 'success' : 'info'" size="small">
                {{ row.status === 'REPORTED' ? '已上报' : '草稿' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="row.status === 'DRAFT'" link type="success" size="small" @click="report(row)">
                上报</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="病种定义" name="defs">
        <el-table :data="defs" size="small" border>
          <el-table-column prop="code" label="编码" width="110" />
          <el-table-column prop="name" label="病种" />
          <el-table-column prop="icd_prefix" label="ICD 前缀" width="110" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('candidates')
const candidates = ref<Record<string, unknown>[]>([])
const cases = ref<Record<string, unknown>[]>([])
const defs = ref<Record<string, unknown>[]>([])

async function loadAll() {
  const [c, k, d] = await Promise.all([
    client.get('/quality/single-disease/candidates'),
    client.get('/quality/single-disease/cases'),
    client.get('/quality/single-disease/defs'),
  ])
  candidates.value = c.data.data
  cases.value = k.data.data
  defs.value = d.data.data
}

async function createCase(row: Record<string, unknown>) {
  const { value } = await ElMessageBox.prompt('上报数据集（关键指标 JSON/文本）', '建上报卡',
    { inputValue: '{"到院至给药时间(min)": 30, "住院天数": 7}' })
  await client.post('/quality/single-disease/cases',
    { admissionId: row.admission_id, diseaseCode: row.disease_code, dataset: value })
  ElMessage.success('已建卡')
  await loadAll()
}
async function report(row: Record<string, unknown>) {
  await client.put(`/quality/single-disease/cases/${row.id}/report`)
  await loadAll()
}

onMounted(loadAll)
</script>
