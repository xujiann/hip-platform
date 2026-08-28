<template>
  <div class="emr-copy-page">
    <!-- 左：受理 + 申请队列（打印时隐藏） -->
    <el-card class="side no-print">
      <template #header>病案复印受理</template>

      <el-form label-width="76px" size="small">
        <el-form-item label="住院病案">
          <el-select v-model="form.admissionId" filterable remote :remote-method="searchAdm"
                     placeholder="输入住院号/姓名检索" style="width: 100%" @focus="searchAdm('')">
            <el-option v-for="a in admOptions" :key="a.id as number"
                       :label="`${a.admission_no} ${a.patient_name}`" :value="a.id as number" />
          </el-select>
        </el-form-item>
        <el-form-item label="申请人">
          <el-input v-model="form.applicantName" placeholder="患者本人/家属/保险/司法" />
        </el-form-item>
        <el-form-item label="关系">
          <el-select v-model="form.applicantRelation" style="width: 100%">
            <el-option label="患者本人" value="SELF" />
            <el-option label="家属" value="FAMILY" />
            <el-option label="保险机构" value="INSURER" />
            <el-option label="司法机关" value="LEGAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="证件号">
          <el-input v-model="form.applicantIdNo" placeholder="申请人证件号（核验身份）" />
        </el-form-item>
        <el-form-item label="复印范围">
          <el-select v-model="form.copyScope" style="width: 100%">
            <el-option label="全部病历" value="全部病历" />
            <el-option label="病案首页" value="病案首页" />
            <el-option label="出院记录" value="出院记录" />
            <el-option label="检查检验报告" value="检查检验报告" />
          </el-select>
        </el-form-item>
        <el-form-item label="用途">
          <el-select v-model="form.purpose" style="width: 100%">
            <el-option label="医保报销" value="医保报销" />
            <el-option label="商业保险" value="商业保险" />
            <el-option label="法律诉讼" value="法律诉讼" />
            <el-option label="转诊" value="转诊" />
          </el-select>
        </el-form-item>
        <el-form-item label="份数">
          <el-input-number v-model="form.copies" :min="1" :max="20" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="applying" @click="apply">受理登记</el-button>
        </el-form-item>
      </el-form>

      <el-divider>复印申请队列</el-divider>
      <el-select v-model="statusFilter" size="small" style="width: 100%; margin-bottom: 6px" @change="loadList">
        <el-option label="全部" value="" />
        <el-option label="待登记" value="APPLIED" />
        <el-option label="已登记" value="REGISTERED" />
        <el-option label="已出件" value="ISSUED" />
      </el-select>
      <el-table :data="list" highlight-current-row height="300" size="small" @current-change="pick">
        <el-table-column prop="patient_name" label="患者" width="80" />
        <el-table-column prop="reg_no" label="登记号" show-overflow-tooltip />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status as string)">{{ statusText(row.status as string) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 右：复印件预览 + 操作 -->
    <el-card v-if="doc" class="sheet">
      <div class="toolbar no-print">
        <el-tag :type="statusTag(current?.status as string)">{{ statusText(current?.status as string) }}</el-tag>
        <span class="hint">复印件须盖章生效；出件即计入复印登记留痕</span>
        <el-button v-if="current?.status === 'APPLIED'" type="primary" :loading="acting" @click="register">登记出号</el-button>
        <el-button v-if="current?.status === 'REGISTERED'" type="success" :loading="acting" @click="issue">确认出件</el-button>
        <el-button :disabled="current?.status === 'APPLIED'" @click="doPrint">打印复印件</el-button>
      </div>

      <div class="print-area">
        <!-- 法定"复印件"水印 -->
        <div class="watermark">{{ doc.watermark }}</div>
        <h2 class="title">{{ hospitalName }}病历复印件</h2>
        <p class="regline">复印登记号：<b>{{ reqMeta.reg_no || '（未登记）' }}</b>　份数：{{ reqMeta.copies }}</p>

        <table class="kv">
          <tr>
            <th>患者</th><td>{{ reqMeta.patient_name }}</td>
            <th>住院号</th><td>{{ adm.admission_no ?? '—' }}</td>
          </tr>
          <tr>
            <th>申请人</th><td>{{ reqMeta.applicant_name }}（{{ relText(reqMeta.applicant_relation as string) }}）</td>
            <th>证件号</th><td>{{ reqMeta.applicant_id_no || '—' }}</td>
          </tr>
          <tr>
            <th>复印范围</th><td>{{ reqMeta.copy_scope }}</td>
            <th>用途</th><td>{{ reqMeta.purpose }}</td>
          </tr>
          <tr>
            <th>经办人</th><td>{{ reqMeta.operator_name || '—' }}</td>
            <th>出件时间</th><td>{{ fmt(reqMeta.issued_at) || '未出件' }}</td>
          </tr>
        </table>

        <h3>病历正文</h3>
        <table v-if="(doc.records as unknown[])?.length" class="grid">
          <tr><th style="width: 110px">类型</th><th style="width: 150px">标题</th><th>内容</th></tr>
          <tr v-for="(r, i) in (doc.records as Record<string, unknown>[])" :key="i">
            <td>{{ r.record_type }}</td>
            <td>{{ r.title }} <el-tag v-if="r.signed" size="small" type="success">已签</el-tag></td>
            <td>{{ r.content }}</td>
          </tr>
        </table>
        <p v-else class="none">该病案暂无病历正文记录</p>

        <p class="foot">打印时间：{{ now }}　（复印件仅复制病历原文，不含系统内部标记）</p>
      </div>
    </el-card>
    <el-empty v-else class="sheet" description="从左侧队列选择一份复印申请，或先受理新申请" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const admOptions = ref<Record<string, unknown>[]>([])
const list = ref<Record<string, unknown>[]>([])
const statusFilter = ref('')
const current = ref<Record<string, unknown> | null>(null)
const doc = ref<Record<string, any> | null>(null)
const applying = ref(false)
const acting = ref(false)
const hospitalName = ref('')
const now = new Date().toLocaleString('zh-CN')

const form = reactive({
  admissionId: undefined as number | undefined,
  applicantName: '', applicantRelation: 'SELF', applicantIdNo: '',
  copyScope: '全部病历', purpose: '医保报销', copies: 1,
})

const reqMeta = computed(() => (doc.value?.request ?? {}) as Record<string, unknown>)
const adm = computed(() => (doc.value?.admission ?? {}) as Record<string, unknown>)

const statusText = (s: string) => ({ APPLIED: '待登记', REGISTERED: '已登记', ISSUED: '已出件' }[s] ?? s)
const statusTag = (s: string) => ({ APPLIED: 'info', REGISTERED: 'warning', ISSUED: 'success' }[s] ?? 'info')
const relText = (r: string) => ({ SELF: '本人', FAMILY: '家属', INSURER: '保险', LEGAL: '司法' }[r] ?? r ?? '')
function fmt(v: unknown): string { return v ? String(v).slice(0, 19).replace('T', ' ') : '' }

async function searchAdm(kw: string) {
  admOptions.value = (await client.get('/quality/med-records', { params: { keyword: kw } })).data.data
}

async function loadList() {
  list.value = (await client.get('/quality/emr-copy', { params: { status: statusFilter.value } })).data.data
}

async function apply() {
  if (!form.admissionId) { ElMessage.warning('请选择住院病案'); return }
  applying.value = true
  try {
    // 必填/校验类业务错误（9810/9813/9814）由拦截器统一红字提示
    await client.post('/quality/emr-copy', {
      admissionId: form.admissionId,
      applicantName: form.applicantName, applicantRelation: form.applicantRelation,
      applicantIdNo: form.applicantIdNo, copyScope: form.copyScope,
      purpose: form.purpose, copies: form.copies,
    })
    ElMessage.success('复印申请已受理')
    await loadList()
  } finally { applying.value = false }
}

async function pick(row: Record<string, unknown> | null) {
  current.value = row
  doc.value = null
  if (!row) return
  // 待登记态无复印件数据集（后端 9812），仅在已登记/已出件时取
  if (row.status === 'APPLIED') return
  doc.value = (await client.get(`/quality/emr-copy/${row.id}/document`)).data.data
}

async function register() {
  if (!current.value) return
  acting.value = true
  try {
    await client.put(`/quality/emr-copy/${current.value.id}/register`)
    ElMessage.success('已登记并生成复印登记号')
    await loadList()
    // 重新载入所选申请（状态转 REGISTERED，可取复印件数据集）
    await pick({ ...current.value, status: 'REGISTERED' })
  } finally { acting.value = false }
}

async function issue() {
  if (!current.value) return
  acting.value = true
  try {
    await client.put(`/quality/emr-copy/${current.value.id}/issue`)
    ElMessage.success('已确认出件')
    await loadList()
    await pick({ ...current.value, status: 'ISSUED' })
  } finally { acting.value = false }
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
.emr-copy-page { display: grid; grid-template-columns: 340px 1fr; gap: 12px; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.toolbar .hint { color: #909399; font-size: 12px; flex: 1; }
.print-area { position: relative; }
.watermark {
  position: absolute; top: 40%; left: 50%; transform: translate(-50%, -50%) rotate(-30deg);
  font-size: 90px; color: rgba(230, 72, 46, 0.12); font-weight: 800; letter-spacing: 12px;
  pointer-events: none; user-select: none; white-space: nowrap;
}
.title { text-align: center; margin: 4px 0 8px; }
.regline { text-align: center; color: #555; font-size: 13px; margin: 0 0 12px; }
h3 { margin: 16px 0 6px; padding-left: 6px; border-left: 3px solid #409eff; font-size: 15px; }
.kv, .grid { width: 100%; border-collapse: collapse; margin-bottom: 6px; font-size: 13px; }
.kv th, .kv td, .grid th, .grid td { border: 1px solid #d0d3d9; padding: 5px 8px; text-align: left; vertical-align: top; }
.kv th { background: #f5f7fa; width: 80px; white-space: nowrap; }
.grid th { background: #f5f7fa; }
.none { color: #909399; font-size: 13px; margin: 4px 0; }
.foot { color: #888; font-size: 12px; margin-top: 14px; text-align: right; }
@media print {
  .no-print { display: none !important; }
  .emr-copy-page { display: block; }
  .sheet { border: none; box-shadow: none; }
  .watermark { color: rgba(230, 72, 46, 0.18); }
}
</style>
