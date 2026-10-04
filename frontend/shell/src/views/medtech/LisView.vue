<template>
  <el-card>
    <!-- v77 车道 B（1016★ 流向动态路由）：两个队列按执行科室分流。默认选登录人科室（/auth/me 的 deptId），
         登录人无科室或手动清空 = 全部；两列 exec_dept_id/exec_dept_name 由后端随行下发（规则落值优先、回落字典）。 -->
    <div class="dept-bar">
      <span class="dept-label">执行科室</span>
      <el-select v-model="deptId" size="small" filterable clearable placeholder="全部" style="width: 200px" @change="load">
        <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
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
              <el-button link type="primary" size="small" :loading="busyId === row.order_id" @click="collect(row)">采样打码</el-button>
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
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button v-if="row.status === 'COLLECTED'" link type="primary" size="small" :loading="busyId === row.id" @click="receive(row)">核收</el-button>
              <el-button v-if="row.status === 'RECEIVED'" link type="success" size="small" @click="openResult(row)">录结果并发布</el-button>
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
        <el-button type="success" @click="publish" :loading="publishLoading">审核发布</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const tab = ref('pending')
// v77：执行科室过滤。默认登录人科室；无科室为 null = 全部（不传 deptId，输出与此前逐行相同、只多两列）
const deptId = ref<number | null>(auth.user?.deptId ?? null)
const depts = ref<{ id: number; name: string; enabled?: boolean }[]>([])
const statusNames: Record<string, string> = { COLLECTED: '已采样', RECEIVED: '已核收', PUBLISHED: '已发布' }
const pending = ref<Record<string, unknown>[]>([])
const samples = ref<Record<string, unknown>[]>([])
const dialogVisible = ref(false)
const current = ref<Record<string, unknown> | null>(null)
const results = ref<Record<string, string>[]>([])
const busyId = ref<unknown>(null)
const publishLoading = ref(false)

async function load() {
  const params = { deptId: deptId.value ?? undefined }
  pending.value = (await client.get('/lis/pending', { params })).data.data
  samples.value = (await client.get('/lis/samples', { params })).data.data
}

/** 科室下拉沿用系统管理的科室接口（所有登录用户可读）；停用科室不列（已开出的历史行仍按 exec_dept_name 原样显示） */
async function loadDepts() {
  const all = (await client.get('/system/depts')).data.data as { id: number; name: string; enabled?: boolean }[]
  depts.value = all.filter((d) => d.enabled !== false)
}

async function collect(row: Record<string, unknown>) {
  // 三十九期：替检参数化——留空为本人，填写则登记替检人（分检页红色醒目提示）
  const res = await ElMessageBox.prompt('替检人（本人采样请留空）', '采样打码',
    { inputValue: '', inputPlaceholder: '如：家属 张某' }).catch(() => null)
  if (!res) return
  const { value } = res
  busyId.value = row.order_id
  try {
    const resp = await client.post('/lis/samples', null,
      { params: { orderId: row.order_id, substituteName: value || undefined } })
    ElMessage.success(`条码 ${resp.data.data.barcode}${value ? '（替检已标识）' : ''}`)
    await load()
  } finally { busyId.value = null }
}

async function receive(row: Record<string, unknown>) {
  busyId.value = row.id
  try {
    await client.put(`/lis/samples/${row.barcode}/receive`)
    await load()
  } finally { busyId.value = null }
}

function openResult(row: Record<string, unknown>) {
  current.value = row
  results.value = [{ code: '', name: String(row.item_name), value: '', unit: '', refRange: '', flag: 'N' }]
  dialogVisible.value = true
}

async function publish() {
  if (!current.value) return
  publishLoading.value = true
  try {
    await client.post(`/lis/samples/${current.value.barcode}/publish`, { results: results.value })
    ElMessage.success('已发布，医嘱自动执行')
    dialogVisible.value = false
    // v74 复核补入口：检验报告单打印页（/print?type=lab-report）此前全前端无任何页面打开它，
    // 偏离表 47 行却写着「已提供检验报告单打印」——后端做了、用户够不着。发布即出报告是最自然的入口；
    // 标本列表本身不含已发布行（后端 where status <> 'PUBLISHED'），所以不能放行内按钮。
    printReport(current.value.order_id)
    await load()
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
</script>

<style scoped>
.dept-bar { display: flex; align-items: center; margin-bottom: 8px; }
.dept-label { font-size: 13px; color: var(--el-text-color-secondary); margin-right: 8px; }
</style>
