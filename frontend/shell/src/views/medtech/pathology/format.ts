/**
 * 病理工作台（v48 车道 F1）共用的显示工具与字典。
 *
 * <p><b>字典值域一律照抄后端白名单</b>，不在前端另立一套：
 * 标本类别照 {@code PathologyRegistryController.TYPE_CODES} 与 {@code chk_path_specimen_type}，
 * 染色类型照 {@code PathologyProcessController.STAIN_TYPES}，
 * 切片质量照 {@code SLIDE_QUALITIES}，流转节点照 {@code chk_path_process_node} 的 12 档。
 * 中文名照 {@code PathQcController.TYPE_NAME} 与该控制器 node_name 的 case 分支逐字取，
 * 免得同一个 FROZEN 在质控页叫「术中冰冻」、在工作台叫「冰冻」。
 *
 * <p><b>返回体键名驼峰与蛇形并存是后端的既定口径，不在前端做归一</b>：
 * JdbcTemplate 直出的行是蛇形（path_no / patient_name / block_code……），
 * 外层信封与手工装配的对象是驼峰（pathNo / blockCount / doubleSignGate……）。
 * 归一化会掩盖「这一段是数据库行、那一段是控制器装配的」这个真实差别，读代码的人反而对不上账。
 */

import { fmtDateTime } from '../../../utils/date'

/**
 * 列表行一律按无类型字典消费：本域各端点的行是 JdbcTemplate 直出的数据库行，
 * 列集合随 SQL 变化（如 {@code s.*}），写死接口反而会在后端加列时静默漏显示。
 */
export type Row = Record<string, unknown>

export const SPECIMEN_TYPES = [
  { value: 'ROUTINE', label: '常规', code: 'C' },
  { value: 'FROZEN', label: '术中冰冻', code: 'F' },
  { value: 'CYTOLOGY', label: '细胞学', code: 'Y' },
  { value: 'CONSULT', label: '会诊', code: 'H' },
  { value: 'MOLECULAR', label: '分子病理', code: 'M' },
]

/** path_specimen.status 的既有三档——v48 刻意不扩值域，「已拒收」看 rejected_at 而不是 status */
export const SPECIMEN_STATUSES = [
  { value: 'COLLECTED', label: '已登记' },
  { value: 'RECEIVED', label: '已核收' },
  { value: 'DIAGNOSED', label: '已诊断' },
]

export const SOURCES = [
  { value: 'OUTP', label: '门诊' },
  { value: 'INP', label: '住院' },
]

export const STAIN_TYPES = [
  { value: 'HE', label: 'HE' },
  { value: 'IHC', label: '免疫组化' },
  { value: 'SPECIAL', label: '特殊染色' },
  { value: 'MOLECULAR', label: '分子病理' },
]

export const SLIDE_QUALITIES = [
  { value: 'GOOD', label: '优（GOOD）' },
  { value: 'FAIR', label: '良（FAIR）' },
  { value: 'POOR', label: '差（POOR）' },
]

/** chk_path_process_node 的 12 档，中文名与 PathQcController 的 node_name 逐字一致 */
export const PROCESS_NODES: Record<string, string> = {
  RECEIVE: '核收',
  REJECT: '拒收',
  GROSSING: '取材',
  DEHYDRATE: '脱水',
  EMBED: '包埋',
  SECTION: '切片',
  STAIN: '染色',
  READ: '阅片',
  FIRST_SIGN: '初诊签名',
  SECOND_SIGN: '复诊签名',
  ISSUE: '报告签发',
  SUPPLEMENT: '补充报告',
  // v58（V165）：技术医嘱进流转节点
  TECH_ORDER: '下达特检医嘱',
  TECH_DONE: '确认完成特检医嘱',
  TECH_CANCEL: '取消特检医嘱',
}

export function typeName(v: unknown): string {
  const s = v == null ? '' : String(v)
  return SPECIMEN_TYPES.find((t) => t.value === s)?.label ?? (s === '' ? '（未填类别）' : s)
}

export function statusName(v: unknown): string {
  const s = v == null ? '' : String(v)
  return SPECIMEN_STATUSES.find((t) => t.value === s)?.label ?? s
}

export function stainName(v: unknown): string {
  const s = v == null ? '' : String(v)
  return STAIN_TYPES.find((t) => t.value === s)?.label ?? s
}

export function nodeName(v: unknown): string {
  const s = v == null ? '' : String(v)
  return PROCESS_NODES[s] ?? s
}

export function sourceName(v: unknown): string {
  return v === 'OUTP' ? '门诊' : v === 'INP' ? '住院' : String(v ?? '—')
}

/** 特检技术医嘱状态（chk_path_tech_status 三档；ALL 是 v55 给全院清单加的显式取值，不是库值） */
export const TECH_STATUSES = [
  { value: 'ORDERED', label: '待执行' },
  { value: 'DONE', label: '已完成' },
  { value: 'CANCELLED', label: '已取消' },
]

export function techStatusName(v: unknown): string {
  const s = v == null ? '' : String(v)
  return TECH_STATUSES.find((t) => t.value === s)?.label ?? s
}

export function techStatusTag(v: unknown): 'warning' | 'success' | 'info' {
  return v === 'ORDERED' ? 'warning' : v === 'DONE' ? 'success' : 'info'
}

/**
 * 流转异常四类（v55），中文名与 {@code PathologyProcessController.ANOMALY_KIND_NAMES} 逐字一致。
 * 后端行里已带 kind_name，这里只用于下拉与后端没回 kind_name 时的兜底。
 */
export const ANOMALY_KINDS = [
  { value: 'STALLED', label: '超时未流转' },
  { value: 'SECTION_WITHOUT_EMBED', label: '切片前无包埋记录' },
  { value: 'DIAGNOSED_WITHOUT_STAIN', label: '诊断前无已染色切片' },
  { value: 'ISSUED_WITHOUT_DOUBLE_SIGN', label: '签发时缺双签' },
]

export function anomalyName(v: unknown): string {
  const s = v == null ? '' : String(v)
  return ANOMALY_KINDS.find((k) => k.value === s)?.label ?? s
}

export function anomalyTag(v: unknown): 'danger' | 'warning' | 'info' {
  return v === 'STALLED' ? 'danger' : v === 'ISSUED_WITHOUT_DOUBLE_SIGN' ? 'danger' : 'warning'
}

/**
 * 报告进度：由 diagnosed_at / report_issued_at 派生，不新造状态值。
 * 既有 status 只有 COLLECTED/RECEIVED/DIAGNOSED 三档，「已签发」看的是 report_issued_at 这一列。
 */
export function reportStage(row: Row): { label: string; tag: 'info' | 'warning' | 'success' } {
  if (row.report_issued_at) return { label: '已签发', tag: 'success' }
  if (row.diagnosed_at) return { label: '已诊断未签发', tag: 'warning' }
  return { label: '未诊断', tag: 'info' }
}

/**
 * 通用取值渲染。
 *
 * <p><b>null 一律显示「—」而不是 0 或空白</b>：本域大量字段是「刻意没采集」
 * （V144 零回填），把 null 画成 0 正是本版要消灭的假象。
 */
export function fmt(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—'
  if (typeof v === 'boolean') return v ? '是' : '否'
  const s = String(v)
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s) ? fmtDateTime(s) : s
}

/** 时间戳截到分钟（业务时区）；空值给「—」——委托 utils/date 的 fmtDateTime，带偏移的线格式才换算 */
export function fmtTime(v: unknown): string {
  return fmtDateTime(v)
}

export function num(v: unknown): number {
  return typeof v === 'number' ? v : Number(v ?? 0)
}

/** 表格列由首行键推出：各指标列结构不同，写死列名必然漏列 */
export function columnsOf(rows: Row[] | undefined): string[] {
  if (!rows || rows.length === 0) return []
  return Object.keys(rows[0])
}

export function keysOf(row: Row | undefined | null): string[] {
  return row ? Object.keys(row) : []
}

/**
 * 覆盖率显示：把「分子 / 分母」连同百分比一起给出。
 *
 * <p><b>只显示百分比是危险的</b>——「100%」既可能是 200/200 也可能是 2/2。
 * 本域的字段覆盖率恰恰经常是后者，故两者必须同屏。分母为 0 时给「无数据」而不是 0%。
 */
export function ratio(numerator: unknown, denominator: unknown): string {
  const n = num(numerator)
  const d = num(denominator)
  if (d === 0) return `${n} / 0（无数据）`
  return `${n} / ${d}（${((100 * n) / d).toFixed(1)}%）`
}

/**
 * 列名中文化——与 {@code PathQcController.columnLabel()} 逐键同源（CSV 导出走后端那一份）。
 * 未登记的列名原样显示英文，<b>不猜也不隐藏</b>：漏一个键只是显示成列名，
 * 比显示成空白或猜错一个意思要好。
 */
const ZH: Record<string, string> = {
  // 标本主键与身份
  id: 'ID',
  specimen_id: '标本ID',
  specimenId: '标本ID',
  path_no: '病理号',
  barcode: '条码',
  part_no: '部位序号',
  source: '来源',
  patient_id: '患者ID',
  patient_no: '患者号',
  patient_name: '患者',
  sex: '性别',
  birth_date: '出生日期',
  dept_name: '科室',
  item_name: '申请项目',
  group_no: '组号',
  order_id: '门诊医嘱ID',
  inp_order_id: '住院医嘱ID',
  order_status: '医嘱状态',
  registered_parts: '已登记部位数',
  clinical_summary: '临床摘要',
  specimen_type: '标本类别',
  type_name: '标本类别',
  specimen_desc: '标本描述',
  sampling_site: '取材部位',
  clinical_diagnosis: '临床诊断',
  urgent: '加急',
  status: '状态',
  // 时间
  created_at: '创建时刻',
  collected_at: '登记时刻',
  received_at: '签收时刻',
  fixative: '固定液',
  fixed_at: '固定时刻',
  diagnosed_at: '写完诊断时刻',
  first_signed_at: '初诊签名时刻',
  second_signed_at: '复诊签名时刻',
  report_issued_at: '报告签发时刻',
  rejected_at: '拒收时刻',
  reject_reason: '拒收原因',
  pathologist_name: '诊断医师',
  first_signer_name: '初诊签名人',
  second_signer_name: '复诊签名人',
  hours_since_received: '距签收(小时)',
  // 接收
  submitted: '送检总数(含拒收)',
  rejected: '拒收数',
  received: '已签收数',
  not_received: '未签收数',
  received_rate_pct: '签收率(%)',
  negative_interval: '签收早于登记(补录)',
  within_30min: '30分钟内签收',
  within_2h: '2小时内签收',
  within_24h: '24小时内签收',
  over_24h: '超24小时签收',
  median_minutes: '登记→签收中位数(分钟)',
  p90_minutes: '登记→签收P90(分钟)',
  receive_minutes: '登记→签收(分钟)',
  receive_band: '签收时长分档',
  // 固定
  with_fixative: '已录固定液',
  with_fixed_at: '已录固定时刻',
  fixation_recorded: '固定信息完整数',
  fixation_recorded_rate_pct: '固定信息完整率(%,非国标规范率)',
  fixatives: '实际录入的固定液',
  fixation_status: '固定信息状态',
  minutes_to_fixation: '登记→固定(分钟)',
  // 报告
  issued: '签发份数',
  timely: '及时数',
  overdue: '超时数',
  unjudgeable: '无法判定(无签收时刻)',
  timely_rate_pct: '及时率(%)',
  avg_tat_hours: '平均周转(小时)',
  median_tat_hours: '周转中位数(小时)',
  avg_tat_minutes: '平均周转(分钟)',
  median_tat_minutes: '周转中位数(分钟)',
  tat_hours: '签收→签发(小时)',
  tat_minutes: '签收→签发(分钟)',
  judgement: '判定',
  with_tech_order: '其中加做过特检',
  urgent_cases: '其中加急',
  diagnosed: '写完诊断数',
  // 双签
  with_first_sign: '已初签',
  with_second_sign: '已复签',
  double_signed: '已双签',
  double_sign_rate_pct: '双签完成率(%)',
  no_sign: '未签名即签发',
  only_second_sign: '仅复签(异常)',
  same_person_double_sign: '同一人双签(异常)',
  sign_status: '签名状态',
  // 切片
  stain_type: '染色类型编码',
  stain_name: '染色类型',
  stain_item: '染色项目',
  slides: '切片数',
  graded: '已评质量数',
  good: '优(GOOD)',
  fair: '良(FAIR)',
  poor: '差(POOR)',
  good_rate_pct: '优良率(%,仅GOOD)',
  good_or_fair_rate_pct: '优良率(%,GOOD+FAIR)',
  grade_coverage_pct: '质量评价覆盖率(%)',
  slide_id: '切片ID',
  slide_code: '切片编码',
  slide_no: '片号',
  quality: '切片质量',
  stained_at: '染色时刻',
  stained_by: '染色人ID',
  stained_by_name: '染色人',
  stained: '已录染色时刻',
  slide_count: '切片数',
  stained_slide_count: '已染色切片数',
  he: 'HE',
  ihc: '免疫组化',
  special_stain: '特殊染色',
  molecular: '分子病理',
  // 蜡块
  block_id: '蜡块ID',
  block_code: '蜡块编码',
  block_no: '块号',
  block_count: '蜡块数',
  tissue_desc: '取材组织描述',
  dehydrate_batch: '脱水篮批次',
  dehydrate_batches: '脱水篮批次数',
  embedded_at: '包埋时刻',
  embedded_by: '包埋人ID',
  embedded_by_name: '包埋人',
  blocks: '蜡块数',
  embedded: '已确认包埋',
  embedded_count: '已包埋数',
  pending_count: '未包埋数',
  batch_no: '脱水篮批次',
  progress: '进度',
  first_block_created_at: '最早建块时刻',
  last_block_created_at: '最晚建块时刻',
  last_embedded_at: '最后包埋时刻',
  blocks_per_specimen: '蜡块/标本',
  specimens: '涉及标本数',
  specimen_count: '涉及标本数',
  // 流转
  node: '环节编码',
  node_name: '环节',
  events: '打点次数',
  operators: '操作人数',
  median_hours_from_receive: '距签收中位数(小时)',
  no_receive_time: '无签收时刻(不进中位数)',
  process_id: '打点ID',
  occurred_at: '打点时刻',
  operator_id: '操作人ID',
  operator_name: '操作人',
  remark: '备注',
  hours_from_receive: '距签收(小时)',
  // 工作量
  registered: '登记标本数',
  outp_source: '门诊来源',
  inp_source: '住院来源',
  routine: '常规',
  frozen: '术中冰冻',
  cytology: '细胞学',
  consult: '会诊',
  type_unfilled: '类别未填',
  issued_reports: '首次报告签发',
  supplement_reports: '补充报告',
  supplement_count: '补充报告数',
  report_kind: '报告类型',
  report_time: '报告时刻',
  signer_id: '签名人ID',
  signer_name: '签名人',
  seq_no: '补充报告序号',
  reason: '原因',
  content: '内容',
  user_id: '用户ID',
  user_name: '姓名',
  first_signed: '初诊签名数',
  second_signed: '复诊签名数',
  grossing: '取材打点数',
  tech_orders: '特检开单数',
  activities: '操作次数合计',
  activity: '操作',
  act_time: '操作时刻',
  // 特检
  tech_order_id: '技术医嘱ID',
  tech_type: '技术类型编码',
  tech_name: '技术类型',
  tech_item: '技术项目',
  ordered: '开单数',
  done: '已完成',
  pending: '未完成',
  cancelled: '已取消',
  ordered_at: '开单时刻',
  done_at: '完成时刻',
  ordered_by: '开单人ID',
  ordered_by_name: '开单人',
  done_by: '完成人ID',
  done_by_name: '完成人',
  cancelled_at: '取消时刻',
  cancelled_by: '取消人ID',
  cancelled_by_name: '取消人',
  cancel_reason: '取消原因',
  median_hours_to_done: '开单→完成中位数(小时)',
  // 覆盖率段
  with_specimen_type: '已录标本类别',
  with_path_no: '已录病理号',
  with_sampling_site: '已录取材部位',
  with_clinical_diagnosis: '已录临床诊断',
  with_received_at: '已录签收时刻',
  with_diagnosed_at: '已录诊断时刻',
  with_first_sign_at: '已初诊签名',
  with_report_issued: '已录报告签发',
  diagnosed_not_issued: '写了诊断未走签发',
  with_dehydrate_batch: '已归入脱水篮',
  with_embedded_at: '已录包埋时刻',
  with_stained_at: '已录染色时刻',
  with_quality: '已评切片质量',
  process_events: '流转打点数',
  distinct_nodes: '出现过的环节数',
  note: '口径说明',
  created_by: '建档人ID',
  // v55 可达性收口新增列
  hours_since_ordered: '距开单(小时)',
  tech_type_name: '技术类型',
  kind: '异常类别编码',
  kind_name: '异常类别',
  anchor_at: '异常发生时刻',
  last_node: '最近环节编码',
  last_node_name: '最近环节',
  last_node_at: '最近环节时刻',
  hours: '已停滞(小时)',
  hours_since_prev: '距上一环节(小时)',
  detail: '异常说明',
  created_by_name: '建档人',
}

export function zh(col: string): string {
  return ZH[col] ?? col
}

/** 备注类长列给宽一点，其余按数值列常宽 */
export function colWidth(col: string): number {
  if (col === 'note' || col === 'remark' || col === 'reason' || col === 'content'
      || col === 'tissue_desc' || col === 'reject_reason' || col === 'fixatives'
      || col === 'clinical_diagnosis' || col === 'specimen_desc') return 220
  if (col === 'patient_name' || col === 'path_no' || col === 'block_code'
      || col === 'slide_code' || col === 'item_name') return 140
  return 110
}

/**
 * 最近 30 天（含今天），与后端缺省窗口 to-29 天一致。
 *
 * **必须取本地日期，不能用 `toISOString()`**（v55 复核实测的 D3）：
 * `toISOString()` 返回 UTC 时刻，`slice(0,10)` 切出来的是 **UTC 日期**。
 * 北京时间 0–8 点打开页面，UTC 还在昨天，默认窗就止于**昨天**——
 * 今天发生的跳节点三类（含 counts）整体不显示，且日期框不可清空、前端恒传 from/to，
 * 后端 `BusinessDates.today()` 的缺省窗被绕过，用户看不出少了什么。
 * 这与本仓 Java 侧「裸 LocalDate.now() 取 JVM 时区」是同一类时区缺陷，只是换到了浏览器。
 * PathQcView 此前同用本函数，同病同修。
 */
export function defaultRange(): [string, string] {
  const to = new Date()
  const from = new Date(to.getTime() - 29 * 86400000)
  const local = (d: Date) =>
    `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return [local(from), local(to)]
}
