<template>
  <div class="mrfront-page">
    <!-- 选单：按住院号/姓名检索病案（打印时隐藏） -->
    <el-card class="picker no-print">
      <template #header>
        病案检索
        <el-button link type="primary" style="float: right" @click="loadList">刷新</el-button>
      </template>
      <el-input v-model="keyword" placeholder="住院号 / 姓名" clearable style="margin-bottom: 8px"
                @keyup.enter="loadList" @clear="loadList">
        <template #append><el-button @click="loadList">查询</el-button></template>
      </el-input>
      <el-table :data="list" highlight-current-row height="calc(100vh - 240px)" size="small"
                @current-change="pick">
        <el-table-column prop="admission_no" label="住院号" width="150" />
        <el-table-column prop="patient_name" label="姓名" width="80" />
        <el-table-column label="状态" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'DISCHARGED' ? 'success' : 'warning'">
              {{ row.status === 'DISCHARGED' ? '已出院' : '在院' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 病案首页正文 -->
    <el-card v-if="page" class="sheet">
      <div class="toolbar no-print">
        <el-tag v-if="page.admission?.archived" type="success">已归档</el-tag>
        <el-tag v-else type="info">未归档</el-tag>
        <span class="hint">院内查看/打印用组装件，非国标上报文件（HQMS/DRG-DIP 结算清单留待实施期对接）</span>
        <el-button type="primary" @click="doPrint">打 印</el-button>
      </div>

      <div class="print-area">
        <h2 class="title">{{ hospitalName }}住院病案首页</h2>

        <table class="kv">
          <tr>
            <th>姓名</th><td>{{ page.patient?.name }}</td>
            <th>性别</th><td>{{ sexName[page.patient?.sex as string] ?? page.patient?.sex }}</td>
            <th>出生日期</th><td>{{ page.patient?.birthDate }}</td>
          </tr>
          <tr>
            <th>病案号</th><td>{{ page.patient?.patientNo }}</td>
            <th>证件号</th><td>{{ page.patient?.idNo }}</td>
            <th>医保类型</th><td>{{ insName[page.patient?.insuranceType as string] ?? page.patient?.insuranceType }}</td>
          </tr>
          <tr>
            <th>联系电话</th><td>{{ page.patient?.phone }}</td>
            <th>住址</th><td colspan="3">{{ page.patient?.address }}</td>
          </tr>
        </table>

        <h3>入出院信息</h3>
        <table class="kv">
          <tr>
            <th>住院号</th><td>{{ page.admission?.admissionNo }}</td>
            <th>科室</th><td>{{ page.admission?.deptName }}</td>
            <th>病区/床</th><td>{{ page.admission?.wardName }} {{ page.admission?.bedNo }}</td>
          </tr>
          <tr>
            <th>护理级别</th><td>{{ page.admission?.careLevel }}</td>
            <th>入院时间</th><td>{{ fmt(page.admission?.admitAt) }}</td>
            <th>出院时间</th><td>{{ fmt(page.admission?.dischargedAt) || '在院' }}</td>
          </tr>
        </table>

        <h3>诊断信息</h3>
        <table class="grid">
          <tr><th style="width: 90px">主要诊断</th><th style="width: 110px">ICD</th><th>名称</th></tr>
          <tr>
            <td>{{ page.diagnoses?.primary?.source === 'DISCHARGE' ? '出院主诊断' : '入院诊断' }}</td>
            <td>{{ page.diagnoses?.primary?.icd || '—' }}</td>
            <td>{{ page.diagnoses?.primary?.name || '—' }}</td>
          </tr>
          <tr v-for="(d, i) in (page.diagnoses?.others as Record<string, unknown>[] ?? [])" :key="i">
            <td>其他诊断{{ i + 1 }}</td>
            <td>{{ d.icd }}</td>
            <td>{{ d.name }}</td>
          </tr>
        </table>

        <h3>手术信息</h3>
        <table v-if="(page.surgeries as unknown[])?.length" class="grid">
          <tr><th>术式</th><th style="width: 90px">麻醉</th><th style="width: 130px">时间</th><th style="width: 80px">状态</th></tr>
          <tr v-for="(s, i) in (page.surgeries as Record<string, unknown>[])" :key="i">
            <td>{{ s.procedure_name }}</td>
            <td>{{ s.anesthesia_type }}</td>
            <td>{{ fmt(s.scheduled_at) }}</td>
            <td>{{ s.status }}</td>
          </tr>
        </table>
        <p v-else class="none">无手术记录</p>

        <h3>费用信息</h3>
        <table class="grid">
          <tr><th>费用类别</th><th style="width: 100px">项数</th><th style="width: 140px">金额（¥）</th></tr>
          <tr v-for="(f, i) in (page.fees?.byCategory as Record<string, unknown>[] ?? [])" :key="i">
            <td>{{ feeCat[f.order_type as string] ?? f.order_type }}</td>
            <td>{{ f.items }}</td>
            <td>{{ Number(f.amount).toFixed(2) }}</td>
          </tr>
          <tr class="sum">
            <td>费用总额</td>
            <td>—</td>
            <td>{{ page.fees?.totalAmount != null ? Number(page.fees.totalAmount).toFixed(2) : '未结算' }}</td>
          </tr>
          <tr>
            <td>押金 / 结余</td>
            <td>—</td>
            <td>
              {{ page.fees?.depositAmount != null ? Number(page.fees.depositAmount).toFixed(2) : '—' }}
              / {{ page.fees?.balance != null ? Number(page.fees.balance).toFixed(2) : '—' }}
            </td>
          </tr>
        </table>

        <p class="foot">病历记录 {{ page.recordCount }} 条　打印时间：{{ now }}</p>
      </div>
    </el-card>
    <el-empty v-else class="sheet" description="从左侧选择一份病案查看首页" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import client from '../../api/client'

const sexName: Record<string, string> = { M: '男', F: '女', U: '未知' }
const insName: Record<string, string> = { SELF: '自费', YB_STAFF: '职工医保', YB_RESIDENT: '居民医保' }
const feeCat: Record<string, string> = { DRUG: '药品费', LAB: '检验费', EXAM: '检查费', TREAT: '治疗费' }

const keyword = ref('')
const list = ref<Record<string, unknown>[]>([])
const page = ref<Record<string, any> | null>(null)
const hospitalName = ref('')
const now = new Date().toLocaleString('zh-CN')

function fmt(v: unknown): string {
  if (!v) return ''
  return String(v).slice(0, 16).replace('T', ' ')
}

async function loadList() {
  const resp = await client.get('/quality/med-records', { params: { keyword: keyword.value } })
  list.value = resp.data.data
}

async function pick(row: Record<string, unknown> | null) {
  if (!row) return
  const resp = await client.get(`/inpatient/admissions/${row.id}/front-page`)
  page.value = resp.data.data
}

function doPrint() {
  window.print()
}

onMounted(async () => {
  const [, cfg] = await Promise.all([loadList(), client.get('/config/public')])
  hospitalName.value = cfg.data.data.hospital_name ?? ''
})
</script>

<style scoped>
.mrfront-page { display: grid; grid-template-columns: 320px 1fr; gap: 12px; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.toolbar .hint { color: #909399; font-size: 12px; flex: 1; }
.title { text-align: center; margin: 4px 0 14px; }
h3 { margin: 16px 0 6px; padding-left: 6px; border-left: 3px solid #409eff; font-size: 15px; }
.kv, .grid { width: 100%; border-collapse: collapse; margin-bottom: 6px; font-size: 13px; }
.kv th, .kv td, .grid th, .grid td { border: 1px solid #d0d3d9; padding: 5px 8px; text-align: left; }
.kv th { background: #f5f7fa; width: 80px; white-space: nowrap; }
.grid th { background: #f5f7fa; }
.grid .sum td { font-weight: 700; }
.none { color: #909399; font-size: 13px; margin: 4px 0; }
.foot { color: #888; font-size: 12px; margin-top: 14px; text-align: right; }
@media print {
  .no-print { display: none !important; }
  .mrfront-page { display: block; }
  .sheet { border: none; box-shadow: none; }
}
</style>
