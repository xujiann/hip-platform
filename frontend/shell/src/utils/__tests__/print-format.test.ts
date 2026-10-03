import { describe, expect, it } from 'vitest'
import {
  ageText, allergyLine, briefHistory, docDoctor, docDoctorSuffix, docTitleSuffix,
  firstOf, formatDiagnoses, guideStatusOf, money2, stripBlockMarks, printedAt } from '../print-format'

/**
 * v75 车道C：PrintView 纯拼装逻辑的锁定测试。
 * 每一组用例对应 v74 三轮复核（1026★）点名过的纸面事故——这些口径此前只靠人眼核纸，
 * 现在改一个字符就会红。用例期望值取自 v74 定版时 PrintView.vue 的实际输出。
 */

describe('formatDiagnoses：诊断拼接', () => {
  it('标准名 + 编码：名称(编码)', () => {
    expect(formatDiagnoses([{ icd_name: '急性上呼吸道感染', icd_code: 'J06.900' }]))
      .toBe('急性上呼吸道感染(J06.900)')
  })

  it('前缀、后缀跟着印：疑似 名称 后缀(编码)', () => {
    expect(formatDiagnoses([{ prefix: '疑似', icd_name: '急性上呼吸道感染', suffix: '待排', icd_code: 'J06.900' }]))
      .toBe('疑似 急性上呼吸道感染 待排(J06.900)')
  })

  it('疑诊：「（疑诊）」缀在编码之后，处方笺上不能把疑诊印成确诊', () => {
    expect(formatDiagnoses([{ icd_name: '急性上呼吸道感染', icd_code: 'J06.900', certainty: 'SUSPECTED' }]))
      .toBe('急性上呼吸道感染(J06.900)（疑诊）')
    // 非 SUSPECTED（含 CONFIRMED、null）不加标记
    expect(formatDiagnoses([{ icd_name: '感冒', certainty: 'CONFIRMED' }])).toBe('感冒')
    expect(formatDiagnoses([{ icd_name: '感冒', certainty: null }])).toBe('感冒')
  })

  it('自定义描述与标准名并存：标准名在前，自定义作『［］』括注，不替代标准名', () => {
    expect(formatDiagnoses([{ icd_name: '急性上呼吸道感染', custom_name: '感冒伴咽痛', icd_code: 'J06.900' }]))
      .toBe('急性上呼吸道感染［感冒伴咽痛］(J06.900)')
  })

  it('只有自定义描述（无标准名）：自定义当名称印，不带括注', () => {
    expect(formatDiagnoses([{ custom_name: '感冒伴咽痛' }])).toBe('感冒伴咽痛')
    expect(formatDiagnoses([{ icd_name: '', custom_name: '感冒伴咽痛', icd_code: '' }])).toBe('感冒伴咽痛')
  })

  it('[中医] 前缀：diag_system=TCM（icd_code 留空串则不印编码）；历史行 null 按西医', () => {
    expect(formatDiagnoses([{ diag_system: 'TCM', icd_name: '感冒', icd_code: '' }])).toBe('[中医]感冒')
    expect(formatDiagnoses([{ diag_system: 'TCM', icd_name: '感冒', icd_code: 'BNV010' }])).toBe('[中医]感冒(BNV010)')
    expect(formatDiagnoses([{ diag_system: null, icd_name: '感冒', icd_code: 'J06.900' }])).toBe('感冒(J06.900)')
  })

  it('全部要素叠加的顺序：[中医]前缀 名称［自定义］后缀(编码)（疑诊）', () => {
    expect(formatDiagnoses([{
      diag_system: 'TCM', prefix: '疑似', icd_name: '感冒', custom_name: '风寒', suffix: '待排',
      icd_code: 'A1', certainty: 'SUSPECTED',
    }])).toBe('[中医]疑似 感冒［风寒］ 待排(A1)（疑诊）')
  })

  it('多条诊断用全角分号连接；中西医混排', () => {
    expect(formatDiagnoses([
      { icd_name: '高血压', icd_code: 'I10' },
      { diag_system: 'TCM', icd_name: '眩晕', icd_code: '' },
    ])).toBe('高血压(I10)；[中医]眩晕')
  })

  it('空白被 trim；缺数据返回空串（模板侧再兜底「—」）', () => {
    expect(formatDiagnoses([{ prefix: '  ', icd_name: '  感冒  ', suffix: ' ' }])).toBe('感冒')
    expect(formatDiagnoses([])).toBe('')
    expect(formatDiagnoses(null)).toBe('')
    expect(formatDiagnoses(undefined)).toBe('')
  })
})

describe('ageText：年龄文案（后端口径，前端只兜底）', () => {
  it('后端给什么印什么：婴幼儿日/月龄、成人岁', () => {
    expect(ageText('35岁')).toBe('35岁')
    expect(ageText('15天')).toBe('15天')
    expect(ageText('8个月')).toBe('8个月')
  })

  it('缺生日/出生日晚于就诊日（负年龄）由后端给「—」，前端原样透传，不再自算成「0岁」', () => {
    expect(ageText('—')).toBe('—')
  })

  it('前端只兜底：字段缺失（null/undefined）才印「—」', () => {
    expect(ageText(null)).toBe('—')
    expect(ageText(undefined)).toBe('—')
  })
})

describe('guideStatusOf：导诊单状态列', () => {
  it('未缴费一律待缴费（任何类型）', () => {
    expect(guideStatusOf({ status: 'CREATED', order_type: 'DRUG' })).toBe('待缴费')
    expect(guideStatusOf({ status: 'CREATED', order_type: 'LAB' })).toBe('待缴费')
  })

  it('药品已缴费 = 待取药', () => {
    expect(guideStatusOf({ status: 'CHARGED', order_type: 'DRUG' })).toBe('待取药')
  })

  it('检验：无标本记录=待采样；有标本记录=已采样', () => {
    expect(guideStatusOf({ status: 'CHARGED', order_type: 'LAB' })).toBe('待采样')
    expect(guideStatusOf({ status: 'CHARGED', order_type: 'LAB', sample_status: null })).toBe('待采样')
    expect(guideStatusOf({ status: 'CHARGED', order_type: 'LAB', sample_status: 'COLLECTED' })).toBe('已采样')
  })

  it('检查：无检查记录或仍是 REGISTERED=待检查；已到检（非 REGISTERED）=检查中', () => {
    expect(guideStatusOf({ status: 'CHARGED', order_type: 'EXAM' })).toBe('待检查')
    expect(guideStatusOf({ status: 'CHARGED', order_type: 'EXAM', exam_status: 'REGISTERED' })).toBe('待检查')
    expect(guideStatusOf({ status: 'CHARGED', order_type: 'EXAM', exam_status: 'IN_PROGRESS' })).toBe('检查中')
  })

  it('治疗及其余类型 = 待执行', () => {
    expect(guideStatusOf({ status: 'CHARGED', order_type: 'TREAT' })).toBe('待执行')
  })
})

describe('firstOf：组内首个非空值', () => {
  it('跳过 null/空串/纯空白，取第一个非空；数字转字符串', () => {
    const g = { rows: [{ k: null }, { k: '' }, { k: '   ' }, { k: '第一个' }, { k: '第二个' }] }
    expect(firstOf(g, 'k')).toBe('第一个')
    expect(firstOf({ rows: [{ n: 0 }] }, 'n')).toBe('0')
  })

  it('全空 / 缺 key / 无 rows 返回空串', () => {
    expect(firstOf({ rows: [{ k: '' }] }, 'k')).toBe('')
    expect(firstOf({ rows: [{ a: 1 }] }, 'k')).toBe('')
    expect(firstOf({}, 'k')).toBe('')
  })
})

describe('署名：docDoctor / docTitleSuffix / docDoctorSuffix', () => {
  const header = { doctor_name: '张排班', doctor_title: '主任医师' }

  it('开单人≠排班医：署名取开单人，且不带排班医的职称（别把职称安错人）', () => {
    const g = { rows: [{ order_doctor_name: '李代班' }] }
    expect(docDoctor(g, header)).toBe('李代班')
    expect(docDoctorSuffix(g, header)).toBe('')
  })

  it('开单人=排班医：署名同一人，职称后缀印出', () => {
    const g = { rows: [{ order_doctor_name: '张排班' }] }
    expect(docDoctor(g, header)).toBe('张排班')
    expect(docDoctorSuffix(g, header)).toBe('（主任医师）')
  })

  it('历史行无开单人：回落页眉医生，并带职称', () => {
    const g = { rows: [{ order_doctor_name: null }, { order_doctor_name: '' }] }
    expect(docDoctor(g, header)).toBe('张排班')
    expect(docDoctorSuffix(g, header)).toBe('（主任医师）')
  })

  it('组内多行取第一个非空开单人', () => {
    const g = { rows: [{ order_doctor_name: null }, { order_doctor_name: '李代班' }, { order_doctor_name: '王三' }] }
    expect(docDoctor(g, header)).toBe('李代班')
  })

  it('页眉医生也缺：署名空串（模板侧兜底），无职称时后缀空串', () => {
    expect(docDoctor({ rows: [{}] }, {})).toBe('')
    expect(docDoctor({ rows: [{}] }, null)).toBe('')
    expect(docDoctorSuffix({ rows: [{}] }, { doctor_name: '张排班' })).toBe('')
  })

  it('docTitleSuffix：有职称印（职称），无职称空串', () => {
    expect(docTitleSuffix({ doctor_title: '副主任医师' })).toBe('（副主任医师）')
    expect(docTitleSuffix({ doctor_title: '' })).toBe('')
    expect(docTitleSuffix({})).toBe('')
    expect(docTitleSuffix(null)).toBe('')
  })
})

describe('money2：金额两位', () => {
  it('12.5 → 12.50；整数补零；字符串数值同样处理', () => {
    expect(money2(12.5)).toBe('12.50')
    expect(money2(25)).toBe('25.00')
    expect(money2('12.5')).toBe('12.50')
  })

  it('缺值按 0 印：0.00', () => {
    expect(money2(null)).toBe('0.00')
    expect(money2(undefined)).toBe('0.00')
  })

  it('浮点累加误差被两位截断吸收', () => {
    expect(money2(0.1 + 0.2)).toBe('0.30')
  })
})

describe('stripBlockMarks / briefHistory：现病史剥标记', () => {
  it('剥掉【结构化记录】/【结构化记录结束】标记、保留块内正文', () => {
    expect(stripBlockMarks('【结构化记录】发热3天，咳嗽【结构化记录结束】')).toBe('发热3天，咳嗽')
    expect(stripBlockMarks('前文\n【结构化记录】\n血压 120/80\n【结构化记录结束】'))
      .toBe('前文\n\n血压 120/80')
  })

  it('无标记原样（仅 trim）；null/undefined → 空串', () => {
    expect(stripBlockMarks('  发热  ')).toBe('发热')
    expect(stripBlockMarks(null)).toBe('')
    expect(stripBlockMarks(undefined)).toBe('')
  })

  it('briefHistory：主诉；现病史（分号连接），任一剥标记后为空则略去', () => {
    expect(briefHistory({ chief_complaint: '发热3天', present_illness: '伴咳嗽' })).toBe('发热3天；伴咳嗽')
    expect(briefHistory({ chief_complaint: '', present_illness: '伴咳嗽' })).toBe('伴咳嗽')
    expect(briefHistory({ chief_complaint: '发热3天', present_illness: '【结构化记录】【结构化记录结束】' })).toBe('发热3天')
  })

  it('briefHistory：标记不上纸；都空印「—」', () => {
    expect(briefHistory({ present_illness: '【结构化记录】咳嗽3天【结构化记录结束】' })).toBe('咳嗽3天')
    expect(briefHistory({})).toBe('—')
    expect(briefHistory(null)).toBe('—')
  })
})

describe('allergyLine：过敏行只兜底', () => {
  it('后端 allergyText 已把档案文本与结构化过敏记录合并，有内容原样印', () => {
    expect(allergyLine('青霉素；磺胺类')).toBe('青霉素；磺胺类')
  })

  it('皆空（null/undefined/空串）才印「无」', () => {
    expect(allergyLine(null)).toBe('无')
    expect(allergyLine(undefined)).toBe('无')
    expect(allergyLine('')).toBe('无')
  })
})

describe('printedAt（打印时间：日期取业务日期，时刻取本机钟）', () => {
  const fixed = new Date(2026, 9, 3, 17, 5, 9)
  it('有 printedOn 时日期取它、时刻取本机', () => {
    expect(printedAt('2026-09-29', fixed)).toBe('2026-09-29 17:05:09')
  })
  it('无 printedOn（凭条/票据）照旧印本地完整时间', () => {
    expect(printedAt(null, fixed)).toBe(fixed.toLocaleString('zh-CN'))
    expect(printedAt('', fixed)).toBe(fixed.toLocaleString('zh-CN'))
  })
})
