<template>
  <div class="temp-sheet">
    <div class="sheet-scroll">
      <svg :width="W * scale" :height="H * scale" :viewBox="`0 0 ${W} ${H}`" class="sheet-svg">
        <!-- ===== 表头三行：日期 / 住院日 / 时点 ===== -->
        <g class="head">
          <text v-for="lab in ROW_LABELS.head" :key="lab.t" :x="PAD_L - 6" :y="lab.y"
                text-anchor="end" class="row-label">{{ lab.t }}</text>
          <template v-for="b in dayBlocks" :key="'h' + b.key">
            <text :x="b.xc" :y="HEAD_Y1" text-anchor="middle" class="head-date">{{ mmdd(b.date) }}</text>
            <text :x="b.xc" :y="HEAD_Y2" text-anchor="middle" class="head-sub">第 {{ b.hospitalDay }} 日</text>
          </template>
          <text v-for="c in slotHeads" :key="'s' + c.x" :x="c.x" :y="HEAD_Y3"
                text-anchor="middle" class="head-slot">{{ c.h }}</text>
        </g>

        <!-- ===== 坐标格：横轴 日期×时点，纵轴 体温 34–42℃ 与脉搏 40–160 双刻度共格 ===== -->
        <g class="grid">
          <line v-for="g in tempGrid" :key="'gt' + g.v" :x1="PAD_L" :x2="PAD_L + GRID_W"
                :y1="g.y" :y2="g.y" :class="g.major ? 'grid-major' : 'grid-minor'" />
          <line v-for="c in colLines" :key="'gc' + c.x" :x1="c.x" :x2="c.x"
                :y1="PLOT_TOP" :y2="BOTTOM_END" :class="c.major ? 'grid-major' : 'grid-minor'" />
          <rect :x="PAD_L" :y="PLOT_TOP" :width="GRID_W" :height="PLOT_H" class="plot-box" />
          <!-- 左：体温刻度 -->
          <text v-for="g in tempGrid.filter((t) => t.major)" :key="'lt' + g.v" :x="PAD_L - 6" :y="tickY(g.y)"
                text-anchor="end" class="axis-t">{{ g.v }}</text>
          <text :x="14" :y="PLOT_TOP + 12" class="axis-cap axis-t">体温℃</text>
          <!-- 右：脉搏刻度（量纲不同，与体温共用格但各自刻度） -->
          <g v-for="g in pulseGrid" :key="'lp' + g.v">
            <line :x1="PAD_L + GRID_W" :x2="PAD_L + GRID_W + 4" :y1="g.y" :y2="g.y" class="grid-major" />
            <text :x="PAD_L + GRID_W + 7" :y="tickY(g.y)" text-anchor="start" class="axis-p">{{ g.v }}</text>
          </g>
          <!-- 右侧标题放在格区上方：与 160 刻度同 x，若也放格内会与刻度字重叠 -->
          <text :x="PAD_L + GRID_W + 7" :y="PLOT_TOP - 12" text-anchor="start" class="axis-cap axis-p">脉搏</text>
          <!-- 发热参考线（37.3 / 38，仅作参考，不改任何阈值口径） -->
          <line v-for="f in FEVER_LINES" :key="'f' + f" :x1="PAD_L" :x2="PAD_L + GRID_W"
                :y1="yT(f)" :y2="yT(f)" class="fever-line" />
          <text v-for="f in FEVER_LINES" :key="'ft' + f" :x="PAD_L + 4" :y="yT(f) - 2"
                class="fever-text">{{ f }}℃</text>
        </g>

        <!-- ===== 曲线：体温实心点连线 / 脉搏空心点连线 ===== -->
        <polyline v-if="tempLine" :points="tempLine" class="line-temp" />
        <polyline v-if="pulseLine" :points="pulseLine" class="line-pulse" />
        <!-- 物理降温后体温：虚线自降温后温度回连降温前体温点 -->
        <line v-for="(c, i) in coolMarks" :key="'cl' + i" :x1="c.x" :x2="c.x" :y1="c.y" :y2="c.yFrom"
              class="line-cool" />
        <circle v-for="(c, i) in coolMarks" :key="'cc' + i" :cx="c.x" :cy="c.y" r="3.4" class="dot-cool">
          <title>{{ c.tip }}</title>
        </circle>
        <circle v-for="(p, i) in pulseDots" :key="'p' + i" :cx="p.x" :cy="p.y" r="3.2" class="dot-pulse">
          <title>{{ p.tip }}</title>
        </circle>
        <circle v-for="(p, i) in tempDots" :key="'t' + i" :cx="p.x" :cy="p.y" r="3.2" class="dot-temp">
          <title>{{ p.tip }}</title>
        </circle>
        <!-- 未测：画「未测」而不断线（曲线跨过该时点直连前后两点） -->
        <text v-for="(n, i) in notMeasured" :key="'n' + i" :x="n.x" :y="n.y"
              text-anchor="middle" class="not-measured"
              :transform="`rotate(-90 ${n.x} ${n.y})`">未测<title>{{ n.tip }}</title></text>

        <!-- ===== 下方数据行 ===== -->
        <g class="bottom">
          <line v-for="(r, i) in BOTTOM_ROWS" :key="'br' + r.key" :x1="PAD_L" :x2="PAD_L + GRID_W"
                :y1="rowY(i + 1)" :y2="rowY(i + 1)" class="grid-major" />
          <text v-for="(r, i) in BOTTOM_ROWS" :key="'bl' + r.key" :x="PAD_L - 6" :y="rowY(i) + 14"
                text-anchor="end" class="row-label">{{ r.label }}</text>
          <text v-for="(t, i) in bottomTexts" :key="'bt' + i" :x="t.x" :y="t.y"
                text-anchor="middle" :class="t.cls">{{ t.t }}</text>
        </g>

        <!-- ===== 图例 ===== -->
        <g class="legend" :transform="`translate(${PAD_L}, ${H - 8})`">
          <circle cx="4" cy="-4" r="3.2" class="dot-temp" />
          <text x="12" y="0" class="legend-text">体温（实心·连线）</text>
          <circle cx="118" cy="-4" r="3.2" class="dot-pulse" />
          <text x="126" y="0" class="legend-text">脉搏（空心·连线）</text>
          <circle cx="232" cy="-4" r="3.2" class="dot-cool" />
          <text x="240" y="0" class="legend-text">物理降温后体温（虚线回连）</text>
          <text x="410" y="0" class="legend-text">呼吸单独成行；未测点标「未测」且曲线不断</text>
          <text x="700" y="0" class="legend-text">出入量标 (ICU) 者取自 ICU 记录，余取自体征记录</text>
        </g>
      </svg>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 三测单（体温单）自绘 SVG 格点版式——v42 车道1。
 *
 * 刻意**不复用** PrintView 的票据 isSheet 版式（那是文字流式单据），
 * 也**不引 echarts**（既定技术债结论：体温单是有法定格位的纸面表格，不是统计图，
 * 图表库的坐标系/图例/交互一概用不上，反而挡住格位对齐与打印分页）。
 *
 * 版式：横轴 7 天 × 每日 6 个标准时点（2/6/10/14/18/22 时），
 * 纵轴体温 34–42℃ 与脉搏 40–160 双刻度共用同一格区（量纲不同，各自标刻度）。
 * 体温实心点连线、脉搏空心点连线、呼吸单独一行数字、
 * 物理降温后体温以虚线回连降温前体温点、未测时点画「未测」且曲线不断。
 */
const props = withDefaults(defineProps<{
  sheet: Record<string, unknown>
  /** 缩放倍率，由外层工具条控制（应答明文承诺的缩放能力） */
  scale?: number
}>(), { scale: 1 })

interface SheetPoint {
  measuredAt: string
  slot: number
  slotHour: number
  temperature: number | string | null
  tempAfterCooling: number | string | null
  pulse: number | null
  respiration: number | null
  sbp: number | null
  dbp: number | null
  spo2: number | null
  measureSite: string | null
  notMeasuredReason: string | null
}
interface SheetDay {
  date: string
  hospitalDay: number
  points: SheetPoint[]
  intakeMl: number | null
  outputMl: number | null
  intakeSource: string | null
  outputSource: string | null
  stoolCount: number | null
  weightKg: number | string | null
  heightCm: number | null
}

const DAYS = 7
const SLOTS = 6
const COL_W = 26
const PAD_L = 76
const PAD_R = 44
const GRID_W = DAYS * SLOTS * COL_W
const W = PAD_L + GRID_W + PAD_R
const HEAD_Y1 = 16
const HEAD_Y2 = 31
const HEAD_Y3 = 46
const PLOT_TOP = 58
const PLOT_H = 336
const PLOT_BOTTOM = PLOT_TOP + PLOT_H
const ROW_H = 20

const T_MIN = 34
const T_MAX = 42
const P_MIN = 40
const P_MAX = 160
/** 发热参考线——仅画线，**不改** 37.3℃ 阈值口径本身（v33 已上线并被 E2E 断言锁住） */
const FEVER_LINES = [37.3, 38]

const BOTTOM_ROWS = [
  { key: 'respiration', label: '呼吸(次/分)' },
  { key: 'bp', label: '血压(mmHg)' },
  { key: 'spo2', label: 'SpO₂(%)' },
  { key: 'intake', label: '入量(ml)' },
  { key: 'output', label: '出量(ml)' },
  { key: 'stool', label: '大便(次)' },
  { key: 'weight', label: '体重(kg)' },
  { key: 'height', label: '身高(cm)' },
  { key: 'note', label: '未测/备注' },
]
const BOTTOM_END = PLOT_BOTTOM + BOTTOM_ROWS.length * ROW_H
const H = BOTTOM_END + 22

const ROW_LABELS = {
  head: [
    { t: '日期', y: HEAD_Y1 },
    { t: '住院日', y: HEAD_Y2 },
    { t: '时间', y: HEAD_Y3 },
  ],
}

const days = computed<SheetDay[]>(() => (props.sheet?.days as SheetDay[]) ?? [])
const slotHours = computed<number[]>(() =>
  (props.sheet?.slotHours as number[]) ?? [2, 6, 10, 14, 18, 22])

function num(v: unknown): number | null {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isNaN(n) ? null : n
}

function xOf(dayIndex: number, slot: number) {
  const s = Math.min(SLOTS - 1, Math.max(0, slot ?? 0))
  return PAD_L + (dayIndex * SLOTS + s) * COL_W + COL_W / 2
}
function yT(v: number) {
  return PLOT_BOTTOM - ((v - T_MIN) / (T_MAX - T_MIN)) * PLOT_H
}
function yP(v: number) {
  return PLOT_BOTTOM - ((v - P_MIN) / (P_MAX - P_MIN)) * PLOT_H
}
function clampY(y: number) {
  return Math.min(PLOT_BOTTOM, Math.max(PLOT_TOP, y))
}
/** 刻度字基线：贴线下 3px，但最低一格（34℃ / 脉搏 40）不许溢出格区，否则压住下方「呼吸」行标 */
function tickY(y: number) {
  return Math.min(y + 3, PLOT_BOTTOM - 1)
}
function mmdd(d: string) {
  return String(d ?? '').slice(5)
}
function rowY(i: number) {
  return PLOT_BOTTOM + i * ROW_H
}

const dayBlocks = computed(() =>
  days.value.map((d, i) => ({
    key: d.date ?? String(i),
    date: d.date,
    hospitalDay: d.hospitalDay,
    x0: PAD_L + i * SLOTS * COL_W,
    xc: PAD_L + (i + 0.5) * SLOTS * COL_W,
    d,
  })))

const slotHeads = computed(() => {
  const out: { x: number; h: number }[] = []
  for (let i = 0; i < DAYS; i++) {
    slotHours.value.forEach((h, s) => out.push({ x: xOf(i, s), h }))
  }
  return out
})

const colLines = computed(() => {
  const out: { x: number; major: boolean }[] = []
  for (let i = 0; i <= DAYS * SLOTS; i++) {
    out.push({ x: PAD_L + i * COL_W, major: i % SLOTS === 0 })
  }
  return out
})

const tempGrid = computed(() => {
  const out: { v: number; y: number; major: boolean }[] = []
  for (let v = T_MIN; v <= T_MAX + 1e-6; v += 0.5) {
    const r = Math.round(v * 10) / 10
    out.push({ v: r, y: yT(r), major: Number.isInteger(r) })
  }
  return out
})

const pulseGrid = computed(() => {
  const out: { v: number; y: number }[] = []
  for (let v = P_MIN; v <= P_MAX; v += 20) out.push({ v, y: yP(v) })
  return out
})

interface Cell { x: number; p: SheetPoint; date: string }

/** 全周时点展平（后端已按 measuredAt 正序返回，日序即时间序） */
const cells = computed<Cell[]>(() => {
  const out: Cell[] = []
  days.value.forEach((d, di) => {
    (d.points ?? []).forEach((p) => out.push({ x: xOf(di, p.slot), p, date: d.date }))
  })
  return out
})

function hhmm(iso: string) {
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toTimeString().slice(0, 5)
}

const tempDots = computed(() =>
  cells.value
    .map((c) => ({ c, v: num(c.p.temperature) }))
    .filter((d): d is { c: Cell; v: number } => d.v !== null)
    .map((d) => ({
      x: d.c.x,
      y: clampY(yT(d.v)),
      tip: `${d.c.date} ${hhmm(d.c.p.measuredAt)} 体温 ${d.v}℃`
        + (d.c.p.measureSite ? `（${siteName(d.c.p.measureSite)}）` : ''),
    })))

const pulseDots = computed(() =>
  cells.value
    .map((c) => ({ c, v: num(c.p.pulse) }))
    .filter((d): d is { c: Cell; v: number } => d.v !== null)
    .map((d) => ({
      x: d.c.x,
      y: clampY(yP(d.v)),
      tip: `${d.c.date} ${hhmm(d.c.p.measuredAt)} 脉搏 ${d.v} 次/分`,
    })))

const tempLine = computed(() => tempDots.value.map((p) => `${p.x},${p.y}`).join(' '))
const pulseLine = computed(() => pulseDots.value.map((p) => `${p.x},${p.y}`).join(' '))

/** 降温后体温：空心点 + 虚线回连降温前体温（无降温前值时回连到格区底边） */
const coolMarks = computed(() =>
  cells.value
    .map((c) => ({ c, v: num(c.p.tempAfterCooling), from: num(c.p.temperature) }))
    .filter((d): d is { c: Cell; v: number; from: number | null } => d.v !== null)
    .map((d) => ({
      x: d.c.x,
      y: clampY(yT(d.v)),
      yFrom: d.from === null ? PLOT_BOTTOM : clampY(yT(d.from)),
      tip: `${d.c.date} ${hhmm(d.c.p.measuredAt)} 物理降温后体温 ${d.v}℃`,
    })))

/** 未测时点：标「未测」，曲线**不断**（该时点不参与折线，前后两点直连） */
const notMeasured = computed(() =>
  cells.value
    .filter((c) => c.p.notMeasuredReason && num(c.p.temperature) === null)
    .map((c) => ({
      x: c.x,
      y: yT(36),
      tip: `${c.date} ${hhmm(c.p.measuredAt)} 未测：${c.p.notMeasuredReason}`,
    })))

function siteName(s: string) {
  return ({ ORAL: '口温', AXILLARY: '腋温', RECTAL: '肛温' } as Record<string, string>)[s] ?? s
}

interface TextItem { x: number; y: number; t: string; cls: string }

const bottomTexts = computed<TextItem[]>(() => {
  const out: TextItem[] = []
  const idx = (k: string) => BOTTOM_ROWS.findIndex((r) => r.key === k)
  const baseline = (k: string) => rowY(idx(k)) + 14
  // 按时点的行
  cells.value.forEach((c) => {
    const resp = num(c.p.respiration)
    if (resp !== null) out.push({ x: c.x, y: baseline('respiration'), t: String(resp), cls: 'cell-num' })
    const sbp = num(c.p.sbp)
    const dbp = num(c.p.dbp)
    if (sbp !== null || dbp !== null) {
      out.push({ x: c.x, y: baseline('bp'), t: `${sbp ?? '-'}/${dbp ?? '-'}`, cls: 'cell-num small' })
    }
    const spo2 = num(c.p.spo2)
    if (spo2 !== null) out.push({ x: c.x, y: baseline('spo2'), t: String(spo2), cls: 'cell-num' })
    if (c.p.notMeasuredReason) {
      out.push({ x: c.x, y: baseline('note'), t: String(c.p.notMeasuredReason), cls: 'cell-num small' })
    }
  })
  // 按日汇总的行（出入量带来源标注：ICU 记录优先于体征记录新列）
  dayBlocks.value.forEach((b) => {
    const d = b.d
    if (d.intakeMl !== null && d.intakeMl !== undefined) {
      out.push({
        x: b.xc, y: baseline('intake'),
        t: `${d.intakeMl}${d.intakeSource === 'ICU' ? ' (ICU)' : ''}`, cls: 'cell-num',
      })
    }
    if (d.outputMl !== null && d.outputMl !== undefined) {
      out.push({
        x: b.xc, y: baseline('output'),
        t: `${d.outputMl}${d.outputSource === 'ICU' ? ' (ICU)' : ''}`, cls: 'cell-num',
      })
    }
    if (d.stoolCount !== null && d.stoolCount !== undefined) {
      out.push({ x: b.xc, y: baseline('stool'), t: String(d.stoolCount), cls: 'cell-num' })
    }
    const w = num(d.weightKg)
    if (w !== null) out.push({ x: b.xc, y: baseline('weight'), t: String(w), cls: 'cell-num' })
    if (d.heightCm !== null && d.heightCm !== undefined) {
      out.push({ x: b.xc, y: baseline('height'), t: String(d.heightCm), cls: 'cell-num' })
    }
  })
  return out
})
</script>

<style scoped>
.temp-sheet { width: 100%; }
.sheet-scroll { overflow: auto; border: 1px solid #e4e7ed; background: #fff; }
.sheet-svg { display: block; background: #fff; }
.grid-minor { stroke: #eceff3; stroke-width: 1; }
.grid-major { stroke: #b8bfc9; stroke-width: 1; }
.plot-box { fill: none; stroke: #606266; stroke-width: 1.2; }
.axis-t { fill: #c0392b; font-size: 10px; }
.axis-p { fill: #2563eb; font-size: 10px; }
.axis-cap { font-size: 10px; font-weight: 600; }
.fever-line { stroke: #e6a23c; stroke-width: 1; stroke-dasharray: 4 3; }
.fever-text { fill: #e6a23c; font-size: 9px; }
.line-temp { fill: none; stroke: #c0392b; stroke-width: 1.6; stroke-linejoin: round; }
.line-pulse { fill: none; stroke: #2563eb; stroke-width: 1.4; stroke-linejoin: round; }
.line-cool { stroke: #c0392b; stroke-width: 1.2; stroke-dasharray: 3 2; }
.dot-temp { fill: #c0392b; stroke: #c0392b; stroke-width: 1; }
.dot-pulse { fill: #fff; stroke: #2563eb; stroke-width: 1.4; }
.dot-cool { fill: #fff; stroke: #c0392b; stroke-width: 1.2; }
.not-measured { fill: #909399; font-size: 9px; }
.head-date { font-size: 11px; font-weight: 600; fill: #303133; }
.head-sub { font-size: 9px; fill: #909399; }
.head-slot { font-size: 9px; fill: #606266; }
.row-label { font-size: 10px; fill: #606266; }
.cell-num { font-size: 9px; fill: #303133; }
.cell-num.small { font-size: 7.5px; }
.legend-text { font-size: 9px; fill: #909399; }
@media print {
  .sheet-scroll { overflow: visible; border: none; }
  /* 打印一律按纸宽自适应，不受页面缩放倍率影响 */
  .sheet-svg { width: 100% !important; height: auto !important; }
}
</style>
