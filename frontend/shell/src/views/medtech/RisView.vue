<template>
  <el-card>
    <div class="toolbar">
      <h3>RIS 检查报告</h3>
      <el-button link type="primary" @click="load">刷新</el-button>
    </div>
    <el-table :data="records" size="small" border>
      <el-table-column prop="group_no" label="申请单号" width="150" />
      <el-table-column prop="patient_name" label="患者" width="90" />
      <el-table-column prop="item_name" label="检查项目" width="160" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">{{ { REGISTERED: '已登记', REPORTED: '已报告' }[row.status as string] }}</template>
      </el-table-column>
      <el-table-column prop="impression" label="诊断意见" show-overflow-tooltip />
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openReport(row)">
            {{ row.status === 'REGISTERED' ? '书写报告' : '修改报告' }}
          </el-button>
          <el-button v-if="row.status === 'REPORTED'" link type="warning" size="small" @click="markCritical(row)">标记危急</el-button>
          <el-button v-if="row.status === 'REPORTED'" link type="success" size="small" :loading="busyId === row.id" @click="verify(row)">审核发布</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="`报告：${current?.item_name}`" width="560px">
      <el-form label-width="80px">
        <el-form-item label="影像所见">
          <el-input v-model="findings" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="诊断意见">
          <el-input v-model="impression" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveReport" :loading="saveReportLoading">保存报告</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const records = ref<Record<string, unknown>[]>([])
const dialogVisible = ref(false)
const current = ref<Record<string, unknown> | null>(null)
const findings = ref('')
const impression = ref('')
const busyId = ref<unknown>(null)
const saveReportLoading = ref(false)

async function load() {
  records.value = (await client.get('/ris/worklist')).data.data
}

function openReport(row: Record<string, unknown>) {
  current.value = row
  findings.value = String(row.findings ?? '')
  impression.value = String(row.impression ?? '')
  dialogVisible.value = true
}

async function saveReport() {
  if (!current.value) return
  saveReportLoading.value = true
  try {
    await client.put(`/ris/exams/${current.value.id}/report`, { findings: findings.value, impression: impression.value })
    ElMessage.success('报告已保存')
    dialogVisible.value = false
    await load()
  } finally { saveReportLoading.value = false }
}

async function markCritical(row: Record<string, unknown>) {
  // 影像危急值（气胸/夹层/颅内出血等）：上报后走与检验同一条确认闭环，通知开单医师限时确认
  const res = await ElMessageBox.prompt('危急描述（必填，如：右侧气胸，肺压缩约 40%）', '影像危急值上报',
    { inputPlaceholder: '危急征象描述' }).catch(() => null)
  if (!res) return
  const note = (res.value || '').trim()
  if (!note) { ElMessage.warning('危急描述必填'); return }
  await client.put(`/ris/exams/${row.id}/critical`, { note })
  ElMessage.success('已上报危急值，已通知开单医师确认')
  await load()
}

async function verify(row: Record<string, unknown>) {
  busyId.value = row.id
  try {
    await client.put(`/ris/exams/${row.id}/verify`)
    ElMessage.success('已审核发布，医嘱转已执行')
    // 云胶片/报告对外分享：发布即出 72h 有效短链，可发给患者
    const share = (await client.post(`/ris/exams/${row.id}/share`)).data.data
    await ElMessageBox.alert(
      `<div style="word-break:break-all">匿名访问链接（72 小时有效，姓名已脱敏）：
<b>${location.origin}${share.url}</b></div>`,
      '报告分享')
    await load()
  } finally { busyId.value = null }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
</style>
