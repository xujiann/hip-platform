<template>
  <el-card>
    <el-descriptions v-if="overview" :column="4" border size="small" style="margin-bottom: 10px">
      <el-descriptions-item label="出院病案">{{ overview.discharged }} 份</el-descriptions-item>
      <el-descriptions-item label="已归档">{{ overview.archived }} 份</el-descriptions-item>
      <el-descriptions-item label="手术例数">{{ overview.surgeries }}</el-descriptions-item>
      <el-descriptions-item label="主诊断编码率">{{ overview.codedRate }}%</el-descriptions-item>
    </el-descriptions>

    <el-tabs v-model="tab">
      <el-tab-pane label="疾病谱 TOP20" name="disease">
        <el-table :data="diseaseTop" size="small" border>
          <el-table-column type="index" label="#" width="55" />
          <el-table-column prop="icd_group" label="ICD" width="80" />
          <el-table-column prop="sample_name" label="诊断" show-overflow-tooltip />
          <el-table-column prop="cases" label="例数" width="90" />
          <el-table-column prop="avg_days" label="平均住院日" width="110" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="手术统计" name="surgery">
        <el-table :data="surgeryStats" size="small" border>
          <el-table-column prop="procedure_name" label="术式" show-overflow-tooltip />
          <el-table-column prop="cases" label="总例数" width="90" />
          <el-table-column prop="recent_cases" label="近30日" width="90" />
          <el-table-column prop="done" label="已完成" width="90" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="科室出院构成" name="dept">
        <el-table :data="deptDischarge" size="small" border>
          <el-table-column prop="dept_name" label="科室" width="160" />
          <el-table-column prop="discharged" label="出院人数" width="100" />
          <el-table-column prop="avg_days" label="平均住院日" width="110" />
          <el-table-column prop="percentage" label="构成比(%)" width="100" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="死亡登记" name="death">
        <el-form inline size="small">
          <el-form-item><el-input v-model="deathForm.patientId" placeholder="患者ID" style="width: 90px" /></el-form-item>
          <el-form-item><el-input v-model="deathForm.admissionId" placeholder="住院ID(选填)" style="width: 110px" /></el-form-item>
          <el-form-item>
            <el-date-picker v-model="deathForm.diedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ"
                            placeholder="死亡时间" style="width: 180px" />
          </el-form-item>
          <el-form-item><el-input v-model="deathForm.directCause" placeholder="直接死因(a)" style="width: 160px" /></el-form-item>
          <el-form-item><el-input v-model="deathForm.directCauseIcd" placeholder="ICD" style="width: 90px" /></el-form-item>
          <el-form-item><el-input v-model="deathForm.chainB" placeholder="死因链(b)" style="width: 140px" /></el-form-item>
          <el-form-item><el-input v-model="deathForm.chainC" placeholder="死因链(c)" style="width: 140px" /></el-form-item>
          <el-form-item><el-input v-model="deathForm.place" placeholder="死亡地点" style="width: 100px" /></el-form-item>
          <el-button type="primary" size="small" @click="saveDeathCard" :loading="saveDeathCardLoading">登记</el-button>
        </el-form>
        <el-table :data="deathCards" size="small" border max-height="420">
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="patient_no" label="病案号" width="110" />
          <el-table-column prop="admission_no" label="住院号" width="150" />
          <el-table-column prop="died_at" label="死亡时间" width="170" />
          <el-table-column prop="direct_cause" label="直接死因" show-overflow-tooltip />
          <el-table-column prop="direct_cause_icd" label="ICD" width="80" />
          <el-table-column prop="place" label="地点" width="90" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="ICD 章节构成" name="icd">
        <el-table :data="icdComposition" size="small" border>
          <el-table-column prop="chapter" label="章节（首字母）" width="130" />
          <el-table-column prop="cases" label="例数" width="90" />
          <el-table-column prop="percentage" label="构成比(%)" width="110" />
        </el-table>
      </el-tab-pane>

      <!-- v41 床位效率：出院人次/平均住院日/占用床日/周转次数/使用率（按科室×月） -->
      <el-tab-pane label="床位效率趋势" name="bed">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 8px"
                  title="口径说明：占用床日按出院病例住院天数归集到出院月（跨月长住计入出院月）；床位数取该科室当前开放床位数（无历史快照），历史月份增减过床位会有偏差。" />
        <el-table :data="bedTrend" size="small" border>
          <el-table-column prop="month" label="月份" width="90" />
          <el-table-column prop="dept_name" label="科室" width="120" />
          <el-table-column prop="discharges" label="出院人次" width="100" />
          <el-table-column prop="avg_stay_days" label="平均住院日" width="110" />
          <el-table-column prop="bed_days" label="占用床日" width="100" />
          <el-table-column prop="bed_count" label="床位数" width="90" />
          <el-table-column prop="turnover" label="周转次数" width="100">
            <template #default="{ row }">{{ row.turnover ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="使用率(%)" width="110">
            <template #default="{ row }">{{ row.occupancy_pct ?? '—' }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import client from '../../api/client'

const tab = ref('disease')
const overview = ref<Record<string, unknown> | null>(null)
const diseaseTop = ref<Record<string, unknown>[]>([])
const surgeryStats = ref<Record<string, unknown>[]>([])
const deptDischarge = ref<Record<string, unknown>[]>([])
const icdComposition = ref<Record<string, unknown>[]>([])
const bedTrend = ref<Record<string, unknown>[]>([])   // v41 床位效率趋势

async function load() {
  overview.value = (await client.get('/mrstats/overview')).data.data
  diseaseTop.value = (await client.get('/mrstats/disease-top')).data.data
}
// 1.0.1（1028）：死亡登记卡
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
const deathCards = ref<Record<string, unknown>[]>([])
const saveDeathCardLoading = ref(false)
const deathForm = reactive({ patientId: '', admissionId: '', diedAt: '', directCause: '',
  directCauseIcd: '', chainB: '', chainC: '', place: '' })
async function loadDeathCards() { deathCards.value = (await client.get('/mrstats/death-cards')).data.data }
async function saveDeathCard() {
  saveDeathCardLoading.value = true
  try {
    await client.post('/mrstats/death-cards', {
      ...deathForm,
      patientId: Number(deathForm.patientId) || null,
      admissionId: deathForm.admissionId ? Number(deathForm.admissionId) : null,
    })
    ElMessage.success('已登记')
    await loadDeathCards()
  } finally { saveDeathCardLoading.value = false }
}

watch(tab, async (t) => {
  if (t === 'surgery') surgeryStats.value = (await client.get('/mrstats/surgery-stats')).data.data
  if (t === 'dept') deptDischarge.value = (await client.get('/mrstats/dept-discharge')).data.data
  if (t === 'icd') icdComposition.value = (await client.get('/mrstats/icd-composition')).data.data
  if (t === 'death') await loadDeathCards()
  if (t === 'bed') bedTrend.value = (await client.get('/mrstats/dept-bed-trend', { params: { months: 12 } })).data.data
})
onMounted(load)
</script>
