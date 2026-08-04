<template>
  <el-card>
    <template #header>
      药房发药队列
      <el-button link type="primary" style="float: right" @click="load">刷新</el-button>
    </template>
    <el-empty v-if="!worklist.length" description="暂无待发药处方" />
    <el-card v-for="w in worklist" :key="w.registrationId as number" class="rx-card" shadow="never">
      <template #header>
        <b>{{ w.patientName }}</b>（{{ w.patientNo }}）
        <el-button type="success" size="small" style="float: right" :loading="dispensing === w.registrationId"
                   @click="dispense(w)">
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
      <el-select v-model="returnRegId" placeholder="选择今日挂号（患者）" style="width: 260px" @change="loadDispensed">
        <el-option v-for="r in todayRegs" :key="r.id as number"
                   :label="`${r.patientName}（${r.patientNo} · ${r.deptName}）`" :value="r.id as number" />
      </el-select>
    </div>
    <el-table v-if="dispensed.length" :data="dispensed" size="small" style="margin-top: 8px">
      <el-table-column prop="groupNo" label="处方号" width="150" />
      <el-table-column prop="itemName" label="药品" />
      <el-table-column prop="qty" label="数量" width="60" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="danger" @click="returnDrug(row)">退药</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const worklist = ref<Record<string, unknown>[]>([])
const dispensing = ref<unknown>(null)
const todayRegs = ref<Record<string, unknown>[]>([])
const returnRegId = ref<number | null>(null)
const dispensed = ref<Record<string, unknown>[]>([])

async function load() {
  const today = new Date().toISOString().slice(0, 10)
  const [wl, regs] = await Promise.all([
    client.get('/outpatient/dispense/worklist'),
    client.get('/outpatient/registrations', { params: { date: today } }),
  ])
  worklist.value = wl.data.data
  todayRegs.value = regs.data.data
}

async function loadDispensed() {
  if (!returnRegId.value) return
  const resp = await client.get('/outpatient/dispense/dispensed', { params: { registrationId: returnRegId.value } })
  dispensed.value = resp.data.data
}

async function returnDrug(row: Record<string, unknown>) {
  await client.post(`/outpatient/dispense/orders/${row.id}/return`)
  ElMessage.success(`已退药：${row.itemName}，库存已回补`)
  await loadDispensed()
}

async function dispense(w: Record<string, unknown>) {
  dispensing.value = w.registrationId
  try {
    await client.post(`/outpatient/dispense/${w.registrationId}`)
    ElMessage.success(`已为 ${w.patientName} 发药`)
    await load()
  } finally {
    dispensing.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.rx-card { margin-bottom: 12px; }
.return-bar { display: flex; gap: 8px; }
</style>
