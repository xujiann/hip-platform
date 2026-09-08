/**
 * 本地日期工具。
 *
 * `new Date().toISOString().slice(0,10)` 取的是 **UTC** 日期：北京时间 08:00 之前
 * 会比本地日期早一天——挂号页号源、药房待发药、日结默认日期全部显示昨天。
 * 仓库里 SchedulesView 早就用对了写法，但只修了那一处，其余 10 处一直沿用 toISOString。
 */
export function toLocalDate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 今天（本地时区） */
export function todayLocal(): string {
  return toLocalDate(new Date())
}

/** 相对今天偏移若干天的本地日期 */
export function localDateOffset(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return toLocalDate(d)
}

// ---------------------------------------------------------------------------
// 时间戳显示（v57）。
//
// 后端 timestamptz 经 JdbcTemplate/Jackson 出来的线格式是 `2026-09-08T05:00:44.229+00:00`
// （UTC 带偏移），实体 Instant 字段是 `...Z`。此前全 shell 43 处 `slice(0, 16).replace('T', ' ')`
// 裸切前 16 位，等于把 UTC 当北京时间画在屏幕上——**病理、药房、护理的每一个时刻都早 8 小时**，
// 门户首页把签发日期 `slice(0, 10)` 后，北京 0–8 点签发的报告日期显示成前一天（2558 核账坐实）。
//
// 规则只有一条：**字符串带时区偏移（Z / ±hh:mm）才换算**，换算目标是医院业务时区（与后端
// HipProfiles.ZONE 同为 Asia/Shanghai，不跟浏览器走——异地运维看到的也应是医院时间）；
// 不带偏移的（LocalDateTime / LocalDate 序列化）视为已是业务时间，原样截取。这样 43 处
// 不必逐一考证字段类型即可统一替换：函数按字符串形态自判。
// ---------------------------------------------------------------------------
const BUSINESS_TZ = 'Asia/Shanghai'
const ISO_PREFIX = /^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2})?/
// 偏移形态：Z / ±hh:mm / ±hhmm / ±hh（后两种是 PG 文本格式与部分序列化器的写法，v57 审阅补）
const TZ_SUFFIX = /(Z|[+-]\d{2}(?::?\d{2})?)$/
const dtf = new Intl.DateTimeFormat('en-CA', {
  timeZone: BUSINESS_TZ, year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})

function businessParts(d: Date): Record<string, string> {
  const m: Record<string, string> = {}
  for (const p of dtf.formatToParts(d)) m[p.type] = p.value
  return m
}

/**
 * 能换算的输入 → 业务时区各部分；否则 null（调用方按「已是业务时间」原样截取）。
 * · Date 对象：直接换算（旧裸切对 Date 会得到 'Tue Sep 08 2' 这类垃圾）；
 * · 带偏移的字符串：先把 ±hhmm / ±hh 归一成 ±hh:mm——V8 对 '+0800' 报 Invalid Date、对 '-0500' 却能解析，
 *   同一形态两种结果，不归一就是静默退回裸切。
 */
function partsOf(v: unknown): Record<string, string> | null {
  if (v instanceof Date) return Number.isNaN(v.getTime()) ? null : businessParts(v)
  const s = String(v)
  if (!ISO_PREFIX.test(s) || !TZ_SUFFIX.test(s)) return null
  const norm = s.replace(/([+-]\d{2})(\d{2})$/, '$1:$2').replace(/([+-]\d{2})$/, '$1:00')
  const d = new Date(norm)
  return Number.isNaN(d.getTime()) ? null : businessParts(d)
}

/** 时间戳 → 「YYYY-MM-DD HH:mm」（业务时区）；空值给 `empty`（默认「—」，与病理 format.ts 同约定） */
export function fmtDateTime(v: unknown, empty = '—'): string {
  if (v === null || v === undefined || v === '') return empty
  const p = partsOf(v)
  if (p) return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}`
  const s = String(v)
  if (!ISO_PREFIX.test(s)) return s
  return s.length >= 16 ? s.slice(0, 16).replace('T', ' ') : s
}

/** 时间戳/日期 → 「YYYY-MM-DD」（业务时区）；纯日期串原样；空值给 `empty` */
export function fmtDate(v: unknown, empty = '—'): string {
  if (v === null || v === undefined || v === '') return empty
  const p = partsOf(v)
  if (p) return `${p.year}-${p.month}-${p.day}`
  const s = String(v)
  if (!ISO_PREFIX.test(s)) return s
  return s.slice(0, 10)
}

/**
 * 时间戳 → 「YYYY-MM-DD HH:mm:ss」（业务时区）；空值给 `empty`。
 * 审计日志、危急值应确认时限、接口监控明细、审签时刻这类原本就显示到秒的位置用——粒度不因换算而丢。
 */
export function fmtDateTimeSec(v: unknown, empty = '—'): string {
  if (v === null || v === undefined || v === '') return empty
  const p = partsOf(v)
  if (p) return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}`
  const s = String(v)
  if (!ISO_PREFIX.test(s)) return s
  return s.length >= 16 ? s.slice(0, 19).replace('T', ' ') : s
}

/** 时间戳 → 「MM-DD HH:mm」（业务时区），移动端窄屏省年份用；空值给 `empty` */
export function fmtMonthDayTime(v: unknown, empty = '—'): string {
  const full = fmtDateTime(v, empty)
  return /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(full) ? full.slice(5) : full
}
