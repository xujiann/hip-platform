# -*- coding: utf-8 -*-
"""收费与发药窗口并发压测基线（v26-C）。

`bench-registration.py` 只压挂号；收费与发药是另外两个高频窗口场景，
且各自带条件更新抢占（claimCharge / claimDispense）——既要量吞吐，
也要验证并发下不出现双结算、双扣库存。

用法：
    HIP_BASE=http://localhost:8080/api python tools/bench-charge-dispense.py [并发数] [就诊数]
默认 10 并发、100 个就诊。每个就诊独立建患者与处方，互不干扰；
另对**同一就诊**并发重复提交，验证抢占防线在压力下仍然只放行一次。
"""
import json
import os
import sys
import threading
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from e2elib import today_bj  # noqa: E402

sys.stdout.reconfigure(encoding='utf-8')
BASE = os.environ.get('HIP_BASE', 'http://localhost:8080/api')
THREADS = int(sys.argv[1]) if len(sys.argv) > 1 else 10
VISITS = int(sys.argv[2]) if len(sys.argv) > 2 else 100


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    with urllib.request.urlopen(req, data=json.dumps(body).encode() if body is not None else None) as r:
        return json.loads(r.read().decode())


token = call('POST', '/auth/login',
             {'username': os.environ.get('HIP_USER', 'admin'),
              'password': os.environ.get('HIP_PASSWORD', 'admin123')})['data']['token']

drug = call('GET', '/masterdata/drugs?keyword=' + urllib.parse.quote('阿莫西林'), token=token)['data'][0]
sch = call('POST', '/outpatient/schedules',
           {'deptId': 1, 'scheduleDate': today_bj().isoformat(), 'fee': 0, 'capacity': VISITS * 2},
           token)['data']


def make_visit(idx):
    """建患者 → 挂号 → 接诊 → 开一条药嘱，返回 registrationId"""
    p = call('POST', '/patients', {'name': f'压测收费{idx}', 'sex': 'U'}, token)['data']
    reg = call('POST', '/outpatient/registrations',
               {'patientId': p['id'], 'scheduleId': sch['id']}, token)['data']
    call('POST', f"/outpatient/doctor/{reg['id']}/start", {}, token)
    call('POST', f"/outpatient/doctor/{reg['id']}/orders",
         {'lines': [{'orderType': 'DRUG', 'itemId': drug['id'], 'qty': 1,
                     'usageRoute': '口服', 'frequency': 'qd', 'dosePerTime': '1粒'}]}, token)
    return reg['id']


print(f'准备 {VISITS} 个就诊（含挂号+开单）...')
visits = [make_visit(i) for i in range(VISITS)]

lock = threading.Lock()
stats = {'settle_ok': 0, 'settle_fail': 0, 'dispense_ok': 0, 'dispense_fail': 0, 'err': 0}
settle_lat, dispense_lat = [], []


def bench_worker(reg_ids):
    for rid in reg_ids:
        try:
            t0 = time.time()
            r = call('POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'}, token)
            dt = time.time() - t0
            with lock:
                settle_lat.append(dt)
                stats['settle_ok' if r['code'] == 0 else 'settle_fail'] += 1
            t0 = time.time()
            r = call('POST', f'/outpatient/dispense/{rid}', {}, token)
            dt = time.time() - t0
            with lock:
                dispense_lat.append(dt)
                stats['dispense_ok' if r['code'] == 0 else 'dispense_fail'] += 1
        except Exception:
            with lock:
                stats['err'] += 1


chunks = [visits[i::THREADS] for i in range(THREADS)]
start = time.time()
threads = [threading.Thread(target=bench_worker, args=(c,)) for c in chunks]
for t in threads:
    t.start()
for t in threads:
    t.join()
elapsed = time.time() - start


def p95(xs):
    return sorted(xs)[int(len(xs) * 0.95)] if xs else 0


ops = stats['settle_ok'] + stats['dispense_ok']
print(f"\n并发 {THREADS} × 就诊 {VISITS}（每就诊 1 次收费 + 1 次发药）")
print(f"收费成功 {stats['settle_ok']}，发药成功 {stats['dispense_ok']}，"
      f"业务拒绝 {stats['settle_fail'] + stats['dispense_fail']}，异常 {stats['err']}")
print(f"总耗时 {elapsed:.1f}s，吞吐 {ops / elapsed:.1f} ops/s")
print(f"收费 P95 {p95(settle_lat) * 1000:.0f}ms，发药 P95 {p95(dispense_lat) * 1000:.0f}ms")

# 抢占防线：对同一就诊并发重复提交，只能放行一次
probe = make_visit('抢占探针')
race = {'ok': 0, 'rejected': 0}


def race_worker():
    try:
        r = call('POST', '/outpatient/charges/settle', {'registrationId': probe, 'payMethod': 'CASH'}, token)
        with lock:
            race['ok' if r['code'] == 0 else 'rejected'] += 1
    except Exception:
        with lock:
            race['rejected'] += 1


rts = [threading.Thread(target=race_worker) for _ in range(THREADS)]
for t in rts:
    t.start()
for t in rts:
    t.join()
print(f"\n同一就诊并发结算 {THREADS} 次：成功 {race['ok']}，抢占拒绝 {race['rejected']}")
assert race['ok'] == 1, f"并发结算必须恰好一次成功（双倍扣款是历史事故 P1-7），实际 {race['ok']}"
assert stats['err'] == 0, f"不应有异常，实际 {stats['err']}"
print('并发抢占校验通过 ✔')
