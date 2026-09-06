<template>
  <el-card>
    <template #header>
      <span style="font-weight: 600">病理质控看板</span>
      <el-tag type="info" size="small" style="margin-left: 10px">
        《病理专业医疗质量控制指标（2024 年版）》方向</el-tag>
      <el-date-picker v-model="range" type="daterange" size="small" unlink-panels
                      value-format="YYYY-MM-DD" range-separator="至"
                      start-placeholder="起始日" end-placeholder="截止日"
                      style="width: 250px; margin-left: 12px" @change="load" />
      <el-select v-model="picked" size="small" clearable placeholder="全部指标"
                 style="width: 300px; margin-left: 8px" @change="load">
        <el-option v-for="c in catalog" :key="String(c.code)" :value="String(c.code)"
                   :label="`${c.code}　${c.name}`">
          <span>{{ c.code }}　{{ c.name }}</span>
          <el-tag v-if="c.available !== true" type="warning" size="small" style="margin-left: 6px">
            缺数据源</el-tag>
        </el-option>
      </el-select>
      <el-button type="primary" size="small" :loading="loading" style="margin-left: 8px" @click="load">
        查询</el-button>
    </template>

    <!-- ============ 口径三处同源之一：页面 alert（另两处是端点 javadoc 与返回体 caveats） ============ -->
    <el-alert v-for="(c, i) in caveats" :key="i" type="warning" show-icon :closable="false" class="cav"
              :title="i === 0 ? '统计口径（请先看完这几条再看指标值）' : ''">
      <div>{{ c }}</div>
    </el-alert>

    <el-descriptions v-if="thresholds" :column="3" border size="small" class="cav" title="时限阈值与统计区间">
      <el-descriptions-item :label="`常规报告时限（${thresholds.routineHoursKey}）`">
        {{ fmt(thresholds.routineHours) }} 小时</el-descriptions-item>
      <el-descriptions-item :label="`冰冻报告时限（${thresholds.frozenMinutesKey}）`">
        {{ fmt(thresholds.frozenMinutes) }} 分钟</el-descriptions-item>
      <el-descriptions-item label="统计区间">
        {{ fmt(body?.from) }} 至 {{ fmt(body?.to) }}（{{ fmt(body?.days) }} 天）</el-descriptions-item>
      <el-descriptions-item label="节假日口径" :span="3">
        {{ fmt(thresholds.holidayNote) }}</el-descriptions-item>
      <el-descriptions-item label="接收及时率为何不给单一数字" :span="3">
        {{ fmt(thresholds.receiveThresholdNote) }}</el-descriptions-item>
    </el-descriptions>

    <!-- ============ 字段录入覆盖率：先看这一段再看指标值 ============ -->
    <template v-if="coverage">
      <h4>本时段字段录入覆盖率（「及时率 100%」很可能只是「仅 2 例录了时间」）</h4>
      <div v-for="sec in coverageSections" :key="sec.key" class="cov">
        <el-descriptions :column="4" border size="small" :title="sec.title">
          <el-descriptions-item v-for="item in sec.items" :key="item.key" :label="item.label">
            {{ item.text }}
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="sec.note" type="info" :closable="false" class="cav" :title="sec.note" />
      </div>
    </template>

    <!-- ============ 逐指标 ============ -->
    <div v-for="ind in indicators" :key="String(ind.code)" class="indicator">
      <div class="ind-head">
        <span class="ind-code">{{ ind.code }}</span>
        <span class="ind-name">{{ ind.name }}</span>
        <el-tag v-if="ind.available !== true" type="danger" size="small" style="margin-left: 8px">
          缺数据源</el-tag>
        <span v-if="ind.available === true" style="float: right">
          <el-button link type="primary" size="small" @click="openDetail(ind)">穿透明细</el-button>
          <el-button link type="primary" size="small" @click="exportCsv('indicators', ind)">
            导出汇总</el-button>
          <el-button link type="primary" size="small" @click="exportCsv('detail', ind)">
            导出明细</el-button>
        </span>
      </div>

      <!-- 缺数据源：只说「为什么没有」与「缺哪几列」，绝不画一张全 0 的表 -->
      <template v-if="ind.available !== true">
        <el-alert type="error" show-icon :closable="false" class="cav"
                  title="本指标缺数据源，本平台不给近似值、不显示为 0">
          <div>{{ ind.unavailableReason }}</div>
        </el-alert>
        <el-descriptions :column="1" border size="small" title="需要补的字段（补齐后本指标即可按现成口径出）">
          <el-descriptions-item v-for="(f, i) in missingOf(ind)" :key="i" :label="`缺 ${i + 1}`">
            {{ f }}
          </el-descriptions-item>
        </el-descriptions>
      </template>

      <template v-else>
        <el-alert type="info" :closable="false" class="cav"
                  :title="`归集锚点：${ind.anchorField}　${stripStars(String(ind.anchor ?? ''))}`" />
        <el-alert v-if="ind.caveat" type="warning" :closable="false" class="cav"
                  :title="stripStars(String(ind.caveat))" />
        <el-descriptions v-if="ind.summary" :column="4" border size="small" class="cav">
          <el-descriptions-item v-for="k in keysOf(ind.summary as Row)" :key="k" :label="zh(k)">
            {{ fmt((ind.summary as Row)[k]) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="ind.rowsTruncated === true" type="warning" show-icon :closable="false" class="cav"
                  :title="String(ind.rowsTruncatedNote ?? '汇总行超限，已截断，请缩小统计区间')" />
        <el-table v-if="rowsOf(ind).length" :data="rowsOf(ind)" size="small" border max-height="360">
          <el-table-column v-for="c in columnsOf(rowsOf(ind))" :key="c" :prop="c" :label="zh(c)"
                           :min-width="colWidth(c)" show-overflow-tooltip>
            <template #default="{ row }">{{ fmt(row[c]) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="该统计区间内无数据" :image-size="60" />
      </template>
    </div>

    <el-empty v-if="!loading && indicators.length === 0" description="请选择统计区间后查询" />
  </el-card>

  <!-- ============ 穿透明细 ============ -->
  <el-drawer v-model="drawer" size="72%" :title="`${detailCode} ${detailName} — 取值明细`">
    <el-alert type="info" show-icon :closable="false" class="cav"
              title="明细与汇总同时间窗、同锚点、同过滤条件——对不上账即为缺陷，请据此核对。" />
    <el-alert v-if="detailUnavailable" type="error" show-icon :closable="false" class="cav"
              :title="detailUnavailable" />
    <template v-else>
      <el-alert v-if="detailCaveat" type="warning" :closable="false" class="cav" :title="detailCaveat" />
      <el-alert v-if="detailTruncated" type="warning" show-icon :closable="false" class="cav"
                :title="`命中超过 ${detailLimit} 条，仅显示前 ${detailLimit} 条（不做翻页）；请缩小统计区间后再穿透`" />
      <el-table :data="detailItems" v-loading="detailLoading" size="small" border
                height="calc(100vh - 260px)">
        <el-table-column v-for="c in columnsOf(detailItems)" :key="c" :prop="c" :label="zh(c)"
                         :min-width="colWidth(c)" show-overflow-tooltip>
          <template #default="{ row }">{{ fmt(row[c]) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!detailLoading && detailItems.length === 0" description="该统计区间内无明细" />
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * 病理质控看板（v48 车道 F1）。对接 {@code PathQcController}（/api/path-qc）。
 *
 * <p><b>本页存在的唯一理由是把后端的诚实标注显示出来</b>：
 * <ul>
 *   <li>{@code available=false} 的三条符合率（冰冻—石蜡 / 临床—病理 / 外院会诊）
 *       <b>显示「缺数据源」与原因与缺哪几列，绝不显示成 0</b>——
 *       后端连 rows / summary 键都不给，前端更不能自己补一个 0 出来。</li>
 *   <li>{@code coverage} 四段（标本 / 蜡块 / 切片 / 流转）与每段的 note 全部上屏，
 *       且每个覆盖率都以「分子 / 分母（百分比）」形式给出——只给百分比时
 *       「100%」既可能是 200/200 也可能是 2/2，而本域经常是后者。</li>
 *   <li>顶层 {@code caveats} 与每个指标自己的 {@code caveat} 逐条显示，不折叠不省略。</li>
 * </ul>
 *
 * <p>路由 {@code /pathology/qc}、菜单 169（perm {@code path:qc}，端点限 ADMIN / QUALITY）。
 */
import { computed, onMounted, ref } from 'vue'
import client from '../../../api/client'
import {
  colWidth, columnsOf, defaultRange, fmt, keysOf, num, ratio, zh, type Row,
} from './format'

const range = ref<[string, string]>(defaultRange())
/**
 * 已生效的统计区间：穿透与导出一律用它，<b>不用 range</b>。
 * 用户改了日期但没点查询时，页面上的数还是旧区间的——此时按 range 去穿透，
 * 拿到的明细与屏幕上的汇总不是同一个窗口，正好制造出「指标算得出但对不上账」。
 */
const applied = ref<[string, string]>(defaultRange())
const picked = ref('')
const catalog = ref<Row[]>([])
const body = ref<Row | null>(null)
const loading = ref(false)

const indicators = computed<Row[]>(() => (body.value?.indicators ?? []) as Row[])
const caveats = computed<string[]>(() => (body.value?.caveats ?? []) as string[])
const thresholds = computed<Row | null>(() => (body.value?.thresholds ?? null) as Row | null)
const coverage = computed<Row | null>(() => (body.value?.coverage ?? null) as Row | null)

function rowsOf(ind: Row): Row[] {
  return (ind.rows ?? []) as Row[]
}

function missingOf(ind: Row): string[] {
  return (ind.missingFields ?? []) as string[]
}

/** 后端 caveat 里用 ** 强调，页面按纯文本显示——不做 Markdown 渲染，去掉星号即可 */
function stripStars(s: string): string {
  return s.replace(/\*\*/g, '')
}

/* ---------------- 覆盖率四段 ---------------- */
interface CovItem { key: string; label: string; text: string }
interface CovSection { key: string; title: string; note: string; items: CovItem[] }

/** 各段的分母列：覆盖率必须相对本段分母算，跨段拿别的分母就是张冠李戴 */
const COVERAGE_SECTIONS: { key: string; title: string; denom: string }[] = [
  { key: 'specimens', title: '标本（按登记时刻落窗）', denom: 'specimens' },
  { key: 'blocks', title: '蜡块（按包埋时刻落窗，未录则回落建档时刻）', denom: 'blocks' },
  { key: 'slides', title: '切片（按染色时刻落窗，未录则回落建档时刻）', denom: 'slides' },
  { key: 'process', title: '流转与文书（各按自己的事件时刻落窗）', denom: '' },
]

const coverageSections = computed<CovSection[]>(() => {
  const cov = coverage.value
  if (!cov) return []
  const out: CovSection[] = []
  for (const sec of COVERAGE_SECTIONS) {
    const seg = cov[sec.key] as Row | undefined
    if (!seg) continue
    const denom = sec.denom ? num(seg[sec.denom]) : 0
    const items: CovItem[] = []
    for (const k of Object.keys(seg)) {
      if (k === 'note') continue
      const isDenom = k === sec.denom
      // 分母本身与「无分母可比」的计数列给原始数；其余列给「分子 / 分母（百分比）」，
      // 只给百分比会让 2/2 与 200/200 长得一模一样
      items.push({
        key: k,
        label: zh(k),
        text: sec.denom && !isDenom && denom > 0 ? ratio(seg[k], denom) : fmt(seg[k]),
      })
    }
    out.push({ key: sec.key, title: sec.title, note: String(seg.note ?? ''), items })
  }
  return out
})

/* ---------------- 取数 ---------------- */
async function loadCatalog() {
  const d = (await client.get('/path-qc/catalog')).data.data as Row
  catalog.value = (d.indicators ?? []) as Row[]
}

async function load() {
  if (!range.value || range.value.length !== 2) return
  const [from, to] = range.value
  loading.value = true
  try {
    const resp = await client.get('/path-qc/indicators', {
      params: { from, to, indicator: picked.value || undefined },
    })
    body.value = resp.data.data as Row
    applied.value = [from, to]
  } finally {
    loading.value = false
  }
}

/* ---------------- 穿透明细 ---------------- */
const drawer = ref(false)
const detailCode = ref('')
const detailName = ref('')
const detailCaveat = ref('')
const detailUnavailable = ref('')
const detailItems = ref<Row[]>([])
const detailTruncated = ref(false)
const detailLimit = ref(200)
const detailLoading = ref(false)

async function openDetail(ind: Row) {
  detailCode.value = String(ind.code)
  detailName.value = String(ind.name)
  detailItems.value = []
  detailTruncated.value = false
  detailCaveat.value = ''
  detailUnavailable.value = ''
  drawer.value = true
  detailLoading.value = true
  try {
    const d = (await client.get('/path-qc/detail', {
      params: { indicator: ind.code, from: applied.value[0], to: applied.value[1] },
    })).data.data as Row
    if (d.available !== true) {
      detailUnavailable.value = String(d.unavailableReason ?? '本指标缺数据源，无明细可穿透')
      return
    }
    detailItems.value = (d.items ?? []) as Row[]
    detailTruncated.value = d.truncated === true
    detailLimit.value = num(d.limit) || 200
    detailCaveat.value = stripStars(String(d.caveat ?? ''))
  } finally {
    detailLoading.value = false
  }
}

/* ---------------- CSV 导出（口径页脚随文件走，不只写在页面上） ---------------- */
async function exportCsv(kind: 'indicators' | 'detail', ind: Row) {
  const resp = await client.get(`/path-qc/${kind}.csv`, {
    params: { indicator: ind.code, from: applied.value[0], to: applied.value[1] },
    responseType: 'blob',
  })
  const href = URL.createObjectURL(resp.data as Blob)
  const a = document.createElement('a')
  a.href = href
  a.download = `病理质控${ind.code}_${ind.name}_${kind === 'indicators' ? '汇总' : '明细'}`
    + `_${applied.value[0]}至${applied.value[1]}.csv`
  a.click()
  URL.revokeObjectURL(href)
}

onMounted(async () => {
  await loadCatalog()
  await load()
})
</script>

<style scoped>
.cav { margin-bottom: 8px; }
.cov { margin-bottom: 12px; }
h4 { margin: 16px 0 8px; }
.indicator {
  margin-top: 18px;
  padding-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.ind-head { margin-bottom: 8px; line-height: 24px; }
.ind-code { font-weight: 600; color: var(--el-color-primary); margin-right: 6px; }
.ind-name { font-weight: 600; }
</style>
