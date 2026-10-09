/**
 * v79 审阅修补（乙组 D2/N10，偏离表 2457★）：住院病历时间线「修改」的纯逻辑。
 *
 * 未签名即「暂存」，可直接修改（PUT /inpatient/admissions/{admissionId}/records/{recordId}）；
 * 已签名即「已提交」，原文冻结，只能补正——所以「修改」入口只给未签名记录。
 */

/** 只有未签名（暂存）的记录可修改；已签名（已提交）的走补正 */
export function canEditInpRecord(r: Record<string, unknown> | null | undefined): boolean {
  return !!r && r.id != null && !r.signature
}

/** 点「修改」时回填编辑区的值。三级查房的正文即查房意见，上级修正意见单独一栏 */
export function inpEditFormOf(r: Record<string, unknown>) {
  return {
    recordType: String(r.recordType ?? 'PROGRESS'),
    title: String(r.title ?? ''),
    content: String(r.content ?? ''),
    roundLevel: r.roundLevel == null ? null : String(r.roundLevel),
    superiorCorrection: r.superiorCorrection == null ? '' : String(r.superiorCorrection),
  }
}

/**
 * 保存修改的请求体。标题留空不传（后端不动原标题）；上级修正意见只对查房记录传
 * （非查房记录后端本就忽略，不传更干净）。
 */
export function inpUpdatePayload(form: {
  recordType: string
  title: string
  content: string
  superiorCorrection: string
}) {
  const body: { title?: string; content: string; superiorCorrection?: string } = { content: form.content }
  if (form.title.trim()) body.title = form.title.trim()
  if (form.recordType === 'ROUND') body.superiorCorrection = form.superiorCorrection
  return body
}
