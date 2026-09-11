<template>
  <!-- ============ 工位六：流转轨迹与流转异常（不分诊断状态；超时未流转 / 跳节点可筛） ============ -->
  <el-tabs v-model="tab">
    <!-- ---------------- 流转异常 ---------------- -->
    <el-tab-pane name="anomalies" :label="`流转异常（${anomalyRows.length}）`">
      <el-form inline size="small">
        <el-form-item label="类别">
          <el-select v-model="aq.kind" style="width: 190px" @change="loadAnomalies">
            <el-option value="ALL" label="全部四类" />
            <el-option v-for="k in ANOMALY_KINDS" :key="k.value" :label="k.label" :value="k.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="停滞阈值(小时)">
          <el-input-number v-model="aq.stallHours" :min="1" :max="8784" :controls="false" style="width: 90px" />
        </el-form-item>
        <el-form-item label="标本类别">
          <el-select v-model="aq.specimenType" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="t in SPECIMEN_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="发生日期（跳节点三类）">
          <el-date-picker v-model="aRange" type="daterange" unlink-panels value-format="YYYY-MM-DD"
                          range-separator="至" start-placeholder="起" end-placeholder="止"
                          :clearable="false" style="width: 230px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="aLoading" @click="loadAnomalies">筛选</el-button>
        </el-form-item>
      </el-form>

      <div class="bar">
        <el-tag v-for="k in ANOMALY_KINDS" :key="k.value" size="small" :type="anomalyTag(k.value)" effect="plain">
          {{ k.label }}：{{ num(counts[k.value]) }}
        </el-tag>
        <span class="muted">各类总数不受本页 {{ aLimit }} 条上限截断；停滞阈值 {{ aq.stallHours }} 小时由本页显式给出，不是配置项</span>
      </div>
      <el-alert v-if="aTruncated" type="warning" show-icon :closable="false" class="cav"
                :title="`命中超过 ${aLimit} 条，仅显示前 ${aLimit} 条（不做翻页）；请收窄条件`" />

      <el-table :data="anomalyRows" v-loading="aLoading" size="small" border stripe max-height="440">
        <el-table-column label="异常" width="160">
          <template #default="{ row }">
            <el-tag size="small" :type="anomalyTag(row.kind)">{{ row.kind_name ?? anomalyName(row.kind) }}</el-tag>
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
        <el-table-column label="发生时刻" width="150">
          <template #default="{ row }">{{ fmtTime(row.anchor_at) }}</template>
        </el-table-column>
        <el-table-column label="最近环节 / 停滞" width="170">
          <template #default="{ row }">
            <template v-if="row.kind === 'STALLED'">
              {{ row.last_node_name ?? nodeName(row.last_node ?? 'RECEIVE') }}
              <span class="muted">　{{ fmt(row.hours) }} 小时</span>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ fmt(row.detail) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openTrail(Number(row.specimen_id))">查看轨迹</el-button>
            <el-button link type="primary" size="small" @click="emit('open-specimen', Number(row.specimen_id))">
              打开报告</el-button>
          </template>
        </el-table-column>
        <template #empty>该条件下无流转异常</template>
      </el-table>
      <p v-if="aNote" class="muted">{{ aNote }}</p>
    </el-tab-pane>

    <!-- ---------------- 轨迹查询 ---------------- -->
    <el-tab-pane name="lookup" label="轨迹查询">
      <el-form inline size="small">
        <el-form-item label="关键词">
          <el-input v-model="lq.keyword" clearable placeholder="条码 / 病理号 / 患者 / 患者号"
                    style="width: 220px" @keyup.enter="lookup" />
        </el-form-item>
        <el-form-item label="核收日期">
          <el-date-picker v-model="lRange" type="daterange" unlink-panels value-format="YYYY-MM-DD"
                          range-separator="至" start-placeholder="起" end-placeholder="止" clearable
                          style="width: 230px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="lLoading" @click="lookup">查询</el-button>
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" class="cav"
                title="不限诊断状态：已核收、未拒收的标本都在这里能找到（走阅片列表的 scope=any 档）。已拒收标本请到「① 登记 → 标本检索」看。" />
      <el-alert v-if="lTruncated" type="warning" show-icon :closable="false" class="cav"
                :title="`命中超过 ${lLimit} 条，仅显示前 ${lLimit} 条（不做翻页）；请收窄条件`" />
      <el-table :data="lookupRows" v-loading="lLoading" size="small" border stripe max-height="440">
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
          <template #default="{ row }">{{ typeName(row.specimen_type) }}</template>
        </el-table-column>
        <el-table-column label="报告进度" width="200">
          <template #default="{ row }">
            <el-tag size="small" :type="reportStage(row).tag">{{ reportStage(row).label }}</el-tag>
            <span class="muted" style="margin-left: 4px">{{ fmtTime(row.report_issued_at ?? row.diagnosed_at) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="核收时刻" width="150">
          <template #default="{ row }">{{ fmtTime(row.received_at) }}</template>
        </el-table-column>
        <el-table-column label="蜡块 / 切片 / 已染色" width="140">
          <template #default="{ row }">
            {{ num(row.block_count) }} / {{ num(row.slide_count) }} / {{ num(row.stained_slide_count) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openTrail(Number(row.id))">查看轨迹</el-button>
            <el-button link type="primary" size="small" @click="emit('open-specimen', Number(row.id))">
              打开报告</el-button>
          </template>
        </el-table-column>
        <template #empty>输入关键词后查询</template>
      </el-table>
    </el-tab-pane>
  </el-tabs>

  <!-- ============ 轨迹抽屉 ============ -->
  <el-drawer v-model="drawer" size="70%" :title="drawerTitle">
    <div v-loading="tLoading">
      <el-descriptions :column="4" border size="small" class="cav">
        <el-descriptions-item label="病理号"><span class="code">{{ fmt(tSpecimen.path_no) }}</span></el-descriptions-item>
        <el-descriptions-item label="条码"><span class="code">{{ fmt(tSpecimen.barcode) }}</span></el-descriptions-item>
        <el-descriptions-item label="患者">{{ fmt(tSpecimen.patient_name) }} {{ fmt(tSpecimen.patient_no) }}</el-descriptions-item>
        <el-descriptions-item label="类别">{{ typeName(tSpecimen.specimen_type) }}</el-descriptions-item>
        <el-descriptions-item label="核收">{{ fmtTime(tSpecimen.received_at) }}</el-descriptions-item>
        <el-descriptions-item label="写完诊断">{{ fmtTime(tSpecimen.diagnosed_at) }}</el-descriptions-item>
        <el-descriptions-item label="签发">{{ fmtTime(tSpecimen.report_issued_at) }}</el-descriptions-item>
        <el-descriptions-item label="蜡块 / 切片 / 已染色">
          {{ num(tSpecimen.block_count) }} / {{ num(tSpecimen.slide_count) }} / {{ num(tSpecimen.stained_slide_count) }}
        </el-descriptions-item>
        <!-- v60（2530 尾）：登记时录入的标本描述。轨迹头 specimen_desc 由车道 B 加；B 未合入前回落同抽屉已取的 GET /grossing/{id} 头（本就带该列），两处都没有才「—」 -->
        <el-descriptions-item label="标本描述" :span="4">{{ fmt(tSpecimen.specimen_desc ?? gSpecimen.specimen_desc) }}</el-descriptions-item>
      </el-descriptions>

      <el-alert :type="tAnomalies.length ? 'warning' : 'success'" show-icon :closable="false" class="cav"
                :title="tAnomalies.length
                  ? `本标本命中 ${tAnomalies.length} 项流转异常（停滞阈值 ${fmt(trail.stallHours)} 小时）`
                  : `未命中流转异常（停滞阈值 ${fmt(trail.stallHours)} 小时）；最近环节 ${fmt(trail.lastNodeName ?? '核收')}，距今 ${fmt(trail.hoursSinceLastNode)} 小时`">
        <div v-for="a in tAnomalies" :key="String(a.kind)">
          <el-tag size="small" :type="anomalyTag(a.kind)">{{ a.kind_name ?? anomalyName(a.kind) }}</el-tag>
          <span style="margin-left: 6px">{{ fmt(a.detail) }}</span>
        </div>
      </el-alert>

      <el-alert type="info" :closable="false" class="cav"
                title="没打点的环节根本不出现在时间线里，而不是显示为 0——行的缺席本身就是「这个环节没在系统里打点」的信号。相邻环节间隔是原始事实，各环节时限阈值归病理质控页定义，本页不判超时。" />

      <el-timeline v-if="tNodes.length">
        <el-timeline-item v-for="n in tNodes" :key="Number(n.id)" :timestamp="fmtTime(n.occurred_at)" placement="top">
          <b>{{ n.node_name ?? nodeName(n.node) }}</b>
          <span class="muted" style="margin-left: 8px">{{ fmt(n.operator_name) }}</span>
          <span v-if="n.hours_since_prev != null" class="muted" style="margin-left: 8px">
            距上一环节 {{ fmt(n.hours_since_prev) }} 小时</span>
          <span v-else class="muted" style="margin-left: 8px">距核收 {{ fmt(n.hours_from_receive) }} 小时</span>
          <div v-if="n.remark" class="muted">{{ n.remark }}</div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="该标本尚无流转打点" :image-size="50" />
      <p v-if="trail.note" class="muted">{{ trail.note }}</p>

      <!-- ============ 大体所见修订（v58，2530：path_gross_revision 留痕，按 seq 列 old→new） ============ -->
      <h4>大体所见修订</h4>
      <div v-loading="gLoading">
        <!-- v59：字段随版本走——标出字段属于第几版；与文本不同版（诊断只改文本）时明说，以文本为准 -->
        <el-tag v-if="gross.fieldsAvailable === true" size="small" :type="gross.fieldsCurrent === false ? 'warning' : 'success'">
          第 {{ fmt(gross.fieldsRevisionSeq) }} 版字段级记录（{{ grossFields.length }} 项）{{
            gross.fieldsCurrent === false ? `；${gross.fieldsNote || `文本已在第 ${fmt(gross.textRevisionSeq)} 版修订，以文本为准`}` : '，与当前文本同版' }}</el-tag>
        <el-tag v-else size="small" type="info">历史标本，无字段级记录（或本次取材只写了自由文本；不从文本反解析）</el-tag>
        <el-table v-if="grossRevisions.length" :data="grossRevisions" size="small" border max-height="320"
                  style="margin-top: 6px" row-key="seq">
          <!-- v60（2530 尾）：每版字段可展开调阅——fieldsByRevision（车道 B 契约）按 revisionSeq 对上本行 seq；
               对不上或空即「本版未填写字段」，不从文本反解析。B 未合入前只有最新字段版（fields / fieldsRevisionSeq）能对上 -->
          <el-table-column type="expand" width="40">
            <template #default="{ row }">
              <div class="rev-fields">
                <template v-if="revisionFields(row.seq).length">
                  <el-tag size="small" type="success">第 {{ fmt(row.seq) }} 版字段级记录（{{ revisionFields(row.seq).length }} 项）</el-tag>
                  <span v-if="gross.fieldsByRevision == null" class="muted" style="margin-left: 6px">后端未回 fieldsByRevision：仅最新字段版可展开</span>
                  <el-table :data="revisionFields(row.seq)" size="small" border style="margin-top: 4px; max-width: 640px">
                    <el-table-column label="#" width="50">
                      <template #default="{ row: f }">{{ fmt(f.seq) }}</template>
                    </el-table-column>
                    <el-table-column label="字段" width="140">
                      <template #default="{ row: f }">{{ fmt(f.label) }}</template>
                    </el-table-column>
                    <el-table-column label="内容" min-width="200" show-overflow-tooltip>
                      <template #default="{ row: f }">{{ fmt(f.value) }}</template>
                    </el-table-column>
                  </el-table>
                </template>
                <span v-else class="muted">
                  本版未填写字段{{ gross.fieldsByRevision == null
                    ? '（后端未回 fieldsByRevision：被取代版本的字段暂不可调阅，仅最新字段版可展开）'
                    : '（只写了自由文本，或该版由诊断端点修订文本、字段留在上一版）' }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="60">
            <template #default="{ row }">{{ fmt(row.seq) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="revisionTag(row.source)">{{ row.sourceName ?? revisionSource(row.source) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="模板" width="110">
            <template #default="{ row }"><span class="code">{{ fmt(row.templateCode) }}</span></template>
          </el-table-column>
          <el-table-column label="时刻" width="150">
            <template #default="{ row }">{{ fmtDateTime(row.changedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作人" width="110">
            <template #default="{ row }">{{ fmt(row.changedByName) }}</template>
          </el-table-column>
          <el-table-column label="修订前 → 修订后" min-width="320">
            <template #default="{ row }">
              <div class="rev"><span class="muted">前：</span>{{ row.oldText == null ? '（无：首次写入）' : String(row.oldText) }}</div>
              <div class="rev"><span class="muted">后：</span>{{ fmt(row.newText) }}</div>
            </template>
          </el-table-column>
        </el-table>
        <p v-else class="muted">无</p>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * 工位六：流转轨迹与流转异常（v55 车道 R1，2522）。
 *
 * <p>轨迹此前只在诊断抽屉的「流转节点」页签里渲染，而抽屉进不去（阅片列表写死未诊断）。
 * 本面板对接 {@code GET /api/pathology/process/anomalies}（异常筛选）与
 * {@code GET /api/pathology/process/trail/{specimenId}}（轨迹），
 * 轨迹查询走阅片列表新放开的 {@code scope=any} 档——不分诊断状态。
 *
 * <p><b>STALLED 不受日期窗约束</b>：它是此刻的积压，三个月前核收后没人碰的标本正是最该被看见的。
 * 日期窗只约束跳节点三类（按异常发生时刻）。停滞阈值是本页显式给出的参数，不是配置项——
 * 后端在返回体回显，页面上也写明，不让一个看不见的默认值决定谁算「超时」。
 */
import { computed, reactive, ref } from 'vue'
import client from '../../../api/client'
import { fmtDateTime } from '../../../utils/date'
import {
  ANOMALY_KINDS, SPECIMEN_TYPES, anomalyName, anomalyTag, defaultRange, fmt, fmtTime, nodeName, num,
  reportStage, typeName, type Row,
} from './format'

const emit = defineEmits<{ (e: 'changed'): void; (e: 'open-specimen', specimenId: number): void }>()

const tab = ref('anomalies')

/* ---------------- 流转异常 ---------------- */
const anomalyRows = ref<Row[]>([])
const counts = ref<Record<string, unknown>>({})
const aLoading = ref(false)
const aTruncated = ref(false)
const aLimit = ref(100)
const aNote = ref('')
const aRange = ref<[string, string]>(defaultRange())
const aq = reactive({ kind: 'ALL', stallHours: 24, specimenType: '' })

async function loadAnomalies() {
  aLoading.value = true
  try {
    const d = (await client.get('/pathology/process/anomalies', {
      params: {
        kind: aq.kind,
        stallHours: aq.stallHours,
        specimenType: aq.specimenType || undefined,
        from: aRange.value?.[0] || undefined,
        to: aRange.value?.[1] || undefined,
      },
    })).data.data as Row
    anomalyRows.value = (d.items ?? []) as Row[]
    counts.value = (d.counts ?? {}) as Record<string, unknown>
    aTruncated.value = d.truncated === true
    aLimit.value = num(d.limit) || 100
    aNote.value = String(d.note ?? '')
  } finally {
    aLoading.value = false
  }
}

/* ---------------- 轨迹查询（scope=any） ---------------- */
const lookupRows = ref<Row[]>([])
const lLoading = ref(false)
const lTruncated = ref(false)
const lLimit = ref(100)
const lRange = ref<[string, string] | null>(null)
const lq = reactive({ keyword: '' })

async function lookup() {
  lLoading.value = true
  try {
    const d = (await client.get('/pathology/report/worklist', {
      params: {
        scope: 'any',
        keyword: lq.keyword || undefined,
        from: lRange.value?.[0] || undefined,
        to: lRange.value?.[1] || undefined,
      },
    })).data.data as Row
    lookupRows.value = (d.items ?? []) as Row[]
    lTruncated.value = d.truncated === true
    lLimit.value = num(d.limit) || 100
  } finally {
    lLoading.value = false
  }
}

/* ---------------- 轨迹抽屉 ---------------- */
const drawer = ref(false)
const tLoading = ref(false)
const trail = ref<Row>({})
const tSpecimen = computed<Row>(() => (trail.value.specimen ?? {}) as Row)
const tNodes = computed<Row[]>(() => (trail.value.nodes ?? []) as Row[])
const tAnomalies = computed<Row[]>(() => (trail.value.anomalies ?? []) as Row[])
const drawerTitle = computed(
  () => `流转轨迹 — ${fmt(tSpecimen.value.path_no)}　${fmt(tSpecimen.value.patient_name)}`,
)

/* ---------------- 大体所见修订（v58，2530） ---------------- */
const gLoading = ref(false)
const gross = ref<Row>({})
const gSpecimen = computed<Row>(() => (gross.value.specimen ?? {}) as Row)   // v60：GET /grossing/{id} 的标本头（带 specimen_desc）
const grossFields = computed<Row[]>(() => (gross.value.fields ?? []) as Row[])
const grossRevisions = computed<Row[]>(() => (gross.value.revisions ?? []) as Row[])

/**
 * v60（2530 尾）：某一版修订的字段行。fieldsByRevision（车道 B 契约：[{revisionSeq, source, sourceName, templateCode,
 * fields:[{seq,label,value}]}]）按 revisionSeq 对上修订行的 seq；后端没回该键时只有最新字段版（fields / fieldsRevisionSeq）
 * 能对上，其余版一律空——不从文本反解析、不拿最新字段冒充旧版。
 */
function revisionFields(seq: unknown): Row[] {
  const raw = gross.value.fieldsByRevision
  if (Array.isArray(raw)) {
    const hit = (raw as Row[]).find((v) => Number(v.revisionSeq) === Number(seq))
    return (hit?.fields ?? []) as Row[]
  }
  const latestSeq = gross.value.fieldsRevisionSeq
  return latestSeq != null && Number(latestSeq) === Number(seq) ? grossFields.value : []
}

/** path_gross_revision.source 的三档（V166），与 chk_path_gross_revision_source 一致；后端 sourceName 优先，这里只是兜底 */
function revisionSource(v: unknown): string {
  const s = v == null ? '' : String(v)
  return s === 'GROSSING' ? '取材' : s === 'GROSSING_EDIT' ? '取材修订' : s === 'DIAGNOSE' ? '诊断' : s
}

function revisionTag(v: unknown): 'warning' | 'success' | 'info' {
  const s = v == null ? '' : String(v)
  return s === 'DIAGNOSE' ? 'warning' : s === 'GROSSING_EDIT' ? 'success' : 'info'
}

/**
 * 修订留痕单独取（GET /grossing/{id}）：它与轨迹是两个端点，任一失败不该把另一个的内容一起藏起来。
 * fieldsAvailable=false 就按后端说的显示「无字段级记录」，前端不从 grossFinding 文本猜字段。
 */
async function loadGross(specimenId: number) {
  gLoading.value = true
  gross.value = {}
  try {
    gross.value = (await client.get(`/pathology/process/grossing/${specimenId}`)).data.data as Row
  } catch {
    gross.value = {}   // 取不到就按「无记录」显示，由轨迹本身照常渲染
  } finally {
    gLoading.value = false
  }
}

async function openTrail(specimenId: number) {
  drawer.value = true
  tLoading.value = true
  void loadGross(specimenId)
  try {
    trail.value = (await client.get(`/pathology/process/trail/${specimenId}`, {
      params: { stallHours: aq.stallHours },
    })).data.data as Row
  } finally {
    tLoading.value = false
  }
}

defineExpose({ reload: loadAnomalies, openTrail })

void loadAnomalies()
</script>

<style scoped>
.cav { margin-bottom: 8px; }
.muted { color: #909399; font-size: 12px; }
.code { font-family: Consolas, Monaco, monospace; }
.bar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.rev { white-space: pre-wrap; word-break: break-word; }
.rev-fields { padding: 4px 8px 8px; }
h4 { margin: 12px 0 6px; }
</style>
