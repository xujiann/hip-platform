<template>
  <el-card>
    <div class="toolbar">
      <h3 style="margin:0">收费班结缴款单</h3>
      <div style="display:flex; gap:8px">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width:140px" @change="reload" />
        <el-button size="small" @click="reload">刷新</el-button>
      </div>
    </div>

    <!-- 收费员本人的班结：系统数只读、实点金额手填、差额自动算 -->
    <el-descriptions :column="3" border size="small">
      <el-descriptions-item label="系统收款">{{ money(preview.sysPaid) }}</el-descriptions-item>
      <el-descriptions-item label="系统退费">{{ money(preview.sysRefund) }}</el-descriptions-item>
      <el-descriptions-item label="系统应收净额">
        <b>{{ money(preview.sysNet) }}</b>
      </el-descriptions-item>
    </el-descriptions>

    <div class="declare">
      <span>实际点钞金额</span>
      <el-input-number v-model="declaredCash" :precision="2" :step="1" :min="0" :controls="false"
                       style="width:160px" :disabled="!!existing" />
      <span>差额</span>
      <el-tag :type="diffTag" size="large">{{ money(diff) }}</el-tag>
      <span class="hint">{{ diffHint }}</span>
    </div>
    <el-input v-model="note" type="textarea" :rows="2" style="margin-top:8px" :disabled="!!existing"
              placeholder="差额说明（有长款/短款时请写明原因，财务确认时会看到）" />

    <div style="margin-top:10px">
      <el-alert v-if="existing" :closable="false" type="info" show-icon
                :title="`${date} 的班结已提交（${statusText(String(existing.status))}），如需更正请联系财务`" />
      <el-button v-else type="primary" :loading="submitting" @click="submit">提交班结</el-button>
    </div>

    <div class="toolbar" style="margin-top:18px">
      <h4 style="margin:0">{{ isAdmin ? '全院班结（财务确认）' : '我的班结记录' }}</h4>
      <el-select v-model="statusFilter" size="small" style="width:130px" clearable placeholder="全部状态"
                 @change="loadList">
        <el-option label="待确认" value="SUBMITTED" />
        <el-option label="已确认" value="CONFIRMED" />
      </el-select>
    </div>
    <el-table :data="rows" size="small" border>
      <el-table-column prop="shift_date" label="班结日" width="110" />
      <el-table-column v-if="isAdmin" prop="cashier" label="收费员" width="110" />
      <el-table-column prop="sys_paid" label="系统收款" width="100" />
      <el-table-column prop="sys_refund" label="系统退费" width="100" />
      <el-table-column prop="sys_net" label="应收净额" width="100" />
      <el-table-column prop="declared_cash" label="实点金额" width="100" />
      <el-table-column label="差额" width="100">
        <template #default="{ row }">
          <span :class="{ bad: Number(row.diff) !== 0 }">{{ money(row.diff) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'CONFIRMED' ? 'success' : 'warning'" size="small">
            {{ statusText(row.status as string) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="note" label="说明" min-width="140" show-overflow-tooltip />
      <el-table-column prop="confirmed_by_name" label="确认人" width="100" />
      <el-table-column v-if="isAdmin" label="操作" width="90">
        <template #default="{ row }">
          <el-button v-if="row.status === 'SUBMITTED'" link type="primary" size="small"
                     @click="confirmShift(row)">确认</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { todayLocal } from '../../utils/date'
import { useAuthStore } from '../../stores/auth'

interface Preview {
  sysPaid?: number | string
  sysRefund?: number | string
  sysNet?: number | string
  existing?: Record<string, unknown> | null
}

const auth = useAuthStore()
const isAdmin = computed(() => !!auth.user?.roles?.includes('ADMIN'))

const date = ref(todayLocal())
const preview = ref<Preview>({})
const existing = ref<Record<string, unknown> | null>(null)
const declaredCash = ref<number>(0)
const note = ref('')
const submitting = ref(false)
const rows = ref<Record<string, unknown>[]>([])
const statusFilter = ref('')

function money(v: unknown): string {
  return Number(v ?? 0).toFixed(2)
}
function statusText(s: string): string {
  return ({ DRAFT: '草稿', SUBMITTED: '待确认', CONFIRMED: '已确认' } as Record<string, string>)[s] ?? s
}

/** 差额 = 实点 - 系统净额（长款为正、短款为负）——与后端 diff 同一算式 */
const diff = computed(() => Number(declaredCash.value ?? 0) - Number(preview.value.sysNet ?? 0))
const diffTag = computed(() => (Math.abs(diff.value) < 0.005 ? 'success' : 'danger'))
const diffHint = computed(() => {
  if (Math.abs(diff.value) < 0.005) return '账实相符'
  return diff.value > 0 ? '长款（实点多于系统），请在说明中写明原因' : '短款（实点少于系统），请在说明中写明原因'
})

async function loadPreview() {
  const d = (await client.get('/finance/shift-close/preview', { params: { date: date.value } })).data.data as Preview
  preview.value = d
  existing.value = (d.existing ?? null) as Record<string, unknown> | null
  if (existing.value) {
    declaredCash.value = Number(existing.value.declared_cash ?? 0)
    note.value = String(existing.value.note ?? '')
  } else {
    // 默认填成账实相符，收费员只需在有差额时改动
    declaredCash.value = Number(d.sysNet ?? 0)
    note.value = ''
  }
}

async function loadList() {
  rows.value = (await client.get('/finance/shift-close', {
    params: { date: date.value, status: statusFilter.value || undefined },
  })).data.data
}

async function reload() {
  await Promise.all([loadPreview(), loadList()])
}

async function submit() {
  if (Math.abs(diff.value) >= 0.005 && !note.value.trim()) {
    ElMessage.warning('存在差额，请先填写差额说明')
    return
  }
  submitting.value = true
  try {
    await client.post('/finance/shift-close', {
      date: date.value,
      declaredCash: Number(declaredCash.value ?? 0).toFixed(2),
      note: note.value,
    })
    ElMessage.success('班结已提交，等待财务确认')
    await reload()
  } finally {
    submitting.value = false
  }
}

async function confirmShift(row: Record<string, unknown>) {
  const ok = await ElMessageBox.confirm(
    `确认 ${row.cashier} ${row.shift_date} 的缴款单？差额 ${money(row.diff)} 元。确认后不可撤销。`,
    '财务确认', { type: 'warning' }).catch(() => null)
  if (!ok) return
  await client.put(`/finance/shift-close/${row.id}/confirm`)
  ElMessage.success('已确认')
  await reload()
}

onMounted(reload)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.declare { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
.hint { color: #909399; font-size: 12px; }
.bad { color: #f56c6c; font-weight: 600; }
</style>
