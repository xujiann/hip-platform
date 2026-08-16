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
