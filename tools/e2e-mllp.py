# -*- coding: utf-8 -*-
"""MLLP/TCP E2E：socket 发送 MLLP 帧的 ORU^R01 → 医嘱自动执行 + ACK 校验"""
import json
import socket
import sys
import datetime
import urllib.parse
import urllib.request
from e2elib import BASE, call, login, ok, q  # noqa: E402



def mllp_send(payload: str) -> str:
    with socket.create_connection(('localhost', 2575), timeout=10) as s:
        s.sendall(b'\x0b' + payload.encode('utf-8') + b'\x1c\x0d')
        buf = b''
        while b'\x1c\x0d' not in buf:
            chunk = s.recv(4096)
            if not chunk:
                break
            buf += chunk
        return buf.split(b'\x0b', 1)[-1].split(b'\x1c', 1)[0].decode('utf-8')


t = login()
today = datetime.date.today().isoformat()

# 准备：挂号→接诊→开检验→收费
sch = ok(call('POST', '/outpatient/schedules', {'deptId': 1, 'scheduleDate': today, 'fee': 0, 'capacity': 10}, t), '排班')
reg = ok(call('POST', '/outpatient/registrations', {'patientId': 2, 'scheduleId': sch['id']}, t), '挂号')
rid = reg['id']
ok(call('POST', f'/outpatient/doctor/{rid}/start', {}, t), '接诊')
lab = ok(call('GET', '/masterdata/charge-items?keyword=' + q('尿常规'), token=t), '项目')[0]
orders = ok(call('POST', f'/outpatient/doctor/{rid}/orders', {'lines': [{'orderType': 'LAB', 'itemId': lab['id'], 'qty': 1}]}, t), '开单')
group_no = orders[0]['groupNo']
ok(call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, t), '收费')
print(f'[0] 准备就绪，申请单 {group_no}')

# 1 MLLP 发送 ORU → ACK AA
oru = (
    "MSH|^~\\&|LIS|EMSH|HIP|EMSH|20260804||ORU^R01|MLLP001|P|2.5\r"
    f"OBR|1|{group_no}||C0002^尿常规\r"
    "OBX|1|ST|PRO^尿蛋白|1|阴性||阴性|N\r"
    "OBX|2|ST|GLU^尿糖|2|阴性||阴性|N\r"
)
ack = mllp_send(oru)
assert 'MSA|AA|MLLP001' in ack, ack
print(f'[1] MLLP ORU 发送成功，ACK: {ack.splitlines()[1]}')

# 2 医嘱自动执行 + 结果落库
ws = ok(call('GET', f'/outpatient/doctor/{rid}/workspace', token=t), '工作区')
o = next(x for x in ws['orders'] if x['groupNo'] == group_no)
assert o['status'] == 'EXECUTED', o
results = ok(call('GET', f"/outpatient/lab-results?orderId={o['id']}", token=t), '结果')
assert len(results) == 2
print(f'[2] 医嘱经 MLLP 通道自动执行，{len(results)} 行结果落库')

# 3 非法报文 → ACK AE
bad_ack = mllp_send("MSH|^~\\&|LIS|E|HIP|E|20260804||ADT^A01|MLLP002|P|2.5\r")
assert 'MSA|AE|MLLP002' in bad_ack, bad_ack
print('[3] 非法报文 ACK AE 拒收 OK')

print('\n=== MLLP/TCP E2E 全部通过 ✔ ===')
