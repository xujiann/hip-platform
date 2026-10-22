<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="输液执行" name="infusion">
        <h4>待建输液单（静脉用药）
          <el-button link type="primary" size="small" @click="refreshInfusionLists">刷新</el-button>
        </h4>
        <el-table :data="candidates" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="item_name" label="药品" show-overflow-tooltip />
          <el-table-column prop="usage_route" label="用法" width="100" />
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button link type="primary" size="small" v-if="canNurse" :disabled="infusionBusy || !candidatesReady" :loading="createInfusionBusy === row.order_id" @click="createInfusion(row)">生成输液单</el-button>
            </template>
          </el-table-column>
        </el-table>
        <h4>输液单</h4>
        <el-table :data="infusions" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="item_name" label="药品" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'DONE' ? 'success' : row.status === 'RUNNING' ? 'warning' : 'info'"
                      size="small">{{ { PENDING: '待开始', RUNNING: '输液中', DONE: '已结束' }[row.status as string] ?? row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="源医嘱状态" width="110">
            <template #default="{ row }">{{ orderStatusLabel(row.order_status) }}</template>
          </el-table-column>
          <el-table-column prop="checks" label="巡视次数" width="90" />
          <el-table-column label="操作" width="230">
            <template #default="{ row }">
              <el-button v-if="canNurse && row.status === 'PENDING' && ['CHARGED', 'DISPENSED'].includes(row.order_status)" :disabled="infusionBusy || !infusionsReady" link type="primary" size="small"
                         :loading="startInfusionBusy === row.id" @click="startInfusion(row)">
                开始</el-button>
              <template v-if="canNurse && row.status === 'RUNNING'">
                <el-button link type="warning" size="small" :disabled="infusionBusy || !infusionsReady" :loading="addCheckBusy === row.id" @click="addCheck(row)">巡视</el-button>
                <el-button link type="success" size="small" :disabled="infusionBusy || !infusionsReady" :loading="finishInfusionBusy === row.id" @click="finishInfusion(row)">结束</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="皮试登记" name="skin">
        <el-form inline size="small">
          <el-form-item><el-input v-model="skin.registrationId" placeholder="挂号ID" style="width: 120px" /></el-form-item>
          <el-form-item><el-input v-model="skin.drugName" placeholder="皮试药品（如 青霉素）" style="width: 200px" /></el-form-item>
          <el-button type="primary" size="small" :loading="createSkinTestLoading" @click="createSkinTest">登记皮试</el-button>
        </el-form>
        <el-table :data="skinTests" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="drug_name" label="药品" />
          <el-table-column label="结果" width="180">
            <template #default="{ row }">
              <template v-if="row.result === 'PENDING'">
                <el-button link type="success" size="small" :loading="recordResultBusy === row.id" @click="recordResult(row, 'NEG')">阴性(-)</el-button>
                <el-button link type="danger" size="small" :loading="recordResultBusy === row.id" @click="recordResult(row, 'POS')">阳性(+)</el-button>
              </template>
              <el-tag v-else :type="row.result === 'NEG' ? 'success' : 'danger'" size="small">
                {{ row.result === 'NEG' ? '阴性' : '阳性' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="tested_at" label="判读时间" width="170" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client, { type BizError } from '../../api/client'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const canNurse = computed(() => auth.user?.roles.some(role => ['ADMIN', 'NURSE'].includes(role)) ?? false)
const tab = ref('infusion')
const candidates = ref<Record<string, unknown>[]>([])
const infusions = ref<Record<string, unknown>[]>([])
const skinTests = ref<Record<string, unknown>[]>([])
const skin = reactive({ registrationId: '', drugName: '' })
const createSkinTestLoading = ref(false)
const createInfusionBusy = ref<unknown>(null)
const startInfusionBusy = ref<unknown>(null)
const addCheckBusy = ref<unknown>(null)
const finishInfusionBusy = ref<unknown>(null)
const recordResultBusy = ref<unknown>(null)

const candidatesReady = ref(false)
const infusionsReady = ref(false)
const infusionBusy = computed(() => [createInfusionBusy.value, startInfusionBusy.value, addCheckBusy.value, finishInfusionBusy.value].some(id => id !== null))
let alive = true
let candidateSequence = 0
let infusionSequence = 0

function orderStatusLabel(status: unknown) {
  const labels: Record<string, string> = { CREATED: '未收费', CHARGED: '已收费', DISPENSED: '已发药', EXECUTED: '已执行', CANCELLED: '已作废' }
  return labels[String(status)] ?? String(status ?? '未知')
}

async function loadCandidates() {
  const sequence = ++candidateSequence
  candidatesReady.value = false
  candidates.value = []
  if (!alive || !canNurse.value) return
  try {
    const resp = await client.get('/outpatient/nurse/infusion-candidates')
    if (!alive || sequence !== candidateSequence || !canNurse.value) return
    candidates.value = resp.data.data
    candidatesReady.value = true
  } catch {
    // Errors are displayed by the shared client; the old candidates stay cleared.
  }
}
async function loadInfusions() {
  const sequence = ++infusionSequence
  infusionsReady.value = false
  infusions.value = []
  if (!alive || !canNurse.value) return
  try {
    const resp = await client.get('/outpatient/nurse/infusions')
    if (!alive || sequence !== infusionSequence || !canNurse.value) return
    infusions.value = resp.data.data
    infusionsReady.value = true
  } catch {
    // Do not offer actions using a status from an unsuccessful refresh.
  }
}
async function refreshInfusionLists() {
  await Promise.all([loadCandidates(), loadInfusions()])
}
async function loadSkinTests() { skinTests.value = (await client.get('/outpatient/nurse/skin-tests')).data.data }

function invalidateInfusionReads() {
  candidateSequence++
  infusionSequence++
  candidatesReady.value = false
  infusionsReady.value = false
}

async function refreshAfterInfusion(error?: unknown) {
  if (!alive) return
  if (!canNurse.value || (error as BizError | undefined)?.bizCode === 1005) {
    invalidateInfusionReads()
    candidates.value = []
    infusions.value = []
    return
  }
  await refreshInfusionLists()
}

function latestInfusion(id: unknown) {
  return infusions.value.find(item => item.id === id)
}

async function createInfusion(row: Record<string, unknown>) {
  if (!alive || !canNurse.value || infusionBusy.value || !candidatesReady.value || !candidates.value.some(item => item.order_id === row.order_id)) return
  const orderId = row.order_id
  createInfusionBusy.value = orderId
  invalidateInfusionReads()
  try {
    await client.post('/outpatient/nurse/infusions', null, { params: { orderId } })
    await refreshAfterInfusion()
  } catch (error) {
    await refreshAfterInfusion(error)
  } finally {
    createInfusionBusy.value = null
  }
}
async function startInfusion(row: Record<string, unknown>) {
  if (!alive || !canNurse.value || infusionBusy.value || !infusionsReady.value) return
  const current = latestInfusion(row.id)
  if (current?.status !== 'PENDING' || !['CHARGED', 'DISPENSED'].includes(String(current.order_status))) return
  const id = current.id
  startInfusionBusy.value = id
  invalidateInfusionReads()
  try {
    await client.put(`/outpatient/nurse/infusions/${id}/start`)
    if (alive && canNurse.value) ElMessage.success('已开始输液')
    await refreshAfterInfusion()
  } catch (error) {
    await refreshAfterInfusion(error)
  } finally {
    startInfusionBusy.value = null
  }
}
async function addCheck(row: Record<string, unknown>) {
  if (!alive || !canNurse.value || infusionBusy.value || !infusionsReady.value || latestInfusion(row.id)?.status !== 'RUNNING') return
  const id = row.id
  // Reserve the action before opening the prompt so repeated clicks cannot create two prompts.
  addCheckBusy.value = id
  try {
    const res = await ElMessageBox.prompt('请填写实际观察到的巡视情况', '输液巡视', {
      inputValue: '',
      inputValidator: (value: string) => !!value.trim() && value.length <= 500 || '请填写1至500字的实际巡视记录',
    }).catch(() => null)
    if (!res || !alive || !canNurse.value || !infusionsReady.value || latestInfusion(id)?.status !== 'RUNNING') return
    const note = res.value.trim()
    if (!note || note.length > 500) return
    invalidateInfusionReads()
    await client.post(`/outpatient/nurse/infusions/${id}/checks`, null, { params: { note } })
    await refreshAfterInfusion()
  } catch (error) {
    await refreshAfterInfusion(error)
  } finally {
    addCheckBusy.value = null
  }
}
async function finishInfusion(row: Record<string, unknown>) {
  if (!alive || !canNurse.value || infusionBusy.value || !infusionsReady.value || latestInfusion(row.id)?.status !== 'RUNNING') return
  const id = row.id
  finishInfusionBusy.value = id
  invalidateInfusionReads()
  try {
    await client.put(`/outpatient/nurse/infusions/${id}/finish`)
    await refreshAfterInfusion()
  } catch (error) {
    await refreshAfterInfusion(error)
  } finally {
    finishInfusionBusy.value = null
  }
}
async function createSkinTest() {
  if (!skin.registrationId || !skin.drugName) { ElMessage.warning('请填写挂号ID和皮试药品'); return }
  createSkinTestLoading.value = true
  try {
    await client.post('/outpatient/nurse/skin-tests', { registrationId: Number(skin.registrationId), drugName: skin.drugName })
    ElMessage.success('已登记，请判读结果')
    await loadSkinTests()
  } finally {
    createSkinTestLoading.value = false
  }
}
async function recordResult(row: Record<string, unknown>, result: string) {
  recordResultBusy.value = row.id
  try {
    await client.put(`/outpatient/nurse/skin-tests/${row.id}/result`, null, { params: { result } })
    await loadSkinTests()
  } finally {
    recordResultBusy.value = null
  }
}

onMounted(async () => { await Promise.all([loadCandidates(), loadInfusions(), loadSkinTests()]) })
onUnmounted(() => {
  alive = false
  invalidateInfusionReads()
  candidates.value = []
  infusions.value = []
})
</script>
