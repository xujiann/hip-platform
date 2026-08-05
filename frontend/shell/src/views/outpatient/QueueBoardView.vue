<template>
  <el-card>
    <div class="toolbar">
      <h3>叫号大屏 · {{ deptName }}</h3>
      <div class="ops">
        <el-select v-model="deptId" style="width: 160px" @change="load">
          <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-button type="primary" @click="callNext">叫下一号</el-button>
        <el-button link @click="load">刷新</el-button>
      </div>
    </div>
    <div class="board">
      <div class="col">
        <h4>请就诊</h4>
        <div v-for="c in called" :key="c.registrationId as number" class="big called">
          {{ c.regNo }} 号 · {{ maskName(c.patientName as string) }}
          <el-button link type="warning" size="small" @click="pass(c)">过号</el-button>
        </div>
        <el-empty v-if="!called.length" description="—" :image-size="40" />
        <h4 style="margin-top: 12px">过号（{{ passed.length }} 人）</h4>
        <div v-for="p in passed" :key="p.registrationId as number" class="row passed-row">
          {{ p.regNo }} 号 · {{ maskName(p.patientName as string) }}
          <el-button link type="primary" size="small" @click="recall(p)">重呼</el-button>
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
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

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

async function load() {
  if (!deptId.value) return
  const resp = await client.get('/outpatient/queue', { params: { deptId: deptId.value } })
  const d = resp.data.data
  deptName.value = d.deptName
  waiting.value = d.waiting
  called.value = d.called
  visiting.value = d.visiting
  passed.value = d.passed ?? []
}

async function pass(row: Record<string, unknown>) {
  await client.post('/outpatient/queue/pass', null, { params: { registrationId: row.registrationId } })
  await load()
}

async function recall(row: Record<string, unknown>) {
  const resp = await client.post('/outpatient/queue/recall', null, { params: { registrationId: row.registrationId } })
  ElMessage.success(`重呼 ${resp.data.data.regNo} 号`)
  await load()
}

async function callNext() {
  const resp = await client.post('/outpatient/queue/call-next', null, { params: { deptId: deptId.value } })
  const r = resp.data.data
  ElMessage.success(`请 ${r.regNo} 号 ${r.patientName} 就诊`)
  await load()
}

onMounted(async () => {
  const resp = await client.get('/system/depts')
  depts.value = resp.data.data.filter((d: { type: string }) => d.type === 'CLINICAL')
  if (depts.value.length) {
    deptId.value = depts.value[0].id
    await load()
  }
  timer = window.setInterval(load, 10000)
})
onUnmounted(() => window.clearInterval(timer))
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
