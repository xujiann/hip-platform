<template>
  <div class="p360">
    <el-card class="left">
      <template #header>
        患者检索
        <el-button link type="primary" style="float: right" @click="syncNow" :loading="syncing">立即同步</el-button>
      </template>
      <div class="search">
        <el-input v-model="keyword" placeholder="姓名/患者号/证件/手机" clearable @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
      </div>
      <el-table :data="patients" highlight-current-row height="calc(100vh - 260px)" @current-change="openPatient">
        <el-table-column prop="patientNo" label="患者号" width="110" />
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column prop="age" label="年龄" width="60" />
      </el-table>
    </el-card>

    <el-card class="right">
      <template #header>
        <span v-if="current"><b>{{ current.name }}</b>（{{ current.patientNo }}）的临床文档时间轴</span>
        <span v-else>患者 360 视图</span>
        <el-radio-group v-if="current" v-model="docType" size="small" style="float: right" @change="loadDocs">
          <el-radio-button :value="''">全部</el-radio-button>
          <el-radio-button value="OUTP_ENCOUNTER">门诊</el-radio-button>
          <el-radio-button value="LAB_REPORT">检验</el-radio-button>
          <el-radio-button value="INP_SUMMARY">住院</el-radio-button>
        </el-radio-group>
      </template>
      <el-empty v-if="!current" description="从左侧选择患者" />
      <el-timeline v-else>
        <el-timeline-item v-for="d in docs" :key="d.id as number"
                          :timestamp="String(d.docTime).slice(0, 16).replace('T', ' ')"
                          :type="typeColor[d.docType as string]">
          <b>{{ d.title }}</b>
          <el-button link type="primary" size="small" @click="viewDoc(d)">查看</el-button>
        </el-timeline-item>
      </el-timeline>
    </el-card>

    <el-dialog v-model="docVisible" :title="String(viewing?.title ?? '')" width="560px">
      <pre class="doc-json">{{ prettyContent }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const keyword = ref('')
const patients = ref<Record<string, unknown>[]>([])
const current = ref<Record<string, unknown> | null>(null)
const docs = ref<Record<string, unknown>[]>([])
const docType = ref('')
const docVisible = ref(false)
const viewing = ref<Record<string, unknown> | null>(null)
const syncing = ref(false)

const typeColor: Record<string, string> = { OUTP_ENCOUNTER: 'primary', LAB_REPORT: 'warning', INP_SUMMARY: 'success' }

const prettyContent = computed(() => {
  if (!viewing.value) return ''
  try {
    return JSON.stringify(JSON.parse(String(viewing.value.content)), null, 2)
  } catch {
    return String(viewing.value.content)
  }
})

async function search() {
  const resp = await client.get('/patients', { params: { keyword: keyword.value, page: 0, size: 20 } })
  patients.value = resp.data.data.records
}

async function openPatient(row: Record<string, unknown> | null) {
  current.value = row
  if (row) await loadDocs()
}

async function loadDocs() {
  if (!current.value) return
  const resp = await client.get(`/cdr/patients/${current.value.id}/documents`, {
    params: { docType: docType.value || undefined },
  })
  docs.value = resp.data.data
}

async function viewDoc(d: Record<string, unknown>) {
  // 列表已投影化不含全文（1.1.7），点开才取明细
  viewing.value = d
  docVisible.value = true
  const resp = await client.get(`/cdr/documents/${d.id}`)
  viewing.value = resp.data.data
}

async function syncNow() {
  syncing.value = true
  try {
    const resp = await client.post('/cdr/sync')
    const s = resp.data.data
    ElMessage.success(`同步完成：门诊 ${s.outpEncounters}，检验 ${s.labReports}，住院 ${s.inpSummaries}`)
    if (current.value) await loadDocs()
  } finally {
    syncing.value = false
  }
}
</script>

<style scoped>
.p360 { display: grid; grid-template-columns: 340px 1fr; gap: 12px; }
.search { display: flex; gap: 8px; margin-bottom: 8px; }
.doc-json {
  max-height: 420px;
  overflow: auto;
  background: #f7f8fa;
  padding: 12px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
