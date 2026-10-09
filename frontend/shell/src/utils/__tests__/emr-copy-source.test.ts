import { describe, expect, it } from 'vitest'
import { cdrDocCopySource, versionListCopySource } from '../emr-copy-source'

/**
 * v79 审阅修补（乙组 D4，1082★/2458）：只读病历页复制来源的纯逻辑锁定。
 * 返回体形状：/cdr/documents/{id} 明细带 patientId（CdrSyncService#document 整实体直出）；
 * /emr/versions/{type}/{id} 列表带 patientId/patientName（EmrVersionService#list）。
 */

describe('cdrDocCopySource：来源取正在查看的文档所属患者', () => {
  it('左侧选中的就是该患者 → id 与姓名都有', () => {
    expect(cdrDocCopySource({ id: 9, patientId: 12 }, { id: 12, name: '张三' }))
      .toEqual({ patientId: 12, patientName: '张三' })
  })
  it('全文检索点开别的患者的文档 → id 取文档的、姓名留空（不把左侧患者的名字安上去）', () => {
    expect(cdrDocCopySource({ id: 9, patientId: 34 }, { id: 12, name: '张三' }))
      .toEqual({ patientId: 34, patientName: '' })
  })
  it('明细未回（只有列表投影、无 patientId）或未查看 → null，管控放行', () => {
    expect(cdrDocCopySource({ id: 9 }, { id: 12, name: '张三' }).patientId).toBeNull()
    expect(cdrDocCopySource(null, null)).toEqual({ patientId: null, patientName: '' })
  })
  it('字符串数字 id 也认；非法值不认', () => {
    expect(cdrDocCopySource({ patientId: '12' }, { id: '12', name: '张三' }))
      .toEqual({ patientId: 12, patientName: '张三' })
    expect(cdrDocCopySource({ patientId: 'x' }, null).patientId).toBeNull()
    expect(cdrDocCopySource({ patientId: 0 }, null).patientId).toBeNull()
  })
})

describe('versionListCopySource：来源取版本列表随体带回的患者', () => {
  it('带回 → 原样取', () => {
    expect(versionListCopySource({ patientId: 5, patientName: '李四' })).toEqual({ patientId: 5, patientName: '李四' })
  })
  it('病历行不存在（两键 null）或列表未加载 → null', () => {
    expect(versionListCopySource({ patientId: null, patientName: null })).toEqual({ patientId: null, patientName: '' })
    expect(versionListCopySource(null)).toEqual({ patientId: null, patientName: '' })
  })
})
