import { describe, expect, it } from 'vitest'
import { presaveBeforeSign } from '../emr-submit'
import { sourceLabel } from '../../views/outpatient/emr-version/types'

/**
 * v79 审阅修补（三）（乙组反驳 B3-2e，2457★）：提交前先暂存只对「无病历 / 本人书写」生效。
 * 后端 saveEmr 落的 doctorId 即书写人，他人先暂存会把书写医师改成自己、架空 4023。
 */
describe('presaveBeforeSign：只有本人的草稿（或还没有病历）才先暂存', () => {
  it('工作区还没有病历 → 先暂存（首次落库，书写人就是本人）', () => {
    expect(presaveBeforeSign({ hasEmr: false, authorId: null, meId: 7 })).toBe(true)
  })
  it('书写医师就是本人 → 先暂存', () => {
    expect(presaveBeforeSign({ hasEmr: true, authorId: 7, meId: 7 })).toBe(true)
  })
  it('书写医师是别人 → 不暂存，直签让后端回 4023', () => {
    expect(presaveBeforeSign({ hasEmr: true, authorId: 8, meId: 7 })).toBe(false)
  })
  it('登录人未知而病历有书写人 → 不暂存（不能拿未知去改书写医师）', () => {
    expect(presaveBeforeSign({ hasEmr: true, authorId: 8, meId: null })).toBe(false)
  })
  it('历史行书写医师为空 → 先暂存（后端 4023 本就不判，与修前同口径）', () => {
    expect(presaveBeforeSign({ hasEmr: true, authorId: null, meId: 7 })).toBe(true)
  })
})

/** v79 审阅修补（三）（甲组反驳 A2-V4）：版本页来源列中文，未知值原样 */
describe('sourceLabel：MANUAL/AUTO/SUBMIT 显示中文', () => {
  it('三个既有枚举', () => {
    expect(sourceLabel('MANUAL')).toBe('保存/修改')
    expect(sourceLabel('SUBMIT')).toBe('签名提交')
    expect(sourceLabel('AUTO')).toBe('自动保存')
  })
  it('未登记的值原样显示，空值显示破折号', () => {
    expect(sourceLabel('CURRENT')).toBe('CURRENT')
    expect(sourceLabel(null)).toBe('—')
  })
})
