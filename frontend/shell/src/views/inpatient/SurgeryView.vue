<template>
  <el-card>
    <div class="toolbar">
      <h3>手术麻醉</h3>
      <el-button type="primary" size="small" @click="dialogVisible = true">手术申请</el-button>
    </div>
    <el-table :data="records" size="small" border>
      <el-table-column prop="admission_no" label="住院号" width="170" />
      <el-table-column prop="patient_name" label="患者" width="90" />
      <el-table-column prop="procedure_name" label="术式" />
      <el-table-column prop="anesthesia_type" label="麻醉方式" width="100" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'DONE' ? 'success' : 'warning'" size="small">
            {{ { REQUESTED: '已申请', SCHEDULED: '已排台', DONE: '已完成' }[row.status as string] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button v-if="row.status !== 'DONE'" link type="success" size="small" @click="complete(row)">
            术后记录
          </el-button>
          <el-tooltip v-else :content="`术中：${row.op_note} / 麻醉：${row.anes_note}`">
            <el-button link size="small">查看记录</el-button>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="手术申请" width="460px">
      <el-form label-width="90px">
        <el-form-item label="在院患者">
          <el-select v-model="form.admissionId" style="width: 100%">
            <el-option v-for="a in admissions" :key="a.id as number"
                       :label="`${a.bedNo}床 ${a.patientName}`" :value="a.id as number" />
          </el-select>
        </el-form-item>
        <el-form-item label="术式"><el-input v-model="form.procedureName" /></el-form-item>
        <el-form-item label="麻醉方式">
          <el-select v-model="form.anesthesiaType" style="width: 100%">
            <el-option v-for="t in ['全身麻醉', '椎管内麻醉', '局部麻醉', '神经阻滞']" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="排台时间">
          <el-date-picker v-model="form.scheduledAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">提交</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const records = ref<Record<string, unknown>[]>([])
const admissions = ref<Record<string, unknown>[]>([])
const dialogVisible = ref(false)
const form = reactive({ admissionId: null as number | null, procedureName: '', anesthesiaType: '全身麻醉', scheduledAt: '' })

async function load() {
  records.value = (await client.get('/inpatient/surgeries')).data.data
  admissions.value = (await client.get('/inpatient/admissions')).data.data
}

async function save() {
  if (!form.admissionId || !form.procedureName) {
    ElMessage.warning('患者与术式必填')
    return
  }
  await client.post('/inpatient/surgeries', form)
  ElMessage.success('已提交')
  dialogVisible.value = false
  await load()
}

async function complete(row: Record<string, unknown>) {
  const { value: opNote } = await ElMessageBox.prompt('术中记录', '术后记录', { inputValue: '手术顺利，出血约 50ml' })
  const { value: anesNote } = await ElMessageBox.prompt('麻醉记录', '术后记录', { inputValue: '麻醉平稳，苏醒完全' })
  await client.put(`/inpatient/surgeries/${row.id}/complete`, { opNote, anesNote })
  await load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
</style>
