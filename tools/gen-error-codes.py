# -*- coding: utf-8 -*-
"""生成错误码速查表（docs/错误码速查表.md）——一线支持按码定位含义与处置。

从源码全量提取 `R.fail(码, "文案")` 与各业务异常构造，按码升序输出对照表，
并单列「跨语义码」（同码多义，按码查询须结合发生模块）。

用法：python tools/gen-error-codes.py
纪律：发版前重跑，保持与代码同步；新增码前先查 docs/错误码分段.md 的分段规约。
"""
import collections
import glob
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')
BS = chr(92)
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATTERN = re.compile(r'(?:R\.fail|new \w*Exception)\(\s*(\d{4})\s*,\s*"([^"]{2,120})"')

MODULE_HINTS = {
    'Charge': '门诊收费', 'Registration': '门诊挂号', 'DoctorStation': '门诊医生站',
    'Dispense': '药房', 'Review': '审方', 'Pay': '支付', 'Inpatient': '住院',
    'MedTech': '医技', 'Pathology': '病理', 'Insurance': '医保', 'Portal': '患者端',
    'Cdr': '数据中心', 'Drg': 'DRG', 'Cdss': 'CDSS', 'Hrp': '运营后勤', 'Hr': '人事',
    'Ops': '运维', 'Patient': '患者主索引', 'SysConfig': '系统配置', 'Auth': '认证',
    'User': '用户', 'Queue': '叫号', 'Blood': '用血', 'Nursing': '护理',
    'Infection': '院感', 'Exam': '体检', 'Asset': '资产', 'DataStd': '数据治理',
    'DataGov': '数据治理', 'MedRecord': '病案', 'ReportShare': '报告分享',
    'Hl7': '集成', 'Legacy': '存量迁移', 'Finance': '财务', 'Print': '打印',
    'Stats': '统计', 'PatientCare': '患者服务', 'RiskAssess': '护理风险',
    'Anes': '麻醉', 'Surgery': '手术', 'Equipment': '设备管理',
}


def owner(filename):
    for key, label in MODULE_HINTS.items():
        if key.lower() in filename.lower():
            return label
    return filename.replace('Controller.java', '').replace('Service.java', '')


def advice(code, msg):
    if code in (1000, 1001, 1002, 1005):
        return '重新登录 / 联系管理员分配角色'
    if code == 1402:
        return '按提示取值域重填配置'
    if code in (4000, 4001, 4040, 4041, 4050, 4090, 4091):
        return '检查请求参数；持续出现联系实施'
    if code == 5000:
        return '记录发生时间点，交运维查服务端日志'
    if 9500 <= code <= 9599:
        return '患者端：核对患者号/手机号；9503 为锁定，等窗口过后再试'
    if '不存在' in msg or '已删除' in msg:
        return '刷新页面重查；确认单据号是否正确'
    if '已' in msg and any(k in msg for k in ('结算', '退费', '发药', '执行')):
        return '状态已变化，刷新后按最新状态操作'
    if '权限' in msg or '角色' in msg:
        return '联系管理员授权'
    if '医保' in msg:
        return '联系医保办核对；通道失败可查集成留痕'
    if '库存' in msg:
        return '药库补货或改开其他规格'
    return '按提示处理；无法自决时报运维并附单据号'


def collect():
    hits = collections.defaultdict(set)
    for root in ('modules', 'platform', 'server/src/main', 'datacenter'):
        for path in glob.glob(os.path.join(ROOT, root, '**', '*.java'), recursive=True):
            if '/target/' in path.replace(BS, '/'):
                continue
            text = open(path, encoding='utf-8').read()
            for code, msg in PATTERN.findall(text):
                hits[int(code)].add((msg.strip(), os.path.basename(path)))
    return hits


def main():
    hits = collect()
    rows = sorted((code, sorted({m for m, _ in v}), sorted({owner(f) for _, f in v}))
                  for code, v in hits.items())
    conflicts = [r for r in rows if len(r[1]) > 1]

    out = ['# 错误码速查表（一线支持用，自动生成）', '',
           '> 由 `python tools/gen-error-codes.py` 从源码全量提取，覆盖 %d 个业务码。' % len(rows),
           '> **开发新增码前先查 [错误码分段.md](错误码分段.md) 的分段规约**；本表面向一线：',
           '> 遇到码先按此表定位含义与处置。发版前应重跑本工具保持同步。', '',
           '## 一、跨语义码（同码多义，按码查询须结合发生模块）', '',
           '共 %d 个。多数是不同模块在各自段内独立分配所致（可接受）；' % len(conflicts),
           '**跨模块同码**（如设备管理与存量迁移共用 468x）按码查会查错模块，新增时应避让。', '',
           '| 码 | 含义（多义） | 涉及模块 |', '|---|---|---|']
    for code, msgs, owners in conflicts:
        out.append('| %d | %s | %s |' % (code, '<br>'.join(msgs), ' / '.join(owners)))

    out += ['', '## 二、全量对照（按码升序）', '',
            '| 码 | 提示文案 | 模块 | 常见处置 |', '|---|---|---|---|']
    for code, msgs, owners in rows:
        out.append('| %d | %s | %s | %s |'
                   % (code, '<br>'.join(msgs), ' / '.join(owners), advice(code, msgs[0])))

    target = os.path.join(ROOT, 'docs', '错误码速查表.md')
    open(target, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
    print('已生成 %s：全量 %d 码，跨语义 %d 码' % (target, len(rows), len(conflicts)))


if __name__ == '__main__':
    main()
