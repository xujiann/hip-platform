# -*- coding: utf-8 -*-
"""参数覆盖矩阵初评：按已实现功能关键词做首轮自动评定（初评符合/待人工评估）。
仅为人工复核加速器——初评结果必须经项目组逐条确认后方可写入技术偏离表。
用法：python tools/matrix-autoreview.py [矩阵csv]
"""
import csv
import sys

path = sys.argv[1] if len(sys.argv) > 1 else 'docs/验收/参数覆盖矩阵.csv'

IMPLEMENTED_KEYWORDS = [
    '挂号', '排班', '号源', '叫号', '收费', '退费', '结算', '发药', '退药', '药房', '药库',
    '库存', '入库', '出库', '盘点', '处方', '审方', '合理用药', '过敏', '医嘱', '病历',
    '诊断', 'ICD', '签名', '检验', '标本', '检查', '报告', '危急值', 'LIS', 'RIS', 'HL7',
    '住院', '入院', '出院', '转科', '床位', '押金', '护理', '体温', '体征', '手术', '麻醉',
    '会诊', '随访', '满意度', '体检', '分诊', '急诊', '患者', '建档', '主索引', '360',
    '权限', '角色', '菜单', '用户', '科室', '登录', '审计', '密码', '打印', '报表', '日结',
    '导出', '统计', '指标', '驾驶舱', '填报', '数据质量', '固定资产', '物资', '耗材',
    '供应商', 'OA', '审批', '不良事件', '院感', '临床路径', '病案', '归档', '互联网',
    '预约', 'APP', '公众号', '集成', '接口', '消息', '同步', 'CDA', 'CDR', '数据中心',
    'PACS', '影像', 'DICOM', 'AI', '模板',
]
EXTERNAL_KEYWORDS = ['医保局', '国家医保', '电子健康卡', '省平台', '区域', 'CA机构', '实名认证']

rows = []
with open(path, encoding='utf-8-sig') as f:
    reader = csv.reader(f)
    header = next(reader)
    rows = list(reader)

if len(header) < 7:
    header.append('初评')

stats = {'初评符合': 0, '待人工评估': 0, '涉外部条件': 0}
for r in rows:
    summary = r[3]
    if any(k in summary for k in EXTERNAL_KEYWORDS):
        verdict = '涉外部条件'
    elif any(k in summary for k in IMPLEMENTED_KEYWORDS):
        verdict = '初评符合'
    else:
        verdict = '待人工评估'
    stats[verdict] += 1
    if len(r) < 7:
        r.append(verdict)
    else:
        r[6] = verdict

with open(path, 'w', newline='', encoding='utf-8-sig') as f:
    w = csv.writer(f)
    w.writerow(header)
    w.writerows(rows)

total = len(rows)
print(f'初评完成 {total} 条：')
for k, v in stats.items():
    print(f'  {k}: {v} ({v * 100 // total}%)')
print('注意：初评仅供人工复核提速，不能直接作为投标响应依据。')
