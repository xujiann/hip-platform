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
      <!-- v42：白板是护士的主入口，护理文书入口挂在这里，否则新表没人写 -->
      <el-table-column label="护理文书" width="130">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openRecords(row)">
            记录 {{ row.nursing_records }} 条
          </el-button>
        </template>
      </el-table-column>
      <el-table-column prop="admission_no" label="住院号" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const router = useRouter()
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

/**
 * 调级走 v42 的带原因端点：分级护理的升降级是护理工作量与收费的直接依据，
 * 无原因的调级在质控上不可追溯。取消则重新加载还原下拉显示（下拉是非受控回显）。
 */
async function setLevel(row: Record<string, unknown>, level: string) {
  const res = await ElMessageBox.prompt(`调整为「${level}护理」的原因`, '护理级别变更', {
    inputPlaceholder: '如：病情加重转特级护理 / 病情稳定降级',
  }).catch(() => null)
  if (!res) {
    await load()
    return
  }
  await client.put(`/inpatient/admissions/${row.admission_id}/care-level/change`,
    { level, reason: res.value })
  ElMessage.success(`已调整为${level}护理`)
  await load()
}

function openRecords(row: Record<string, unknown>) {
  router.push({ path: '/inpatient/nursing-record', query: { admissionId: String(row.admission_id) } })
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
</style>
