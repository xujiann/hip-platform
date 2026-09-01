<template>
  <div>
  <el-card style="margin-bottom: 12px">
    <template #header>
      生命体征录入（三测单数据源）
      <el-button link type="primary" style="float: right" :disabled="!vitalAdmissionId"
                 @click="printTempSheet">打印体温单</el-button>
    </template>
    <el-form inline>
      <el-form-item label="患者">
        <el-select v-model="vitalAdmissionId" style="width: 200px" @change="loadVitals">
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
      <!-- v42：三测单纸面格位（V129 新列，全部可空——不填即该项未记录） -->
      <el-form-item label="测量部位">
        <el-select v-model="vital.measureSite" clearable placeholder="不限" style="width: 110px">
          <el-option v-for="s in MEASURE_SITES" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="降温后体温℃">
        <el-input-number v-model="vital.tempAfterCooling" :min="VITAL_RANGES.temperature.min"
                         :max="VITAL_RANGES.temperature.max" :precision="1" :step="0.1"
                         :controls="false" placeholder="物理降温后" style="width: 120px" />
      </el-form-item>
      <el-form-item label="入量ml"><el-input-number v-model="vital.intakeMl" :min="0" :max="20000" :controls="false" style="width: 100px" /></el-form-item>
      <el-form-item label="出量ml"><el-input-number v-model="vital.outputMl" :min="0" :max="20000" :controls="false" style="width: 100px" /></el-form-item>
      <el-form-item label="大便次"><el-input-number v-model="vital.stoolCount" :min="0" :max="30" :controls="false" style="width: 90px" /></el-form-item>
      <el-form-item label="体重kg"><el-input-number v-model="vital.weightKg" :min="0" :max="400" :precision="1" :step="0.1" :controls="false" style="width: 100px" /></el-form-item>
      <el-form-item label="身高cm"><el-input-number v-model="vital.heightCm" :min="0" :max="260" :controls="false" style="width: 100px" /></el-form-item>
      <el-form-item label="未测原因">
        <el-input v-model="vital.notMeasuredReason" clearable maxlength="32" placeholder="外出/拒测等" style="width: 140px" />
      </el-form-item>
      <el-button type="primary" @click="saveVital">保存</el-button>
    </el-form>
    <!-- 保存后曲线回显：护士站此前录完无任何反馈，异常趋势要到医生站才看得见 -->
    <VitalsChart v-if="vitals.length" :vitals="vitals" />
    <el-empty v-else-if="vitalAdmissionId" description="该患者暂无体征记录" :image-size="60" />
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

  <!-- v39 长期医嘱执行行：今日按频次展开的逐次执行队列（按行执行、按行计费） -->
  <el-card>
    <template #header>
      长期医嘱执行行（今日）
      <el-button link type="primary" style="float: right" @click="loadExecLines">刷新</el-button>
    </template>
    <el-empty v-if="!execLines.length" description="今日无待执行长期医嘱" :image-size="60" />
    <el-table v-else :data="execLines" border stripe size="small">
      <el-table-column prop="admission_no" label="住院号" width="160" />
      <el-table-column prop="patient_name" label="姓名" width="90" />
      <el-table-column prop="bed_no" label="床" width="50" />
      <el-table-column prop="item_name" label="项目" />
      <el-table-column label="用法" width="160">
        <template #default="{ row }">{{ row.usage_route }} {{ row.dose_per_time }} {{ row.frequency }}</template>
      </el-table-column>
      <el-table-column prop="seq_no" label="次" width="50" />
      <el-table-column prop="amount" label="金额" width="80" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button type="success" size="small" @click="executeLine(row)">执行</el-button>
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
import VitalsChart from '../../components/VitalsChart.vue'
import { VITAL_RANGES } from '../../utils/vitals'

/** 三测单测量部位（与后端 measure_site 同码；取值校验推 v43，本版不动写路径） */
const MEASURE_SITES = [
  { value: 'ORAL', label: '口温' },
  { value: 'AXILLARY', label: '腋温' },
  { value: 'RECTAL', label: '肛温' },
]

const worklist = ref<Record<string, unknown>[]>([])
const execLines = ref<Record<string, unknown>[]>([])
function todayStr() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
async function loadExecLines() {
  execLines.value = (await client.get('/inpatient/exec-lines', { params: { date: todayStr() } })).data.data
}
async function executeLine(row: Record<string, unknown>) {
  await client.put(`/inpatient/exec-lines/${row.id}/execute`)
  ElMessage.success(`已执行：${row.item_name}（第 ${row.seq_no} 次）`)
  await loadExecLines()
}
const admissions = ref<Record<string, unknown>[]>([])
const vitalAdmissionId = ref<number | null>(null)
const vitals = ref<Record<string, unknown>[]>([])
type Num = number | null | undefined
interface VitalForm {
  temperature: Num; pulse: Num; respiration: Num; sbp: Num; dbp: Num; spo2: Num
  measureSite: string | undefined
  tempAfterCooling: Num
  intakeMl: Num; outputMl: Num; stoolCount: Num; weightKg: Num; heightCm: Num
  notMeasuredReason: string | undefined
}
const vital = reactive<VitalForm>({
  temperature: 36.5, pulse: 80, respiration: 18, sbp: 120, dbp: 80, spo2: 98,
  // v42 三测单新列：默认 undefined（不提交即库中为 null，前端按「未测」渲染）
  measureSite: undefined, tempAfterCooling: undefined,
  intakeMl: undefined, outputMl: undefined, stoolCount: undefined,
  weightKg: undefined, heightCm: undefined, notMeasuredReason: undefined,
})

async function loadVitals() {
  if (!vitalAdmissionId.value) {
    vitals.value = []
    return
  }
  vitals.value = (await client.get(`/inpatient/admissions/${vitalAdmissionId.value}/vitals`)).data.data
}

/** 体温单打印：沿用既有 window.open('/print?type=...') 模式，周次由打印页内翻页控件切换 */
function printTempSheet() {
  if (!vitalAdmissionId.value) {
    ElMessage.warning('请选择患者')
    return
  }
  window.open(`/print?type=temp-sheet&id=${vitalAdmissionId.value}&week=1`)
}

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
  await loadVitals()
}

async function execute(row: Record<string, unknown>) {
  await client.put(`/inpatient/orders/${row.orderId}/execute`)
  ElMessage.success(`已执行：${row.itemName}`)
  await load()
}

onMounted(() => { load(); loadExecLines() })
</script>
