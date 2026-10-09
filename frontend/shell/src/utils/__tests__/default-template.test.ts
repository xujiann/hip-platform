import { describe, expect, it } from 'vitest'
import { applyTemplate, type EmrFields } from '../emr-template'
import {
  editedSinceApplied, eligibleForDefaultTemplate, emrAllBlank, emrEquals, pickDefaultTemplate,
  planDefaultTemplate, revertDefaultTemplate, untouchedDefaultTemplate,
} from '../default-template'

/**
 * v79 车道B（988★）：新病历自动套用科室默认模板的判定锁定测试。
 * 口径：新病历（工作区无病历）且五段全空、本科室有启用的默认模板才套；已有正文 / 已签名 / 无默认模板一律不动；可撤销。
 * 返回体形状取自 EmrTemplateService.defaultTemplate：`select * from emr_template` 整行直出（snake_case 列名），无默认时 null。
 */

const blank = (): EmrFields => ({
  chiefComplaint: '', presentIllness: '', pastHistory: '', physicalExam: '', advice: '',
})
const row = (over: Record<string, unknown> = {}) => ({
  id: 7, name: '呼吸科门诊默认', content: '主诉：咳嗽_天\n体格检查：双肺呼吸音清', template_type: 'EMR',
  record_type: 'OUTP', dept_id: 3, is_default: true, enabled: true, scope: 'DEPT', ...over,
})

describe('eligibleForDefaultTemplate：只有新病历且五段全空才去取', () => {
  it('新病历、未签、全空 → 取', () => {
    expect(eligibleForDefaultTemplate({ hasEmr: false, signed: false, emr: blank() })).toBe(true)
  })
  it('工作区已有病历 → 不取（哪怕正文全空）', () => {
    expect(eligibleForDefaultTemplate({ hasEmr: true, signed: false, emr: blank() })).toBe(false)
  })
  it('已签名 → 不取', () => {
    expect(eligibleForDefaultTemplate({ hasEmr: false, signed: true, emr: blank() })).toBe(false)
  })
  it('任一段有正文 → 不取；纯空白不算正文', () => {
    expect(eligibleForDefaultTemplate({ hasEmr: false, signed: false, emr: { ...blank(), advice: '多饮水' } })).toBe(false)
    expect(eligibleForDefaultTemplate({ hasEmr: false, signed: false, emr: { ...blank(), advice: ' \n ' } })).toBe(true)
  })
  it('本次就诊医生撤销过 → 再次进页不取', () => {
    expect(eligibleForDefaultTemplate({ hasEmr: false, signed: false, emr: blank(), declined: true })).toBe(false)
  })
  it('emrAllBlank 逐段判', () => {
    expect(emrAllBlank(blank())).toBe(true)
    expect(emrAllBlank({ ...blank(), physicalExam: 'T 36.5' })).toBe(false)
  })
})

describe('pickDefaultTemplate：返回体可用性', () => {
  it('无默认模板（data=null）→ null', () => {
    expect(pickDefaultTemplate(null)).toBeNull()
    expect(pickDefaultTemplate(undefined)).toBeNull()
  })
  it('snake_case 整行 → 取 id/name/content', () => {
    expect(pickDefaultTemplate(row())).toEqual({ id: 7, name: '呼吸科门诊默认', content: '主诉：咳嗽_天\n体格检查：双肺呼吸音清' })
  })
  it('已停用 → null；非 EMR 模板（RIS）→ null；template_type 为空按病历模板', () => {
    expect(pickDefaultTemplate(row({ enabled: false }))).toBeNull()
    expect(pickDefaultTemplate(row({ template_type: 'RIS' }))).toBeNull()
    expect(pickDefaultTemplate(row({ template_type: null }))?.name).toBe('呼吸科门诊默认')
    expect(pickDefaultTemplate(row({ template_type: undefined, templateType: 'ris' }))).toBeNull()
  })
  it('没名字 / 非对象 → null', () => {
    expect(pickDefaultTemplate(row({ name: '  ' }))).toBeNull()
    expect(pickDefaultTemplate([row()])).toBeNull()
    expect(pickDefaultTemplate('x')).toBeNull()
  })
  it('返回体没带正文列 → content=undefined（页面按 id 再取）；正文为 null → null', () => {
    const r = row()
    delete (r as Record<string, unknown>).content
    expect(pickDefaultTemplate(r)?.content).toBeUndefined()
    expect(pickDefaultTemplate(row({ content: null }))?.content).toBeNull()
  })
})

describe('planDefaultTemplate：拆段复用 splitTemplateContent，与套用前的值无损合并', () => {
  it('标签行模板 → 按段落位', () => {
    expect(planDefaultTemplate('主诉：咳嗽_天\n体格检查：双肺呼吸音清', blank())).toEqual({
      parts: { chiefComplaint: '咳嗽_天', physicalExam: '双肺呼吸音清' }, skipped: [],
    })
  })
  it('纯文本模板 → 整段落现病史', () => {
    expect(planDefaultTemplate('患者自述……', blank())?.parts).toEqual({ presentIllness: '患者自述……' })
  })
  it('空正文 / 只有空段 → null（不套、不提示）', () => {
    expect(planDefaultTemplate('', blank())).toBeNull()
    expect(planDefaultTemplate(null, blank())).toBeNull()
    expect(planDefaultTemplate('{"chiefComplaint":"  "}', { ...blank() })?.parts)
      .toEqual({ presentIllness: '{"chiefComplaint":"  "}' })   // 一个五段键都没取到的 JSON 回落纯文本，不吞字
  })
  it('系统已预填的过敏史不被盖掉：合并为「预填 + 换行 + 模板」', () => {
    const before = { ...blank(), pastHistory: '过敏史：青霉素' }
    expect(planDefaultTemplate('既往史：否认高血压\n处理意见：对症', before)?.parts).toEqual({
      pastHistory: '过敏史：青霉素\n否认高血压', advice: '对症',
    })
  })
  it('诊断行列为 skipped，不并入体格检查', () => {
    const p = planDefaultTemplate('体格检查：咽红\n初步诊断：急性咽炎', blank())
    expect(p?.parts).toEqual({ physicalExam: '咽红' })
    expect(p?.skipped).toEqual([{ label: '初步诊断', text: '急性咽炎' }])
  })
  it('与 applyTemplate 联用：合并后的覆盖确认放行也不丢预填', async () => {
    const emr = { ...blank(), pastHistory: '过敏史：青霉素' }
    const plan = planDefaultTemplate('既往史：否认高血压', emr)!
    const r = await applyTemplate(emr, plan.parts, { confirmOverwrite: async () => true })
    expect(r.cancelled).toBe(false)
    expect(emr.pastHistory).toBe('过敏史：青霉素\n否认高血压')
  })
})

describe('撤销与「是否动过」', () => {
  it('撤销恢复成套用前（新病历 = 空），模板没碰过的段不动', async () => {
    const emr = { ...blank(), pastHistory: '过敏史：青霉素' }
    const before = { ...emr }
    const plan = planDefaultTemplate('主诉：咳嗽\n既往史：否认高血压', before)!
    await applyTemplate(emr, plan.parts, { confirmOverwrite: async () => true })
    emr.advice = '医生自己写的'
    expect(revertDefaultTemplate(emr, before, plan.parts)).toEqual(['chiefComplaint', 'pastHistory'])
    expect(emr).toEqual({ ...blank(), pastHistory: '过敏史：青霉素', advice: '医生自己写的' })
  })
  it('editedSinceApplied 只报模板写过且被改过的段', () => {
    const written = { chiefComplaint: '咳嗽', physicalExam: '咽红' }
    const emr = { ...blank(), chiefComplaint: '咳嗽三天', physicalExam: '咽红', advice: 'x' }
    expect(editedSinceApplied(emr, written)).toEqual(['chiefComplaint'])
    expect(editedSinceApplied({ ...blank(), ...written }, written)).toEqual([])
  })
  it('emrEquals 逐段逐字', () => {
    expect(emrEquals(blank(), blank())).toBe(true)
    expect(emrEquals({ ...blank(), advice: ' ' }, blank())).toBe(false)
  })
})

/** v79 审阅修补（三）（乙组反驳 B3-1f）：自动套用的模板一字未改不得一键提交 */
describe('untouchedDefaultTemplate：套用后一字未动才拦', () => {
  const after = { ...blank(), chiefComplaint: '咳嗽_天', physicalExam: 'T  ℃' }
  it('没有自动套用 → 不拦', () => {
    expect(untouchedDefaultTemplate(null, { ...after })).toBe(false)
    expect(untouchedDefaultTemplate(undefined, { ...after })).toBe(false)
  })
  it('套用后逐字未改 → 拦', () => {
    expect(untouchedDefaultTemplate({ after }, { ...after })).toBe(true)
  })
  it('改了任意一段（哪怕只加一个空格）→ 不拦', () => {
    expect(untouchedDefaultTemplate({ after }, { ...after, chiefComplaint: '咳嗽3天' })).toBe(false)
    expect(untouchedDefaultTemplate({ after }, { ...after, advice: ' ' })).toBe(false)
  })
})
