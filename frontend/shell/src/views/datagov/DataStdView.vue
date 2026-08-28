<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="ODR 运营总览" name="odr">
        <el-descriptions v-if="odr" :column="3" border size="small">
          <el-descriptions-item label="今日门诊">{{ odr.outpToday }} 人次</el-descriptions-item>
          <el-descriptions-item label="在院患者">{{ odr.inHospital }} 人</el-descriptions-item>
          <el-descriptions-item label="今日收入">￥{{ odr.revenueToday }}</el-descriptions-item>
          <el-descriptions-item label="待检标本">{{ odr.labPending }}</el-descriptions-item>
          <el-descriptions-item label="待审报告">{{ odr.examPending }}</el-descriptions-item>
          <el-descriptions-item label="未处理危急值">{{ odr.criticalOpen }}</el-descriptions-item>
        </el-descriptions>
        <h4>核心指标（最新快照）</h4>
        <el-table :data="odr?.metricSnapshots ?? []" size="small" border>
          <el-table-column prop="code" label="编码" width="90" />
          <el-table-column prop="name" label="指标" />
          <el-table-column prop="value" label="值" width="110" />
          <el-table-column prop="unit" label="单位" width="80" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="数据元" name="elements">
        <el-form inline size="small">
          <el-form-item><el-input v-model="elem.code" placeholder="标识 DE.." style="width: 150px" /></el-form-item>
          <el-form-item><el-input v-model="elem.name" placeholder="名称" style="width: 140px" /></el-form-item>
          <el-form-item>
            <el-select v-model="elem.datatype" style="width: 110px">
              <el-option v-for="t in ['S', 'N', 'D', 'B', 'C']" :key="t" :label="t" :value="t" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="elem.format" placeholder="格式" style="width: 110px" /></el-form-item>
          <el-form-item><el-input v-model="elem.stdRef" placeholder="引用标准" style="width: 120px" /></el-form-item>
          <el-button type="primary" size="small" @click="addElement" :loading="addElementLoading">新增数据元</el-button>
        </el-form>
        <el-table :data="elements" size="small" border>
          <el-table-column prop="code" label="标识" width="150" />
          <el-table-column prop="name" label="名称" width="140" />
          <el-table-column prop="datatype" label="类型" width="70" />
          <el-table-column prop="format" label="格式" width="110" />
          <el-table-column prop="value_set" label="值域" show-overflow-tooltip />
          <el-table-column prop="std_ref" label="引用标准" width="110" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="术语管理" name="terms">
        <el-form inline size="small">
          <el-form-item>
            <el-select v-model="term.category" style="width: 110px">
              <el-option v-for="c in ['DIAG', 'DRUG', 'LAB', 'EXAM', 'PROC']" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="term.localName" placeholder="本地名称" style="width: 150px" /></el-form-item>
          <el-form-item><el-input v-model="term.stdCode" placeholder="标准编码" style="width: 120px" /></el-form-item>
          <el-form-item>
            <el-select v-model="term.stdSystem" style="width: 110px">
              <el-option v-for="s in ['ICD10', 'LOINC', 'ATC', 'ICD9CM3']" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
          <el-button type="primary" size="small" @click="addTerm" :loading="addTermLoading">新增映射</el-button>
        </el-form>
        <el-table :data="terms" size="small" border>
          <el-table-column prop="category" label="类别" width="90" />
          <el-table-column prop="local_name" label="本地名称" />
          <el-table-column prop="std_code" label="标准编码" width="120" />
          <el-table-column prop="std_name" label="标准名称" show-overflow-tooltip />
          <el-table-column prop="std_system" label="标准体系" width="100" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="服务订阅" name="subs">
        <el-form inline size="small">
          <el-form-item><el-input v-model="sub.eventType" placeholder="事件（如 LAB_PUBLISHED）" style="width: 190px" /></el-form-item>
          <el-form-item><el-input v-model="sub.subscriber" placeholder="订阅方" style="width: 130px" /></el-form-item>
          <el-form-item><el-input v-model="sub.targetUrl" placeholder="回调地址" style="width: 240px" /></el-form-item>
          <el-button type="primary" size="small" @click="subscribe" :loading="subscribeLoading">订阅</el-button>
        </el-form>
        <el-table :data="subs" size="small" border>
          <el-table-column prop="event_type" label="事件" width="160" />
          <el-table-column prop="subscriber" label="订阅方" width="120" />
          <el-table-column prop="target_url" label="回调地址" show-overflow-tooltip />
          <el-table-column prop="pushes" label="已推送" width="80" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button link type="primary" size="small" :loading="busyId === row.id" @click="testPush(row)">测试推送</el-button>
              <el-button link size="small" :loading="busyId === row.id" @click="toggleSub(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="报表引擎" name="reports">
        <el-form size="small">
          <el-form-item><el-input v-model="report.name" placeholder="报表名称" style="width: 250px" /></el-form-item>
          <el-form-item>
            <el-input v-model="report.sqlText" type="textarea" :rows="3"
                      placeholder="SELECT 查询语句（只读，自动限 200 行）" />
          </el-form-item>
          <el-button type="primary" size="small" @click="addReport" :loading="addReportLoading">保存报表</el-button>
        </el-form>
        <el-table :data="reports" size="small" border style="margin-top: 8px">
          <el-table-column prop="name" label="报表" width="200" />
          <el-table-column prop="remark" label="说明" show-overflow-tooltip />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" size="small" :loading="busyId === row.id" @click="runReport(row)">运行</el-button>
            </template>
          </el-table-column>
        </el-table>
        <template v-if="reportResult">
          <h4>运行结果（{{ reportResult.rows.length }} 行）</h4>
          <el-table :data="reportResult.rows" size="small" border max-height="360">
            <el-table-column v-for="c in reportResult.columns" :key="c" :prop="c" :label="c" />
          </el-table>
        </template>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

interface ReportResult { columns: string[]; rows: Record<string, unknown>[] }

const tab = ref('odr')
const odr = ref<Record<string, unknown> | null>(null)
const elements = ref<Record<string, unknown>[]>([])
const terms = ref<Record<string, unknown>[]>([])
const subs = ref<Record<string, unknown>[]>([])
const reports = ref<Record<string, unknown>[]>([])
const reportResult = ref<ReportResult | null>(null)
const elem = reactive({ code: '', name: '', datatype: 'S', format: '', stdRef: '' })
const term = reactive({ category: 'DIAG', localName: '', stdCode: '', stdSystem: 'ICD10' })
const sub = reactive({ eventType: '', subscriber: '', targetUrl: '' })
const report = reactive({ name: '', sqlText: '' })
const addElementLoading = ref(false)
const addTermLoading = ref(false)
const subscribeLoading = ref(false)
const addReportLoading = ref(false)
const busyId = ref<unknown>(null)

async function loadOdr() { odr.value = (await client.get('/datagov/odr/summary')).data.data }
async function loadElements() { elements.value = (await client.get('/datagov/elements')).data.data }
async function loadTerms() { terms.value = (await client.get('/datagov/terms')).data.data }
async function loadSubs() { subs.value = (await client.get('/datagov/subscriptions')).data.data }
async function loadReports() { reports.value = (await client.get('/datagov/reports')).data.data }

async function addElement() {
  if (!elem.code || !elem.name) { ElMessage.warning('请填写数据元标识与名称'); return }
  addElementLoading.value = true
  try {
    await client.post('/datagov/elements', elem)
    elem.code = ''
    await loadElements()
  } finally { addElementLoading.value = false }
}
async function addTerm() {
  if (!term.localName || !term.stdCode) { ElMessage.warning('请填写本地名称与标准编码'); return }
  addTermLoading.value = true
  try {
    await client.post('/datagov/terms', term)
    term.localName = ''
    await loadTerms()
  } finally { addTermLoading.value = false }
}
async function subscribe() {
  if (!sub.eventType || !sub.targetUrl) { ElMessage.warning('请填写事件与回调地址'); return }
  subscribeLoading.value = true
  try {
    await client.post('/datagov/subscriptions', sub)
    await loadSubs()
  } finally { subscribeLoading.value = false }
}
async function testPush(row: Record<string, unknown>) {
  busyId.value = row.id
  try {
    await client.post(`/datagov/subscriptions/${row.id}/test-push`)
    ElMessage.success('已推送（Mock）')
    await loadSubs()
  } finally { busyId.value = null }
}
async function toggleSub(row: Record<string, unknown>) {
  busyId.value = row.id
  try {
    await client.put(`/datagov/subscriptions/${row.id}/toggle`)
    await loadSubs()
  } finally { busyId.value = null }
}
async function addReport() {
  if (!report.name || !report.sqlText) { ElMessage.warning('请填写报表名称与查询语句'); return }
  addReportLoading.value = true
  try {
    await client.post('/datagov/reports', report)
    ElMessage.success('已保存')
    await loadReports()
  } finally { addReportLoading.value = false }
}
async function runReport(row: Record<string, unknown>) {
  busyId.value = row.id
  try {
    reportResult.value = (await client.post(`/datagov/reports/${row.id}/run`)).data.data
  } finally { busyId.value = null }
}

watch(tab, (t) => {
  if (t === 'elements') loadElements()
  if (t === 'terms') loadTerms()
  if (t === 'subs') loadSubs()
  if (t === 'reports') loadReports()
})
onMounted(loadOdr)
</script>
