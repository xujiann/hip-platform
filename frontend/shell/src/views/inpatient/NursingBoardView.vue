<template>
  <el-card>
    <div class="toolbar">
      <h3>护理白板 · 在院一览</h3>
      <el-button link type="primary" @click="load">刷新</el-button>
    </div>
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="ward_name" label="病区" width="110" />
      <el-table-column prop="bed_no" label="床号" width="70" />
      <el-table-column prop="patient_name" label="姓名" width="90" />
      <el-table-column label="护理级别" width="130">
        <template #default="{ row }">
          <el-select :model-value="row.care_level" size="small" style="width: 90px"
                     @change="(v: string) => setLevel(row, v)">
            <el-option v-for="l in ['特级', '一级', '二级', '三级']" :key="l" :label="l" :value="l" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="过敏" width="140">
        <template #default="{ row }">
          <el-tag v-if="row.allergy_history" type="danger" size="small">{{ row.allergy_history }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="dept_name" label="科室" width="110" />
      <el-table-column label="待执行医嘱" width="110">
        <template #default="{ row }">
          <el-tag :type="Number(row.pending_orders) > 0 ? 'warning' : 'success'" size="small">
            {{ row.pending_orders }} 条
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="admission_no" label="住院号" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const records = ref<Record<string, unknown>[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/inpatient/nursing/board')
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

async function setLevel(row: Record<string, unknown>, level: string) {
  await client.put(`/inpatient/admissions/${row.admission_id}/care-level`, null, { params: { level } })
  ElMessage.success(`已调整为${level}护理`)
  await load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
</style>
