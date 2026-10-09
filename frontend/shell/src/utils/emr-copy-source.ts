/**
 * v79 审阅修补（乙组 D4，偏离表 1082★/2458）：只读病历页的「复制来源患者」取法。
 *
 * 跨患者粘贴管控（`useEmrPasteGuard`，EmrRefDrawer.vue）只认复制时记进本地伴随状态的来源患者。
 * 此前只有门诊、住院两个编辑容器登记来源，从患者 360、病历版本页复制他人病历再粘贴，block 档照样放行。
 * 本文件给这两页各一个纯函数，算出「本页当前展示的正文属于哪个患者」——**认不出就如实给 null**，
 * 管控对 null 侧一律放行（不猜、不把别人的病历安到当前患者头上）。
 */

export interface CopyPatient {
  patientId: number | null
  patientName: string
}

function toId(v: unknown): number | null {
  const n = typeof v === 'number' ? v : typeof v === 'string' && /^\d+$/.test(v) ? Number(v) : NaN
  return Number.isSafeInteger(n) && n > 0 ? n : null
}

/**
 * 患者 360：来源患者取**正在查看的那份文档**的 patientId（`/cdr/documents/{id}` 明细带回），
 * 不取左侧选中的患者——全文检索页签点开的文档可能属于任何患者。
 * 姓名只在左侧选中的恰好是同一患者时才有；不同或未选时留空（弹框里显示为「其他患者」）。
 */
export function cdrDocCopySource(
  viewing: Record<string, unknown> | null | undefined,
  current: Record<string, unknown> | null | undefined,
): CopyPatient {
  const patientId = toId(viewing?.patientId)
  const sameAsCurrent = patientId != null && toId(current?.id) === patientId
  return { patientId, patientName: sameAsCurrent ? String(current?.name ?? '') : '' }
}

/**
 * 病历版本页：来源患者取版本列表随体带回的 patientId/patientName（EmrVersionService#list，
 * 病历行不存在时两键为 null）。列表未加载时为 null。
 */
export function versionListCopySource(
  body: { patientId?: unknown; patientName?: unknown } | null | undefined,
): CopyPatient {
  return {
    patientId: toId(body?.patientId),
    patientName: body?.patientName == null ? '' : String(body.patientName),
  }
}
