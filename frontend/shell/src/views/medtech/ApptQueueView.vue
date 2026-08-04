<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="时段预约" name="appt">
        <h4>待预约申请单（已收费）
          <el-button link type="primary" size="small" @click="loadPending">刷新</el-button>
        </h4>
        <el-table :data="pending" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="order_type" label="类型" width="80" />
          <el-table-column prop="item_name" label="项目" show-overflow-tooltip />
          <el-table-column label="预约" width="330">
            <template #default="{ row }">
              <el-date-picker v-model="row._date" type="date" value-format="YYYY-MM-DD" size="small"
                              placeholder="日期" style="width: 130px" />
              <el-select v-model="row._period" size="small" style="width: 80px; margin: 0 4px">
                <el-option label="上午" value="AM" /><el-option label="下午" value="PM" />
              </el-select>
              <el-button type="primary" size="small" @click="book(row)">预约</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4>
          预约队列
          <el-date-picker v-model="queryDate" type="date" value-format="YYYY-MM-DD" size="small"
                          style="width: 140px; margin-left: 8px" @change="loadAppts" />
        </h4>
        <el-table :data="appts" size="small" border>
          <el-table-column prop="period" label="时段" width="70" />
          <el-table-column prop="seq_no" label="顺序号" width="80" />
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="item_name" label="项目" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button v-if="row.status !== 'DONE'" link type="success" size="small" @click="done(row)">完成</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane v-for="q in queues" :key="q.type" :label="q.label" :name="q.type">
        <el-button type="primary" size="small" @click="callNext(q.type)">叫下一号</el-button>
        <el-row :gutter="16" style="margin-top: 10px">
          <el-col :span="12">
            <h4>等待中（{{ q.waiting.length }}）</h4>
            <el-table :data="q.waiting" size="small" border>
              <el-table-column prop="patient_name" label="患者" width="100" />
              <el-table-column prop="detail" label="内容" show-overflow-tooltip />
            </el-table>
          </el-col>
          <el-col :span="12">
            <h4>今日已叫</h4>
            <el-table :data="q.called" size="small" border>
              <el-table-column prop="patient_name" label="患者" width="100" />
              <el-table-column prop="called_at" label="叫号时间" />
            </el-table>
          </el-col>
        </el-row>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const tab = ref('appt')
const pending = ref<Record<string, unknown>[]>([])
const appts = ref<Record<string, unknown>[]>([])
const queryDate = ref(new Date().toISOString().slice(0, 10))

const queues = reactive([
  { type: 'PHARMACY', label: '药房取药叫号', waiting: [] as Record<string, unknown>[], called: [] as Record<string, unknown>[] },
  { type: 'EXAM', label: '检查叫号', waiting: [] as Record<string, unknown>[], called: [] as Record<string, unknown>[] },
  { type: 'LAB', label: '检验叫号', waiting: [] as Record<string, unknown>[], called: [] as Record<string, unknown>[] },
])

async function loadPending() { pending.value = (await client.get('/appointments/pending')).data.data }
async function loadAppts() { appts.value = (await client.get('/appointments', { params: { date: queryDate.value } })).data.data }
async function loadQueue(type: string) {
  const q = queues.find((x) => x.type === type)
  if (!q) return
  const d = (await client.get(`/queue-center/${type}`)).data.data
  q.waiting = d.waiting
  q.called = d.called
}

async function book(row: Record<string, unknown>) {
  if (!row._date || !row._period) return ElMessage.warning('请选择日期与时段')
  const resp = await client.post('/appointments', { orderId: row.order_id, slotDate: row._date, period: row._period })
  ElMessage.success(`预约成功，顺序号 ${resp.data.data.seqNo}`)
  await Promise.all([loadPending(), loadAppts()])
}
async function done(row: Record<string, unknown>) {
  await client.put(`/appointments/${row.id}/done`)
  await loadAppts()
}
async function callNext(type: string) {
  const resp = await client.post(`/queue-center/${type}/call-next`)
  ElMessage.success(`请 ${resp.data.data.patient_name} 就位`)
  await loadQueue(type)
}

watch(tab, (t) => { if (t !== 'appt') loadQueue(t) })
onMounted(async () => { await Promise.all([loadPending(), loadAppts()]) })
</script>
