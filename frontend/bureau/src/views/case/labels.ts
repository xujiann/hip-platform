// 字典标签（与后端枚举一致）
export const SOURCE: Record<string, string> = {
  INSPECTION: '监督检查', COMPLAINT: '投诉举报', TRANSFER: '部门移送',
  ASSIGNED: '上级交办', MONITOR: '智能监控',
}
export const PARTY_TYPE: Record<string, string> = {
  AGENCY: '经办机构', PROVIDER: '定点医药机构', INDIVIDUAL: '自然人', OTHER: '其他主体',
}
export const CLUE_STATUS: Record<string, string> = {
  PENDING: '核查中', FILED: '已立案', REJECTED: '不予立案',
}
export const CASE_STATUS: Record<string, string> = {
  INVESTIGATING: '调查中', SUSPENDED: '已中止', TERMINATED: '已终止',
  REPORTED: '调查终结', NOTIFIED: '已告知', DECIDED: '已决定',
  DELIVERED: '已送达', CLOSED: '已结案',
}
export const CASE_STATUS_TAG: Record<string, string> = {
  INVESTIGATING: 'primary', SUSPENDED: 'warning', TERMINATED: 'info',
  REPORTED: 'primary', NOTIFIED: 'warning', DECIDED: 'success',
  DELIVERED: 'success', CLOSED: 'info',
}
export const EVIDENCE_TYPE: Record<string, string> = {
  DOCUMENT: '书证', PHYSICAL: '物证', AV: '视听资料', EDATA: '电子数据',
  TESTIMONY: '证人证言', STATEMENT: '当事人陈述', EXPERT: '鉴定意见', RECORD: '勘验/现场笔录',
}
export const DOC_TYPE: Record<string, string> = {
  SCENE_RECORD: '现场笔录', INQUIRY_RECORD: '询问笔录', ASSIST_LETTER: '协查函',
  FINAL_REPORT: '调查终结报告', SUSPEND_DECISION: '中止决定书', RESUME_NOTICE: '恢复通知',
  TERMINATE_DECISION: '终止决定', NOTICE: '告知书', HEARING_RECORD: '听证笔录',
  MEETING_RECORD: '集体讨论记录', DECISION: '决定书', DELIVERY_RECEIPT: '送达回证',
  CLOSE_REPORT: '结案报告', OTHER: '其他',
}
export const DECISION_TYPE: Record<string, string> = {
  PUNISH: '行政处罚', NO_PUNISH: '不予处罚', NOT_ESTABLISHED: '违法事实不成立',
  TRANSFER_ADMIN: '移送其他部门', TRANSFER_JUDICIAL: '移送司法机关',
}
export const DELIVERY_METHOD: Record<string, string> = {
  DIRECT: '直接送达', MAIL: '邮寄送达', LEFT: '留置送达', ELECTRONIC: '电子送达', ANNOUNCE: '公告送达',
}
export const EXEC_KIND: Record<string, string> = {
  FINE: '罚款', RECOUP: '退回基金', CONFISCATE: '没收违法所得', LATE_FEE: '加处罚款',
}
export const EXEC_METHOD: Record<string, string> = {
  ONSITE: '当场收缴', BANK: '银行缴付', INSTALLMENT: '分期缴纳',
}
export const EXCLUSION_REASON: Record<string, string> = {
  TEST: '检测检验', APPRAISE: '鉴定', HEARING: '听证', ANNOUNCE: '公告', EXPERT: '专家评审',
}
export const REVIEW_OPINION: Record<string, string> = {
  AGREE: '同意', CONTINUE: '继续调查', CHANGE: '变更', CORRECT: '纠正', OTHER: '其他',
}
