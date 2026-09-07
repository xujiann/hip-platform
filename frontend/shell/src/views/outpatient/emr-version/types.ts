/**
 * 病历版本留痕（/api/emr/versions）的返回体类型。
 *
 * 逐字段抄自 `EmrVersionService`，**不是照着文档猜的**：
 * - 列表项与快照的元信息由 `EmrVersionService#metaMap` 组装，**全部驼峰**
 *   （版本行虽然是 JdbcTemplate 直出的蛇形列，但 metaMap 一列一列重新装过一遍，
 *   所以这一组端点没有本仓常见的「外层驼峰、数组元素蛇形」混用）；
 * - 列表信封是 `{emrType, emrId, total, limit, offset, gate, dedup, items, notice?, notes}`，
 *   **不是** `{items, truncated, limit}` 那一套；
 * - `notice` 只在 `total === 0` 时才有这一个键，其余情况后端根本不 put。
 *
 * 猜错这里不会让构建失败，只会让页面白给——所以每个键都标了出处行为。
 */

/** 版本行元信息（`metaMap`）。`versionNo` 为 null 表示「当前正文」这一侧不是版本记录。 */
export interface VersionMeta {
  versionId: number | null
  versionNo: number | null
  source: string
  contentLen: number
  contentHash: string
  /** 保存人 id；null = 这次保存没有登录上下文，后端如实回 null，**不回填猜测** */
  savedBy: number | null
  savedByName: string | null
  /** Instant，Jackson 默认 ISO-8601（UTC，带 Z）——显示前必须转本地时区 */
  savedAt: string | null
  /** 业务日，后端 SQL 里已 `::text`，不经 JVM 时区换算 */
  savedOn: string | null
}

/** 列表项 = metaMap + 三个列表专有键。**列表不返 content 全文**。 */
export interface VersionListItem extends VersionMeta {
  /** 与「上一版」的字数差；上一版不在本页时为 null（后端只在本页内找 v-1） */
  deltaLen: number | null
  firstVersion: boolean
  /** 仅 withChangedFields=true 时存在，**只有字段名、没有正文** */
  changedFields?: string[]
}

export interface VersionListBody {
  emrType: string
  emrId: number
  total: number
  limit: number
  offset: number
  gate: string
  dedup: boolean
  items: VersionListItem[]
  /** 只在 total === 0 时下发的「不伪造初版」说明——必须原样上屏 */
  notice?: string
  notes: string[]
}

export interface FieldValue {
  label: string
  value: string | null
}

/** 单版查看 / 对比两侧的快照（`snapshotMap`）：metaMap + label + content + fields。 */
export interface VersionSnapshot extends VersionMeta {
  /** `v3`，或当前正文那一侧的 `CURRENT` */
  label: string
  content: string
  /** 按临床阅读顺序排列的 `字段名 -> {label, value}` */
  fields: Record<string, FieldValue>
}

export interface VersionDetailBody extends VersionSnapshot {
  emrType: string
  emrId: number
}

export type DiffStatus = 'ADDED' | 'REMOVED' | 'MODIFIED' | 'UNCHANGED'

/** 一个字段的结构化差异（`FieldDiff#asMap`）。**UNCHANGED 时 from/to 为 null**（长度仍在）。 */
export interface FieldDiff {
  field: string
  label: string
  status: DiffStatus
  from: string | null
  to: string | null
  fromLen: number | null
  toLen: number | null
  /** 首个不同字符下标（0 起），后端按码点边界对齐；UNCHANGED 时 null */
  firstDiffAt: number | null
  /** 去掉公共前后缀的差异片段，超 2000 字后端会截断并加省略号 */
  fromChanged: string | null
  toChanged: string | null
}

export interface DiffSummary {
  added: number
  removed: number
  modified: number
  unchanged: number
  changedFields: string[]
  identical: boolean
}

export interface CompareBody {
  emrType: string
  emrId: number
  from: VersionSnapshot
  to: VersionSnapshot
  summary: DiffSummary
  diffs: FieldDiff[]
  /** 只有 compare-current 会带：右侧不是版本记录、本次对比不写库 */
  notice?: string
}

export interface VersionCounters {
  recorded: number
  deduped: number
  failed: number
  skipped: number
  lastFailure: string | null
  lastFailureAt: string | null
  note: string
}

export interface VersionSettings {
  gate: string
  gateKey: string
  dedup: boolean
  dedupKey: string
  maxContentChars: number
  maxContentCharsKey: string
  /** gate=off 时为 false：「病历修改留痕可追溯」这句合规声明在那一档下不成立 */
  complianceClaimHolds: boolean
  emrTypes: string[]
  sources: string[]
  outpFields: string[]
  inpFields: string[]
  counters: VersionCounters
  notes: string[]
}

/**
 * 字段中文名 —— 与 `EmrVersionService.LABELS` 逐键同源。
 *
 * 只用于**列表页的 changedFields**（那里后端只回字段名）。单版查看与对比页一律用后端
 * 随数据下发的 `label`，不查这张表。未登记的键原样显示英文，不猜也不隐藏。
 */
export const FIELD_LABELS: Record<string, string> = {
  chiefComplaint: '主诉',
  presentIllness: '现病史',
  pastHistory: '既往史',
  physicalExam: '体格检查',
  advice: '处理意见',
  title: '文书标题',
  content: '病历正文',
  roundLevel: '查房级别',
  roundOpinion: '查房意见',
  superiorCorrection: '上级修正意见',
}

export function fieldLabel(field: string): string {
  return FIELD_LABELS[field] ?? field
}

/** ISO-8601（UTC）转本地时间显示。直接 slice 会把 UTC 当北京时间，差 8 小时。 */
export function fmtTime(v: string | null | undefined): string {
  if (!v) return '—'
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? v : d.toLocaleString('zh-CN', { hour12: false })
}
