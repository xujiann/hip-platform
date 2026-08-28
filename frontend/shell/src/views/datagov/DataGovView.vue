<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="指标管理" name="metrics">
        <el-button size="small" type="primary" @click="snapshot" :loading="snapshotLoading">生成今日快照</el-button>
        <el-table :data="metrics" size="small" border style="margin-top: 10px">
          <el-table-column prop="code" label="编码" width="80" />
          <el-table-column prop="name" label="指标" width="140" />
          <el-table-column prop="latest_value" label="最新值" width="110" />
          <el-table-column prop="unit" label="单位" width="70" />
          <el-table-column prop="target" label="目标值" width="90" />
          <el-table-column label="达标" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.target != null && row.latest_value != null"
                      :type="Number(row.latest_value) <= Number(row.target) ? 'success' : 'danger'" size="small">
                {{ Number(row.latest_value) <= Number(row.target) ? '达标' : '超标' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="snap_date" label="快照日期" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="数据填报" name="report">
        <el-form inline size="small">
          <el-form-item><el-input v-model="task.title" placeholder="填报任务标题" style="width: 220px" /></el-form-item>
          <el-form-item>
            <el-date-picker v-model="task.dueDate" type="date" value-format="YYYY-MM-DD" placeholder="截止日期" />
          </el-form-item>
          <el-form-item><el-input v-model="task.fields" placeholder="填报字段说明" style="width: 220px" /></el-form-item>
          <el-button type="primary" size="small" @click="createTask" :loading="createTaskLoading">下发任务</el-button>
        </el-form>
        <el-table :data="tasks" size="small" border @row-click="openTask" highlight-current-row>
          <el-table-column prop="title" label="任务" />
          <el-table-column prop="due_date" label="截止" width="110" />
          <el-table-column prop="submissions" label="已报" width="70" />
        </el-table>
        <template v-if="currentTask">
          <h4>「{{ currentTask.title }}」提交记录
            <el-button link type="primary" size="small" @click="submitReport" :loading="submitReportLoading">模拟提交一条</el-button>
          </h4>
          <el-table :data="submissions" size="small" border>
            <el-table-column prop="dept_name" label="科室" width="110" />
            <el-table-column prop="content" label="内容" show-overflow-tooltip />
            <el-table-column label="状态" width="160">
              <template #default="{ row }">
                <template v-if="row.status === 'SUBMITTED'">
                  <el-button link type="success" size="small" :loading="busyId === row.id" @click="review(row, true)">通过</el-button>
                  <el-button link type="danger" size="small" :loading="busyId === row.id" @click="review(row, false)">退回</el-button>
                </template>
                <el-tag v-else :type="row.status === 'APPROVED' ? 'success' : 'danger'" size="small">
                  {{ row.status === 'APPROVED' ? '已通过' : '已退回' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>

      <el-tab-pane label="数据质量" name="quality">
        <el-button size="small" @click="loadChecks">重新核查</el-button>
        <el-table :data="checks" size="small" border style="margin-top: 10px">
          <el-table-column prop="rule" label="核查规则" />
          <el-table-column prop="count" label="问题数" width="90" />
          <el-table-column label="结果" width="90">
            <template #default="{ row }">
              <el-tag :type="row.pass ? 'success' : 'warning'" size="small">{{ row.pass ? '通过' : '待治理' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('metrics')
const metrics = ref<Record<string, unknown>[]>([])
const tasks = ref<Record<string, unknown>[]>([])
const currentTask = ref<Record<string, unknown> | null>(null)
const submissions = ref<Record<string, unknown>[]>([])
const checks = ref<Record<string, unknown>[]>([])
const task = reactive({ title: '', dueDate: '', fields: '' })
const snapshotLoading = ref(false)
const createTaskLoading = ref(false)
const submitReportLoading = ref(false)
const busyId = ref<unknown>(null)

async function loadMetrics() {
  metrics.value = (await client.get('/datagov/metrics')).data.data
}
async function loadTasks() {
  tasks.value = (await client.get('/datagov/report-tasks')).data.data
}
async function loadChecks() {
  checks.value = (await client.get('/datagov/quality-checks')).data.data
}

async function snapshot() {
  snapshotLoading.value = true
  try {
    const resp = await client.post('/datagov/metrics/snapshot')
    ElMessage.success(`已生成 ${resp.data.data.snapshotted} 项指标快照`)
    await loadMetrics()
  } finally { snapshotLoading.value = false }
}

async function createTask() {
  if (!task.title || !task.dueDate) { ElMessage.warning('请填写任务标题与截止日期'); return }
  createTaskLoading.value = true
  try {
    await client.post('/datagov/report-tasks', task)
    ElMessage.success('任务已下发')
    await loadTasks()
  } finally { createTaskLoading.value = false }
}

async function openTask(row: Record<string, unknown>) {
  currentTask.value = row
  submissions.value = (await client.get('/datagov/submissions', { params: { taskId: row.id } })).data.data
}

async function submitReport() {
  if (!currentTask.value) return
  const res = await ElMessageBox.prompt('填报内容', '提交填报').catch(() => null)
  if (!res) return
  const { value } = res
  submitReportLoading.value = true
  try {
    await client.post('/datagov/submissions', { taskId: currentTask.value.id, deptId: 1, content: value })
    await openTask(currentTask.value)
    await loadTasks()
  } finally { submitReportLoading.value = false }
}

async function review(row: Record<string, unknown>, approve: boolean) {
  busyId.value = row.id
  try {
    await client.put(`/datagov/submissions/${row.id}/review`, null, { params: { approve, note: approve ? '' : '数据有误' } })
    if (currentTask.value) await openTask(currentTask.value)
  } finally { busyId.value = null }
}

onMounted(() => {
  loadMetrics()
  loadTasks()
  loadChecks()
})
</script>
