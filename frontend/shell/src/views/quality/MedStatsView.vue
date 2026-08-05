<template>
  <el-card>
    <el-descriptions v-if="overview" :column="4" border size="small" style="margin-bottom: 10px">
      <el-descriptions-item label="出院病案">{{ overview.discharged }} 份</el-descriptions-item>
      <el-descriptions-item label="已归档">{{ overview.archived }} 份</el-descriptions-item>
      <el-descriptions-item label="手术例数">{{ overview.surgeries }}</el-descriptions-item>
      <el-descriptions-item label="主诊断编码率">{{ overview.codedRate }}%</el-descriptions-item>
    </el-descriptions>

    <el-tabs v-model="tab">
      <el-tab-pane label="疾病谱 TOP20" name="disease">
        <el-table :data="diseaseTop" size="small" border>
          <el-table-column type="index" label="#" width="55" />
          <el-table-column prop="icd_group" label="ICD" width="80" />
          <el-table-column prop="sample_name" label="诊断" show-overflow-tooltip />
          <el-table-column prop="cases" label="例数" width="90" />
          <el-table-column prop="avg_days" label="平均住院日" width="110" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="手术统计" name="surgery">
        <el-table :data="surgeryStats" size="small" border>
          <el-table-column prop="procedure_name" label="术式" show-overflow-tooltip />
          <el-table-column prop="cases" label="总例数" width="90" />
          <el-table-column prop="recent_cases" label="近30日" width="90" />
          <el-table-column prop="done" label="已完成" width="90" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="科室出院构成" name="dept">
        <el-table :data="deptDischarge" size="small" border>
          <el-table-column prop="dept_name" label="科室" width="160" />
          <el-table-column prop="discharged" label="出院人数" width="100" />
          <el-table-column prop="avg_days" label="平均住院日" width="110" />
          <el-table-column prop="percentage" label="构成比(%)" width="100" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="ICD 章节构成" name="icd">
        <el-table :data="icdComposition" size="small" border>
          <el-table-column prop="chapter" label="章节（首字母）" width="130" />
          <el-table-column prop="cases" label="例数" width="90" />
          <el-table-column prop="percentage" label="构成比(%)" width="110" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import client from '../../api/client'

const tab = ref('disease')
const overview = ref<Record<string, unknown> | null>(null)
const diseaseTop = ref<Record<string, unknown>[]>([])
const surgeryStats = ref<Record<string, unknown>[]>([])
const deptDischarge = ref<Record<string, unknown>[]>([])
const icdComposition = ref<Record<string, unknown>[]>([])

async function load() {
  overview.value = (await client.get('/mrstats/overview')).data.data
  diseaseTop.value = (await client.get('/mrstats/disease-top')).data.data
}
watch(tab, async (t) => {
  if (t === 'surgery') surgeryStats.value = (await client.get('/mrstats/surgery-stats')).data.data
  if (t === 'dept') deptDischarge.value = (await client.get('/mrstats/dept-discharge')).data.data
  if (t === 'icd') icdComposition.value = (await client.get('/mrstats/icd-composition')).data.data
})
onMounted(load)
</script>
