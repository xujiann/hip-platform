import { describe, expect, it, vi } from 'vitest'
import {
  EMR_LIMITS, applyTemplate, outpTemplatesOnly, overLimitOf, splitTemplateContent,
  type EmrFields,
} from '../emr-template'

/**
 * v76 车道B：门诊「套用正文」的纯逻辑锁定测试。
 * 模板 content 实际有三种形态（v76 摸底，库内 33 行样本）：
 *   ① 旧 JSON 对象（键恰为五段字段名，32 行）；
 *   ② 门诊「存为模板」产物——EmrTemplateService.loadRecord 把五段拼成「主诉：…\n现病史：…」（空段省略）；
 *   ③ 住院病历存成的模板，纯自由正文。
 * 标签行的期望串逐字取自后端 concat_ws(chr(10), '主诉：'||…) 的拼法。
 */

const blank = (): EmrFields => ({
  chiefComplaint: '', presentIllness: '', pastHistory: '', physicalExam: '', advice: '',
})

describe('splitTemplateContent：旧 JSON 五段键', () => {
  it('库内样本原样：只回有值的键', () => {
    const r = splitTemplateContent('{"chiefComplaint":"咽痛发热_天","advice":"多饮水，对症治疗"}')
    expect(r.format).toBe('json')
    expect(r.parts).toEqual({ chiefComplaint: '咽痛发热_天', advice: '多饮水，对症治疗' })
  })

  it('空串/纯空白值丢弃，非五段键忽略，数字值转字符串', () => {
    const r = splitTemplateContent('{"chiefComplaint":"  ","pastHistory":"无","foo":"x","physicalExam":37}')
    expect(r.format).toBe('json')
    expect(r.parts).toEqual({ pastHistory: '无', physicalExam: '37' })
  })

  it('JSON 但一个五段键都没有 → 回落纯文本，不报错、不丢字', () => {
    const src = '{"foo":"bar"}'
    const r = splitTemplateContent(src)
    expect(r.format).toBe('plain')
    expect(r.parts).toEqual({ presentIllness: src })
  })

  it('JSON 数组 / 坏 JSON → 回落纯文本', () => {
    expect(splitTemplateContent('["主诉"]').format).toBe('plain')
    const broken = '{"chiefComplaint":"咽痛'
    const r = splitTemplateContent(broken)
    expect(r.format).toBe('plain')
    expect(r.parts.presentIllness).toBe(broken)
  })
})

describe('splitTemplateContent：标签行（门诊存为模板的产物）', () => {
  it('五段齐全，逐字对应后端拼法', () => {
    const content = ['主诉：咽痛3天', '现病史：3天前受凉后出现咽痛', '既往史：否认高血压', '体格检查：咽充血', '处理意见：多饮水']
      .join('\n')
    const r = splitTemplateContent(content)
    expect(r.format).toBe('labeled')
    expect(r.parts).toEqual({
      chiefComplaint: '咽痛3天', presentIllness: '3天前受凉后出现咽痛', pastHistory: '否认高血压',
      physicalExam: '咽充血', advice: '多饮水',
    })
  })

  it('空段在后端被省略：只有两段也能拆，缺的段不进 parts', () => {
    const r = splitTemplateContent('主诉：头痛\n处理意见：休息')
    expect(r.parts).toEqual({ chiefComplaint: '头痛', advice: '休息' })
    expect(Object.keys(r.parts)).toHaveLength(2)
  })

  it('多行值归上一个标签（含空行）', () => {
    const r = splitTemplateContent('主诉：腹痛\n现病史：昨日起上腹痛\n伴恶心\n\n无呕吐\n既往史：无')
    expect(r.parts.presentIllness).toBe('昨日起上腹痛\n伴恶心\n\n无呕吐')
    expect(r.parts.pastHistory).toBe('无')
  })

  it('CRLF 换行同样可拆', () => {
    const r = splitTemplateContent('主诉：咳嗽\r\n现病史：一周')
    expect(r.parts).toEqual({ chiefComplaint: '咳嗽', presentIllness: '一周' })
  })

  it('同一标签重复出现：后者追加到同段，不丢字', () => {
    const r = splitTemplateContent('现病史：甲\n主诉：乙\n现病史：丙')
    expect(r.parts.presentIllness).toBe('甲\n丙')
    expect(r.parts.chiefComplaint).toBe('乙')
  })

  it('标签只认全角冒号且必须在行首', () => {
    expect(splitTemplateContent('主诉:咽痛').format).toBe('plain')
    expect(splitTemplateContent('患者主诉：咽痛').format).toBe('plain')
  })

  it('首个非空行不是标签（前面有前言）→ 整段按纯文本处理，不静默丢前言', () => {
    const src = '感冒常用模板\n主诉：咽痛'
    const r = splitTemplateContent(src)
    expect(r.format).toBe('plain')
    expect(r.parts).toEqual({ presentIllness: src })
  })
})

describe('splitTemplateContent：纯文本与空', () => {
  it('整段进默认目标段（现病史）', () => {
    const r = splitTemplateContent('患者因发热就诊，予对症处理。')
    expect(r.format).toBe('plain')
    expect(r.parts).toEqual({ presentIllness: '患者因发热就诊，予对症处理。' })
  })

  it('目标段由调用方指定（insertTarget）', () => {
    const r = splitTemplateContent('多休息', 'advice')
    expect(r.parts).toEqual({ advice: '多休息' })
  })

  it('空串/空白/null/undefined → empty，parts 为空', () => {
    for (const v of ['', '  \n ', null, undefined]) {
      const r = splitTemplateContent(v as string | null | undefined)
      expect(r.format).toBe('empty')
      expect(r.parts).toEqual({})
    }
  })
})

describe('overLimitOf：列宽 512/2000/1000/1000/1000', () => {
  it('列宽常量与库列一致', () => {
    expect(EMR_LIMITS).toEqual({
      chiefComplaint: 512, presentIllness: 2000, pastHistory: 1000, physicalExam: 1000, advice: 1000,
    })
  })

  it('恰好等于列宽不算超，多一个字算超', () => {
    expect(overLimitOf({ chiefComplaint: 'a'.repeat(512) })).toEqual([])
    const over = overLimitOf({ chiefComplaint: 'a'.repeat(513) })
    expect(over).toEqual([{ key: 'chiefComplaint', label: '主诉', length: 513, limit: 512 }])
  })

  it('按码点计数（与 PG varchar 按字符一致），不按 UTF-16 单元', () => {
    // 增补平面字符在 UTF-16 里占两个单元；512 个这样的字符是 512 码点，不得误判超长
    expect(overLimitOf({ chiefComplaint: '𠮷'.repeat(512) })).toEqual([])
    expect(overLimitOf({ chiefComplaint: '𠮷'.repeat(513) })).toHaveLength(1)
  })

  it('各段独立判定，只回超长的段', () => {
    const over = overLimitOf({
      chiefComplaint: 'x', presentIllness: 'y'.repeat(2001), advice: 'z'.repeat(1001),
    })
    expect(over.map((o) => o.key)).toEqual(['presentIllness', 'advice'])
  })
})

describe('applyTemplate：覆盖确认与超长警示', () => {
  it('目标段全空：不询问，直接写入', async () => {
    const emr = blank()
    const confirmOverwrite = vi.fn(async () => true)
    const r = await applyTemplate(emr, { chiefComplaint: '咽痛', advice: '休息' }, { confirmOverwrite })
    expect(confirmOverwrite).not.toHaveBeenCalled()
    expect(r.applied).toEqual(['chiefComplaint', 'advice'])
    expect(r.cancelled).toBe(false)
    expect(emr.chiefComplaint).toBe('咽痛')
    expect(emr.advice).toBe('休息')
  })

  it('目标段非空：询问，传入将被覆盖的段名；确认后覆盖', async () => {
    const emr = { ...blank(), chiefComplaint: '旧主诉', pastHistory: '   ' }
    const confirmOverwrite = vi.fn(async () => true)
    const r = await applyTemplate(emr, { chiefComplaint: '新主诉', pastHistory: '无' }, { confirmOverwrite })
    expect(confirmOverwrite).toHaveBeenCalledTimes(1)
    // 纯空白不算「已有内容」，只问主诉
    expect(confirmOverwrite).toHaveBeenCalledWith(['主诉'])
    expect(r.overwritten).toEqual(['chiefComplaint'])
    expect(emr.chiefComplaint).toBe('新主诉')
    expect(emr.pastHistory).toBe('无')
  })

  it('目标段非空且医生取消：一个字都不改', async () => {
    const emr = { ...blank(), presentIllness: '医生写了一半' }
    const r = await applyTemplate(emr, { presentIllness: '模板正文', advice: '休息' }, {
      confirmOverwrite: async () => false,
    })
    expect(r.cancelled).toBe(true)
    expect(r.applied).toEqual([])
    expect(emr.presentIllness).toBe('医生写了一半')
    expect(emr.advice).toBe('')
  })

  it('模板没有的段保持医生原有内容（只写模板里有值的段）', async () => {
    const emr = { ...blank(), pastHistory: '高血压10年' }
    await applyTemplate(emr, { chiefComplaint: '头晕' }, { confirmOverwrite: async () => true })
    expect(emr.pastHistory).toBe('高血压10年')
    expect(emr.chiefComplaint).toBe('头晕')
  })

  it('纯文本 + 默认目标段非空 → 询问「现病史」', async () => {
    const emr = { ...blank(), presentIllness: '已有' }
    const confirmOverwrite = vi.fn(async () => true)
    const split = splitTemplateContent('整段正文')
    await applyTemplate(emr, split.parts, { confirmOverwrite })
    expect(confirmOverwrite).toHaveBeenCalledWith(['现病史'])
    expect(emr.presentIllness).toBe('整段正文')
  })

  it('超长：不截断、照常写入，只在结果里列出超长段', async () => {
    const emr = blank()
    const long = 'a'.repeat(600)
    const r = await applyTemplate(emr, { chiefComplaint: long }, { confirmOverwrite: async () => true })
    expect(emr.chiefComplaint).toBe(long)
    expect(r.overLimit).toEqual([{ key: 'chiefComplaint', label: '主诉', length: 600, limit: 512 }])
  })

  it('取消时不返回超长警示（没写入就没有超长）', async () => {
    const emr = { ...blank(), chiefComplaint: 'x' }
    const r = await applyTemplate(emr, { chiefComplaint: 'a'.repeat(600) }, { confirmOverwrite: async () => false })
    expect(r.overLimit).toEqual([])
  })

  it('空 parts：什么都不做，不询问', async () => {
    const emr = { ...blank(), advice: '保留' }
    const confirmOverwrite = vi.fn(async () => true)
    const r = await applyTemplate(emr, {}, { confirmOverwrite })
    expect(confirmOverwrite).not.toHaveBeenCalled()
    expect(r.applied).toEqual([])
    expect(emr.advice).toBe('保留')
  })
})

describe('outpTemplatesOnly：下拉只列 OUTP 或空 record_type', () => {
  it('OUTP、空串、null、缺键都保留；INP 等剔除；兼容 recordType 驼峰键与大小写', () => {
    const list = [
      { id: 1, record_type: 'OUTP' },
      { id: 2, record_type: null },
      { id: 3 },
      { id: 4, record_type: '' },
      { id: 5, record_type: 'INP' },
      { id: 6, recordType: 'outp' },
      { id: 7, recordType: 'INP' },
      { id: 8, record_type: 'DISCHARGE' },
    ]
    expect(outpTemplatesOnly(list).map((t) => t.id)).toEqual([1, 2, 3, 4, 6])
  })
})

describe('未知标签段（维护页骨架「辅助检查/初步诊断」）', () => {
  it('不并入上一段，单列为 skipped', () => {
    const r = splitTemplateContent('主诉：咳嗽\n体格检查：咽充血\n辅助检查：血常规正常\n初步诊断：上感\n处理意见：休息')
    expect(r.format).toBe('labeled')
    expect(r.parts.physicalExam).toBe('咽充血')
    expect(r.parts.advice).toBe('休息')
    expect(r.skipped).toEqual([{ label: '辅助检查', text: '血常规正常' }, { label: '初步诊断', text: '上感' }])
  })
  it('空值的未知标签不进 skipped', () => {
    const r = splitTemplateContent('主诉：咳嗽\n初步诊断：\n')
    expect(r.skipped).toEqual([])
    expect(r.parts.chiefComplaint).toBe('咳嗽')
  })
})
