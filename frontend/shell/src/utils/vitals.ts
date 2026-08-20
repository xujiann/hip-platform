/**
 * 体征量程单一事实源（1.2.5 v23-B）：桌面护士站与移动端 /m 曾各写一套阈值——
 * 体温桌面 34–43 / 移动 30–45、脉搏 220 vs 250。同一份临床数据两套合理性判断，
 * 同一个患者在两端录入会得到不同的拦截结果，且改阈值必漏改一处。
 *
 * 取值取两端并集中较宽者（临床极端值确实存在：低温治疗 30℃、室上速 250 次/分），
 * 由医务科按院内规范调整时只改这一处。
 */
export interface VitalRange {
  min: number
  max: number
  label: string
  precision?: number
  step?: number
}

export const VITAL_RANGES: Record<string, VitalRange> = {
  temperature: { min: 30, max: 45, label: '体温(℃)', precision: 1, step: 0.1 },
  pulse: { min: 20, max: 250, label: '脉搏' },
  respiration: { min: 5, max: 60, label: '呼吸' },
  sbp: { min: 40, max: 300, label: '收缩压' },
  dbp: { min: 20, max: 200, label: '舒张压' },
  spo2: { min: 50, max: 100, label: '血氧' },
}

/**
 * 文本输入转体征数值：中文句号归一化 + 量程校验。
 * 空串返回 null（该项不录）；非法值抛错由调用方提示——**不得静默转 null**，
 * 否则"成功提示照弹、数据其实为空"（1.1.8 B-12 的原始事故）。
 */
export function parseVital(field: string, v: string): number | null {
  if (v === '' || v == null) return null
  const range = VITAL_RANGES[field]
  if (!range) throw new Error(`未知体征字段：${field}`)
  const n = Number(String(v).replace('。', '.').trim())
  if (Number.isNaN(n)) throw new Error(`${range.label} 不是有效数字：${v}`)
  if (n < range.min || n > range.max) {
    throw new Error(`${range.label} 超出合理范围（${range.min}–${range.max}）：${v}`)
  }
  return n
}
