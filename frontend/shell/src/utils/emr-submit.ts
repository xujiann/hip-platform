/**
 * v79 审阅修补（三）（乙组反驳 B3-2e，2457★）：门诊「提交（签名）」要不要先把屏上内容暂存。
 *
 * 背景：提交前先暂存（乙组 D1）让「签的就是屏上的」成立；但后端 saveEmr 落的 doctorId 即书写人
 * （v43 起的既定口径，DoctorStationService#signEmr 的 4023 正是拿它比签名人）——他人打开别人未签的草稿点提交，
 * 先暂存就把书写医师改成了自己，4023「仅病历书写医师本人可签名」随之被架空。
 *
 * 口径：只在「工作区还没有病历」或「病历的书写医师就是当前登录人」时先暂存；
 * 书写医师是别人时不暂存、直接调签名端点，由后端回 4023（页面不另起一套判定、不改书写医师）。
 * 书写医师未知（历史行 doctor_id 为空）时后端 4023 本就不判，先暂存与修前「先暂存再签」同口径。
 */
export function presaveBeforeSign(s: {
  /** 工作区是否已有病历行（openPatient 时 ws.emr 非空，或本页已暂存过） */
  hasEmr: boolean
  /** 病历行上的书写医师 id（outp_emr.doctor_id）；null = 未知 */
  authorId: number | null
  /** 当前登录人 id（/auth/me 的 id，与后端 currentUserService.idOf 同一 id 空间）；null = 未知 */
  meId: number | null
}): boolean {
  if (!s.hasEmr || s.authorId == null) return true
  return s.meId != null && s.authorId === s.meId
}
