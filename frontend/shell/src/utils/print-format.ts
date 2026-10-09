/**
 * 打印单据的纯拼装逻辑（v75 车道C 从 PrintView.vue 抽出）。
 *
 * 全部是无 Vue、无网络依赖的纯函数，可被 vitest 直接测（见 __tests__/print-format.test.ts）。
 * 每个函数的口径逐字取自 v74 定版时 PrintView.vue 里的内联实现——抽取不改变纸面输出。
 * 改动这里的任何口径，必须同步改测试：这些口径都是三轮复核（1026★）点名过的纸面事故。
 */

type Row = Record<string, unknown>
type Group = { rows?: Row[] }

/** v74 复核修：单头级字段从组内各行取第一个非空值（v44 字段挂在行上，纸面上只印一次） */
export function firstOf(g: Group, key: string): string {
  for (const r of g.rows ?? []) {
    const v = r[key]
    if (v != null && String(v).trim() !== '') return String(v)
  }
  return ''
}

/**
 * v74 复核（1026★ 第二轮反驳者三 + 第三轮自查）：诊断要连前缀/后缀/疑诊标记一起印——
 * 「疑似 急性上呼吸道感染(J06.900)（疑诊）」，否则疑诊在处方笺上就成了确诊。
 * 自定义描述与标准名**并存**（医生站占位符与 V135 注释口径：不替代标准名），作括注跟在标准名后。
 */
export function formatDiagnoses(rows: Row[] | null | undefined): string {
  return (rows ?? [])
    .map((d) => {
      const s = (v: unknown) => (v == null ? '' : String(v).trim())
      const name = s(d.icd_name) || s(d.custom_name)
      const custom = s(d.icd_name) && s(d.custom_name) ? '［' + s(d.custom_name) + '］' : ''
      const core = `${s(d.prefix) ? s(d.prefix) + ' ' : ''}${name}${custom}${s(d.suffix) ? ' ' + s(d.suffix) : ''}`
      const code = d.icd_code ? '(' + d.icd_code + ')' : ''
      const cert = s(d.certainty) === 'SUSPECTED' ? '（疑诊）' : ''
      // diag_system='TCM' 为中医诊断（V135：icd_code 留空串），与西医诊断混排时加标识；历史行 null 按西医解释
      const sys = s(d.diag_system) === 'TCM' ? '[中医]' : ''
      return sys + core + code + cert
    }).join('；')
}

/**
 * v80 审阅修补（D8，v80 复核·方案三审计）：门诊医生站「历史就诊」抽屉的一条诊断。
 * 接口 /outpatient/doctor/patient/{id}/history 回 camelCase 键（icdName/customName/prefix/suffix/certainty/diagSystem），
 * 这里换成打印数据集的列名后交给 {@link formatDiagnoses}——前缀、名称［自定义描述］、后缀、（疑诊）、[中医] 与 1026★ 纸面同一口径，
 * 此前抽屉只印标准名，疑诊在这里读作确诊。抽屉历来不印编码：只有名称与自定义描述都空时才回落为编码。
 */
export function formatHistoryDiagnosis(d: Row): string {
  const s = (v: unknown) => (v == null ? '' : String(v).trim())
  if (!s(d.icdName) && !s(d.customName)) return s(d.icdCode)
  return formatDiagnoses([{
    icd_name: d.icdName, custom_name: d.customName, prefix: d.prefix, suffix: d.suffix,
    certainty: d.certainty, diag_system: d.diagSystem,
  }])
}

/**
 * 出生日期缺失或晚于就诊日时纸面留「—」而不是「—岁」/「0 岁」；婴幼儿按后端 ageText 印日、月龄，基准为就诊日。
 * 口径全在后端，前端只兜底字段缺失。
 */
export function ageText(value: unknown): string {
  return String(value ?? '—')
}

/**
 * 导诊单状态列：药品未缴费=待缴费、已缴费=待取药；
 * v74 复核（1026★ 第三轮自查）：检验已采样、检查已到检后医嘱仍是 CHARGED，此前一律印「待执行」。
 * 按标本/检查记录细分：检验 待采样→已采样；检查 待检查→检查中；治疗 待执行。
 */
export function guideStatusOf(r: Row): string {
  if (r.status === 'CREATED') return '待缴费'
  if (r.order_type === 'DRUG') return '待取药'
  if (r.order_type === 'LAB') return r.sample_status ? '已采样' : '待采样'
  if (r.order_type === 'EXAM') return r.exam_status && r.exam_status !== 'REGISTERED' ? '检查中' : '待检查'
  return '待执行'
}

/** 页眉医生（挂号排班医）的职称后缀「（主任医师）」；无职称为空串。header 即打印数据集顶层对象 */
export function docTitleSuffix(header: Row | null | undefined): string {
  return header?.doctor_title ? `（${header.doctor_title}）` : ''
}

/**
 * v74 复核修（1026★ 三方复核反驳者三）：四种临床单据的"申请/开单医师"此前印的是**挂号排班医生**
 * （页眉 doctor_name 来自挂号记录），而接诊队列不校验接诊人须等于排班医生——代班/转接时纸上署错人。
 * 行上的 order_doctor_name 才是"这行医嘱是谁开的"，后端一直在返回、此前从未使用。
 * 取不到（历史行无开单人）时回落接诊医生；职称后缀只在两者是同一人时才印，别把排班医生的职称安到开单人头上。
 */
export function docDoctor(g: Group, header: Row | null | undefined): string {
  return firstOf(g, 'order_doctor_name') || String(header?.doctor_name ?? '')
}

export function docDoctorSuffix(g: Group, header: Row | null | undefined): string {
  const od = firstOf(g, 'order_doctor_name')
  return !od || od === String(header?.doctor_name ?? '') ? docTitleSuffix(header) : ''
}

/** 金额两位：12.5 → 12.50；缺值按 0 */
export function money2(v: unknown): string {
  return Number(v ?? 0).toFixed(2)
}

/**
 * v74 复核（1026★ 第三轮审计者）：现病史里由医生站写入的「【结构化记录】…【结构化记录结束】」是渲染块的内部标记，
 * 不能原样上纸；只剥标记、保留块内正文（与 DoctorStationService.BLOCK_BEGIN/END 同一对字面量）。
 */
export function stripBlockMarks(v: unknown): string {
  return String(v ?? '').replace(/【结构化记录结束】|【结构化记录】/g, '').trim()
}

/** 病史摘要：主诉；现病史（各自剥标记后非空的才连），都空印「—」 */
export function briefHistory(emr: Row | null | undefined): string {
  const cc = stripBlockMarks(emr?.chief_complaint)
  const pi = stripBlockMarks(emr?.present_illness)
  return [cc, pi].filter(Boolean).join('；') || '—'
}

/**
 * v74 复核（1026★ 第三轮自查）：后端 allergyText = 档案文本 + 结构化过敏记录，
 * 合并口径在后端；前端只在皆空时兜底印「无」。
 */
export function allergyLine(v: unknown): string {
  return v ? String(v) : '无'
}

/**
 * 打印时间。v75 合并后补齐：后端随临床单据下发 printedOn（BusinessDates 业务日期——演示库可冻结日期），
 * 此前前端只印浏览器本地时间、printedOn 无人消费，冻结日期的演示库上"就诊日期"与"打印时间"会对不上。
 * 口径：日期取 printedOn（有则用），时刻取本机钟；没有 printedOn（挂号凭条/票据等）照旧印本地完整时间。
 */
export function printedAt(printedOn: unknown, now: Date = new Date()): string {
  const d = printedOn == null ? '' : String(printedOn).trim()
  const hms = now.toLocaleTimeString('zh-CN', { hour12: false })
  return d ? `${d} ${hms}` : now.toLocaleString('zh-CN')
}
