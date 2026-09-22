<template>
  <div class="p360">
    <el-card class="left">
      <template #header>
        患者检索
        <el-button link type="primary" style="float: right" @click="syncNow" :loading="syncing">立即同步</el-button>
      </template>
      <el-tabs v-model="leftTab">
        <el-tab-pane label="按患者" name="patient">
          <div class="search">
            <el-input v-model="keyword" placeholder="姓名/患者号/证件/手机" clearable @keyup.enter="search" />
            <el-button type="primary" @click="search">查询</el-button>
          </div>
          <el-table :data="patients" highlight-current-row height="calc(100vh - 306px)" @current-change="openPatient">
            <el-table-column prop="patientNo" label="患者号" width="110" />
            <el-table-column prop="name" label="姓名" width="90" />
            <el-table-column prop="age" label="年龄" width="60" />
          </el-table>
        </el-tab-pane>

        <!-- v69 包 A（1096★/2472★）：临床文档全文检索。端点此前齐备而界面无入口。 -->
        <el-tab-pane label="全文检索" name="fulltext">
          <div class="search">
            <el-input v-model="ftKeyword" placeholder="文档标题或正文关键字" clearable @keyup.enter="fullTextSearch" />
            <el-button type="primary" :loading="ftLoading" @click="fullTextSearch">检索</el-button>
          </div>
          <!-- 实测锁定的两条边界，如实写在屏上，不让截断结果冒充全集：
               ① 后端对空关键字 like '%%' 恒真、会回最近若干份，故空串由前端挡住，根本不发请求；
               ② 单次最多 50 条，满 50 即明示还有更多。 -->
          <el-alert v-if="ftTruncated" type="warning" :closable="false" show-icon style="margin-bottom: 6px"
                    title="仅显示前 50 条，可能还有更多匹配；请补充关键字缩小范围。" />
          <el-table :data="ftDocs" height="calc(100vh - 306px)" v-loading="ftLoading">
            <el-table-column prop="title" label="文档" min-width="140" show-overflow-tooltip />
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ fmtDateTime(row.docTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="70">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="viewDoc(row)">查看</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!ftLoading && ftSearched && !ftDocs.length" description="没有匹配的临床文档" />
        </el-tab-pane>
      </el-tabs>
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
                          :timestamp="fmtDateTime(d.docTime)"
                          :type="typeColor[d.docType as string]">
          <b>{{ d.title }}</b>
          <el-button link type="primary" size="small" @click="viewDoc(d)">查看</el-button>
        </el-timeline-item>
      </el-timeline>
    </el-card>

    <el-dialog v-model="docVisible" :title="String(viewing?.title ?? '')" width="560px">
      <pre class="doc-json">{{ prettyContent }}</pre>
      <template #footer>
        <!-- v69 包 A（2466★）：标准文档（XML）导出。端点限 ADMIN/QUALITY，
             不持有该角色的人不显示按钮——不摆一个点了必然 403 的死入口。 -->
        <el-button v-if="canExportCda && viewing?.id" :loading="exporting"
                   @click="exportCda">导出标准文档（XML）</el-button>
        <el-button @click="docVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { fmtDateTime } from '../../utils/date'
import { useAuthStore } from '../../stores/auth'

const keyword = ref('')
const patients = ref<Record<string, unknown>[]>([])
const current = ref<Record<string, unknown> | null>(null)
const docs = ref<Record<string, unknown>[]>([])
const docType = ref('')
const docVisible = ref(false)
const viewing = ref<Record<string, unknown> | null>(null)
const syncing = ref(false)

/* ===================== v69 包 A：全文检索 / 标准文档导出 =====================
 * 两个端点后端早已齐备（检索由本轮新增的 JUnit 锁定契约，导出另有含脱敏两档的 JUnit），
 * 此前**前端没有任何入口**。本轮纯接线，零后端改动。
 */
const leftTab = ref('patient')
const ftKeyword = ref('')
const ftDocs = ref<Record<string, unknown>[]>([])
const ftLoading = ref(false)
const ftSearched = ref(false)
const ftTruncated = ref(false)
const exporting = ref(false)

/** 端点限 ADMIN/QUALITY；菜单未知不拦（与别处同判据），否则按角色决定显不显示按钮 */
const auth = useAuthStore()
const canExportCda = computed(() => {
  const roles = auth.user?.roles ?? []
  return roles.length === 0 || roles.some((r) => r === 'ADMIN' || r === 'QUALITY')
})

const FT_PAGE_SIZE = 50   // 与端点内硬编码的分页大小一致（Phase104CdrTest 已钉住）

async function fullTextSearch() {
  // 实测：后端对空关键字是 like '%%' 恒真，会回最近 50 份文档。
  // 那不是用户要的"检索"，所以空串在前端就挡住、根本不发请求。
  const kw = ftKeyword.value.trim()
  if (!kw) {
    ElMessage.warning('请输入检索关键字')
    return
  }
  ftLoading.value = true
  try {
    ftDocs.value = (await client.get('/cdr/search', { params: { keyword: kw } })).data.data ?? []
    ftTruncated.value = ftDocs.value.length >= FT_PAGE_SIZE
    ftSearched.value = true
  } finally {
    ftLoading.value = false
  }
}

async function exportCda() {
  const d = viewing.value
  if (!d?.id) return
  exporting.value = true
  try {
    const resp = await client.get(`/cdr/documents/${d.id}/cda`, { responseType: 'blob' })
    const href = URL.createObjectURL(resp.data as Blob)
    const a = document.createElement('a')
    a.href = href
    a.download = `临床文档_${String(d.title ?? d.id)}.xml`
    a.click()
    URL.revokeObjectURL(href)
  } finally {
    exporting.value = false
  }
}

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
