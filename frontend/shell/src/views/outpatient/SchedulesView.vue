<template>
  <el-card>
    <div class="toolbar">
      <h3>医生排班</h3>
      <div class="filters">
        <el-button size="small" @click="shiftWeek(-7)">上一周</el-button>
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="load" />
        <el-button size="small" @click="shiftWeek(7)">下一周</el-button>
        <el-select v-model="deptId" placeholder="全部科室" clearable style="width: 160px" @change="load">
          <el-option v-for="d in clinicalDepts" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-button type="primary" @click="openCreate">新增排班</el-button>
      </div>
    </div>

    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="deptName" label="科室" width="140" />
      <el-table-column prop="doctorName" label="医生" width="120">
        <template #default="{ row }">{{ row.doctorName || '（普通号）' }}</template>
      </el-table-column>
      <el-table-column label="时段" width="80">
        <template #default="{ row }">{{ shiftNames[row.shift] }}</template>
      </el-table-column>
      <el-table-column label="号别" width="90">
        <template #default="{ row }">{{ row.regType === 'EXPERT' ? '专家号' : '普通号' }}</template>
      </el-table-column>
      <el-table-column prop="fee" label="挂号费" width="90" />
      <el-table-column prop="capacity" label="号源" width="70" />
      <el-table-column prop="booked" label="已挂" width="70" />
      <el-table-column prop="remaining" label="余号" width="70" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="danger" @click="disable(row)">停诊</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新增排班" width="440px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="日期" required>
          <el-date-picker v-model="form.scheduleDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="科室" required>
          <el-select v-model="form.deptId" style="width: 100%">
            <el-option v-for="d in clinicalDepts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="医生">
          <el-select v-model="form.doctorId" clearable placeholder="不选则为科室普通号" style="width: 100%">
            <el-option v-for="u in doctors" :key="u.id" :label="u.realName" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="时段">
          <el-radio-group v-model="form.shift">
            <el-radio value="AM">上午</el-radio>
            <el-radio value="PM">下午</el-radio>
            <el-radio value="FULL">全天</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="号别">
          <el-radio-group v-model="form.regType">
            <el-radio value="GENERAL">普通号</el-radio>
            <el-radio value="EXPERT">专家号</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="挂号费" required>
          <el-input-number v-model="form.fee" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="号源数量" required>
          <el-input-number v-model="form.capacity" :min="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const shiftNames: Record<string, string> = { AM: '上午', PM: '下午', FULL: '全天' }

const today = new Date().toISOString().slice(0, 10)
const date = ref(today)

/** 1.0.1（893）：按周快捷切换（本地日期拼串，避免 toISOString 的 UTC 偏移跨日） */
function shiftWeek(days: number) {
  const d = new Date(date.value + 'T00:00:00')
  d.setDate(d.getDate() + days)
  date.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  load()
}
const deptId = ref<number | null>(null)
const records = ref<Record<string, unknown>[]>([])
const depts = ref<{ id: number; name: string; type: string }[]>([])
const doctors = ref<{ id: number; realName: string }[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)

const clinicalDepts = computed(() => depts.value.filter((d) => d.type === 'CLINICAL'))

const form = reactive({
  scheduleDate: today, deptId: null as number | null, doctorId: null as number | null,
  shift: 'FULL', regType: 'GENERAL', fee: 10, capacity: 50,
})

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/outpatient/schedules', {
      params: { date: date.value, deptId: deptId.value ?? undefined },
    })
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.scheduleDate = date.value
  dialogVisible.value = true
}

async function save() {
  if (!form.deptId) {
    ElMessage.warning('请选择科室')
    return
  }
  saving.value = true
  try {
    await client.post('/outpatient/schedules', form)
    ElMessage.success('排班已创建')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function disable(row: Record<string, unknown>) {
  await client.put(`/outpatient/schedules/${row.id}/enabled`, null, { params: { enabled: false } })
  ElMessage.success('已停诊')
  await load()
}

onMounted(async () => {
  const [d, u] = await Promise.all([
    client.get('/system/depts'),
    client.get('/system/users', { params: { page: 0, size: 100 } }),
  ])
  depts.value = d.data.data
  doctors.value = u.data.data.records
  await load()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.toolbar h3 { margin: 0; }
.filters { display: flex; gap: 8px; }
</style>
