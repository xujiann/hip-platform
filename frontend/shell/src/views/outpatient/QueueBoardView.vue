<template>
  <el-card>
    <div class="toolbar">
      <h3>叫号大屏 · {{ deptName }}</h3>
      <div class="ops">
        <el-select v-model="deptId" style="width: 160px" @change="changeDept">
          <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-button v-if="canWrite" type="primary" :disabled="busy || !ready" :loading="busy" @click="callNext">叫下一号</el-button>
        <el-checkbox v-model="voiceOn" style="margin: 0 4px">语音播报</el-checkbox>
        <el-button link @click="load">刷新</el-button>
      </div>
    </div>
    <div class="board">
      <div class="col">
        <h4>请就诊</h4>
        <div v-for="c in called" :key="c.registrationId as number" class="big called">
          {{ c.regNo }} 号 · {{ maskName(c.patientName as string) }}
          <el-button v-if="canWrite" link type="warning" size="small" :disabled="busy || !ready" @click="pass(c)">过号</el-button>
        </div>
        <el-empty v-if="!called.length" description="—" :image-size="40" />
        <h4 style="margin-top: 12px">过号（{{ passed.length }} 人）</h4>
        <div v-for="p in passed" :key="p.registrationId as number" class="row passed-row">
          {{ p.regNo }} 号 · {{ maskName(p.patientName as string) }}
          <el-button v-if="canWrite" link type="primary" size="small" :disabled="busy || !ready" @click="recall(p)">重呼</el-button>
        </div>
      </div>
      <div class="col">
        <h4>候诊（{{ waiting.length }} 人）</h4>
        <div v-for="w in waiting" :key="w.registrationId as number" class="row">
          {{ w.regNo }} 号 · {{ maskName(w.patientName as string) }}
        </div>
      </div>
      <div class="col">
        <h4>就诊中</h4>
        <div v-for="v in visiting" :key="v.registrationId as number" class="row">
          {{ v.regNo }} 号 · {{ maskName(v.patientName as string) }}
        </div>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const canWrite = computed(() => auth.user?.roles.some(r => r === 'ADMIN' || r === 'DOCTOR_OUTP') ?? false)
const busy = ref(false)
const ready = ref(false)
let alive = true
let revision = 0
let loadId = 0
const depts = ref<{ id: number; name: string; type: string }[]>([])
const deptId = ref<number | null>(null)
const deptName = ref('')
const waiting = ref<Record<string, unknown>[]>([])
const called = ref<Record<string, unknown>[]>([])
const visiting = ref<Record<string, unknown>[]>([])
const passed = ref<Record<string, unknown>[]>([])
let timer: number | undefined

function maskName(name: string) {
  if (!name) return ''
  return name.length <= 1 ? name : name[0] + '＊'.repeat(name.length - 1)
}

function clearBoard() {
  ready.value = false
  deptName.value = ''
  waiting.value = []; called.value = []; visiting.value = []; passed.value = []
}

function changeDept() {
  revision++; loadId++
  clearBoard()
  if ('speechSynthesis' in window) window.speechSynthesis.cancel()
  void load()
}

async function load() {
  const target = deptId.value
  if (!target || !alive || busy.value) return
  const id = ++loadId
  const page = revision
  try {
    const resp = await client.get('/outpatient/queue', { params: { deptId: target } })
    if (!alive || page !== revision || id !== loadId || target !== deptId.value) return
    const d = resp.data.data
    deptName.value = d.deptName
    waiting.value = d.waiting; called.value = d.called
    visiting.value = d.visiting; passed.value = d.passed ?? []
    ready.value = true
  } catch {
    if (alive && page === revision && id === loadId) clearBoard()
  }
}

async function perform(kind: 'call-next' | 'pass' | 'recall', row?: Record<string, unknown>) {
  const target = deptId.value
  if (!alive || !canWrite.value || !target || !ready.value || busy.value) return
  const rows = kind === 'pass' ? called.value : passed.value
  if (kind !== 'call-next' && !rows.some(r => r.registrationId === row?.registrationId)) return
  const page = revision
  const name = deptName.value
  const params = kind === 'call-next' ? { deptId: target } : { registrationId: row!.registrationId }
  busy.value = true; loadId++
  try {
    const resp = await client.post('/outpatient/queue/' + kind, null, { params })
    if (!alive || page !== revision || target !== deptId.value) return
    const r = resp.data.data
    if (kind === 'call-next') {
      ElMessage.success(`请 ${r.regNo} 号 ${maskName(r.patientName as string)} 就诊`)
      speak(`请 ${r.regNo} 号，${maskName(r.patientName as string)}，到${name}就诊`)
    } else if (kind === 'recall') {
      ElMessage.success(`重呼 ${r.regNo} 号`)
      speak(`重呼 ${r.regNo} 号，请尽快到${name}就诊`)
    }
  } catch {
    if (alive && page === revision) clearBoard()
  } finally {
    busy.value = false
    if (alive) { clearBoard(); await load() }
  }
}
function pass(row: Record<string, unknown>) { return perform('pass', row) }
function recall(row: Record<string, unknown>) { return perform('recall', row) }
function callNext() { return perform('call-next') }

// 三十六期：叫号语音播报（Web Speech，无需外部依赖）
const voiceOn = ref(localStorage.getItem('hip_board_voice') !== 'off')
watch(voiceOn, (v) => localStorage.setItem('hip_board_voice', v ? 'on' : 'off'))
function speak(text: string) {
  if (!voiceOn.value || !('speechSynthesis' in window)) return
  const u = new SpeechSynthesisUtterance(text)
  u.lang = 'zh-CN'
  u.rate = 0.9
  window.speechSynthesis.speak(u)
}

onMounted(async () => {
  try {
  const resp = await client.get('/system/depts')
  if (!alive) return
  depts.value = resp.data.data.filter((d: { type: string }) => d.type === 'CLINICAL')
  if (depts.value.length) {
    deptId.value = depts.value.find(d => d.id === auth.user?.deptId)?.id ?? depts.value[0].id
    await load()
  }
  if (!alive) return
  timer = window.setInterval(load, 10000)
  } catch { clearBoard() }
})
onUnmounted(() => {
  alive = false; revision++; loadId++
  window.clearInterval(timer)
  if ('speechSynthesis' in window) window.speechSynthesis.cancel()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.ops { display: flex; gap: 8px; }
.board { display: grid; grid-template-columns: 1.2fr 1fr 1fr; gap: 16px; }
.col h4 { border-bottom: 1px solid #eee; padding-bottom: 6px; }
.big.called { font-size: 26px; font-weight: 700; color: #d03050; padding: 10px 0; }
.row { font-size: 16px; padding: 6px 0; color: #444; }
</style>
