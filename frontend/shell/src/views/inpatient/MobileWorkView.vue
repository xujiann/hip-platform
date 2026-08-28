<template>
  <div class="mobile-work">
    <el-alert title="移动工作台：手机/PDA 单列布局，护理体征录入与医生查房" type="info"
              show-icon :closable="false" style="margin-bottom: 8px" />
    <el-segmented v-model="mode" :options="[{ label: '移动护理', value: 'nurse' }, { label: '移动查房', value: 'round' }]"
                  block style="margin-bottom: 10px" />

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
        <h4>查房病历
          <el-button link type="primary" size="small" :loading="addRoundLoading" @click="addRound">记查房</el-button>
        </h4>
        <el-card v-for="r in records" :key="String(r.id)" class="pt-card" shadow="never">
          <b>{{ r.title }}</b>
          <span class="muted">{{ String(r.createdAt ?? '').slice(0, 10) }}</span>
          <div class="muted">{{ String(r.content).slice(0, 80) }}</div>
        </el-card>
        <el-empty v-if="!records.length" description="暂无记录" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { parseVital } from '../../utils/vitals'

const mode = ref('nurse')
const admissions = ref<Record<string, unknown>[]>([])
const current = ref<Record<string, unknown> | null>(null)
const drawer = ref(false)
const vitals = ref<Record<string, unknown>[]>([])
const records = ref<Record<string, unknown>[]>([])
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
  vitals.value = (await client.get(`/inpatient/admissions/${a.id}/vitals`)).data.data
  records.value = (await client.get(`/inpatient/admissions/${a.id}/records`)).data.data
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

async function addRound() {
  if (!current.value) return
  const res = await ElMessageBox.prompt('查房情况', '移动查房', { inputValue: '生命体征平稳，继续目前治疗' }).catch(() => null)
  if (!res) return
  const { value } = res
  addRoundLoading.value = true
  try {
    await client.post(`/inpatient/admissions/${current.value.id}/records`,
      { recordType: 'PROGRESS', title: '移动查房记录', content: value })
    records.value = (await client.get(`/inpatient/admissions/${current.value.id}/records`)).data.data
  } finally { addRoundLoading.value = false }
}

onMounted(load)
</script>

<style scoped>
.mobile-work { max-width: 480px; margin: 0 auto; }
.pt-card { margin-bottom: 8px; cursor: pointer; }
.row { display: flex; justify-content: space-between; align-items: center; }
.muted { color: #909399; font-size: 12px; margin-left: 6px; }
.vgrid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
</style>
