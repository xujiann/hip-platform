<template>
  <div class="take-page">
    <el-card>
      <template #header>新建盘点单</template>
      <el-form label-width="72px">
        <el-form-item label="药品">
          <el-select v-model="selectedDrugIds" filterable remote multiple :remote-method="searchDrugs"
                     placeholder="搜索并选择要盘点的药品" style="width: 100%">
            <el-option v-for="d in drugOptions" :key="d.id as number"
                       :label="`${d.name}（账面 ${d.stock}）`" :value="d.id as number" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="remark" placeholder="选填" /></el-form-item>
        <el-button type="primary" :loading="creating" @click="createTake">建盘点单</el-button>
      </el-form>

      <el-divider>历史盘点单</el-divider>
      <el-table :data="takes" size="small" height="360" @row-click="(r: Record<string, unknown>) => openTake(r.id as number)">
        <el-table-column prop="takeNo" label="单号" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status as string)" size="small">{{ statusText(row.status as string) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="current">
      <template #header>
        <div class="detail-header">
          <span>{{ current.takeNo }}
            <el-tag :type="statusTag(current.status)" size="small">{{ statusText(current.status) }}</el-tag>
          </span>
          <span class="summary" v-if="current.countedLines">
            已盘 {{ current.countedLines }}/{{ current.lineCount }}　盘盈 {{ current.gainLines }}　盘亏 {{ current.lossLines }}　净盈亏
            <b :class="netClass(current.netDiff)">{{ current.netDiff > 0 ? '+' + current.netDiff : current.netDiff }}</b>
          </span>
        </div>
      </template>
      <el-table :data="current.lines" size="small" border height="440">
        <el-table-column prop="drugName" label="药品" />
        <el-table-column prop="bookQty" label="账面数" width="90" />
        <el-table-column label="实盘数" width="150">
          <template #default="{ row }">
            <el-input-number v-if="current!.status === 'DRAFT'" v-model="row.actualQty" :min="0"
                             size="small" controls-position="right" style="width: 120px" />
            <span v-else>{{ row.actualQty ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="盈亏" width="90">
          <template #default="{ row }">
            <span v-if="row.actualQty != null" :class="netClass((row.actualQty as number) - (row.bookQty as number))">
              {{ diffText((row.actualQty as number) - (row.bookQty as number)) }}
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="actions" v-if="current.status === 'DRAFT'">
        <el-button :loading="saving" @click="saveCounts">保存实盘数</el-button>
        <el-button type="primary" :loading="confirming" @click="confirmTake">确认调库</el-button>
        <el-button type="danger" plain :loading="cancelling" @click="cancelTake">作废</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

interface TakeLine { lineId: number; drugId: number; drugName: string; bookQty: number; actualQty: number | null; diff: number | null }
interface TakeView {
  id: number; takeNo: string; status: string; remark: string | null
  lines: TakeLine[]; lineCount: number; countedLines: number; gainLines: number; lossLines: number; netDiff: number
}

const drugOptions = ref<Record<string, unknown>[]>([])
const selectedDrugIds = ref<number[]>([])
const remark = ref('')
const takes = ref<Record<string, unknown>[]>([])
const current = ref<TakeView | null>(null)
const creating = ref(false)
const saving = ref(false)
const confirming = ref(false)
const cancelling = ref(false)

function statusText(s: string) { return { DRAFT: '草稿', CONFIRMED: '已确认', CANCELLED: '已作废' }[s] ?? s }
function statusTag(s: string) { return ({ DRAFT: 'info', CONFIRMED: 'success', CANCELLED: 'danger' } as Record<string, string>)[s] ?? 'info' }
function netClass(n: number) { return n > 0 ? 'gain' : n < 0 ? 'loss' : '' }
function diffText(n: number) { return n > 0 ? '+' + n : String(n) }

async function searchDrugs(kw: string) {
  const resp = await client.get('/masterdata/drugs', { params: { keyword: kw } })
  drugOptions.value = resp.data.data
}

async function loadTakes() {
  const resp = await client.get('/inventory/stock-takes')
  takes.value = resp.data.data
}

async function createTake() {
  if (selectedDrugIds.value.length === 0) {
    ElMessage.warning('请先选择要盘点的药品')
    return
  }
  creating.value = true
  try {
    const resp = await client.post('/inventory/stock-take', { drugIds: selectedDrugIds.value, remark: remark.value })
    current.value = resp.data.data
    selectedDrugIds.value = []
    remark.value = ''
    await loadTakes()
    ElMessage.success(`已建盘点单 ${current.value!.takeNo}`)
  } finally {
    creating.value = false
  }
}

async function openTake(id: number) {
  const resp = await client.get(`/inventory/stock-take/${id}`)
  current.value = resp.data.data
}

function pendingEntries() {
  // 只提交已录实盘数的行
  return (current.value?.lines ?? [])
    .filter((l) => l.actualQty != null)
    .map((l) => ({ drugId: l.drugId, actualQty: l.actualQty }))
}

async function saveCounts() {
  if (!current.value) return
  saving.value = true
  try {
    const resp = await client.post(`/inventory/stock-take/${current.value.id}/counts`, { entries: pendingEntries() })
    current.value = resp.data.data
    ElMessage.success('实盘数已保存')
  } finally {
    saving.value = false
  }
}

async function confirmTake() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm('确认后将按盈亏调整库存并记盘点流水，且盘点单不可再改。是否继续？', '确认调库', {
      confirmButtonText: '确认调库', cancelButtonText: '取消', type: 'warning',
    })
  } catch {
    return   // 用户取消
  }
  confirming.value = true
  try {
    // 先保存最新实盘数，再确认，避免只在输入框改了未保存
    await client.post(`/inventory/stock-take/${current.value.id}/counts`, { entries: pendingEntries() })
    const resp = await client.post(`/inventory/stock-take/${current.value.id}/confirm`)
    current.value = resp.data.data
    await loadTakes()
    ElMessage.success('已确认并调整库存')
  } finally {
    confirming.value = false
  }
}

async function cancelTake() {
  if (!current.value) return
  cancelling.value = true
  try {
    const resp = await client.post(`/inventory/stock-take/${current.value.id}/cancel`)
    current.value = resp.data.data
    await loadTakes()
    ElMessage.success('盘点单已作废')
  } finally {
    cancelling.value = false
  }
}

onMounted(() => {
  searchDrugs('')
  loadTakes()
})
</script>

<style scoped>
.take-page { display: grid; grid-template-columns: 420px 1fr; gap: 12px; }
.detail-header { display: flex; justify-content: space-between; align-items: center; }
.summary { font-size: 13px; color: #666; }
.actions { margin-top: 12px; display: flex; gap: 8px; }
.gain { color: #67c23a; font-weight: 600; }
.loss { color: #f56c6c; font-weight: 600; }
</style>
