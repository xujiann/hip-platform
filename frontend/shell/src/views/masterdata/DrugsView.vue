<template>
  <el-card>
    <div class="toolbar">
      <h3>药品目录</h3>
      <div class="filters">
        <!-- v43：状态筛选。本页默认 all=true（看得到停用药），与开单侧选择器的默认
             （只返回启用中）刻意不同——见 MasterDataController.drugs 注释。 -->
        <el-select v-model="statusFilter" style="width: 130px" @change="load">
          <el-option label="全部状态" value="ALL" />
          <el-option label="仅启用" value="ON" />
          <el-option label="仅停用" value="OFF" />
        </el-select>
        <el-input v-model="keyword" placeholder="按名称搜索" clearable style="width: 240px"
                  @keyup.enter="load" @clear="load" />
      </div>
    </div>
    <el-table :data="records" v-loading="loading" border stripe :row-class-name="rowClass">
      <el-table-column prop="code" label="编码" width="90" />
      <el-table-column prop="name" label="药品名称" min-width="140" />
      <el-table-column prop="spec" label="规格" width="140" />
      <el-table-column prop="doseForm" label="剂型" width="70" />
      <!-- v42：财务口径的费用类别（V132 已按 drug_class 回填 W→西药费 / C→中成药费） -->
      <el-table-column label="费用类别" width="110">
        <template #default="{ row }">
          <span v-if="row.feeCategoryCode">{{ categoryName(row.feeCategoryCode as string) }}</span>
          <el-tag v-else type="warning" size="small">未挂类</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="60" />
      <el-table-column prop="price" label="单价" width="80" />
      <el-table-column prop="stock" label="库存" width="70" />
      <el-table-column label="抗菌药" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.antibiotic" type="warning" size="small">是</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="自费" width="60">
        <template #default="{ row }">
          <el-tag v-if="row.selfPay" type="danger" size="small">自费</el-tag>
        </template>
      </el-table-column>
      <!-- v43 启停：状态 + 停用留痕（原因/时间）同列展示，停用了却说不清原因是本条补齐要治的病 -->
      <el-table-column label="状态" min-width="190">
        <template #default="{ row }">
          <template v-if="row.enabled">
            <el-tag type="success" size="small">启用中</el-tag>
          </template>
          <template v-else>
            <el-tag type="info" size="small">已停用</el-tag>
            <div class="disable-info">
              原因：{{ row.disableReason || '（历史停用，无留痕）' }}
              <br />
              停用时间：{{ formatTime(row.disabledAt) }}
            </div>
          </template>
        </template>
      </el-table-column>
      <el-table-column v-if="canManage" label="操作" width="150">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">挂类/自费</el-button>
          <el-button v-if="row.enabled" link type="danger" size="small" @click="openDisable(row)">
            停用
          </el-button>
          <el-button v-else link type="success" size="small" :loading="toggling === row.id"
                     @click="doEnable(row)">
            启用
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <p class="hint">
      药品新增/调价/入库在药库模块提供，本页维护费用类别、自费标记与启用/停用状态。
      未挂类的药品在「费用分类报表」里进入「未分类」行；自费标记是自费知情同意 gate
      （emr.gate.consent.selfpay）的判定依据，未标记则该 gate 永远不会触发。
      <br />
      <b>停用的作用范围</b>：停用后该药<b>不再出现在医生站的药品选择器里，也不能被开进新医嘱</b>
      （门诊与住院开单均返回 8016）；但<b>已开出的处方与医嘱照常发药执行</b>——药已在架上，
      在药房窗口拒发只会把患者堵在那里。停用<b>不删除数据、不清库存</b>，随时可启用恢复。
      <br />
      <b>按批次停用本版不提供</b>：本平台的 md_drug.stock 是单一聚合值，批次级在库量不落库
      （入库单只记录入库时的批次与效期，不维护批次余量），"停用某批次"既拦不住发药、
      也答不出该批次还剩多少，故不做假入口。批次召回请走药库的效期预警与盘点。
      <br />
      列表按编码取前 20 条，请配合上方的名称搜索与状态筛选定位。
    </p>

    <el-dialog v-model="dialogVisible" title="维护费用类别 / 自费标记" width="460px">
      <el-form label-width="100px" size="small">
        <el-form-item label="药品">{{ current.code }} {{ current.name }}</el-form-item>
        <el-form-item label="费用类别">
          <el-select v-model="formCategory" clearable placeholder="不挂类" style="width:220px">
            <el-option v-for="c in categories" :key="String(c.code)" :label="`${c.name}（${c.code}）`"
                       :value="String(c.code)" />
          </el-select>
        </el-form-item>
        <el-form-item label="自费药品">
          <el-switch v-model="formSelfPay" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- v43 停用弹框：原因必填（后端 8015 兜底，前端先拦一道给出即时反馈） -->
    <el-dialog v-model="disableVisible" title="停用药品" width="480px">
      <el-alert type="warning" :closable="false" show-icon class="disable-alert"
                title="停用后该药不再出现在医生站选择器中、不能开进新医嘱；已开出的处方与医嘱不受影响。" />
      <el-form label-width="90px" size="small">
        <el-form-item label="药品">{{ current.code }} {{ current.name }}</el-form-item>
        <el-form-item label="停用原因" required>
          <el-input v-model="disableReason" type="textarea" :rows="3" maxlength="200" show-word-limit
                    placeholder="如：招标掉标 / 厂家召回批次 XXX / 临床暂停使用 / 已由 XXX 替代" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="disableVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="doDisable">确认停用</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'

type Row = Record<string, unknown>

const auth = useAuthStore()
/** 挂类/自费与启停共用一道权限门：药品目录菜单自 V36 起即授权给药师，启停是药事管理动作 */
const canManage = computed(() => {
  const roles = auth.user?.roles ?? []
  return roles.includes('ADMIN') || roles.includes('PHARMACIST')
})

const keyword = ref('')
const statusFilter = ref<'ALL' | 'ON' | 'OFF'>('ALL')
const records = ref<Row[]>([])
const categories = ref<Row[]>([])
const loading = ref(false)

const dialogVisible = ref(false)
const disableVisible = ref(false)
const disableReason = ref('')
const saving = ref(false)
const toggling = ref<unknown>(null)
const current = ref<Row>({})
const formCategory = ref('')
const formSelfPay = ref(false)

function categoryName(code: string): string {
  const hit = categories.value.find((c) => c.code === code)
  return hit ? String(hit.name) : code
}

function rowClass({ row }: { row: Row }): string {
  return row.enabled ? '' : 'disabled-row'
}

/** 后端下发的是 Instant（ISO 串）；历史停用行无留痕，诚实显示"—"而不是编一个时间 */
function formatTime(v: unknown): string {
  if (!v) return '—'
  const d = new Date(String(v))
  return Number.isNaN(d.getTime()) ? String(v) : d.toLocaleString()
}

async function load() {
  loading.value = true
  try {
    const params: Record<string, unknown> = { keyword: keyword.value }
    // ALL 走 all=true（维护页要看得到停用药）；ON/OFF 走显式 enabled 筛选
    if (statusFilter.value === 'ALL') params.all = true
    else params.enabled = statusFilter.value === 'ON'
    const resp = await client.get('/masterdata/drugs', { params })
    records.value = resp.data.data
  } finally {
    loading.value = false
  }
}

async function loadCategories() {
  categories.value = (await client.get('/masterdata/fee-categories')).data.data
}

function openEdit(row: Row) {
  current.value = row
  formCategory.value = (row.feeCategoryCode as string) ?? ''
  formSelfPay.value = !!row.selfPay
  dialogVisible.value = true
}

async function save() {
  saving.value = true
  try {
    await client.put(`/masterdata/drugs/${current.value.id}/attrs`, {
      feeCategoryCode: formCategory.value || null,
      selfPay: formSelfPay.value,
    })
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function openDisable(row: Row) {
  current.value = row
  disableReason.value = ''
  disableVisible.value = true
}

async function doDisable() {
  if (!disableReason.value.trim()) {
    ElMessage.warning('请填写停用原因')
    return
  }
  saving.value = true
  try {
    await client.put(`/masterdata/drugs/${current.value.id}/disable`, {
      reason: disableReason.value.trim(),
    })
    ElMessage.success('已停用')
    disableVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function doEnable(row: Row) {
  toggling.value = row.id
  try {
    await client.put(`/masterdata/drugs/${row.id}/enable`)
    ElMessage.success('已启用')
    await load()
  } finally {
    toggling.value = null
  }
}

onMounted(async () => {
  await Promise.all([load(), loadCategories()])
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
.filters { display: flex; gap: 8px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 12px; line-height: 1.7; }
.disable-info { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; margin-top: 2px; }
.disable-alert { margin-bottom: 12px; }
/* 停用行整体置灰：一眼可辨，且与"已停用"标签互为冗余提示 */
:deep(.disabled-row) { background: var(--el-fill-color-light); color: var(--el-text-color-placeholder); }
:deep(.disabled-row .cell) { color: var(--el-text-color-placeholder); }
</style>
