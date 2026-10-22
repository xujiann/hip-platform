<template>
  <el-card>
    <template #header>
      药房发药队列
      <el-button link type="primary" style="float: right" :disabled="writing" @click="refresh">刷新</el-button>
    </template>
    <el-empty v-if="!worklist.length" description="暂无待发药处方" />
    <el-card v-for="w in worklist" :key="w.registrationId as number" class="rx-card" shadow="never">
      <template #header>
        <b>{{ w.patientName }}</b>（{{ w.patientNo }}）
        <el-button v-if="canDispense" type="success" size="small" style="float: right"
                   :loading="dispensing === w.registrationId" :disabled="writing || !queueReady" @click="dispense(w)">
          发 药
        </el-button>
      </template>
      <el-table :data="w.orders as Record<string, unknown>[]" size="small">
        <el-table-column prop="groupNo" label="处方号" width="150" />
        <el-table-column prop="itemName" label="药品" />
        <el-table-column prop="spec" label="规格" width="130" />
        <el-table-column label="用法" width="180">
          <template #default="{ row }">
            {{ row.usageRoute }} {{ row.dosePerTime }} {{ row.frequency }} × {{ row.days }}天
          </template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="60" />
      </el-table>
    </el-card>

    <el-divider>退 药</el-divider>
    <div class="return-bar">
      <el-select v-model="returnRegId" clearable placeholder="选择今日挂号（患者）" style="width: 260px" @change="loadDispensed">
        <el-option v-for="r in todayRegs" :key="r.id as number"
                   :label="`${r.patientName}（${r.patientNo} · ${r.deptName}）`" :value="r.id as number" />
      </el-select>
      <span class="return-hint">已开始或完成输液不可退药；状态变化时请刷新。</span>
    </div>
    <el-table v-if="dispensed.length" :data="dispensed" size="small" style="margin-top: 8px">
      <el-table-column prop="groupNo" label="处方号" width="150" />
      <el-table-column prop="itemName" label="药品" />
      <el-table-column prop="qty" label="数量" width="60" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button v-if="canReturn" link type="danger"
                     :loading="returning === row.id" :disabled="writing || !dispensedReady || !returnRegId" @click="returnDrug(row)">退药</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { todayLocal } from '../../utils/date'
import { ElMessage } from 'element-plus'
import client, { type BizError } from '../../api/client'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const canPharmacy = computed(() => auth.user?.roles.some(role => ['ADMIN', 'PHARMACIST'].includes(role)) ?? false)
const canDispense = computed(() => canPharmacy.value && auth.hasPerm('outp:pharmacy:dispense'))
const canReturn = computed(() => canPharmacy.value && auth.hasPerm('outp:pharmacy:return'))
const worklist = ref<Record<string, unknown>[]>([])
const dispensing = ref<number | null>(null)
const returning = ref<number | null>(null)
const writing = computed(() => dispensing.value !== null || returning.value !== null)
const todayRegs = ref<Record<string, unknown>[]>([])
const returnRegId = ref<number | null>(null)
const dispensed = ref<Record<string, unknown>[]>([])
const queueReady = ref(false)
const dispensedReady = ref(false)
let alive = true
let queueRead = 0
let dispensedRead = 0
let selectionVersion = 0
let selectedRegistration: number | null = null

async function load() {
  const sequence = ++queueRead
  queueReady.value = false
  worklist.value = []
  todayRegs.value = []
  if (!alive || !canPharmacy.value) return
  try {
    const [wl, regs] = await Promise.all([
      client.get('/outpatient/dispense/worklist'),
      client.get('/outpatient/dispense/registrations', { params: { date: todayLocal() } }),
    ])
    if (!alive || sequence !== queueRead || !canPharmacy.value) return
    worklist.value = wl.data.data
    todayRegs.value = regs.data.data
    queueReady.value = true
  } catch {
    // The shared client shows the error; failed reads leave no actionable stale queue.
  }
}

async function loadDispensed() {
  const registrationId = returnRegId.value || null
  if (registrationId !== selectedRegistration) {
    selectedRegistration = registrationId
    selectionVersion++
  }
  const sequence = ++dispensedRead
  dispensed.value = []
  dispensedReady.value = false
  if (!alive || !canPharmacy.value || !registrationId) return
  try {
    const resp = await client.get('/outpatient/dispense/dispensed', { params: { registrationId } })
    if (!alive || sequence !== dispensedRead || returnRegId.value !== registrationId || !canPharmacy.value) return
    dispensed.value = resp.data.data
    dispensedReady.value = true
  } catch {
    // Keep old-patient rows hidden when a selected-patient refresh fails.
  }
}

async function refresh() {
  if (writing.value) return
  await Promise.all([load(), loadDispensed()])
}

async function refreshAfterWrite(registrationId: number | null, version: number) {
  if (!alive) return
  const reads = [load()]
  if (version === selectionVersion && returnRegId.value === registrationId) reads.push(loadDispensed())
  await Promise.all(reads)
}

function clearLists() {
  queueRead++
  dispensedRead++
  selectionVersion++
  selectedRegistration = null
  returnRegId.value = null
  queueReady.value = false
  dispensedReady.value = false
  worklist.value = []
  todayRegs.value = []
  dispensed.value = []
}

async function returnDrug(row: Record<string, unknown>) {
  const registrationId = returnRegId.value
  if (!alive || !canReturn.value || writing.value || !dispensedReady.value || !registrationId
    || typeof row?.id !== 'number' || row.registrationId !== registrationId
    || !dispensed.value.some(item => item.id === row.id && item.registrationId === registrationId)) return
  const orderId = row.id
  const itemName = row.itemName
  const version = selectionVersion
  returning.value = orderId
  queueRead++
  dispensedRead++
  try {
    await client.post(`/outpatient/dispense/orders/${orderId}/return`)
    if (!alive) return
    if (version === selectionVersion && returnRegId.value === registrationId) ElMessage.success(`已退药：${itemName}，库存已回补`)
    await refreshAfterWrite(registrationId, version)
  } catch (error) {
    if (!alive) return
    if ((error as BizError).bizCode === 1005) clearLists()
    else await refreshAfterWrite(registrationId, version)
  } finally {
    returning.value = null
  }
}

async function dispense(w: Record<string, unknown>) {
  if (!alive || !canDispense.value || writing.value || !queueReady.value || typeof w?.registrationId !== 'number'
    || !worklist.value.some(item => item.registrationId === w.registrationId)) return
  const targetRegistrationId = w.registrationId
  const patientName = w.patientName
  const registrationId = returnRegId.value
  const version = selectionVersion
  dispensing.value = targetRegistrationId
  queueRead++
  dispensedRead++
  try {
    await client.post(`/outpatient/dispense/${targetRegistrationId}`)
    if (!alive) return
    if (version === selectionVersion && returnRegId.value === registrationId) ElMessage.success(`已为 ${patientName} 发药`)
    await refreshAfterWrite(registrationId, version)
  } catch (error) {
    if (!alive) return
    if ((error as BizError).bizCode === 1005) clearLists()
    else await refreshAfterWrite(registrationId, version)
  } finally {
    dispensing.value = null
  }
}

onMounted(load)
onUnmounted(() => {
  alive = false
  clearLists()
})
</script>

<style scoped>
.rx-card { margin-bottom: 12px; }
.return-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.return-hint { color: var(--el-text-color-secondary); font-size: 13px; }
</style>
