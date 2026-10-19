<template>
  <el-card>
    <el-tabs v-model="tab">
      <el-tab-pane v-if="canQuality" label="病历时限质控" name="emr">
        <el-button size="small" @click="loadEmr">重新核查</el-button>
        <el-descriptions :column="3" border size="small" style="margin: 10px 0">
          <el-descriptions-item label="缺陷总数">{{ emr.defectTotal ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="门诊病历未签名">{{ emr.unsignedOutpEmrCount ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="缺出院小结">{{ (emr.missingDischargeSummary as unknown[])?.length ?? '-' }}</el-descriptions-item>
        </el-descriptions>
        <h4>入院超 24h 未书写入院记录</h4>
        <el-table :data="(emr.missingAdmissionRecord as Record<string, unknown>[]) ?? []" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="170" />
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="dept_name" label="科室" width="120" />
          <el-table-column prop="hours" label="已入院(小时)" width="110" />
        </el-table>
        <h4 style="margin-top: 12px">出院未书写出院小结</h4>
        <el-table :data="(emr.missingDischargeSummary as Record<string, unknown>[]) ?? []" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="170" />
          <el-table-column prop="patient_name" label="患者" />
        </el-table>

        <h4 style="margin-top: 12px">首程记录超时（超 8h 未书写首次病程）</h4>
        <el-table :data="(emr.missingFirstProgress as Record<string, unknown>[]) ?? []" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="170" />
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="dept_name" label="科室" width="120" />
          <el-table-column prop="hours" label="已入院(小时)" width="110" />
        </el-table>

        <h4 style="margin-top: 12px">
          三级查房超时（超 48h 无查房）
          <el-tag v-if="!emr.roundCheckEnabled" type="info" size="small">未启用（依赖三级查房数据）</el-tag>
        </h4>
        <el-table v-if="emr.roundCheckEnabled" :data="(emr.missingRound as Record<string, unknown>[]) ?? []" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="170" />
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="dept_name" label="科室" width="120" />
          <el-table-column prop="hours" label="已入院(小时)" width="110" />
        </el-table>

        <h4 style="margin-top: 12px">病程记录连续性缺陷（距上次记录超阈值）</h4>
        <el-table :data="(emr.progressContinuityDefect as Record<string, unknown>[]) ?? []" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="170" />
          <el-table-column prop="patient_name" label="患者" width="100" />
          <el-table-column prop="dept_name" label="科室" width="120" />
          <el-table-column prop="care_level" label="护理级别" width="90" />
          <el-table-column prop="gap_days" label="间隔(天)" width="90" />
        </el-table>

        <h4 style="margin-top: 12px">抢救记录超时未闭合</h4>
        <el-table :data="(emr.rescueLateRecord as Record<string, unknown>[]) ?? []" size="small" border>
          <el-table-column prop="id" label="抢救记录号" width="130" />
          <el-table-column prop="hours" label="已开始(小时)" width="120" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="不良事件" name="adverse">
        <el-alert :closable="false" type="info" style="margin-bottom: 10px"
          :title="canQuality ? '质控范围：全院实名及匿名报告' : '仅显示本人实名报告；匿名报告提交后由管理员和质控处理，本人列表不回显。'" />
        <p>匿名报告不保存报告人身份；安全审计仍记录登录请求。描述中请避免填写可识别报告人的信息。</p>
        <el-button size="small" @click="loadAe">刷新事件</el-button>
        <el-form inline size="small">
          <el-form-item><el-select v-model="ae.type" style="width: 130px">
            <el-option v-for="x in ['跌倒', '给药错误', '压疮', '管路滑脱', '其他']" :key="x" :label="x" :value="x" />
          </el-select></el-form-item>
          <el-form-item label="分级"><el-input-number v-model="ae.level" :min="1" :max="4" /></el-form-item>
          <el-form-item><el-date-picker v-model="ae.occurredOn" type="date" value-format="YYYY-MM-DD" placeholder="发生日期" /></el-form-item>
          <!-- v81 审阅修补（D11）：上报接口与列表一直收 / 回科室，表单此前没有科室栏 -->
          <el-form-item><el-select v-model="ae.deptId" placeholder="发生科室（可选）" clearable filterable style="width: 150px">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select></el-form-item>
          <el-form-item><el-input v-model="ae.description" placeholder="事件描述" style="width: 240px" /></el-form-item>
          <el-form-item><el-checkbox v-model="ae.anonymous">匿名</el-checkbox></el-form-item>
          <el-button type="danger" size="small" @click="reportAe" :loading="reportAeLoading">上报</el-button>
        </el-form>
        <el-table :data="aeList" size="small" border>
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column prop="level" label="级" width="50" />
          <el-table-column prop="occurred_on" label="发生日期" width="110" />
          <el-table-column prop="dept_name" label="科室" width="110" />
          <el-table-column prop="description" label="描述" show-overflow-tooltip />
          <el-table-column label="上报" width="70">
            <template #default="{ row }">{{ row.anonymous ? '匿名' : '实名' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'HANDLED'" type="success" size="small">已处理</el-tag>
              <el-button v-else-if="canQuality" link type="primary" size="small" :loading="busyId === row.id"
                         @click="handleAe(row)">处理</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="院感监测" name="infection">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 10px">
          <el-descriptions-item label="登记感染病例">{{ (inf.cases as unknown[])?.length ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="抗菌药物已执行医嘱">{{ inf.antibioticOrderCount ?? 0 }} 条</el-descriptions-item>
        </el-descriptions>
        <el-table :data="(inf.cases as Record<string, unknown>[]) ?? []" size="small" border>
          <el-table-column prop="admission_no" label="住院号" width="170" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="site" label="感染部位" width="110" />
          <el-table-column prop="pathogen" label="病原体" width="120" />
          <el-table-column prop="confirmed_on" label="确诊日期" width="110" />
          <el-table-column prop="note" label="备注" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const canQuality = computed(() => auth.user?.roles.some((r) => ['ADMIN', 'QUALITY'].includes(r)) ?? false)

const tab = ref(canQuality.value ? 'emr' : 'adverse')
const emr = ref<Record<string, unknown>>({})
const aeList = ref<Record<string, unknown>[]>([])
const inf = ref<Record<string, unknown>>({})
const ae = reactive<{ type: string; level: number; occurredOn: string; deptId: number | null; description: string; anonymous: boolean }>(
  { type: '跌倒', level: 3, occurredOn: '', deptId: null, description: '', anonymous: false })
const depts = ref<{ id: number; name: string }[]>([])

async function loadDepts() {
  depts.value = (await client.get('/system/depts')).data.data ?? []
}
const reportAeLoading = ref(false)
const busyId = ref<unknown>(null)

async function loadEmr() {
  emr.value = {}
  if (!canQuality.value) return
  emr.value = (await client.get('/quality/emr-timeliness')).data.data
}
let aeRequest = 0
async function loadAe() {
  const seq = ++aeRequest
  aeList.value = []
  try {
    const rows = (await client.get('/quality/adverse-events')).data.data
    if (seq === aeRequest) aeList.value = rows
  } catch { if (seq === aeRequest) aeList.value = [] }
}
function clearAe() { ++aeRequest; aeList.value = [] }
async function loadInf() {
  inf.value = (await client.get('/quality/infections')).data.data
}

async function reportAe() {
  if (!ae.occurredOn || !ae.description) {
    ElMessage.warning('发生日期与描述必填')
    return
  }
  reportAeLoading.value = true
  try {
    await client.post('/quality/adverse-events', ae)
    ElMessage.success(ae.anonymous ? '匿名报告已提交，仅管理员和质控可查阅' : '已上报')
    ae.description = ''
    await loadAe()
  } catch { clearAe() } finally { reportAeLoading.value = false }
}

async function handleAe(row: Record<string, unknown>) {
  if (!canQuality.value) return
  const res = await ElMessageBox.prompt('处理意见', '处理不良事件', { inputValue: '已核实并整改' }).catch(() => null)
  if (!res || !aeList.value.some(x => x.id === row.id)) return
  const { value } = res
  busyId.value = row.id
  try {
    await client.put(`/quality/adverse-events/${row.id}/handle`, null, { params: { note: value } })
    await loadAe()
  } catch { clearAe() } finally { busyId.value = null }
}

onMounted(() => {
  loadEmr()
  loadAe()
  loadInf()
  loadDepts()
})
</script>
