<template>
  <div>
  <el-card style="margin-bottom: 12px">
    <template #header>生命体征录入</template>
    <el-form inline>
      <el-form-item label="患者">
        <el-select v-model="vitalAdmissionId" style="width: 200px">
          <el-option v-for="a in admissions" :key="a.id as number"
                     :label="`${a.bedNo}床 ${a.patientName}`" :value="a.id as number" />
        </el-select>
      </el-form-item>
      <el-form-item label="体温℃"><el-input-number v-model="vital.temperature" :min="VITAL_RANGES.temperature.min" :max="VITAL_RANGES.temperature.max" :precision="1" :step="0.1" style="width: 110px" /></el-form-item>
      <el-form-item label="脉搏"><el-input-number v-model="vital.pulse" :min="VITAL_RANGES.pulse.min" :max="VITAL_RANGES.pulse.max" style="width: 100px" /></el-form-item>
      <el-form-item label="呼吸"><el-input-number v-model="vital.respiration" :min="VITAL_RANGES.respiration.min" :max="VITAL_RANGES.respiration.max" style="width: 100px" /></el-form-item>
      <el-form-item label="血压">
        <el-input-number v-model="vital.sbp" :min="VITAL_RANGES.sbp.min" :max="VITAL_RANGES.sbp.max" style="width: 100px" /> /
        <el-input-number v-model="vital.dbp" :min="VITAL_RANGES.dbp.min" :max="VITAL_RANGES.dbp.max" style="width: 100px" />
      </el-form-item>
      <el-form-item label="SpO₂"><el-input-number v-model="vital.spo2" :min="VITAL_RANGES.spo2.min" :max="VITAL_RANGES.spo2.max" style="width: 100px" /></el-form-item>
      <el-button type="primary" @click="saveVital">保存</el-button>
    </el-form>
  </el-card>

  <el-card>
    <template #header>
      护士执行队列（全院未执行医嘱）
      <el-button link type="primary" style="float: right" @click="load">刷新</el-button>
    </template>
    <el-empty v-if="!worklist.length" description="暂无待执行医嘱" />
    <el-table v-else :data="worklist" border stripe>
      <el-table-column prop="admissionNo" label="住院号" width="160" />
      <el-table-column prop="patientName" label="姓名" width="90" />
      <el-table-column prop="groupNo" label="医嘱号" width="140" />
      <el-table-column label="类型" width="60">
        <template #default="{ row }">{{ { DRUG: '药', LAB: '验', EXAM: '查', TREAT: '治' }[row.orderType as string] }}</template>
      </el-table-column>
      <el-table-column prop="itemName" label="项目" />
      <el-table-column label="用法" width="160">
        <template #default="{ row }">
          <span v-if="row.orderType === 'DRUG'">{{ row.usageRoute }} {{ row.dosePerTime }} {{ row.frequency }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="qty" label="量" width="50" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button type="success" size="small" @click="execute(row)">执行</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { VITAL_RANGES } from '../../utils/vitals'

const worklist = ref<Record<string, unknown>[]>([])
const admissions = ref<Record<string, unknown>[]>([])
const vitalAdmissionId = ref<number | null>(null)
const vital = reactive({
  temperature: 36.5, pulse: 80, respiration: 18, sbp: 120, dbp: 80, spo2: 98,
})

async function load() {
  const [pending, adm] = await Promise.all([
    client.get('/inpatient/orders/pending'),
    client.get('/inpatient/admissions'),
  ])
  worklist.value = pending.data.data
  admissions.value = adm.data.data
}

async function saveVital() {
  if (!vitalAdmissionId.value) {
    ElMessage.warning('请选择患者')
    return
  }
  await client.post(`/inpatient/admissions/${vitalAdmissionId.value}/vitals`, vital)
  ElMessage.success('体征已记录')
}

async function execute(row: Record<string, unknown>) {
  await client.put(`/inpatient/orders/${row.orderId}/execute`)
  ElMessage.success(`已执行：${row.itemName}`)
  await load()
}

onMounted(load)
</script>
