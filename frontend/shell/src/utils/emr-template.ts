/**
 * v76 车道B（993★ ③）：门诊医生站「套用正文」的纯逻辑。
 *
 * 门诊病历是五段定长列（outp_emr：主诉 512 / 现病史 2000 / 既往史 1000 / 体格检查 1000 / 处理意见 1000），
 * 而模板正文 emr_template.content（varchar 4000）**没有统一的分段契约**——库里实际有三种形态：
 *   ① 旧 JSON 对象，键恰为五段字段名：{"chiefComplaint":"…","advice":"…"}；
 *   ② 门诊「存为模板」的产物：EmrTemplateService.loadRecord 把五段拼成
 *      concat_ws(chr(10), '主诉：…', '现病史：…', '既往史：…', '体格检查：…', '处理意见：…')（空段省略）；
 *   ③ 住院病历存成的模板：纯自由正文。
 * 识别是**启发式**：解析失败一律回落纯文本，绝不抛错、绝不丢字。
 *
 * 只做前端赋值，不新增任何写病历路径：套用后仍走既有「保存病历」；已签名由后端 4008 兜底。
 */

export type EmrSectionKey = 'chiefComplaint' | 'presentIllness' | 'pastHistory' | 'physicalExam' | 'advice'
export type EmrFields = Record<EmrSectionKey, string>
export type EmrParts = Partial<Record<EmrSectionKey, string>>

/** 五段的顺序、中文标签（与标签行的行首标签逐字一致，也是页面上段名） */
export const EMR_SECTION_DEFS: ReadonlyArray<{ key: EmrSectionKey; label: string }> = [
  { key: 'chiefComplaint', label: '主诉' },
  { key: 'presentIllness', label: '现病史' },
  { key: 'pastHistory', label: '既往史' },
  { key: 'physicalExam', label: '体格检查' },
  { key: 'advice', label: '处理意见' },
]

/** outp_emr 各列宽（V 迁移里的 varchar 长度）；超出走库层 4091 兜底，前端只提示不截断 */
export const EMR_LIMITS: Readonly<Record<EmrSectionKey, number>> = {
  chiefComplaint: 512,
  presentIllness: 2000,
  pastHistory: 1000,
  physicalExam: 1000,
  advice: 1000,
}

const KEY_SET = new Set<string>(EMR_SECTION_DEFS.map((s) => s.key))
const LABEL_TO_KEY = new Map<string, EmrSectionKey>(EMR_SECTION_DEFS.map((s) => [s.label, s.key]))
const labelOf = (k: EmrSectionKey) => EMR_SECTION_DEFS.find((s) => s.key === k)!.label
/** 行首标签：只认全角冒号，与后端拼法逐字对应 */
const LABEL_LINE = /^(主诉|现病史|既往史|体格检查|处理意见)：(.*)$/

export type TemplateFormat = 'json' | 'labeled' | 'plain' | 'empty'
export interface SplitResult {
  format: TemplateFormat
  /** 只含有值的段（空串/纯空白已丢弃） */
  parts: EmrParts
}

const isBlank = (s: string | null | undefined) => !s || s.trim().length === 0

function trySplitJson(text: string): EmrParts | null {
  if (!text.startsWith('{')) return null
  let obj: unknown
  try {
    obj = JSON.parse(text)
  } catch {
    return null
  }
  if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) return null
  const parts: EmrParts = {}
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    if (!KEY_SET.has(k)) continue
    if (typeof v !== 'string' && typeof v !== 'number') continue
    const s = String(v).trim()
    if (s) parts[k as EmrSectionKey] = s
  }
  // 一个五段键都没取到：这不是五段 JSON（可能只是恰好长得像），交给纯文本，免得吞掉整段
  return Object.keys(parts).length ? parts : null
}

function trySplitLabeled(text: string): EmrParts | null {
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  // 首个非空行必须是标签行：前面若有前言，说明这不是「存为模板」的产物，整段按纯文本处理才不丢前言
  const first = lines.find((l) => l.trim().length > 0)
  if (first === undefined || !LABEL_LINE.test(first)) return null
  const buckets: Partial<Record<EmrSectionKey, string[]>> = {}
  let cur: string[] | null = null
  for (const line of lines) {
    const m = LABEL_LINE.exec(line)
    if (m) {
      const key = LABEL_TO_KEY.get(m[1])!
      // 同一标签重复出现：追加到同段，不丢字
      cur = buckets[key] ?? (buckets[key] = [])
      cur.push(m[2])
    } else if (cur) {
      cur.push(line)   // 多行值（含空行）归上一个标签
    }
  }
  const parts: EmrParts = {}
  for (const def of EMR_SECTION_DEFS) {
    const v = (buckets[def.key] ?? []).join('\n').trim()
    if (v) parts[def.key] = v
  }
  return parts
}

/**
 * 把模板正文拆成五段。
 * @param insertTarget 纯文本时整段落到哪一段（默认现病史，与页面「插入到」下拉一致）
 */
export function splitTemplateContent(
  content: string | null | undefined,
  insertTarget: EmrSectionKey = 'presentIllness',
): SplitResult {
  const text = (content ?? '').trim()
  if (!text) return { format: 'empty', parts: {} }
  const json = trySplitJson(text)
  if (json) return { format: 'json', parts: json }
  const labeled = trySplitLabeled(text)
  if (labeled) return { format: 'labeled', parts: labeled }
  return { format: 'plain', parts: { [insertTarget]: text } }
}

export interface OverLimit {
  key: EmrSectionKey
  label: string
  length: number
  limit: number
}

/** 超长判定：按码点计数（PG varchar 按字符），恰等于列宽不算超。只判定，不截断。 */
export function overLimitOf(parts: EmrParts): OverLimit[] {
  const out: OverLimit[] = []
  for (const def of EMR_SECTION_DEFS) {
    const v = parts[def.key]
    if (v === undefined) continue
    const length = [...v].length
    const limit = EMR_LIMITS[def.key]
    if (length > limit) out.push({ key: def.key, label: def.label, length, limit })
  }
  return out
}

export interface ApplyOptions {
  /** 目标段已有内容时询问是否覆盖；参数是将被覆盖的段中文名。返回 false = 医生取消 */
  confirmOverwrite: (labels: string[]) => Promise<boolean>
}
export interface ApplyResult {
  cancelled: boolean
  /** 实际写入的段（按五段顺序） */
  applied: EmrSectionKey[]
  /** 其中原本有内容、被覆盖的段 */
  overwritten: EmrSectionKey[]
  /** 写入后超出列宽的段（不截断，仅供提示） */
  overLimit: OverLimit[]
}

/**
 * 把拆好的段写进 emr（reactive 对象）：只写模板里有值的段；目标段非空先 confirm，取消则一个字不改。
 * confirm 作为注入函数，页面里传 ElMessageBox.confirm 的封装，测试里传桩。
 */
export async function applyTemplate(emr: EmrFields, parts: EmrParts, opts: ApplyOptions): Promise<ApplyResult> {
  const targets = EMR_SECTION_DEFS.filter((d) => !isBlank(parts[d.key]))
  const overwritten = targets.filter((d) => !isBlank(emr[d.key]))
  if (overwritten.length) {
    const ok = await opts.confirmOverwrite(overwritten.map((d) => labelOf(d.key)))
    if (!ok) return { cancelled: true, applied: [], overwritten: [], overLimit: [] }
  }
  for (const d of targets) emr[d.key] = parts[d.key] as string
  const written: EmrParts = {}
  for (const d of targets) written[d.key] = parts[d.key]
  return {
    cancelled: false,
    applied: targets.map((d) => d.key),
    overwritten: overwritten.map((d) => d.key),
    overLimit: overLimitOf(written),
  }
}

/**
 * 门诊下拉只列 record_type 为 OUTP 或空的模板：住院模板的长正文套进门诊会超 2000 字，
 * 而旧 JSON 模板的 record_type 是 null（传 recordType=OUTP 给后端会把它们漏掉），所以在前端过滤。
 * 兼容 snake_case（/emr-templates/visible 直出 jdbc 列名）与 camelCase。
 */
export function outpTemplatesOnly<T extends Record<string, unknown>>(list: T[]): T[] {
  return list.filter((t) => {
    const raw = t.record_type ?? t.recordType
    const rt = typeof raw === 'string' ? raw.trim().toUpperCase() : ''
    return rt === '' || rt === 'OUTP'
  })
}
