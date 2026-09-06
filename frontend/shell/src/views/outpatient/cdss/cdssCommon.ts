/**
 * v51 CDSS 前端共用工具（车道 F3）。
 *
 * <p>本文件只做**显示**，不做任何判断：没有相似度、没有关键词、没有推断。
 * 后端 AllergyRuleService / DuplicateRxService / PopulationRuleService / RouteRuleService
 * 四个服务反复写明「不预置任何药学知识」，前端再补一层猜测就会把这条纪律作废。
 */

export type Row = Record<string, unknown>

/** 后端返回体里三种列表键名并存：JdbcTemplate 直出的行是**蛇形**，record/手工 Map 是驼峰。此处一律不改写键名。 */
export function columnsOf(rows: Row[] | undefined | null): string[] {
  if (!rows || rows.length === 0) return []
  const keys = new Set<string>()
  rows.forEach((r) => Object.keys(r).forEach((k) => keys.add(k)))
  return [...keys]
}

export function fmt(v: unknown): string {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'boolean') return v ? '是' : '否'
  if (Array.isArray(v)) return v.length === 0 ? '—' : v.map((x) => fmt(x)).join('、')
  if (typeof v === 'object') return JSON.stringify(v)
  const s = String(v)
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s) ? s.slice(0, 16).replace('T', ' ') : s
}

/** 数值展示：**不把「取不到」显示成 0**。null/undefined 一律显示破折号。 */
export function num(v: unknown): string {
  return v === null || v === undefined ? '—' : String(v)
}

/* ================= gate 三档 ================= */

export type GateValue = 'off' | 'warn' | 'block' | string

export function gateTagType(g: GateValue | null): 'info' | 'warning' | 'danger' {
  if (g === 'block') return 'danger'
  if (g === 'warn') return 'warning'
  return 'info'
}

/** gate 档位的**后果**必须写在档位旁边：只显示 "warn" 三个字母，药剂科无从判断现在拦不拦。 */
export function gateMeaning(g: GateValue | null): string {
  switch (g) {
    case 'off':
      return '旁路：不判定、不提示、不拦截'
    case 'warn':
      return '只提示不拦截（命中照常开单，提示回带并留痕）'
    case 'block':
      return '命中即拦截（开单被拒，返业务错误码）'
    default:
      return '未知档位'
  }
}

/* ================= 枚举中文（仅显示，不参与任何判定） ================= */

export const SEVERITY_TEXT: Record<string, string> = {
  MILD: '轻', MODERATE: '中', SEVERE: '重', UNKNOWN: '不详',
  FORBID: '禁用', CAUTION: '慎用', BLOCK: '可拦', WARN: '提示', INFO: '存疑',
  VIOLATION: '违规', NOTE: '仅提示',
}

export const SOURCE_TEXT: Record<string, string> = {
  SELF_REPORT: '患者自述', CLINICAL: '临床观察/既往病历', TEST: '皮试或激发试验',
  TEXT_REVIEW: '自由文本人工核对', OTHER: '其他',
  CLINICIAN_CONFIRMED: '临床确认', LAB_CONFIRMED: '检验确认',
}

export const RESOLUTION_TEXT: Record<string, string> = {
  STRUCTURED: '已确认并登记结构化过敏原',
  NO_ALLERGY: '原文不构成过敏记录',
  UNCLEAR: '无法判定，需再询问（不等于无过敏）',
}

export const POPULATION_TEXT: Record<string, string> = {
  PREGNANCY: '妊娠期', LACTATION: '哺乳期', HEPATIC: '肝功能不全', RENAL: '肾功能不全',
}

export const STATUS_TEXT: Record<string, string> = {
  PREGNANT: '妊娠', LACTATING: '哺乳', NEITHER: '均否（问过了，不是）', UNKNOWN: '未采集',
}

export const LEVEL_TEXT: Record<string, string> = {
  SAME_DRUG: '完全相同药品', SAME_GENERIC: '同通用名', SAME_CATEGORY: '同药理类别',
}

export const SCOPE_TEXT: Record<string, string> = {
  IN_VISIT: '同次就诊', CROSS_VISIT: '跨处方（上次的药未吃完）',
}

export const ROUTE_KIND_TEXT: Record<string, string> = {
  ROUTE_MISMATCH: '途径不符', SOLVENT_FORBID: '溶媒禁配', ROUTE_UNRECOGNIZED: '用法文本未识别（任何档位都不拦）',
}

export const MAPPED_LEVEL_TEXT: Record<string, string> = {
  INGREDIENT: '成分级', PRODUCT: '药品级', CLASS: '药理类别级',
}

/** 枚举翻译：未登记的值**原样显示**，不猜也不隐藏——显示成英文比显示成空白或猜错要好。 */
export function enumText(dict: Record<string, string>, v: unknown): string {
  if (v === null || v === undefined || v === '') return '—'
  const s = String(v)
  return dict[s] ?? s
}

/* ================= 列名中文化 ================= */

const ZH: Record<string, string> = {
  id: 'ID', code: '编码', name: '名称', remark: '备注', note: '说明', enabled: '启用',
  created_at: '创建时间', created_by: '创建人', message: '提示原文', gate: '当次档位',
  patient_id: '患者ID', patient_no: '患者号', patient_name: '患者', sex: '性别',
  birth_date: '出生日期', operator_id: '操作人ID', operator_name: '操作人',
  registration_id: '挂号ID', occurred_at: '发生时刻', drug_id: '药品ID', drug_name: '药品',
  blocked: '是否拦下',
  // 过敏
  allergen_id: '过敏原ID', allergen_code: '过敏原编码', allergen_name: '过敏原',
  allergen_type: '过敏原类型', drug_level: '药物侧粒度', mapped_level: '映射粒度',
  mapped_drug_count: '已映射药品数', patient_count: '生效患者数', member_count: '族成员数',
  severity: '严重度', manifestation: '表现', source: '来源', status: '状态',
  confirmed_at: '确认时刻', confirmed_by: '确认人ID', confirmed_by_name: '确认人',
  revoked_at: '撤销时刻', revoked_by_name: '撤销人', revoke_reason: '撤销原因',
  source_text: '核对时原文快照', resolution: '核对结论', created_count: '本次登记条数',
  reviewed_at: '核对时刻', reviewed_by: '核对人ID', reviewed_by_name: '核对人',
  allergy_history: '过敏史原文', structured_count: '已结构化条数', latest_resolution: '最近结论',
  group_id_lo: '族A(ID)', group_lo_name: '族 A', group_id_hi: '族B(ID)', group_hi_name: '族 B',
  risk_level: '交叉风险', drug_count: '药品数', spec: '规格',
  direct_hits: '直接命中数', cross_hits: '交叉命中数', drug_count_: '送审药品数',
  unreviewed_text: '仍有未核对原文', unmapped_count: '无映射过敏原数', detail: '命中摘要',
  // 重复用药
  scope: '范围', match_level: '匹配层', new_drug_id: '本次药品ID', new_drug_name: '本次药品',
  prior_drug_id: '既往药品ID', prior_drug_name: '既往药品',
  prior_registration_id: '既往挂号ID', prior_visit_date: '既往就诊日',
  prior_days: '既往疗程(天)', remaining_days: '剩余(天)',
  // 特殊人群
  population: '人群', drug_keyword: '药名关键词', lab_item_code: '检验项目码',
  comparator: '比较符', threshold: '阈值', lab_unit: '阈值单位', lab_max_age_days: '检验有效期(天)',
  basis_source: '依据出处', basis_level: '依据级别', evidence: '证据',
  asserted_at: '申报时刻', asserted_by: '申报人ID', asserted_by_name: '申报人',
  valid_until: '失效日', rule_id: '规则ID',
  // 途径与溶媒
  route_code: '途径编码', route_name: '途径名称', intravenous: '静脉途径', is_example: '示例行',
  alias_text: '用法文本(归一化)', solvent_code: '溶媒编码', solvent_name: '溶媒名称',
  verdict: '配伍结论', kind: '提示类型', route_text_raw: '用法原文',
  route_text_norm: '归一化文本', order_id: '医嘱行ID',
}

export function zh(col: string): string {
  return ZH[col] ?? col
}
