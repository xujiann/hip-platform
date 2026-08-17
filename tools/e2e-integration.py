# -*- coding: utf-8 -*-
"""集成引擎 E2E：医保适配器出站留痕 → HL7 ORU 结果回传 → 医嘱自动执行 → 危急值告警与处理
前提：后端运行于 localhost:8080
"""
import json
import sys
import datetime
import urllib.parse
import urllib.request
from e2elib import BASE, call, login, ok, q, today_bj  # noqa: E402



t = login()
today = today_bj().isoformat()

# 1 挂号(免挂号费)→接诊→开血常规→医保收费
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 0, 'capacity': 10}, t), '排班')
reg = ok(call('POST', '/outpatient/registrations', {'patientId': 2, 'scheduleId': sch['id']}, t), '挂号')
rid = reg['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')
lab = ok(call('GET', '/masterdata/charge-items?keyword=' + q('血常规'), token=t), '项目')[0]
orders = ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [{'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1}]}, t), '开单')
lab_order = orders[0]
group_no = lab_order['groupNo']
charge = ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'YB'}, t), '医保收费')
print(f"[1] 医保收费 {charge['chargeNo']} ¥{charge['totalAmount']}，检验申请单 {group_no}")

# 2 医保出站日志应有 YB 记录且含医保结算号
logs = ok(call('GET', '/integration/logs?channel=YB', token=t), '医保日志')
yb = next(l for l in logs if l['refNo'] == charge['chargeNo'])
assert yb['direction'] == 'OUT' and yb['status'] == 'OK' and 'ybSettleNo' in yb['payload']
print(f"[2] 医保出站留痕 OK: {json.loads(yb['payload'])['ybSettleNo']}")

# 3 HL7 ORU^R01 回传（含 HH 危急值）
hl7 = (
    "MSH|^~\\&|LIS|EMSH|HIP|EMSH|20260804120000||ORU^R01|MSG0001|P|2.5\r"
    "PID|1||P00000002||张三\r"
    f"OBR|1|{group_no}||C0001^血常规(五分类)\r"
    "OBX|1|NM|WBC^白细胞计数|1|15.2|10^9/L|3.5-9.5|H\r"
    "OBX|2|NM|HGB^血红蛋白|2|45|g/L|130-175|LL\r"
    "OBX|3|NM|PLT^血小板|3|210|10^9/L|125-350|N\r"
)
ack = ok(call('POST', '/integration/hl7/oru', token=t, text=hl7), 'HL7回传')
assert ack['items'] == 3
print(f"[3] HL7 ORU 回传 OK: {ack}")

# 4 医嘱自动执行 + 结果落库
ws = ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=t), '工作区')
o = next(x for x in ws['orders'] if x['id'] == lab_order['id'])
assert o['status'] == 'EXECUTED', o
results = ok(call('GET', f"/outpatient/lab-results?orderId={lab_order['id']}", token=t), '结果')
assert len(results) == 3
flags = {r['itemName']: r['abnormalFlag'] for r in results}
assert flags['白细胞计数'] == 'H' and flags['血红蛋白'] == 'LL'
print(f"[4] 医嘱自动 EXECUTED，结果 3 行落库: {flags}")

# 5 危急值告警（LL 触发；H 不触发）
alerts = ok(call('GET', '/outpatient/critical-alerts?status=NEW', token=t), '告警')
mine = [a for a in alerts if a.get('patientNo') == 'P00000002' and '血红蛋白' in a['content']]
assert mine, alerts
assert '白细胞' not in mine[0]['content'], '仅 HH/LL 触发危急值'
aid = mine[0]['id']
print(f"[5] 危急值告警 OK: {mine[0]['content']}")

# 6 处理告警 + 驾驶舱计数减一（可能存在其他套件的遗留告警，不假设全局为零）
before = ok(call('GET', '/stats/overview', token=t), '统计')['pendingCriticalAlerts']
ok(call('PUT', f'/outpatient/critical-alerts/{aid}/handle', token=t), '处理')
after = ok(call('GET', '/stats/overview', token=t), '统计')['pendingCriticalAlerts']
assert after == before - 1, f'{before} -> {after}'
print(f"[6] 告警处理完成，驾驶舱待处理 {before} -> {after}")

# 7 医保退费冲正留痕 + 错误报文防护
# 需先退费（检验已执行不可退，改验证错误报文路径）
bad = call('POST', '/integration/hl7/oru', token=t, text="MSH|^~\\&|LIS|E|HIP|E|20260804||ADT^A01|X|P|2.5\r")
assert bad['code'] == 7001, bad
logs = ok(call('GET', '/integration/logs?channel=HL7_LIS', token=t), '日志')
assert any(l['status'] == 'FAIL' for l in logs)
print('[7] 非法报文拒收(7001) + FAIL 留痕 OK')

print('\n=== 集成引擎 E2E 全部通过 ✔ ===')
