import { describe, expect, it } from 'vitest'
import { dropTemplateBatch, stampTemplateLines } from '../rx-template-apply'

/**
 * v80 审阅修补（D2）：撤组只撤本批。修前按 tplId 过滤而 tplId 从未赋值，
 * 套两张协定处方后撤一组会把两张一起撤掉。
 */
describe('stampTemplateLines：套用时打来源与批次', () => {
  it('每行带上 tplId 与 tplBatch，原字段不丢、入参不被改', () => {
    const src = [{ itemId: 1, locked: true }, { itemId: 2, locked: true }]
    const out = stampTemplateLines(src, 8, '8-1')
    expect(out).toEqual([
      { itemId: 1, locked: true, tplId: 8, tplBatch: '8-1' },
      { itemId: 2, locked: true, tplId: 8, tplBatch: '8-1' },
    ])
    expect(src[0]).not.toHaveProperty('tplBatch')
  })
})

describe('dropTemplateBatch：撤组只撤本批', () => {
  const a = stampTemplateLines([{ itemId: 1, locked: true, orderType: 'DRUG' },
    { itemId: 9, locked: true, orderType: 'LAB' }], 8, '8-1')
  const b = stampTemplateLines([{ itemId: 2, locked: true, orderType: 'DRUG' }], 5, '5-2')
  const manual = { itemId: 3, orderType: 'DRUG' }

  it('套两张协定处方，撤第一张不碰第二张与手工行', () => {
    const r = dropTemplateBatch([a[0], b[0], manual], [a[1]], a[0])
    expect(r.rx).toEqual([b[0], manual])
    expect(r.lab).toEqual([])
    expect(r.removed).toBe(2)   // 药品 1 + 检验 1，修前只数药品行
  })

  it('同一张协定处方套两次是两批，互不牵连', () => {
    const again = stampTemplateLines([{ itemId: 1, locked: true }], 8, '8-3')
    const r = dropTemplateBatch([a[0], again[0]], [], again[0])
    expect(r.rx).toEqual([a[0]])
    expect(r.removed).toBe(1)
  })

  it('行没有批次号时只撤它本身，不按「都为空」匹配别的行', () => {
    const x = { itemId: 4, locked: true }
    const y = { itemId: 5, locked: true }
    const r = dropTemplateBatch([x, y], [], x)
    expect(r.rx).toEqual([y])
    expect(r.removed).toBe(1)
  })
})
