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
const TZ_SUFFIX = /(Z|[+-]\d{2}:?\d{2})$/
const dtf = new Intl.DateTimeFormat('en-CA', {
  timeZone: BUSINESS_TZ, year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})

function businessParts(d: Date): Record<string, string> {
  const m: Record<string, string> = {}
  for (const p of dtf.formatToParts(d)) m[p.type] = p.value
  return m
}

/** 时间戳 → 「YYYY-MM-DD HH:mm」（业务时区）；空值给 `empty`（默认「—」，与病理 format.ts 同约定） */
export function fmtDateTime(v: unknown, empty = '—'): string {
  if (v === null || v === undefined || v === '') return empty
  const s = String(v)
  if (!ISO_PREFIX.test(s)) return s
  if (TZ_SUFFIX.test(s)) {
    const d = new Date(s)
    if (!Number.isNaN(d.getTime())) {
      const p = businessParts(d)
      return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}`
    }
  }
  return s.length >= 16 ? s.slice(0, 16).replace('T', ' ') : s
}

/** 时间戳/日期 → 「YYYY-MM-DD」（业务时区）；纯日期串原样；空值给 `empty` */
export function fmtDate(v: unknown, empty = '—'): string {
  if (v === null || v === undefined || v === '') return empty
  const s = String(v)
  if (!ISO_PREFIX.test(s)) return s
  if (TZ_SUFFIX.test(s)) {
    const d = new Date(s)
    if (!Number.isNaN(d.getTime())) {
      const p = businessParts(d)
      return `${p.year}-${p.month}-${p.day}`
    }
  }
  return s.slice(0, 10)
}
