<template>
  <div class="register-page">
    <el-card class="panel">
      <template #header>1. 选择患者</template>
      <div class="search">
        <el-input v-model="patientKeyword" placeholder="姓名 / 患者号 / 身份证号 / 手机号" clearable
                  @keyup.enter="searchPatients" />
        <el-button type="primary" @click="searchPatients">查询</el-button>
      </div>
      <el-table :data="patients" highlight-current-row height="220" @current-change="selectPatient">
        <el-table-column prop="patientNo" label="患者号" width="110" />
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column label="性别" width="60">
          <template #default="{ row }">{{ { M: '男', F: '女', U: '未知' }[row.sex as string] }}</template>
        </el-table-column>
        <el-table-column prop="age" label="年龄" width="60" />
        <el-table-column prop="phone" label="手机号" />
      </el-table>
      <el-alert v-if="selectedPatient" type="success" :closable="false"
                :title="`已选：${selectedPatient.patientNo} ${selectedPatient.name}`" />
    </el-card>

    <el-card class="panel">
      <template #header>2. 选择号源（{{ date }}）</template>
      <div class="search">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="loadSchedules" />
      </div>
      <el-table :data="schedules" v-loading="loadingSchedules" height="260">
        <el-table-column prop="deptName" label="科室" width="120" />
        <el-table-column label="医生" width="100">
          <template #default="{ row }">{{ row.doctorName || '普通号' }}</template>
        </el-table-column>
        <el-table-column label="时段" width="70">
          <template #default="{ row }">{{ { AM: '上午', PM: '下午', FULL: '全天' }[row.shift as string] }}</template>
        </el-table-column>
        <el-table-column prop="fee" label="费用" width="70" />
        <el-table-column prop="remaining" label="余号" width="60" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button type="primary" size="small" :disabled="row.remaining <= 0 || !selectedPatient"
                       @click="doRegister(row)">
              挂号
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="panel wide">
      <template #header>今日挂号记录</template>
      <el-table :data="registrations" v-loading="loadingRegs" height="240">
        <el-table-column prop="regNo" label="号序" width="60" />
        <el-table-column prop="patientNo" label="患者号" width="110" />
        <el-table-column prop="patientName" label="姓名" width="90" />
        <el-table-column prop="deptName" label="科室" width="120" />
        <el-table-column label="医生" width="100">
          <template #default="{ row }">{{ row.doctorName || '普通号' }}</template>
        </el-table-column>
        <el-table-column prop="fee" label="费用" width="70" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="statusTag[row.status as string]" size="small">{{ statusNames[row.status as string] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button v-if="row.status === 'REGISTERED'" link type="danger" @click="doCancel(row)">退号</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { todayLocal } from '../../utils/date'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const statusNames: Record<string, string> = { REGISTERED: '已挂号', CANCELLED: '已退号', VISITED: '已就诊' }
const statusTag: Record<string, string> = { REGISTERED: 'success', CANCELLED: 'info', VISITED: '' }

const date = ref(todayLocal())
const patientKeyword = ref('')
const patients = ref<Record<string, unknown>[]>([])
const selectedPatient = ref<Record<string, unknown> | null>(null)
const schedules = ref<Record<string, unknown>[]>([])
const registrations = ref<Record<string, unknown>[]>([])
const loadingSchedules = ref(false)
const loadingRegs = ref(false)

async function searchPatients() {
  const resp = await client.get('/patients', { params: { keyword: patientKeyword.value, page: 0, size: 10 } })
  patients.value = resp.data.data.records
}

function selectPatient(row: Record<string, unknown> | null) {
  selectedPatient.value = row
}

async function loadSchedules() {
  loadingSchedules.value = true
  try {
    const resp = await client.get('/outpatient/schedules', { params: { date: date.value } })
    schedules.value = resp.data.data
  } finally {
    loadingSchedules.value = false
  }
}

async function loadRegistrations() {
  loadingRegs.value = true
  try {
    const resp = await client.get('/outpatient/registrations', { params: { date: todayLocal() } })
    registrations.value = resp.data.data
  } finally {
    loadingRegs.value = false
  }
}

async function doRegister(schedule: Record<string, unknown>) {
  if (!selectedPatient.value) return
  const resp = await client.post('/outpatient/registrations', {
    patientId: selectedPatient.value.id,
    scheduleId: schedule.id,
  })
  const reg = resp.data.data
  ElMessage.success(`挂号成功：${reg.deptName} 第 ${reg.regNo} 号，费用 ${reg.fee} 元`)
  await Promise.all([loadSchedules(), loadRegistrations()])
}

async function doCancel(row: Record<string, unknown>) {
  try {
    await ElMessageBox.confirm(`确认为 ${row.patientName} 退号？`, '退号确认')
  } catch {
    return   // 用户取消
  }
  await client.put(`/outpatient/registrations/${row.id}/cancel`)
  ElMessage.success('已退号')
  await Promise.all([loadSchedules(), loadRegistrations()])
}

onMounted(() => {
  loadSchedules()
  loadRegistrations()
})
</script>

<style scoped>
.register-page {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.panel.wide { grid-column: span 2; }
.search { display: flex; gap: 8px; margin-bottom: 8px; }
</style>
