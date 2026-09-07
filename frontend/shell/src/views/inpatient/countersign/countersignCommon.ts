/**
 * v53 车道 W2「上级医师审签」前端共用件。
 *
 * ============================================================================
 * 【契约实读记录 —— 写在这里是为了下一个人不必再猜一遍】
 * ============================================================================
 * 后端 CountersignController（/api/inpatient/countersign）的返回体**驼峰与蛇形混用**，
 * 分界线是「这一层是 Java 手搭的 Map，还是 JdbcTemplate.queryForList 直出的行」：
 *
 *   GET  /config
 *        全驼峰：gate / gateKey / requiredTypes[] / dueHours / notes[]
 *
 *   GET  /check?admissionId=
 *        全驼峰：admissionId / gate / blocked / blockCode / blockMessage /
 *                findings[{ code, recordId, recordType, text }] / warnings[] /
 *                requiredTypes[] / caveat
 *        —— findings 的元素也是驼峰（Controller 里逐字段手搭的 LinkedHashMap）。
 *
 *   GET  /pending
 *        外层驼峰：dueHours / requiredTypes[] / gate / includeArchived / limit / offset / items[]
 *        **items 的元素是蛇形**（JdbcTemplate 直出）：
 *        record_id / admission_id / record_type / title / created_at / author_id / author_name /
 *        admission_no / dept_id / dept_name / ward_id / ward_name / admission_status /
 *        patient_id / patient_name / overdue / countersign_count
 *        **注意：没有 total、没有 truncated** —— 只有 limit/offset，见下方 PAGING_NOTE。
 *
 *   GET  /records/{recordId}
 *        外层驼峰：recordId / countersigns[] / validForCurrentContent
 *        **countersigns 的元素同样是蛇形**：id / record_id / author_id / author_name /
 *        countersigner_id / countersigner_name / countersigner_title / countersigned_at /
 *        opinion / content_sha256 / content_len / version_id / version_no / version_source / stale
 *
 *   POST /records/{recordId}
 *        **成功体全驼峰**（这一层是手搭的 Map，不是数据库行）：recordId / admissionId /
 *        recordType / authorId / countersignerId / countersignedAt / contentSha256 /
 *        versionId / versionNo / versionSource / gate / warnings[]
 *
 * 于是同一个概念在本组端点里有两种写法并存：待审签列表的行里叫 `version_source`，
 * 审签成功体里叫 `versionSource`；列表行里叫 `record_id`，check 的 findings 里叫 `recordId`。
 * 猜错不会让构建失败，只会让页面空白——本文件把两种写法都固化成常量与取值函数。
 *
 * 【信封】统一 R{ code, message, data }；client.ts 的响应拦截器已判 code!==0 并 reject
 * （同时弹红字），故本车道只读 resp.data.data，不再自行判 code。
 */

export type Row = Record<string, unknown>

/**
 * 空值一律显示为「—」，**绝不显示成 0、也不显示成空白**。
 * 顺手把 ISO 时刻截到分钟：本组端点里 created_at / countersigned_at 都是 timestamptz。
 */
export function txt(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—'
  if (typeof v === 'boolean') return v ? '是' : '否'
  const s = String(v)
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s) ? s.slice(0, 16).replace('T', ' ') : s
}

/** 时刻显示到秒（审签时刻是举证材料，分钟粒度不够） */
export function ts(v: unknown): string {
  if (v === null || v === undefined || v === '') return '—'
  const s = String(v)
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s) ? s.slice(0, 19).replace('T', ' ') : s
}

/** 数值：null/undefined 显示「—」而不是 0——「没取到」与「是 0」必须分得开 */
export function num(v: unknown): string {
  return v === null || v === undefined ? '—' : String(v)
}

/** 病历类型中文名（与 InpDoctorView.recordTypeNames 同源）。未登记的类型**原样显示**，不猜不隐藏。 */
export const RECORD_TYPE_TEXT: Record<string, string> = {
  ADMISSION: '入院记录',
  FIRST_PROGRESS: '首次病程',
  PROGRESS: '病程记录',
  ROUND: '三级查房',
  PREOP: '术前小结',
  DISCHARGE: '出院小结',
}

export function recordTypeText(v: unknown): string {
  const k = String(v ?? '')
  return RECORD_TYPE_TEXT[k] ? `${RECORD_TYPE_TEXT[k]}（${k}）` : (k || '—')
}

/** inp_admission.status：本仓只有两态（InpAdmission.java:51） */
export function admissionStatusText(v: unknown): string {
  const k = String(v ?? '')
  return ({ IN_HOSPITAL: '在院', DISCHARGED: '已出院' } as Record<string, string>)[k] ?? (k || '—')
}

/**
 * gate 三态。**坏配置回落 warn 不回落 off**（CountersignService.gate()），
 * 所以页面上看到 warn 有两种可能：真的配了 warn，或者配歪了被回落——两者行为一致，故不区分显示。
 */
export const GATE_META: Record<string, { label: string; tag: 'info' | 'warning' | 'danger' }> = {
  off: { label: 'off　不校验（缺项照算，但不提示、不拦截）', tag: 'info' },
  warn: { label: 'warn　提示但放行（默认档）', tag: 'warning' },
  block: { label: 'block　未完成审签不得出院/归档（5725）', tag: 'danger' },
}

export function gateMeta(v: unknown) {
  const k = String(v ?? '')
  return GATE_META[k] ?? { label: k || '—', tag: 'info' as const }
}

/**
 * ============================================================================
 * version_source 三态 —— 本页最不能糊弄的一块
 * ============================================================================
 * 「本次审签绑定的是哪一版」。DIGEST_ONLY **绝不能显示成空白**：空白会让人以为漏了什么，
 * 而它其实是一个确定的、有原因的状态。原因（warnings）由后端随体下发，见各页的 warnings 原样上屏；
 * 这里只给三态本身的含义（枚举释义，不是对后端某个值的复述）。
 */
export interface VersionSourceMeta {
  label: string
  tag: 'success' | 'warning' | 'info' | 'danger'
  meaning: string
}

export const VERSION_SOURCE_META: Record<string, VersionSourceMeta> = {
  VERSION_TABLE: {
    label: '已绑定版本号',
    tag: 'success',
    meaning: '在病历版本表里取到了这份病历的版本行，且**那一版的正文与审签当时的正文逐字相等**，'
      + '所以「签的是哪一版」有版本号可指。',
  },
  NO_VERSION_ROW: {
    label: '版本表无此病历的版本行',
    tag: 'warning',
    meaning: '版本表在，但这份病历在里面没有任何版本记录——版本留痕上线之前书写的病历本就没有。'
      + '不是漏了，是当时没采集。本次审签只绑定正文摘要（sha256），摘要照样能证明签的是哪一份正文。',
  },
  DIGEST_ONLY: {
    label: '只绑定正文摘要，未绑版本号',
    tag: 'warning',
    meaning: '没能给出版本号，只可能是这几种原因之一：版本表尚未就位 / 寻址配置对不上 / 读表失败 / '
      + '**取到的最新一版正文与本次审签的正文不一致（多为审签与保存并发）**。'
      + '最后一种下后端刻意不绑版本号——绑错比绑不上坏：绑不上只是信息缺失，绑错是信息错误。'
      + '正文摘要（sha256）在任何情况下都成立，仍可证明本次签的是哪一份正文。具体是哪一种，看后端随体下发的 warnings。',
  },
}

/** 未知取值**原样显示并标红**：不猜、不隐藏、更不显示成空白 */
export function versionSourceMeta(v: unknown): VersionSourceMeta {
  const k = String(v ?? '')
  if (VERSION_SOURCE_META[k]) return VERSION_SOURCE_META[k]
  return {
    label: k ? `未知取值：${k}` : '后端未返回 version_source',
    tag: 'danger',
    meaning: '本前端只认识 VERSION_TABLE / NO_VERSION_ROW / DIGEST_ONLY 三态。'
      + '出现别的取值说明前后端口径已经不一致，请勿据此判断「签的是哪一版」。',
  }
}

/** /check 的 findings.code（CountersignService.CODE_*）。新增码只会追加，既有码不改名。 */
export const FINDING_META: Record<string, { label: string; tag: 'danger' | 'warning' }> = {
  NOT_COUNTERSIGNED: { label: '未经上级审签', tag: 'danger' },
  COUNTERSIGN_STALE: { label: '审签后正文被改，原审签已失效', tag: 'warning' },
}

export function findingMeta(v: unknown) {
  const k = String(v ?? '')
  return FINDING_META[k] ?? { label: k || '—', tag: 'danger' as const }
}

/** 摘要太长，列表里只显示头尾，完整值走 tooltip / 复制 */
export function shortSha(v: unknown): string {
  const s = String(v ?? '')
  return s.length > 16 ? `${s.slice(0, 8)}…${s.slice(-8)}` : (s || '—')
}

/**
 * 分页口径说明（多处复用，故收在这里）。
 * GET /pending 只返 limit/offset，**不返总条数、也不返 truncated**——
 * 前端要显示「共 N 条」就只能自己编一个，那正是本版明令禁止的猜测。故一律不显示总数。
 */
export const PAGING_NOTE = '后端 /pending 只返回 limit 与 offset，不返回总条数（也没有 truncated 标志），'
  + '所以本页不显示「共 N 条」「第 X / Y 页」——分母未知时给出的百分比与页数都是编的。'
  + '判断「后面还有没有」的唯一可靠办法：本页取满 limit 条时，多半还有下一页。'

/** 审签意见长度上限，与后端 CountersignService.OPINION_MAX 一致（超长返 5724） */
export const OPINION_MAX = 500

/** 后端 /pending 的 limit 上限（CountersignService.MAX_LIMIT，越界返 5726） */
export const LIMIT_MAX = 200
