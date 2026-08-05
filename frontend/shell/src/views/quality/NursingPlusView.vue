<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane label="传染病报卡" name="cards">
        <el-form inline size="small">
          <el-form-item><el-input v-model="card.patientId" placeholder="患者ID" style="width: 100px" /></el-form-item>
          <el-form-item><el-input v-model="card.diseaseName" placeholder="疾病名称（如 肺结核）" style="width: 180px" /></el-form-item>
          <el-form-item>
            <el-select v-model="card.cardClass" style="width: 110px">
              <el-option label="甲类" value="A" /><el-option label="乙类" value="B" /><el-option label="丙类" value="C" />
            </el-select>
          </el-form-item>
          <el-button type="primary" size="small" @click="reportCard">报卡</el-button>
          <el-tag v-if="card.cardClass === 'A'" type="danger" size="small" style="margin-left: 8px">
            甲类须 2 小时内完成上报</el-tag>
        </el-form>
        <el-table :data="cards" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="disease_name" label="疾病" width="150" />
          <el-table-column label="类别" width="70">
            <template #default="{ row }">
              <el-tag size="small" :type="row.card_class === 'A' ? 'danger' : row.card_class === 'B' ? 'warning' : 'info'">
                {{ { A: '甲', B: '乙', C: '丙' }[row.card_class as string] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reporter" label="报卡人" width="90" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'SUBMITTED' ? 'success'
                : row.status === 'REJECTED' ? 'danger' : 'warning'">
                {{ { REPORTED: '待审核', REVIEWED: '待上报', SUBMITTED: '已上报', REJECTED: '已退回' }[row.status as string] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <template v-if="row.status === 'REPORTED'">
                <el-button link type="success" size="small" @click="reviewCard(row, true)">通过</el-button>
                <el-button link type="danger" size="small" @click="reviewCard(row, false)">退回</el-button>
              </template>
              <el-button v-if="row.status === 'REVIEWED'" link type="primary" size="small" @click="submitCard(row)">
                上报</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="体温日历/发热监测" name="temp">
        <el-form inline size="small">
          <el-form-item><el-input v-model="calAdmId" placeholder="住院ID" style="width: 110px" /></el-form-item>
          <el-button type="primary" size="small" @click="loadCalendar">查看体温日历</el-button>
        </el-form>
        <div v-if="calendar.length" class="cal-grid">
          <div v-for="d in calendar" :key="String(d.day)" class="cal-cell" :class="{ fever: d.fever }">
            <div class="cal-day">{{ String(d.day).slice(5) }}</div>
            <div class="cal-temp">{{ d.max_temp }}℃</div>
          </div>
        </div>
        <el-alert v-if="calendar.length" :title="`发热天数（≥37.3℃）：${calendar.filter(d => d.fever).length} 天`"
                  :type="calendar.some(d => d.fever) ? 'warning' : 'success'" show-icon :closable="false"
                  style="margin: 8px 0" />
        <h4>术后发热监测（术后 72h 内体温 ≥38℃）</h4>
        <el-table :data="postopFever" size="small" border>
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="admission_no" label="住院号" width="140" />
          <el-table-column prop="procedure_name" label="术式" show-overflow-tooltip />
          <el-table-column prop="temperature" label="体温" width="80" />
          <el-table-column prop="measured_at" label="测量时间" width="170" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="质控抽检" name="qccheck">
        <el-form inline size="small">
          <el-form-item><el-input v-model="planForm.title" placeholder="抽检计划名" style="width: 170px" /></el-form-item>
          <el-form-item><el-input v-model="planForm.standard" placeholder="质控标准" style="width: 200px" /></el-form-item>
          <el-form-item><el-checkbox v-model="planForm.adHoc">临时表单</el-checkbox></el-form-item>
          <el-button type="primary" size="small" @click="addPlan">建计划</el-button>
        </el-form>
        <el-table :data="plans" size="small" border @row-click="openPlan" highlight-current-row>
          <el-table-column prop="title" label="计划" width="200" />
          <el-table-column prop="standard" label="标准" show-overflow-tooltip />
          <el-table-column label="临时" width="70">
            <template #default="{ row }"><el-tag v-if="row.ad_hoc" size="small" type="warning">临时</el-tag></template>
          </el-table-column>
          <el-table-column prop="scores" label="评分次数" width="90" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click.stop="addScore(row)">评分</el-button>
            </template>
          </el-table-column>
        </el-table>
        <template v-if="scores.length">
          <h4>评分曲线（{{ currentPlan?.title }}）</h4>
          <el-table :data="scores" size="small" border>
            <el-table-column prop="checked_at" label="时间" width="170" />
            <el-table-column prop="target" label="对象" width="130" />
            <el-table-column prop="score" label="评分" width="80" />
            <el-table-column label="趋势" width="200">
              <template #default="{ row }">
                <div class="bar"><div class="bar-in" :style="{ width: row.score + '%' }" /></div>
              </template>
            </el-table-column>
            <el-table-column prop="note" label="备注" show-overflow-tooltip />
          </el-table>
        </template>
      </el-tab-pane>

      <el-tab-pane label="风险评估" name="risk">
        <el-form inline size="small">
          <el-form-item><el-input v-model="risk.admissionId" placeholder="住院ID" style="width: 100px" /></el-form-item>
          <el-form-item>
            <el-select v-model="risk.assessType" style="width: 170px">
              <el-option label="Braden 压力性损伤" value="BRADEN" />
              <el-option label="Morse 跌倒" value="MORSE" />
            </el-select>
          </el-form-item>
          <el-form-item label="评分"><el-input-number v-model="risk.score" :min="0" :max="125" /></el-form-item>
          <el-button type="primary" size="small" @click="doAssess">评估</el-button>
          <el-button size="small" @click="loadRiskTrend">查趋势</el-button>
        </el-form>
        <el-alert title="Braden 6-23：≤12 高危 / 13-14 中危 / ≥15 低危 ｜ Morse 0-125：≥45 高危 / 25-44 中危 / <25 低危"
                  type="info" show-icon :closable="false" style="margin-bottom: 8px" />
        <el-table :data="riskTrend" size="small" border>
          <el-table-column prop="assessed_at" label="时间" width="170" />
          <el-table-column label="量表" width="100">
            <template #default="{ row }">{{ row.assess_type === 'BRADEN' ? 'Braden' : 'Morse' }}</template>
          </el-table-column>
          <el-table-column prop="score" label="评分" width="80" />
          <el-table-column label="风险" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.risk_level === 'HIGH' ? 'danger'
                : row.risk_level === 'MID' ? 'warning' : 'success'">
                {{ { HIGH: '高危', MID: '中危', LOW: '低危' }[row.risk_level as string] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="趋势" width="180">
            <template #default="{ row }">
              <div class="bar"><div class="bar-in" :style="{ width: Math.min(100, row.score / 1.25) + '%' }" /></div>
            </template>
          </el-table-column>
          <el-table-column prop="assessor" label="评估人" width="90" />
        </el-table>
        <h4>在院高危预警</h4>
        <el-table :data="riskAlerts" size="small" border>
          <el-table-column prop="dept_name" label="科室" width="130" />
          <el-table-column prop="admission_no" label="住院号" width="140" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column label="量表" width="90">
            <template #default="{ row }">{{ row.assess_type === 'BRADEN' ? 'Braden' : 'Morse' }}</template>
          </el-table-column>
          <el-table-column prop="score" label="评分" width="80" />
          <el-table-column prop="assessed_at" label="最近评估" width="170" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="交接班" name="handover">
        <el-form inline size="small">
          <el-form-item><el-input v-model="ho.deptId" placeholder="科室ID" style="width: 90px" /></el-form-item>
          <el-form-item>
            <el-select v-model="ho.shiftType" style="width: 100px">
              <el-option v-for="s in shiftTypes" :key="String(s.code)" :label="String(s.name)" :value="String(s.code)" />
            </el-select>
          </el-form-item>
          <el-form-item><el-input v-model="ho.summary" placeholder="当班情况" style="width: 240px" /></el-form-item>
          <el-form-item><el-input v-model="ho.todo" placeholder="交接待办" style="width: 180px" /></el-form-item>
          <el-button type="primary" size="small" @click="addHandover">交班</el-button>
        </el-form>
        <el-table :data="handovers" size="small" border>
          <el-table-column prop="dept_name" label="科室" width="130" />
          <el-table-column prop="shift_date" label="日期" width="110" />
          <el-table-column prop="shift_type" label="班次" width="80" />
          <el-table-column prop="summary" label="当班情况" show-overflow-tooltip />
          <el-table-column prop="todo" label="待办" show-overflow-tooltip />
          <el-table-column prop="author" label="交班人" width="90" />
        </el-table>
        <h4>班次字典
          <el-button link type="primary" size="small" @click="addShiftType">新增班次</el-button>
        </h4>
        <el-table :data="shiftTypes" size="small" border>
          <el-table-column prop="code" label="编码" width="90" />
          <el-table-column prop="name" label="班次" width="90" />
          <el-table-column prop="start_time" label="开始" width="90" />
          <el-table-column prop="end_time" label="结束" width="90" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const tab = ref('cards')
const cards = ref<Record<string, unknown>[]>([])
const calendar = ref<{ day: string; max_temp: number; fever: boolean }[]>([])
const postopFever = ref<Record<string, unknown>[]>([])
const plans = ref<Record<string, unknown>[]>([])
const scores = ref<Record<string, unknown>[]>([])
const currentPlan = ref<Record<string, unknown> | null>(null)
const shiftTypes = ref<Record<string, unknown>[]>([])
const handovers = ref<Record<string, unknown>[]>([])
const calAdmId = ref('')
const card = reactive({ patientId: '', diseaseName: '', cardClass: 'B' })
const planForm = reactive({ title: '', standard: '', adHoc: false })
const ho = reactive({ deptId: '', shiftType: 'DAY', summary: '', todo: '' })
const riskTrend = ref<Record<string, unknown>[]>([])
const riskAlerts = ref<Record<string, unknown>[]>([])
const risk = reactive({ admissionId: '', assessType: 'BRADEN', score: 15 })

async function loadRiskTrend() {
  if (!risk.admissionId) return
  riskTrend.value = (await client.get('/nursing/risk-assess', { params: { admissionId: risk.admissionId } })).data.data
}
async function loadRiskAlerts() { riskAlerts.value = (await client.get('/nursing/risk-assess/alerts')).data.data }
async function doAssess() {
  if (!risk.admissionId) return
  const r = (await client.post('/nursing/risk-assess',
    { admissionId: Number(risk.admissionId), assessType: risk.assessType, score: risk.score })).data.data
  ElMessage.success(`已评估：${{ HIGH: '高危', MID: '中危', LOW: '低危' }[r.riskLevel as string]}`)
  await Promise.all([loadRiskTrend(), loadRiskAlerts()])
}

async function loadCards() { cards.value = (await client.get('/infection/cards')).data.data }
async function loadCalendar() {
  if (!calAdmId.value) return
  calendar.value = (await client.get('/nursing/temp-calendar', { params: { admissionId: calAdmId.value } })).data.data
}
async function loadFever() { postopFever.value = (await client.get('/nursing/postop-fever')).data.data }
async function loadPlans() { plans.value = (await client.get('/qc-check/plans')).data.data }
async function loadShift() {
  shiftTypes.value = (await client.get('/nursing/shift-types')).data.data
  handovers.value = (await client.get('/nursing/handovers')).data.data
}

async function reportCard() {
  if (!card.patientId || !card.diseaseName) return
  await client.post('/infection/cards', { ...card, patientId: Number(card.patientId) })
  ElMessage.success('已报卡')
  await loadCards()
}
async function reviewCard(row: Record<string, unknown>, approve: boolean) {
  const { value } = await ElMessageBox.prompt('审核意见', approve ? '通过' : '退回', { inputValue: approve ? '属实' : '' })
  await client.put(`/infection/cards/${row.id}/review`, null, { params: { approve, note: value } })
  await loadCards()
}
async function submitCard(row: Record<string, unknown>) {
  await client.put(`/infection/cards/${row.id}/submit`)
  ElMessage.success('已上报疾控')
  await loadCards()
}
async function addPlan() {
  if (!planForm.title || !planForm.standard) return
  await client.post('/qc-check/plans', planForm)
  await loadPlans()
}
async function openPlan(row: Record<string, unknown>) {
  currentPlan.value = row
  scores.value = (await client.get('/qc-check/scores', { params: { planId: row.id } })).data.data
}
async function addScore(row: Record<string, unknown>) {
  const { value } = await ElMessageBox.prompt('对象,评分（逗号分隔）', '抽检评分', { inputValue: '内科病区,92' })
  const [target, score] = value.split(/[,，]/).map((s: string) => s.trim())
  await client.post('/qc-check/scores', { planId: row.id, target, score: Number(score), note: '' })
  await loadPlans()
  await openPlan(row)
}
async function addHandover() {
  if (!ho.deptId || !ho.summary) return
  await client.post('/nursing/handovers', { ...ho, deptId: Number(ho.deptId) })
  ho.summary = ''
  ho.todo = ''
  await loadShift()
}
async function addShiftType() {
  const { value } = await ElMessageBox.prompt('编码,名称,开始,结束（逗号分隔）', '新增班次',
    { inputValue: 'AM8,行政班,08:00,17:30' })
  const [code, name, startTime, endTime] = value.split(/[,，]/).map((s: string) => s.trim())
  await client.post('/nursing/shift-types', { code, name, startTime, endTime })
  await loadShift()
}

watch(tab, (t) => {
  if (t === 'temp') loadFever()
  if (t === 'qccheck') loadPlans()
  if (t === 'handover') loadShift()
  if (t === 'risk') loadRiskAlerts()
})
onMounted(loadCards)
</script>

<style scoped>
.cal-grid { display: flex; flex-wrap: wrap; gap: 6px; }
.cal-cell { border: 1px solid #dcdfe6; border-radius: 4px; padding: 6px 10px; text-align: center; }
.cal-cell.fever { background: #fef0f0; border-color: #f89898; }
.cal-day { font-size: 12px; color: #909399; }
.cal-temp { font-weight: 600; }
.bar { background: #f0f2f5; border-radius: 3px; height: 10px; width: 180px; }
.bar-in { background: #2563eb; height: 10px; border-radius: 3px; }
</style>
