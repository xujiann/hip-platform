<template>
  <el-card>
    <h4>用血申请</h4>
    <el-form inline size="small">
      <el-form-item><el-input v-model="form.admissionId" placeholder="住院ID" style="width: 110px" /></el-form-item>
      <el-form-item>
        <el-select v-model="form.product" style="width: 140px">
          <el-option label="红细胞 RBC" value="RBC" />
          <el-option label="血浆 PLASMA" value="PLASMA" />
          <el-option label="血小板 PLT" value="PLT" />
        </el-select>
      </el-form-item>
      <el-form-item><el-input-number v-model="form.volumeMl" :min="50" :step="50" /> ml</el-form-item>
      <el-form-item><el-input v-model="form.reason" placeholder="用血理由" style="width: 220px" /></el-form-item>
      <el-button type="primary" size="small" @click="apply">提交申请</el-button>
    </el-form>

    <el-table :data="applies" size="small" border style="margin-top: 10px">
      <el-table-column prop="id" label="#" width="55" />
      <el-table-column prop="patient_name" label="患者" width="90" />
      <el-table-column prop="admission_no" label="住院号" width="130" />
      <el-table-column prop="product" label="血制品" width="90" />
      <el-table-column prop="volume_ml" label="用量(ml)" width="90" />
      <el-table-column prop="reason" label="理由" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="tagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="流转" width="220">
        <template #default="{ row }">
          <template v-if="row.status === 'APPLIED'">
            <el-button link type="success" size="small" @click="review(row, true)">批准</el-button>
            <el-button link type="danger" size="small" @click="review(row, false)">驳回</el-button>
          </template>
          <el-button v-if="row.status === 'APPROVED'" link type="primary" size="small" @click="issue(row)">发血</el-button>
          <el-button v-if="row.status === 'ISSUED'" link type="warning" size="small" @click="transfuse(row)">
            输血记录</el-button>
          <span v-if="row.status === 'TRANSFUSED'">{{ row.transfusion_note }}</span>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const applies = ref<Record<string, unknown>[]>([])
const form = reactive({ admissionId: '', product: 'RBC', volumeMl: 200, reason: '' })

const statusMap: Record<string, [string, string]> = {
  APPLIED: ['待审批', 'info'], APPROVED: ['已批准', 'primary'], REJECTED: ['已驳回', 'danger'],
  ISSUED: ['已发血', 'warning'], TRANSFUSED: ['已输血', 'success'],
}
const statusText = (s: string) => statusMap[s]?.[0] ?? s
const tagType = (s: string) => (statusMap[s]?.[1] ?? 'info') as 'info'

async function load() { applies.value = (await client.get('/inpatient/blood/applies')).data.data }

async function apply() {
  if (!form.admissionId) return
  await client.post('/inpatient/blood/applies', { ...form, admissionId: Number(form.admissionId) })
  ElMessage.success('已提交')
  await load()
}
async function review(row: Record<string, unknown>, approve: boolean) {
  const { value } = await ElMessageBox.prompt('审批意见', approve ? '批准' : '驳回', { inputValue: approve ? '同意' : '' })
  await client.put(`/inpatient/blood/applies/${row.id}/review`, null, { params: { approve, note: value } })
  await load()
}
async function issue(row: Record<string, unknown>) {
  await client.put(`/inpatient/blood/applies/${row.id}/issue`)
  await load()
}
async function transfuse(row: Record<string, unknown>) {
  const { value } = await ElMessageBox.prompt('输血过程记录（含不良反应）', '输血记录', { inputValue: '输注顺利，无不良反应' })
  await client.put(`/inpatient/blood/applies/${row.id}/transfuse`, null, { params: { note: value } })
  await load()
}

onMounted(load)
</script>
