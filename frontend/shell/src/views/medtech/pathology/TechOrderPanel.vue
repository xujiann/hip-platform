<template>
  <!-- ============ 工位五：特检技术医嘱全院工作台（技师侧：按状态 / 类型 / 时间集中处理） ============ -->
  <el-alert type="info" show-icon :closable="false" class="cav"
            title="全院视角：不分标本列出深切 / 重切 / 补取材 / 免疫组化 / 特殊染色 / 分子病理的技术医嘱。默认只看「待执行」——这是技师今天要做的活；历史请显式切到「全部状态」。距开单小时数是原始事实，本页不判超时。取消须填写取消原因（取消人 / 取消时刻 / 取消原因留痕，与下达原因分列）。「进度」由挂接切片派生（待切片 / 切片中 / 已染色待确认），不是手工标记；点「完成」时若无已染色挂接切片，按 gate emr.gate.pathology.techdone 提示（warn）或拦截（block，5273）。" />

  <el-form inline size="small">
    <el-form-item label="状态">
      <el-select v-model="query.status" style="width: 120px" @change="load">
        <el-option v-for="s in TECH_STATUSES" :key="s.value" :label="s.label" :value="s.value" />
        <el-option value="ALL" label="全部状态" />
      </el-select>
    </el-form-item>
    <el-form-item label="技术类型">
      <el-select v-model="query.techType" clearable placeholder="全部" style="width: 130px">
        <el-option v-for="t in techTypes" :key="String(t.value)" :value="String(t.value)" :label="String(t.label)" />
      </el-select>
    </el-form-item>
    <el-form-item label="仅加急">
      <el-switch v-model="query.urgentOnly" />
    </el-form-item>
    <el-form-item label="日期口径">
      <el-select v-model="query.dateField" style="width: 130px">
        <el-option value="ORDERED" label="按开单时刻" />
        <el-option value="DONE" label="按完成时刻" />
      </el-select>
    </el-form-item>
    <el-form-item label="日期区间">
      <el-date-picker v-model="range" type="daterange" unlink-panels value-format="YYYY-MM-DD"
                      range-separator="至" start-placeholder="起" end-placeholder="止" clearable
                      style="width: 230px" />
    </el-form-item>
    <el-form-item label="关键词">
      <el-input v-model="query.keyword" clearable placeholder="条码 / 病理号 / 患者 / 项目名"
                style="width: 190px" @keyup.enter="load" />
    </el-form-item>
    <el-form-item>
      <el-button type="primary" :loading="loading" @click="load">查询</el-button>
    </el-form-item>
  </el-form>

  <el-alert v-if="truncated" type="warning" show-icon :closable="false" class="cav"
            :title="`命中超过 ${limit} 条，仅显示前 ${limit} 条（不做翻页）；请收窄条件`" />

  <div class="bar">
    <span>本次命中 <b>{{ rows.length }}</b> 条</span>
    <span class="muted">待执行 {{ countOf('ORDERED') }} / 已完成 {{ countOf('DONE') }} / 已取消 {{ countOf('CANCELLED') }}
      （仅统计本页已显示的行）</span>
  </div>

  <el-table :data="rows" v-loading="loading" size="small" border stripe max-height="480">
    <el-table-column label="状态" width="90">
      <template #default="{ row }">
        <el-tag size="small" :type="techStatusTag(row.status)">{{ techStatusName(row.status) }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="类型 / 项目" width="180">
      <template #default="{ row }">
        {{ techName(row.tech_type) }}
        <span v-if="row.tech_item" class="muted">　{{ row.tech_item }}</span>
      </template>
    </el-table-column>
    <el-table-column label="进度" width="170">
      <template #default="{ row }">
        <el-tag size="small" :type="progressTag(row.progress)">{{ fmt(row.progress_name ?? row.progress) }}</el-tag>
        <span class="muted">　已染 {{ num(row.stained_count) }} / 挂接 {{ num(row.slide_count) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="病理号 / 条码" width="180">
      <template #default="{ row }">
        <span class="code">{{ fmt(row.path_no) }}</span><br>
        <span class="code muted">{{ fmt(row.barcode) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="患者" width="140">
      <template #default="{ row }">
        {{ fmt(row.patient_name) }} <span class="muted">{{ fmt(row.patient_no) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="类别" width="100">
      <template #default="{ row }">
        {{ typeName(row.specimen_type) }}
        <el-tag v-if="row.urgent === true" size="small" type="danger">急</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="蜡块" width="150">
      <template #default="{ row }"><span class="code">{{ fmt(row.block_code) }}</span></template>
    </el-table-column>
    <el-table-column label="开单" width="200">
      <template #default="{ row }">{{ fmt(row.ordered_by_name) }}　{{ fmtTime(row.ordered_at) }}</template>
    </el-table-column>
    <el-table-column label="距开单(小时)" width="110">
      <template #default="{ row }">{{ fmt(row.hours_since_ordered) }}</template>
    </el-table-column>
    <el-table-column label="完成" width="200">
      <template #default="{ row }">{{ fmt(row.done_by_name) }}　{{ fmtTime(row.done_at) }}</template>
    </el-table-column>
    <el-table-column label="原因" min-width="140" show-overflow-tooltip>
      <template #default="{ row }">{{ fmt(row.reason) }}</template>
    </el-table-column>
    <el-table-column label="取消" width="200">
      <template #default="{ row }">
        <template v-if="row.cancelled_at">{{ fmt(row.cancelled_by_name) }}　{{ fmtDateTime(row.cancelled_at) }}</template>
        <span v-else-if="row.status === 'CANCELLED'" class="muted">历史取消（时刻未采集）</span>
        <span v-else class="muted">—</span>
      </template>
    </el-table-column>
    <el-table-column label="取消原因" min-width="140" show-overflow-tooltip>
      <template #default="{ row }">{{ fmt(row.cancel_reason) }}</template>
    </el-table-column>
    <el-table-column label="挂接切片" width="90">
      <template #default="{ row }">{{ fmt(row.slide_count) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="190" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" size="small" :disabled="row.status !== 'ORDERED'"
                   @click="done(row)">完成</el-button>
        <el-button link type="danger" size="small" :disabled="row.status !== 'ORDERED'"
                   @click="cancel(row)">取消</el-button>
        <el-button link type="primary" size="small" @click="emit('open-specimen', Number(row.specimen_id))">
          打开报告</el-button>
      </template>
    </el-table-column>
    <template #empty>该条件下无特检技术医嘱</template>
  </el-table>
  <p v-if="note" class="muted">{{ note }}</p>
</template>

<script setup lang="ts">
/**
 * 工位五：特检技术医嘱全院工作台（v55 车道 R1，2563）。
 *
 * <p>对接 {@code GET /api/pathology/report/tech-orders} <b>不传 specimenId</b> 的全院清单分支——
 * 该分支 v48 就有，但此前全仓唯一调用点恒传 specimenId，技师看不到「今天全院有哪些免疫组化要做」。
 * 完成走既有 {@code PUT /tech-orders/{id}/done}；取消（v57，2563 留痕）改为弹框要取消原因并发
 * {@code {reason}}——后端 5271 拒空白 / 超 255 字，取消人 / 取消时刻 / 取消原因与 status 同一条 update 落库，
 * 下达原因不覆盖。清单显示取消时刻（{@code fmtDateTime}，带偏移才换算到业务时区）、取消原因与挂接切片数；
 * V163 之前取消的历史行三列为 NULL，显式标「历史取消」而不是画成空白。
 *
 * <p>v58（2563 三次核账）：清单多了「进度」列——后端按挂接切片派生的五态（progress / progress_name）与
 * 已染色 / 挂接计数，不是手工标记；「完成」的返回体带 slideCount / stainedCount / warnings，
 * warn 档放行时逐条 ElMessage.warning 提示，block 档 5273 由 client 统一报错。
 *
 * <p>「打开报告」把标本 id 交给工作台切到诊断工位并直接打开抽屉：技师做完免疫组化后，
 * 病理医师要出补充报告的入口就在那里。
 */
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../../api/client'
import { fmtDateTime } from '../../../utils/date'
import { TECH_STATUSES, fmt, fmtTime, num, techStatusName, techStatusTag, typeName, type Row } from './format'

const emit = defineEmits<{ (e: 'changed'): void; (e: 'open-specimen', specimenId: number): void }>()

const rows = ref<Row[]>([])
const loading = ref(false)
const truncated = ref(false)
const limit = ref(100)
const note = ref('')
const range = ref<[string, string] | null>(null)
const query = reactive({ status: 'ORDERED', techType: '', urgentOnly: false, dateField: 'ORDERED', keyword: '' })

const techTypes = ref<Row[]>([])

async function loadDict() {
  const d = (await client.get('/pathology/report/tech-orders/dict')).data.data as Row
  techTypes.value = (d.techTypes ?? []) as Row[]
}

function techName(v: unknown): string {
  const hit = techTypes.value.find((t) => String(t.value) === String(v))
  return hit ? String(hit.label) : String(v ?? '—')
}

function countOf(status: string): number {
  return rows.value.filter((r) => r.status === status).length
}

/**
 * 执行进度五态的标签色（v58）：进度由后端按挂接切片派生（PathologyReportController.techProgress），
 * 前端只画不算——待切片灰、切片中黄、已染色待确认蓝（可以点完成了）、已完成绿、已取消灰。
 */
function progressTag(v: unknown): 'primary' | 'success' | 'warning' | 'info' {
  return v === 'SECTIONING' ? 'warning' : v === 'STAINED' ? 'primary' : v === 'DONE' ? 'success' : 'info'
}

async function load() {
  loading.value = true
  try {
    const d = (await client.get('/pathology/report/tech-orders', {
      params: {
        // 刻意不传 specimenId：这是全院清单分支
        status: query.status,
        techType: query.techType || undefined,
        urgentOnly: query.urgentOnly || undefined,
        dateField: query.dateField,
        from: range.value?.[0] || undefined,
        to: range.value?.[1] || undefined,
        keyword: query.keyword || undefined,
      },
    })).data.data as Row
    rows.value = (d.items ?? []) as Row[]
    truncated.value = d.truncated === true
    limit.value = Number(d.limit ?? 100) || 100
    note.value = String(d.note ?? '')
  } finally {
    loading.value = false
  }
}

async function done(row: Row) {
  const d = (await client.put(`/pathology/report/tech-orders/${Number(row.id)}/done`, null)).data.data as Row
  ElMessage.success(`已标记完成：${techName(row.tech_type)} ${String(row.tech_item ?? '')}`
    + `（挂接 ${num(d.slideCount)} 片 / 已染色 ${num(d.stainedCount)}）`)
  // v58：warn 档放行时后端回带 warnings（无已染色挂接切片即确认完成），逐条提示；block 档 5273 走 client 统一报错
  for (const w of (d.warnings ?? []) as string[]) ElMessage.warning(w)
  await load()
  emit('changed')
}

async function cancel(row: Row) {
  const res = await ElMessageBox.prompt(
    `取消「${techName(row.tech_type)} ${String(row.tech_item ?? '')}」须填写取消原因（留痕：取消人 / 取消时刻 / 取消原因；下达原因不覆盖）`,
    '取消特检技术医嘱',
    {
      type: 'warning',
      inputType: 'textarea',
      inputPlaceholder: '如：临床已另行送检、标本量不足、医师撤回',
      inputValidator: (v: string) => {
        const t = (v ?? '').trim()
        if (!t) return '取消原因不能为空'
        if (t.length > 255) return '取消原因最多 255 字'
        return true
      },
    },
  ).catch(() => null)
  const reason = res?.value?.trim()
  if (!reason) return   // 空则不发：后端 5271 同样会拒，这里只是省一次往返
  await client.put(`/pathology/report/tech-orders/${Number(row.id)}/cancel`, { reason })
  ElMessage.success('已取消并留痕')
  await load()
  emit('changed')
}

defineExpose({ reload: load })

void loadDict()
void load()
</script>

<style scoped>
.cav { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.code { font-family: Consolas, Monaco, monospace; }
.bar { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
</style>
