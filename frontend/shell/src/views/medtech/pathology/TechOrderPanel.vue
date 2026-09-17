<template>
  <!-- ============ 工位五：特检技术医嘱全院工作台（技师侧：按状态 / 类型 / 时间集中处理） ============ -->
  <!-- v64（2563 复核第二条）：页首这两条的正文都来自后端，模板里不再写死任何一句「完成规则」。
       修复前这里写死着 v58 的旧规则「点『完成』时若无已染色挂接切片就提示或拦截」，而实现自 v62 起
       对「已补取材待切片」三档全部放行；v63 生成的真规则只随 note 印在表格下方那行灰字里——
       于是同一屏上顶上说「没染色就拦」、底下说「三档都放行」。规则只能有一个来源：
       写死的那条删掉，真规则（gateRule = 后端 techDoneGateRule）搬到页首最显眼处。 -->
  <el-alert type="info" show-icon :closable="false" class="cav" :title="headNote" />
  <el-alert v-if="gateRule" type="warning" show-icon :closable="false" class="cav" :title="gateRule" />

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
        <el-tag size="small" :type="progressTag(row.progress)">{{ progressLabel(row) }}</el-tag>
        <span class="muted">　已染 {{ num(row.stained_count) }} / 挂接 {{ num(row.slide_count) }}</span>
        <!-- v60：补取材医嘱的「已出块」是 SAMPLED 的事实来源（path_block.tech_order_id，V167） -->
        <span v-if="row.tech_type === 'RESAMPLE'" class="muted">　已出块 {{ num(row.sampled_block_count) }}</span>
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
    <el-table-column label="医嘱号" width="90">
      <!-- v61（2563 复核）：流转节点备注里的「#12」此前在清单上无从对应——同一标本先取消一条 IHC CK7
           再下一条时，两行类型项目逐字相同，只有 id 分得开。补这一列把节点备注对回清单行。 -->
      <template #default="{ row }"><span class="code">#{{ fmt(row.id) }}</span></template>
    </el-table-column>
    <el-table-column label="蜡块" min-width="150" show-overflow-tooltip>
      <template #default="{ row }">
        <!-- v60：「蜡块」列是下达时指定的块 + 为本医嘱补出的块 + 挂接切片所在块的并集（blocks_derived）。
             v61（2563 复核）：「派生」标改读后端与并集同一处 SQL 算出的 blocks_derived_source，
             不再按 !block_code 二次推断——补取材挂接会把 block_id 回写为本次首块，那样判恒为 false，
             于是补出 2 块时显示「P-3、P-4」却不带「派生」标，与「医师指定了这两块」同形。 -->
        <span class="code">{{ fmt(row.blocks_derived ?? row.block_code) }}</span>
        <el-tag v-if="row.blocks_derived_source === 'DERIVED'" size="small" type="info">派生</el-tag>
        <el-tag v-else-if="row.blocks_derived_source === 'MIXED'" size="small" type="warning">含派生</el-tag>
      </template>
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
    <el-table-column label="挂接切片染色" min-width="150" show-overflow-tooltip>
      <template #default="{ row }">
        <!-- v59：挂接切片的实际染色类型 / 项目（后端去重汇总）；v60 改读中文版 attached_stain_name（如「免疫组化 CK7 ×2」），
             英文枚举版 attached_stain 只作旧后端回落；无挂接为「—」 -->
        {{ fmt(row.attached_stain_name ?? row.attached_stain) }}
      </template>
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
 * <p>v59（2563 一致性）：清单多了「挂接切片染色」列（后端 attached_stain：挂接切片按染色类型 / 项目去重计数的汇总，
 * 如「IHC CK7 ×2」）——修复前三处清单 / 穿透都不回挂接切片的实际染色，挂错的片子在清单上看不出来。
 *
 * <p>v61（2563 复核）：「派生」标改读后端 blocks_derived_source（ORDERED / DERIVED / MIXED，与并集同一处 SQL 算出），
 * 不再按 !block_code 二次推断——补取材挂接会把 block_id 回写为本次首块，那样判恒为 false，页面与本说明不符；
 * 同时补「医嘱号」列，把流转节点备注里的「#12」对回清单行。
 *
 * <p>v60（2563 尾）：「蜡块」列改读 blocks_derived（下达指定块 + 为本医嘱补出的块 + 挂接切片所在块的并集）、
 * 「挂接切片染色」列改读中文版 attached_stain_name（如「免疫组化 CK7 ×2」，v59 是后端 SQL 直接拼的英文枚举）、
 * 进度增第六态 SAMPLED（已补取材待切片：补取材医嘱经取材 append 挂接出了蜡块、尚未切片，V167 path_block.tech_order_id）。
 *
 * <p>v64（2563 复核，本屏两条）：①「完成」的成功提示<b>原样印后端的 doneRemark</b>，不再自己挑字段重拼
 * （修复前把补取材医嘱唯一的执行证据「已出块 N」整句抹掉，与同一工作台流转节点里的备注两套口径）；
 * ②页首那条写死的完成规则删掉，改印后端下发的 {@code techDoneGateRule}，并<b>搬到页首</b>——
 * 修复前写死的旧规则在页首最显眼处、真规则被印在表格下方的灰字里，同一屏两句互相打架。
 * 同时「进度分哪几档」改照后端 {@code progressStates} 列，表格下方那整段后端 note 不再上屏
 * （它是给调用方看的接口说明，带库列名、内部键名与迁移号）。
 *
 * <p>「打开报告」把标本 id 交给工作台切到诊断工位并直接打开抽屉：技师做完免疫组化后，
 * 病理医师要出补充报告的入口就在那里。
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../../api/client'
import { fmtDateTime } from '../../../utils/date'
import { TECH_STATUSES, fmt, fmtTime, num, techStatusName, techStatusTag, typeName, type Row } from './format'

const emit = defineEmits<{ (e: 'changed'): void; (e: 'open-specimen', specimenId: number): void }>()

const rows = ref<Row[]>([])
const loading = ref(false)
const truncated = ref(false)
const limit = ref(100)
const range = ref<[string, string] | null>(null)
/** 完成 gate 的规则原文（后端 techDoneGateRule，与它的判定同一张表生成）——页首原样印这一句 */
const gateRule = ref('')
/** 执行进度分哪几档（后端 progressStates：code / name / inProgress）——页首照它列，页面不写死态名 */
const progressStates = ref<Row[]>([])
const query = reactive({ status: 'ORDERED', techType: '', urgentOnly: false, dateField: 'ORDERED', keyword: '' })

const techTypes = ref<Row[]>([])

async function loadDict() {
  const d = (await client.get('/pathology/report/tech-orders/dict')).data.data as Row
  techTypes.value = (d.techTypes ?? []) as Row[]
  progressStates.value = (d.progressStates ?? []) as Row[]
}

/** 「还没完成」的那几档的中文名（后端 inProgress 标好，前端不自己挑） */
const inProgressNames = computed(() => progressStates.value
  .filter((s) => s.inProgress === true).map((s) => String(s.name)))

/**
 * 页首第一句：本屏是干什么的。
 * 「进度分哪几档」照后端下发的 progressStates 列（六态派生是唯一事实源），模板里不写死一份；
 * 完成规则一个字都不在这里说——它由后端 techDoneGateRule 下发，单独印在下面那条里。
 * 屏上只印中文态名，派生用的编码不上屏。
 */
const headNote = computed(() => '全院视角：不分标本列出深切 / 重切 / 补取材 / 免疫组化 / 特殊染色 / 分子病理的技术医嘱。'
  + '默认只看「待执行」——这是技师今天要做的活；历史请显式切到「全部状态」。'
  + '距开单小时数是原始事实，本页不判超时。取消须填写取消原因（取消人 / 取消时刻 / 取消原因留痕，与下达原因分列）。'
  + (inProgressNames.value.length
    ? `「进度」由挂接切片与补取材已出块派生，不是手工标记，未完成的分 ${inProgressNames.value.length} 档：`
      + `${inProgressNames.value.join(' / ')}。`
    : '')
  + '「挂接切片染色」是挂接切片的实际染色类型 / 项目汇总——挂接时已按医嘱的类型 / 项目校验过。'
  + '「蜡块」列在医嘱未指定蜡块时，按补取材已出块与挂接切片所在块派生并标「派生」；'
  + '早年补取材的蜡块没有采集归属医嘱，那些历史医嘱仍显示「—」与「待切片」。')

function techName(v: unknown): string {
  const hit = techTypes.value.find((t) => String(t.value) === String(v))
  return hit ? String(hit.label) : String(v ?? '—')
}

function countOf(status: string): number {
  return rows.value.filter((r) => r.status === status).length
}

/**
 * 执行进度六态的标签色（v58 五态 + v60 SAMPLED）：进度由后端按挂接切片 / 挂接蜡块派生（PathologyReportController.techProgress），
 * 前端只画不算——待切片灰、已补取材待切片黄（补取材已出块、还没切片，与切片中同为「在做」）、切片中黄、
 * 已染色待确认蓝（可以点完成了）、已完成绿、已取消灰。
 */
function progressTag(v: unknown): 'primary' | 'success' | 'warning' | 'info' {
  return v === 'SAMPLED' || v === 'SECTIONING' ? 'warning' : v === 'STAINED' ? 'primary' : v === 'DONE' ? 'success' : 'info'
}

/**
 * 进度中文：后端 progress_name 为准；没有该键时按编码回落到字典端点下发的那份态名。
 * v64：回落表不再在这里写死一份——写死的第二份态名正是「宣告三态、屏上打出第四态」的病根
 * （④ 诊断页页首那句话）。两处态名从此只有 progressStates 一个来源。
 */
function progressLabel(row: Row): string {
  const name = row.progress_name
  if (name) return String(name)
  const code = String(row.progress ?? '')
  const hit = progressStates.value.find((s) => String(s.code) === code)
  return hit ? String(hit.name) : fmt(code)
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
    // v64：页首那条完成规则原样印后端下发的这一句；整段 note 是给调用方看的接口说明，不上屏
    gateRule.value = String(d.techDoneGateRule ?? '')
  } finally {
    loading.value = false
  }
}

async function done(row: Row) {
  const d = (await client.put(`/pathology/report/tech-orders/${Number(row.id)}/done`, null)).data.data as Row
  // v64（2563 复核第一条）：原样印后端给的那一句（doneRemark）——它与写进 TECH_DONE 流转节点的备注
  // 是同一个字符串（同一个变量拼一次）。修复前这里自己挑 slideCount / stainedCount 重拼一句，
  // 把补取材医嘱唯一的执行证据「已出块 2」整句抹掉：屏上读作「什么都没做就点了完成」，
  // 而同一个工作台切到流转节点看同一条，备注却写着「已出块 2」——同一次状态变更两套口径。
  ElMessage.success(String(d.doneRemark ?? ''))
  // 有缺口仍放行时后端回带 warnings，逐条提示；被拦下时由 client 统一报错
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
