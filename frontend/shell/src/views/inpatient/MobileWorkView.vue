<template>
  <div class="mobile-work">
    <el-alert title="移动工作台：手机/PDA 单列布局，护理体征、床旁医嘱执行与三级查房" type="info"
              show-icon :closable="false" style="margin-bottom: 8px" />
    <el-segmented v-model="mode" :options="MODES" block style="margin-bottom: 10px" />

    <!-- ① v40：床旁长期医嘱执行（接 v39 执行行队列 /inpatient/exec-lines） -->
    <template v-if="mode === 'exec'">
      <div class="exec-bar">
        <span class="muted">{{ execDate }} 待执行 {{ execLines.length }} 条</span>
        <el-button link type="primary" size="small" :loading="execLoading" @click="loadExecLines">刷新</el-button>
      </div>
      <el-card v-for="e in execLines" :key="String(e.id)" class="exec-card" shadow="never">
        <div class="row">
          <span class="pt-name">{{ e.patient_name }}</span>
          <el-tag type="warning" size="large" effect="dark">{{ e.bed_no ?? '未分床' }}</el-tag>
        </div>
        <div class="exec-item">
          {{ e.item_name }}<span class="muted">{{ e.spec ?? '' }}</span>
        </div>
        <div class="exec-usage">
          {{ e.usage_route ?? '—' }} · 每次 {{ e.dose_per_time ?? '—' }} · {{ e.frequency ?? '—' }}
        </div>
        <div class="row exec-foot">
          <span class="muted">{{ e.admission_no }} · 第 {{ e.seq_no }} 次 · ¥{{ e.amount ?? '0' }}</span>
          <el-button type="success" :loading="executingId === e.id" @click="executeLine(e)">执行</el-button>
        </div>
      </el-card>
      <el-empty v-if="!execLines.length && !execLoading" description="今日无待执行长期医嘱" />
    </template>

    <!-- ②③ 护理 / 查房：先选患者，再在抽屉里作业 -->
    <template v-else>
      <el-card v-for="a in admissions" :key="String(a.id)" class="pt-card" shadow="never"
               @click="open(a)">
        <div class="row">
          <div>
            <b>{{ a.patientName }}</b>
            <span class="muted">{{ a.bedNo ?? a.admissionNo }}</span>
          </div>
          <el-tag size="small">{{ a.deptName ?? '在院' }}</el-tag>
        </div>
        <div class="muted">{{ a.admitDiagName ?? '—' }}</div>
      </el-card>
      <el-empty v-if="!admissions.length" description="暂无在院患者" />
    </template>

    <el-drawer v-model="drawer" :title="current?.patientName + '（' + (current?.admissionNo ?? '') + '）'"
               direction="btt" size="82%">
      <template v-if="mode === 'nurse'">
        <h4>体征录入</h4>
        <div class="vgrid">
          <el-input v-model="vital.temperature" placeholder="体温℃" />
          <el-input v-model="vital.pulse" placeholder="脉搏" />
          <el-input v-model="vital.respiration" placeholder="呼吸" />
          <el-input v-model="vital.sbp" placeholder="收缩压" />
          <el-input v-model="vital.dbp" placeholder="舒张压" />
          <el-input v-model="vital.spo2" placeholder="SpO2" />
        </div>
        <el-button type="primary" style="width: 100%; margin-top: 8px" :loading="saveVitalLoading" @click="saveVital">保存体征</el-button>
        <h4>最近体征</h4>
        <el-card v-for="(v, i) in vitals.slice(-5).reverse()" :key="i" class="pt-card" shadow="never">
          <span class="muted">{{ String(v.measuredAt).slice(5, 16).replace('T', ' ') }}</span>
          T{{ v.temperature ?? '-' }} P{{ v.pulse ?? '-' }} R{{ v.respiration ?? '-' }}
          BP{{ v.sbp ?? '-' }}/{{ v.dbp ?? '-' }} SpO2 {{ v.spo2 ?? '-' }}
        </el-card>
      </template>
      <template v-else>
        <h4>三级查房记录</h4>
        <el-radio-group v-model="round.roundLevel" size="large" style="width: 100%">
          <el-radio-button v-for="l in ROUND_LEVELS" :key="l.value" :value="l.value">{{ l.label }}</el-radio-button>
        </el-radio-group>
        <el-input v-model="round.roundOpinion" type="textarea" :rows="3" style="margin-top: 8px"
                  placeholder="查房意见（必填）" />
        <el-input v-model="round.superiorCorrection" type="textarea" :rows="2" style="margin-top: 6px"
                  placeholder="上级修正意见（选填）" />
        <el-button type="primary" style="width: 100%; margin-top: 8px"
                   :loading="addRoundLoading" @click="addRound">提交查房记录</el-button>

        <h4>已有查房记录</h4>
        <el-card v-for="r in rounds" :key="String(r.id)" class="pt-card" shadow="never">
          <div class="row">
            <b>{{ ROUND_LEVEL_CN[String(r.round_level)] ?? r.round_level }}查房</b>
            <span>
              <el-tag v-if="r.signed" size="small" type="success">已签名</el-tag>
              <span class="muted">{{ String(r.created_at ?? '').slice(0, 16).replace('T', ' ') }}</span>
            </span>
          </div>
          <div>{{ r.round_opinion }}</div>
          <div v-if="r.superior_correction" class="muted">上级修正：{{ r.superior_correction }}</div>
          <div class="muted">查房医师：{{ r.round_doctor_name ?? '—' }}</div>
        </el-card>
        <el-empty v-if="!rounds.length" description="暂无查房记录" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client, { type BizError } from '../../api/client'
import { parseVital } from '../../utils/vitals'
import { todayLocal } from '../../utils/date'

const MODES = [
  { label: '移动护理', value: 'nurse' },
  { label: '医嘱执行', value: 'exec' },
  { label: '移动查房', value: 'round' },
]
const ROUND_LEVELS = [
  { label: '主任', value: 'CHIEF' },
  { label: '主治', value: 'ATTENDING' },
  { label: '住院医', value: 'RESIDENT' },
]
const ROUND_LEVEL_CN: Record<string, string> = { CHIEF: '主任', ATTENDING: '主治', RESIDENT: '住院医' }

const mode = ref('nurse')
const admissions = ref<Record<string, unknown>[]>([])
const current = ref<Record<string, unknown> | null>(null)
const drawer = ref(false)
const vitals = ref<Record<string, unknown>[]>([])
const rounds = ref<Record<string, unknown>[]>([])
const vital = reactive({ temperature: '', pulse: '', respiration: '', sbp: '', dbp: '', spo2: '' })
const saveVitalLoading = ref(false)
const addRoundLoading = ref(false)

async function load() {
  const all = (await client.get('/inpatient/admissions')).data.data as Record<string, unknown>[]
  admissions.value = all.filter((a) => a.status === 'IN_HOSPITAL')
}

async function open(a: Record<string, unknown>) {
  current.value = a
  drawer.value = true
  const [v, r] = await Promise.all([
    client.get(`/inpatient/admissions/${a.id}/vitals`),
    client.get(`/inpatient/admissions/${a.id}/records/rounds`),
  ])
  vitals.value = v.data.data
  rounds.value = r.data.data
}

/** 体征校验统一走 utils/vitals（1.2.5）：桌面与移动端量程曾各写一套，同一患者两端结果不同 */

async function saveVital() {
  if (!current.value) return
  let payload: Record<string, number | null>
  try {
    payload = {
      temperature: parseVital('temperature', vital.temperature),
      pulse: parseVital('pulse', vital.pulse),
      respiration: parseVital('respiration', vital.respiration),
      sbp: parseVital('sbp', vital.sbp), dbp: parseVital('dbp', vital.dbp),
      spo2: parseVital('spo2', vital.spo2),
    }
  } catch (e) {
    ElMessage.error((e as Error).message)
    return
  }
  saveVitalLoading.value = true
  try {
    await client.post(`/inpatient/admissions/${current.value.id}/vitals`, payload)
    ElMessage.success('已录入')
    vitals.value = (await client.get(`/inpatient/admissions/${current.value.id}/vitals`)).data.data
  } finally { saveVitalLoading.value = false }
}

/**
 * v40 ②：移动查房改接 v34 结构化端点。
 * 此前移动端写死 recordType='PROGRESS' 走通用 /records，写出来的是普通病程记录——
 * round_level/round_opinion 全空，病历时限质控的"查房时限"统计看不见移动端查房，
 * 医生站在桌面记的算数、床旁记的不算，是实打实的数据断层。
 */
const round = reactive({ roundLevel: 'ATTENDING', roundOpinion: '', superiorCorrection: '' })

async function addRound() {
  if (!current.value) return
  if (!round.roundOpinion.trim()) {      // 后端 9120 的前置拦截，省一次往返
    ElMessage.warning('请填写查房意见')
    return
  }
  addRoundLoading.value = true
  try {
    await client.post(`/inpatient/admissions/${current.value.id}/records/round`, {
      roundLevel: round.roundLevel,
      roundOpinion: round.roundOpinion.trim(),
      superiorCorrection: round.superiorCorrection.trim() || undefined,
    })
    ElMessage.success('查房记录已保存')
    round.roundOpinion = ''
    round.superiorCorrection = ''
    rounds.value = (await client.get(`/inpatient/admissions/${current.value.id}/records/rounds`)).data.data
  } finally { addRoundLoading.value = false }
}

/**
 * v40 ①：床旁长期医嘱执行（接 v39 执行行队列）。
 * 日期用 todayLocal()：`toISOString().slice(0,10)` 取的是 UTC 日期，
 * 北京时间 08:00 前的夜班执行会去查昨天的队列（详见 utils/date.ts）。
 */
const execLines = ref<Record<string, unknown>[]>([])
const execLoading = ref(false)
const executingId = ref<unknown>(null)
const execDate = ref(todayLocal())

async function loadExecLines() {
  execLoading.value = true
  execDate.value = todayLocal()
  try {
    execLines.value = (await client.get('/inpatient/exec-lines', { params: { date: execDate.value } })).data.data
  } finally { execLoading.value = false }
}

async function executeLine(e: Record<string, unknown>) {
  executingId.value = e.id
  try {
    // 9126 自处理：服务端条件更新抢占，两个护士同时点同一行只有一方成功，
    // 失败方不该看到"执行行不存在"的红字（吓人且不可行动），提示被抢占并刷新队列即可
    await client.put(`/inpatient/exec-lines/${e.id}/execute`, null, { __silentCodes: [9126] })
    ElMessage.success(`已执行：${e.item_name}（第 ${e.seq_no} 次）`)
  } catch (err) {
    if ((err as BizError).bizCode !== 9126) return   // 其余错误拦截器已弹红字，保留原列表
    ElMessage.warning('该执行行已被他人执行或已停嘱，队列已刷新')
  } finally { executingId.value = null }
  await loadExecLines()
}

watch(mode, (m) => {
  drawer.value = false
  if (m === 'exec') loadExecLines()
})

onMounted(load)
</script>

<style scoped>
.mobile-work { max-width: 480px; margin: 0 auto; }
.pt-card { margin-bottom: 8px; cursor: pointer; }
.row { display: flex; justify-content: space-between; align-items: center; }
.muted { color: #909399; font-size: 12px; margin-left: 6px; }
.vgrid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
/* 床旁：戴手套、举着 PDA 看，姓名床号要一眼认得，执行按钮要够大 */
.exec-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.exec-card { margin-bottom: 10px; }
.pt-name { font-size: 20px; font-weight: 700; }
.exec-item { font-size: 16px; margin-top: 6px; }
.exec-usage { color: #606266; font-size: 14px; margin-top: 2px; }
.exec-foot { margin-top: 8px; }
.exec-foot .el-button { min-width: 88px; height: 40px; font-size: 16px; }
</style>
