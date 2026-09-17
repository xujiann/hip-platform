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
    <!-- v60（2576-②）：后端口径文本带 **强调** 与反引号，一律经 mdText 去标记后按纯文本插值——不做 Markdown 渲染、不用 v-html -->
    <el-alert v-for="(c, i) in caveats" :key="i" type="warning" show-icon :closable="false" class="cav"
              :title="i === 0 ? '统计口径（请先看完这几条再看指标值）' : ''">
      <div>{{ mdText(c) }}</div>
    </el-alert>

    <el-descriptions v-if="thresholds" :column="3" border size="small" class="cav" title="时限阈值与统计区间">
      <el-descriptions-item :label="`常规报告时限（${thresholds.routineHoursKey}）`">
        {{ fmt(thresholds.routineHours) }} 小时</el-descriptions-item>
      <el-descriptions-item :label="`冰冻报告时限（${thresholds.frozenMinutesKey}）`">
        {{ fmt(thresholds.frozenMinutes) }} 分钟</el-descriptions-item>
      <el-descriptions-item label="统计区间">
        {{ fmt(body?.from) }} 至 {{ fmt(body?.to) }}（{{ fmt(body?.days) }} 天）</el-descriptions-item>
      <el-descriptions-item label="节假日口径" :span="3">
        {{ mdText(thresholds.holidayNote) }}</el-descriptions-item>
      <el-descriptions-item label="接收及时率为何不给单一数字" :span="3">
        {{ mdText(thresholds.receiveThresholdNote) }}</el-descriptions-item>
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
        <el-alert v-if="sec.note" type="info" :closable="false" class="cav" :title="mdText(sec.note)" />
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
          <!-- v61（2576 复核）：这里导的是**全科室**；要按科室导请在穿透抽屉里点「导出本次明细」 -->
          <el-button link type="primary" size="small" @click="exportCsv('detail', ind)"
                     :title="ind.code === 'WORKLOAD_DEPT' ? '导出全部科室的明细；要按某个科室导，请点「穿透明细」后在抽屉里导出' : ''">
            导出明细{{ ind.code === 'WORKLOAD_DEPT' ? '（全科室）' : '' }}</el-button>
        </span>
      </div>

      <!-- 缺数据源：只说「为什么没有」与「缺哪几列」，绝不画一张全 0 的表 -->
      <template v-if="ind.available !== true">
        <el-alert type="error" show-icon :closable="false" class="cav"
                  title="本指标缺数据源，本平台不给近似值、不显示为 0">
          <div>{{ mdText(ind.unavailableReason) }}</div>
        </el-alert>
        <el-descriptions :column="1" border size="small" title="需要补的字段（补齐后本指标即可按现成口径出）">
          <el-descriptions-item v-for="(f, i) in missingOf(ind)" :key="i" :label="`缺 ${i + 1}`">
            {{ mdText(f) }}
          </el-descriptions-item>
        </el-descriptions>
      </template>

      <template v-else>
        <el-alert type="info" :closable="false" class="cav"
                  :title="`归集锚点：${ind.anchorField}　${mdText(ind.anchor)}`" />
        <el-alert v-if="ind.caveat" type="warning" :closable="false" class="cav"
                  :title="mdText(ind.caveat)" />
        <!-- v64（2576 复核 demo 镜头）：这一格此前是整页**唯一没有标题**的 el-descriptions，
             屏上没有一个字说它是区间合计；而它四列的中文当时还写着「当日…」。标题带上已生效的统计区间 -->
        <el-descriptions v-if="ind.summary" :column="4" border size="small" class="cav" :title="summaryTitle">
          <el-descriptions-item v-for="k in keysOf(ind.summary as Row)" :key="k"
                                :label="colLabel(k, keysOf(ind.summary as Row))">
            {{ fmt((ind.summary as Row)[k]) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="ind.rowsTruncated === true" type="warning" show-icon :closable="false" class="cav"
                  :title="mdText(ind.rowsTruncatedNote ?? '汇总行超限，已截断，请缩小统计区间')" />
        <el-table v-if="rowsOf(ind).length" :data="rowsOf(ind)" size="small" border max-height="360">
          <!-- v63（2563 复核 demo 镜头）：表头改走 colLabel——它与格子里的 cellText 共用「中文名列在不在场」
               这一个判定，不会再出现「标着编码的列显示中文名」「并排两列表头逐字相同」 -->
          <el-table-column v-for="c in columnsOf(rowsOf(ind))" :key="c" :prop="c"
                           :label="colLabel(c, columnsOf(rowsOf(ind)))"
                           :min-width="colWidth(c)" show-overflow-tooltip>
            <template #default="{ row }">{{ cellText(c, row[c], row) }}</template>
          </el-table-column>
          <!-- v60：科室维度每行可穿透到该科室的标本明细（穿透端点的 dept 过滤只对 WORKLOAD_DEPT 生效；「（未知科室）」也能穿） -->
          <el-table-column v-if="ind.code === 'WORKLOAD_DEPT'" label="穿透" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(ind, String(row.dept_name ?? ''))">
                该科室明细</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="该统计区间内无数据" :image-size="60" />
      </template>
    </div>

    <el-empty v-if="!loading && indicators.length === 0" description="请选择统计区间后查询" />
  </el-card>

  <!-- ============ 穿透明细 ============ -->
  <el-drawer v-model="drawer" size="72%" :title="detailTitle">
    <el-alert type="info" show-icon :closable="false" class="cav"
              title="明细与汇总同时间窗、同锚点、同过滤条件——对不上账即为缺陷，请据此核对。" />
    <el-alert v-if="detailUnavailable" type="error" show-icon :closable="false" class="cav"
              :title="mdText(detailUnavailable)" />
    <template v-else>
      <el-alert v-if="detailCaveat" type="warning" :closable="false" class="cav" :title="mdText(detailCaveat)" />
      <el-alert v-if="detailTruncated" type="warning" show-icon :closable="false" class="cav"
                :title="`命中超过 ${detailLimit} 条，仅显示前 ${detailLimit} 条（不做翻页）；请缩小统计区间后再穿透`" />
      <!-- v61（2576 复核）：此前抽屉里没有导出按钮，科室过滤只有 E2E 调得到——
           评委看完某科室明细、关掉抽屉点指标头部的「导出明细」，拿到的是全科室的另一份 -->
      <div class="drawer-actions">
        <el-button size="small" type="primary" plain @click="exportDetailInDrawer">
          导出本次明细{{ detailDept ? `（科室：${detailDept}）` : '' }}
        </el-button>
        <span class="muted">与上表同时间窗、同锚点、同过滤条件；CSV 页脚写明过滤与口径</span>
      </div>
      <!-- 表头 colLabel()、取值 cellText()（两者同一个判定）；_id 主键列挪到最后并置灰（不删：对账与 CSV 导出仍要它） -->
      <el-table :data="detailItems" v-loading="detailLoading" size="small" border
                height="calc(100vh - 260px)">
        <el-table-column v-for="c in detailColumns" :key="c" :prop="c" :label="colLabel(c, detailColumns)"
                         :min-width="colWidth(c)" :class-name="isIdColumn(c) ? 'id-col' : ''"
                         show-overflow-tooltip>
          <template #default="{ row }">{{ cellText(c, row[c], row) }}</template>
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
 *   <li>顶层 {@code caveats} 与每个指标自己的 {@code caveat} 逐条显示，不折叠不省略。
 *       v60（2576-②）：这些口径文本带 {@code **强调**} / 反引号 / 行首列表符，一律经 {@code format.ts#mdText}
 *       去标记后按<b>纯文本插值</b>显示——不做 Markdown 渲染、不用 v-html（v59 之前 {@code {{ c }}} 直出，星号上屏）。</li>
 *   <li>v60（2576-②）：{@code WORKLOAD_DEPT} 送检科室维度的每一行可穿透到该科室的标本明细（穿透端点 {@code dept} 过滤）。</li>
 * </ul>
 *
 * <p>路由 {@code /pathology/qc}、菜单 169（perm {@code path:qc}，端点限 ADMIN / QUALITY）。
 */
import { computed, onMounted, ref } from 'vue'
import client from '../../../api/client'
import {
  cellText, colLabel, colWidth, columnsOf, defaultRange, fmt, idColumnsLast, isIdColumn, keysOf, mdText,
  num, ratio, zh, type Row,
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

/**
 * 合计块的标题（v64，2576 复核 demo 镜头）。
 *
 * <p><b>修复前的反向事实</b>：整页所有 el-descriptions 里，<b>只有这一格没有 title</b>——
 * 屏上没有一个字说它是区间合计，而它四列的中文当时与按日表逐字相同（「当日产出蜡块数」），
 * 于是同一屏上「当日产出蜡块数 39」（30 天合计）与「当日产出蜡块数 3」（某一天）并存。
 * 列名与中文已在后端分成两套，这里再把「这是本期合计、不是某一天」写在标题上。
 *
 * <p>区间取<b>返回体里的 from / to / days</b>（后端此次实际统计的窗口），不取 range：
 * 用户改了日期还没点查询时，屏上的数仍是上一次的区间，标题必须跟着数走。
 */
const summaryTitle = computed(() =>
  `本期合计　${fmt(body.value?.from)} 至 ${fmt(body.value?.to)}（共 ${fmt(body.value?.days)} 天）`
  + '　——整个统计区间一个数，不是某一天；下面那张表才按日拆分')

function missingOf(ind: Row): string[] {
  return (ind.missingFields ?? []) as string[]
}

/* ---------------- 覆盖率四段 ---------------- */
interface CovItem { key: string; label: string; text: string }
interface CovSection { key: string; title: string; note: string; items: CovItem[] }

/**
 * 各段的分母列、<b>本段专属中文</b>、以及「相对本段分母」的占比列。
 *
 * <p><b>v63（2576 复核 demo 镜头，本轮头号纪律）</b>，本段是**先于所有指标渲染**的第一屏，两处都要修：
 * <ul>
 *   <li><b>按段给中文</b>：{@code blocks} 一个键服务两段语义不同的列——蜡块段是 {@code count(*)}（本段分母、
 *       产出数），切片段是 {@code count(distinct sl.block_id)}（这批切片涉及多少蜡块、去重）。
 *       走 format.ts 那张<b>扁平字典</b> {@code zh(col)} 必然渲染成同一屏上两个一模一样的中文表头「蜡块数」
 *       （演示数据下就是「蜡块数 6」与「蜡块数 3」）。{@code specimens} 同型（标本段是本段分母、蜡块段是涉及标本数）。
 *       v62 只把这件事做到了工作量指标层（blocks_produced / blocks_stained），而<b>本段还在共用一个键</b>。</li>
 *   <li><b>区分「覆盖率列」与「计数列」</b>：此前只分「是不是分母」，不是分母就一律套 {@code ratio()}。
 *       切片段的 {@code blocks} 既不是本段分母、也不是本段分母的子集（单位都不同：蜡块 vs 切片），
 *       却被打成「3 / 8（37.5%）」——一个 {@code not null} 外键的去重计数就此变成一条「37.5% 的覆盖率」，
 *       而这一屏的标题正是「本时段字段录入覆盖率」。蜡块段的 {@code specimens} 同型。</li>
 * </ul>
 *
 * <p>覆盖率列的判据是「本段分母的子集」——后端 {@code PathQcController.coverage()} 里这些一律是
 * {@code count(*) filter (...)}，列名以 {@code with_} 开头，另加各段列进 {@code rates} 的那几个。
 * <b>默认按计数列走（给原始数）</b>：漏登一列最多是少一个百分比，多算一列却是一个假百分比。
 */
const COVERAGE_SECTIONS: {
  key: string; title: string; denom: string; rates: string[]; labels: Record<string, string>
}[] = [
  {
    key: 'specimens',
    title: '标本（按登记时刻落窗）',
    denom: 'specimens',
    // 都是 path_specimen 同一批行的子集计数，占比相对本段分母成立
    rates: ['outp_source', 'inp_source', 'rejected', 'diagnosed_not_issued'],
    labels: { specimens: '登记标本数（本段分母）' },
  },
  {
    key: 'blocks',
    title: '蜡块（按包埋时刻落窗，未录则回落建档时刻）',
    denom: 'blocks',
    rates: [],
    // specimens 在这一段是 count(distinct b.specimen_id)：涉及多少个标本，不是本段分母的子集
    // v64（2576 复核）：本段这两个数落窗的是**整个统计区间**，与蜡块产出数指标的合计格是同一个数——
    // 中文跟着合计列一起正名成「本期…」，同一个数在这一屏的两处呈现从此逐字同源；
    // 按日表里那个「当日产出蜡块数」才是单日数，一眼分得出不是同一个口径。
    labels: { blocks: '本期产出蜡块数（本段分母）', specimens: '本期涉及标本数(去重)' },
  },
  {
    key: 'slides',
    title: '切片（按染色时刻落窗，未录则回落建档时刻）',
    denom: 'slides',
    rates: [],
    // blocks 在这一段是 count(distinct sl.block_id)：这批切片涉及多少个蜡块，不是本段分母的子集
    labels: { slides: '切片数（本段分母）', blocks: '涉及蜡块数（去重）' },
  },
  { key: 'process', title: '流转与文书（各按自己的事件时刻落窗）', denom: '', rates: [], labels: {} },
]

/**
 * 本段的<b>覆盖率列</b>：相对本段分母算出的子集计数（后端一律 {@code count(*) filter (...)}）。
 * 分母本身、以及单位与分母不同的计数列（涉及标本数 / 涉及蜡块数）都不是——它们没有「占比」可言。
 */
function isRateColumn(sec: { denom: string; rates: string[] }, k: string): boolean {
  return k !== sec.denom && (k.startsWith('with_') || sec.rates.includes(k))
}

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
      // 覆盖率列给「分子 / 分母（百分比）」（只给百分比会让 2/2 与 200/200 长得一模一样）；
      // 分母本身与计数列给原始数——不是本段分母的子集，套上百分比就是编一个不存在的覆盖率
      items.push({
        key: k,
        label: sec.labels[k] ?? zh(k),
        text: sec.denom && denom > 0 && isRateColumn(sec, k) ? ratio(seg[k], denom) : fmt(seg[k]),
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
/** v60：WORKLOAD_DEPT 按科室穿透时的科室显示名（空 = 不过滤）；其余指标恒空 */
const detailDept = ref('')
const detailTitle = computed(() =>
  `${detailCode.value} ${detailName.value} — 取值明细` + (detailDept.value ? `（科室：${detailDept.value}）` : ''))
const detailCaveat = ref('')
const detailUnavailable = ref('')
const detailItems = ref<Row[]>([])
const detailTruncated = ref(false)
const detailLimit = ref(200)
const detailLoading = ref(false)
/**
 * 明细列序：后端返回顺序为准，只把 _id 结尾的内部主键挪到最后（v59 2576 复核：
 * 每张明细表首列都是 specimen_id / slide_id / tech_order_id 这类内部主键，患者与病理号被挤到后面）。
 * 不删列——对账与 CSV 导出仍要主键。
 */
const detailColumns = computed<string[]>(() => idColumnsLast(columnsOf(detailItems.value)))

/** @param dept v60：只在 WORKLOAD_DEPT 行级穿透时传科室显示名；后端只对该指标套用过滤 */
async function openDetail(ind: Row, dept?: string) {
  detailCode.value = String(ind.code)
  detailName.value = String(ind.name)
  detailDept.value = ind.code === 'WORKLOAD_DEPT' && dept ? dept : ''
  detailItems.value = []
  detailTruncated.value = false
  detailCaveat.value = ''
  detailUnavailable.value = ''
  drawer.value = true
  detailLoading.value = true
  try {
    const d = (await client.get('/path-qc/detail', {
      params: {
        indicator: ind.code, from: applied.value[0], to: applied.value[1],
        dept: detailDept.value || undefined,
      },
    })).data.data as Row
    if (d.available !== true) {
      detailUnavailable.value = String(d.unavailableReason ?? '本指标缺数据源，无明细可穿透')
      return
    }
    detailItems.value = (d.items ?? []) as Row[]
    detailTruncated.value = d.truncated === true
    detailLimit.value = num(d.limit) || 200
    // 原文进 ref，去标记放在模板绑定处（mdText）——源码扫描按「绑定处经 mdText」判，口径文本只此一条路上屏
    detailCaveat.value = String(d.caveat ?? '')
  } finally {
    detailLoading.value = false
  }
}

/* ---------------- CSV 导出（口径页脚随文件走，不只写在页面上） ----------------
 * v61（2576 复核）：dept 过滤此前只有 E2E 调得到——后端 detailCsv 早有 dept 形参、toCsv 也会写「科室过滤：X」页脚，
 * 但前端两个导出按钮都在指标头部、params 只有 {indicator, from, to}，穿透抽屉里根本没有导出按钮。
 * 评委看完某科室明细再点「导出明细」，拿到的是全科室最多 200 条且页脚不写过滤——与屏幕上刚看的不是同一份，
 * 恰好撞上抽屉顶部那句「明细与汇总同时间窗、同锚点、同过滤条件——对不上账即为缺陷」。
 * 现在抽屉里的「导出本次明细」把当前 dept 传下去；指标头部那个仍导全科室，按钮文案已注明区别。 */
async function exportCsv(kind: 'indicators' | 'detail', ind: Row, dept?: string) {
  const resp = await client.get(`/path-qc/${kind}.csv`, {
    params: { indicator: ind.code, from: applied.value[0], to: applied.value[1], dept: dept || undefined },
    responseType: 'blob',
  })
  const href = URL.createObjectURL(resp.data as Blob)
  const a = document.createElement('a')
  a.href = href
  a.download = `病理质控${ind.code}_${ind.name}_${kind === 'indicators' ? '汇总' : '明细'}`
    + (dept ? `_${dept}` : '') + `_${applied.value[0]}至${applied.value[1]}.csv`
  a.click()
  URL.revokeObjectURL(href)
}

/** 抽屉里导出：带上本次穿透的科室过滤，与屏幕上看到的那份逐行一致 */
async function exportDetailInDrawer() {
  if (!detailCode.value) return
  await exportCsv('detail', { code: detailCode.value, name: detailName.value } as Row,
    detailDept.value || undefined)
}

onMounted(async () => {
  await loadCatalog()
  await load()
})
</script>

<style scoped>
.cav { margin-bottom: 8px; }
.drawer-actions { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.drawer-actions .muted { color: var(--el-text-color-secondary); font-size: 12px; }
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
/* 内部主键列置灰（el-table 的 class-name 落在 td/th 上，穿透 scoped） */
:deep(.id-col) { color: var(--el-text-color-placeholder); }
</style>
