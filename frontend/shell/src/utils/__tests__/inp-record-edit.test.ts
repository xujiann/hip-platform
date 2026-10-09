import { describe, expect, it } from 'vitest'
import { canEditInpRecord, inpEditFormOf, inpUpdatePayload } from '../inp-record-edit'

/**
 * v79 审阅修补（乙组 D2/N10，2457★）：住院时间线「修改」的纯逻辑锁定——
 * 只有未签名（暂存）记录给入口；回填与 PUT 请求体的形状与 InpEmrController#updateRecord 对齐。
 */

describe('canEditInpRecord：只有未签名（暂存）记录给「修改」', () => {
  it('未签名 → 可改', () => {
    expect(canEditInpRecord({ id: 1, signature: null })).toBe(true)
  })
  it('已签名 → 不可改（走补正）', () => {
    expect(canEditInpRecord({ id: 1, signature: 'SIG' })).toBe(false)
  })
  it('无 id / 空 → 不可改', () => {
    expect(canEditInpRecord({ signature: null })).toBe(false)
    expect(canEditInpRecord(null)).toBe(false)
  })
})

describe('inpEditFormOf / inpUpdatePayload：回填与请求体', () => {
  it('病程记录：回填正文与标题；保存时不带上级修正意见', () => {
    const f = inpEditFormOf({ id: 1, recordType: 'PROGRESS', title: '病程记录', content: '病情平稳' })
    expect(f).toMatchObject({ recordType: 'PROGRESS', title: '病程记录', content: '病情平稳', superiorCorrection: '' })
    expect(inpUpdatePayload({ ...f, content: '病情平稳，继续观察' }))
      .toEqual({ title: '病程记录', content: '病情平稳，继续观察' })
  })
  it('三级查房：回填查房级别与上级修正意见；保存时带上级修正意见（清空也传，后端据此清空）', () => {
    const f = inpEditFormOf({ id: 2, recordType: 'ROUND', title: '主治查房', content: '继续观察',
      roundLevel: 'ATTENDING', superiorCorrection: '同意' })
    expect(f.roundLevel).toBe('ATTENDING')
    expect(f.superiorCorrection).toBe('同意')
    expect(inpUpdatePayload({ ...f, superiorCorrection: '' }))
      .toEqual({ title: '主治查房', content: '继续观察', superiorCorrection: '' })
  })
  it('标题留空 → 不传（后端不动原标题）', () => {
    expect(inpUpdatePayload({ recordType: 'PROGRESS', title: '  ', content: 'x', superiorCorrection: '' }))
      .toEqual({ content: 'x' })
  })
})
