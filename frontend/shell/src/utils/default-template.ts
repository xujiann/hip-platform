/**
 * v79 车道B（988★）：门诊医生站「新病历自动套用科室默认模板」的纯判定。
 *
 * 产品口径（v79 规划节，主控定）：只在「新病历（工作区无病历）且五段正文全空、本科室有启用的默认模板」时
 * 自动套用；套用后可撤销；已有正文、已签名、无默认模板一律不动。
 *
 * 取数端点 GET /api/emr-templates/default?deptId=&recordType=OUTP（EmrTemplateService.defaultTemplate）：
 *   · 没设默认 → data=null（不报错）；
 *   · 有 → emr_template 整行 `select *` 直出，键是 **snake_case 列名**（name / content / enabled /
 *     template_type / record_type / dept_id / is_default …），与 /emr-templates/visible 同口径；
 *   · 默认模板对当前登录人不可见 → 4066（页面以 __silentCodes 静默吞掉，按「无默认模板」处理）。
 *
 * 拆段与写入一律复用 utils/emr-template.ts（splitTemplateContent / applyTemplate），本文件不另起拆段逻辑。
 * 只改本页表单，不新增任何写病历路径：仍须点「暂存」才落库。
 */
import { EMR_SECTION_DEFS, splitTemplateContent, type EmrFields, type EmrParts, type EmrSectionKey,
  type SkippedSection } from './emr-template'

export interface DefaultTemplate {
  id: number | null
  name: string
  /** undefined = 返回体没带正文列（按 id 再取一次）；null/'' = 模板确实没有正文 */
  content: string | null | undefined
}

const isBlank = (s: string | null | undefined) => !s || s.trim().length === 0

/** 五段正文是否全空（纯空白算空） */
export function emrAllBlank(emr: EmrFields): boolean {
  return EMR_SECTION_DEFS.every((d) => isBlank(emr[d.key]))
}

/**
 * 是否该去取科室默认模板：工作区无病历、未签名、五段全空、本次就诊未被医生撤销过。
 * 任一不满足即「什么都不做」——不发请求、不提示。
 */
export function eligibleForDefaultTemplate(s: {
  hasEmr: boolean
  signed: boolean
  emr: EmrFields
  /** 医生在本页对这次就诊点过「撤销」：同一就诊再次进页（开单后刷新工作区等）不再自动套 */
  declined?: boolean
}): boolean {
  return !s.hasEmr && !s.signed && !s.declined && emrAllBlank(s.emr)
}

/**
 * 从 /emr-templates/default 的 data 取出可用的默认模板；不可用一律 null（等同「无默认模板」）。
 * 兼容 snake_case（实际返回体）与 camelCase。后端 SQL 已限 enabled，这里再判一次是防御，不是口径。
 */
export function pickDefaultTemplate(data: unknown): DefaultTemplate | null {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) return null
  const row = data as Record<string, unknown>
  if (row.enabled === false) return null
  const typeRaw = row.template_type ?? row.templateType
  const tplType = typeof typeRaw === 'string' ? typeRaw.trim().toUpperCase() : ''
  // 默认模板按 dept_id + record_type 取、不看 template_type；RIS 报告模板不是病历模板，不套
  if (tplType && tplType !== 'EMR') return null
  const name = typeof row.name === 'string' ? row.name.trim() : ''
  if (!name) return null
  const id = typeof row.id === 'number' ? row.id : null
  const c = row.content
  const content = c === undefined ? undefined : (typeof c === 'string' ? c : null)
  return { id, name, content }
}

export interface DefaultTemplatePlan {
  /** 要写进五段的值（已与套用前的既有值合并，见 planDefaultTemplate 注释） */
  parts: EmrParts
  /** 模板里不属于五段正文的段（如「初步诊断」），未套用，供页面提示 */
  skipped: SkippedSection[]
}

/**
 * 把模板正文拆段，并与「套用前」的值**无损合并**：
 * 进页时系统自己预填的内容（1092★ 过敏史预填进「既往史」）不能被默认模板盖掉——
 * 那是医生没写过一个字的系统带出，但盖掉它等于把过敏史从病历里拿走。故某段若已有值，
 * 合并为「既有值 + 换行 + 模板值」，一字不丢；空段直接取模板值。
 * 模板没有任何可套的正文 → null（不套、不提示）。
 * 纯文本模板整段落「现病史」（与手动套用时「插入到」的默认段一致）。
 */
export function planDefaultTemplate(content: string | null | undefined, before: EmrFields): DefaultTemplatePlan | null {
  const split = splitTemplateContent(content, 'presentIllness')
  if (split.format === 'empty') return null
  const parts: EmrParts = {}
  for (const d of EMR_SECTION_DEFS) {
    const v = split.parts[d.key]
    if (isBlank(v)) continue
    parts[d.key] = isBlank(before[d.key]) ? (v as string) : `${before[d.key]}\n${v}`
  }
  if (!Object.keys(parts).length) return null
  return { parts, skipped: split.skipped }
}

/** 套用后医生又改过的段（当前值 ≠ 套用时写入的值）——撤销会连同这些修改一起丢，须先确认 */
export function editedSinceApplied(emr: EmrFields, written: EmrParts): EmrSectionKey[] {
  return EMR_SECTION_DEFS
    .filter((d) => written[d.key] !== undefined && emr[d.key] !== written[d.key])
    .map((d) => d.key)
}

/**
 * 撤销：把模板写过的段恢复成套用前的值（新病历即为空；若系统已预填过敏史，则恢复成那句预填）。
 * 模板没碰过的段一字不动。返回恢复了哪些段。
 */
export function revertDefaultTemplate(emr: EmrFields, before: EmrFields, written: EmrParts): EmrSectionKey[] {
  const keys = EMR_SECTION_DEFS.filter((d) => written[d.key] !== undefined).map((d) => d.key)
  for (const k of keys) emr[k] = before[k]
  return keys
}

/** 五段是否与某个快照逐字相同（套用后医生一个字没动 → 离开时不提示「已书写未签名」） */
export function emrEquals(emr: EmrFields, snap: EmrFields): boolean {
  return EMR_SECTION_DEFS.every((d) => emr[d.key] === snap[d.key])
}

/**
 * v79 审阅修补（三）（乙组反驳 B3-1f）：自动套用的默认模板还在、且五段与套用后的快照逐字相同——医生一个字没写。
 * 「提交（签名）」先暂存后，修前那道 4009「病历不存在，请先书写保存」兜底没了，一次点击就能签下一份纯模板骨架；
 * 提交前用本判定拦下。套用后点过「暂存」（autoTpl 已清）即视为医生认可了这份内容，不再拦。
 */
export function untouchedDefaultTemplate(applied: { after: EmrFields } | null | undefined, emr: EmrFields): boolean {
  return !!applied && emrEquals(emr, applied.after)
}
