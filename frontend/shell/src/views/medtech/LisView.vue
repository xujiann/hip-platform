<template>
  <el-card>
    <!-- v77 车道 B（1016★ 流向动态路由）：两个队列按执行科室分流。默认选登录人科室（/auth/me 的 deptId），
         登录人无科室或手动清空 = 全部；两列 exec_dept_id/exec_dept_name 由后端随行下发（规则落值优先、回落字典）。 -->
    <div class="dept-bar">
      <span class="dept-label">执行科室</span>
      <el-select v-model="deptId" size="small" filterable clearable placeholder="全部" style="width: 200px" @change="load">
        <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-button size="small" style="margin-left: 8px" @click="load">刷新</el-button>
    </div>
    <el-tabs v-model="tab">
      <el-tab-pane label="待采样" name="pending">
        <el-table :data="pending" size="small" border>
          <el-table-column prop="group_no" label="申请单号" width="150" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="item_name" label="项目" />
          <el-table-column label="执行科室" width="110">
            <template #default="{ row }">{{ row.exec_dept_name || '—' }}</template>
          </el-table-column>
          <!-- v74 复核（1013★/1016★ 审计打回点）：加急/标本类型/采样部位/备注后端早就下发，检验科屏幕上此前一个都不显示——
               信息只在医生那张纸上，等于没到检验科。 -->
          <el-table-column label="申请信息" min-width="200">
            <template #default="{ row }">
              <el-tag v-if="row.urgent" type="danger" size="small" style="margin-right: 4px">加急</el-tag>
              <span>{{ [row.specimen_type, row.sampling_site].filter(Boolean).join(' / ') || '—' }}</span>
              <span v-if="row.remark" style="color: #666">（{{ row.remark }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button v-if="canWrite" link type="primary" size="small" :loading="busyId === row.order_id" :disabled="writing" @click="collect(row)">采样打码</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="标本流转" name="samples">
        <el-table :data="samples" size="small" border>
          <el-table-column prop="barcode" label="条码" width="140" />
          <el-table-column prop="patient_name" label="患者" width="90" />
          <el-table-column prop="item_name" label="项目" />
          <el-table-column label="执行科室" width="110">
            <template #default="{ row }">{{ row.exec_dept_name || '—' }}</template>
          </el-table-column>
          <el-table-column label="申请信息" min-width="200">
            <template #default="{ row }">
              <el-tag v-if="row.urgent" type="danger" size="small" style="margin-right: 4px">加急</el-tag>
              <span>{{ [row.specimen_type, row.sampling_site].filter(Boolean).join(' / ') || '—' }}</span>
              <span v-if="row.remark" style="color: #666">（{{ row.remark }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="替检" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.substitute" type="danger" size="small">替检：{{ row.substitute_name }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ statusNames[row.status as string] }}</template>
          </el-table-column>
          <el-table-column label="源医嘱状态" width="110">
            <template #default="{ row }">{{ orderStatusNames[row.order_status as string] || row.order_status || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button v-if="canReceive(row)" link type="primary" size="small" :loading="busyId === row.id" :disabled="writing" @click="receive(row)">核收</el-button>
              <el-button v-if="canPublish(row)" link type="success" size="small" @click="openResult(row)">录结果并发布</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialogVisible" :title="`结果录入：${current?.item_name}`" width="640px">
      <el-table :data="results" size="small">
        <el-table-column label="项目"><template #default="{ row }"><el-input v-model="row.name" size="small" /></template></el-table-column>
        <el-table-column label="结果" width="100"><template #default="{ row }"><el-input v-model="row.value" size="small" /></template></el-table-column>
        <el-table-column label="单位" width="90"><template #default="{ row }"><el-input v-model="row.unit" size="small" /></template></el-table-column>
        <el-table-column label="参考" width="100"><template #default="{ row }"><el-input v-model="row.refRange" size="small" /></template></el-table-column>
        <el-table-column label="标志" width="90">
          <template #default="{ row }">
            <el-select v-model="row.flag" size="small">
              <el-option v-for="f in ['N', 'H', 'L', 'HH', 'LL']" :key="f" :label="f" :value="f" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" style="margin-top: 8px" @click="results.push({ code: '', name: '', value: '', unit: '', refRange: '', flag: 'N' })">
        加一行
      </el-button>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="success" @click="publish" :loading="publishLoading" :disabled="writing || !current || !canPublish(current)">审核发布</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const tab = ref('pending')
// v77：执行科室过滤。默认登录人科室；无科室为 null = 全部（不传 deptId，输出与此前逐行相同、只多两列）
const deptId = ref<number | null>(auth.user?.deptId ?? null)
const depts = ref<{ id: number; name: string; enabled?: boolean }[]>([])
const statusNames: Record<string, string> = { COLLECTED: '已采样', RECEIVED: '已核收', PUBLISHED: '已发布' }
const orderStatusNames: Record<string, string> = { CREATED: '未收费', CHARGED: '已收费', EXECUTED: '已执行', CANCELLED: '已作废', DISPENSED: '已发药' }
const pending = ref<Record<string, unknown>[]>([])
const samples = ref<Record<string, unknown>[]>([])
const dialogVisible = ref(false)
const current = ref<Record<string, unknown> | null>(null)
const results = ref<Record<string, string>[]>([])
const busyId = ref<unknown>(null)
const publishLoading = ref(false)
const canWrite = computed(() => auth.user?.roles.some(role => ['ADMIN', 'TECHNICIAN', 'DOCTOR_OUTP', 'NURSE'].includes(role)) ?? false)
const writing = computed(() => busyId.value != null || publishLoading.value)
let active = true
let viewVersion = 0
let readVersion = 0
let deptReadVersion = 0
let modalVersion = 0

async function load() {
  if (!active) return
  ++viewVersion
  closeResult()
  pending.value = []
  samples.value = []
  await refreshQueues()
}

/** 写入完成后只刷新队列；新打开的另一份结果草稿由自己的弹窗版本保护。 */
async function refreshQueues() {
  if (!active) return
  const version = ++readVersion
  const department = deptId.value
  const params = { deptId: department ?? undefined }
  const [pendingResponse, samplesResponse] = await Promise.all([
    client.get('/lis/pending', { params }), client.get('/lis/samples', { params }),
  ])
  if (!active || version !== readVersion || department !== deptId.value) return
  pending.value = pendingResponse.data.data
  samples.value = samplesResponse.data.data
}

/** 科室下拉沿用系统管理的科室接口（所有登录用户可读）；停用科室不列（已开出的历史行仍按 exec_dept_name 原样显示） */
async function loadDepts() {
  const version = ++deptReadVersion
  const all = (await client.get('/system/depts')).data.data as { id: number; name: string; enabled?: boolean }[]
  if (active && version === deptReadVersion) depts.value = all.filter((d) => d.enabled !== false)
}

async function collect(row: Record<string, unknown>) {
  if (!active || !canWrite.value || writing.value || !pending.value.includes(row)) return
  const orderId = row.order_id
  const department = deptId.value
  const version = viewVersion
  // 在提示框之前占用本页写入口；提示期间切科室/刷新会使旧目标失效。
  busyId.value = orderId
  // 三十九期：替检参数化——留空为本人，填写则登记替检人（分检页红色醒目提示）
  try {
    const res = await ElMessageBox.prompt('替检人（本人采样请留空）', '采样打码',
      { inputValue: '', inputPlaceholder: '如：家属 张某' }).catch(() => null)
    if (!res || !active || !canWrite.value || version !== viewVersion || department !== deptId.value || !pending.value.includes(row)) return
    const { value } = res
    const resp = await client.post('/lis/samples', null,
      { params: { orderId, substituteName: value || undefined } })
    if (!active || version !== viewVersion || department !== deptId.value) return
    ElMessage.success(`条码 ${resp.data.data.barcode}${value ? '（替检已标识）' : ''}`)
    await refreshQueues()
  } catch (error) {
    if ((error as { bizCode?: number }).bizCode !== 9940) throw error
    if (active && version === viewVersion && department === deptId.value) await refreshQueues()
  } finally { busyId.value = null }
}

function canReceive(row: Record<string, unknown>) {
  return canWrite.value && samples.value.includes(row) && row.status === 'COLLECTED' && ['CHARGED', 'EXECUTED'].includes(String(row.order_status))
}

async function receive(row: Record<string, unknown>) {
  if (!active || writing.value || !canReceive(row)) return
  const barcode = row.barcode
  const version = viewVersion
  const department = deptId.value
  busyId.value = row.id
  try {
    await client.put(`/lis/samples/${barcode}/receive`)
    if (active && version === viewVersion && department === deptId.value) await refreshQueues()
  } catch (error) {
    if ((error as { bizCode?: number }).bizCode !== 9941) throw error
    if (active && version === viewVersion && department === deptId.value) await refreshQueues()
  } finally { busyId.value = null }
}

function canPublish(row: Record<string, unknown>) {
  const source = samples.value.find(sample => sample.barcode === row.barcode && sample.order_id === row.order_id)
  return canWrite.value && source != null && source.status === 'RECEIVED' && ['CHARGED', 'EXECUTED'].includes(String(source.order_status))
}

function closeResult() {
  ++modalVersion
  dialogVisible.value = false
  current.value = null
  results.value = []
}

function openResult(row: Record<string, unknown>) {
  if (!active || !samples.value.includes(row) || !canPublish(row)) return
  ++modalVersion
  current.value = row
  results.value = [{ code: '', name: String(row.item_name), value: '', unit: '', refRange: '', flag: 'N' }]
  dialogVisible.value = true
}

async function publish() {
  if (!active || writing.value || !dialogVisible.value || !current.value) return
  const row = samples.value.find(sample => sample.barcode === current.value?.barcode && sample.order_id === current.value?.order_id)
  if (!row || !canPublish(row)) return
  const barcode = row.barcode
  const orderId = row.order_id
  const body = { results: results.value.map(result => ({ ...result })) }
  const version = viewVersion
  const dialogVersion = modalVersion
  const department = deptId.value
  const sameView = () => active && version === viewVersion && department === deptId.value
  const sameDialog = () => sameView() && dialogVersion === modalVersion && dialogVisible.value && current.value?.barcode === barcode && current.value?.order_id === orderId
  publishLoading.value = true
  try {
    await client.post(`/lis/samples/${barcode}/publish`, body)
    if (!sameView()) return
    if (sameDialog()) {
      closeResult()
      ElMessage.success('已发布，医嘱自动执行')
      printReport(orderId)
    }
    await refreshQueues()
  } catch (error) {
    if ((error as { bizCode?: number }).bizCode !== 9942) throw error
    if (!sameView()) return
    if (sameDialog()) closeResult()
    await refreshQueues()
  } finally { publishLoading.value = false }
}

/** 检验报告单打印：走既有 PrintView 契约 `?type=lab-report&id=<orderId>`（后端 /api/print/lab-report/{orderId}） */
function printReport(orderId: unknown) {
  if (orderId == null || orderId === '') return
  window.open(`/print?type=lab-report&id=${orderId}`, '_blank')
}

onMounted(async () => {
  await Promise.all([loadDepts(), load()])
})
onUnmounted(() => {
  active = false
  ++viewVersion
  ++readVersion
  ++deptReadVersion
  ++modalVersion
})
</script>

<style scoped>
.dept-bar { display: flex; align-items: center; margin-bottom: 8px; }
.dept-label { font-size: 13px; color: var(--el-text-color-secondary); margin-right: 8px; }
</style>
