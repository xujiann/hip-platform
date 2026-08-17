# -*- coding: utf-8 -*-
"""性能压测基线：并发挂号（验证原子占号在并发下不超挂 + 吞吐量）。
用法：python tools/bench-registration.py [并发数] [总请求数]
"""
import json
import sys
import time
import datetime
import threading
import urllib.request

BASE = 'http://localhost:8080/api'
THREADS = int(sys.argv[1]) if len(sys.argv) > 1 else 10
TOTAL = int(sys.argv[2]) if len(sys.argv) > 2 else 100
sys.stdout.reconfigure(encoding='utf-8')


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    with urllib.request.urlopen(req, data=json.dumps(body).encode() if body else None) as r:
        return json.loads(r.read().decode())


token = call('POST', '/auth/login', {'username': 'admin', 'password': 'admin123'})['data']['token']
import sys as _s; _s.path.insert(0, 'tools')
from e2elib import today_bj
today = today_bj().isoformat()
# 号源容量 = 总请求数的一半 → 一半成功一半满号，验证并发防超挂
capacity = TOTAL // 2
sch = call('POST', '/outpatient/schedules',
           {'deptId': 1, 'scheduleDate': today, 'fee': 0, 'capacity': capacity}, token)['data']

results = {'ok': 0, 'full': 0, 'dup': 0, 'err': 0}
lock = threading.Lock()
latencies = []


def worker(idx):
    # 每个请求独立新建患者，避免重复挂号拦截干扰
    try:
        p = call('POST', '/patients', {'name': f'压测{idx}', 'sex': 'U'}, token)['data']
        t0 = time.time()
        r = call('POST', '/outpatient/registrations',
                 {'patientId': p['id'], 'scheduleId': sch['id']}, token)
        dt = time.time() - t0
        with lock:
            latencies.append(dt)
            if r['code'] == 0:
                results['ok'] += 1
            elif r['code'] == 3003:
                results['full'] += 1
            else:
                results['err'] += 1
    except Exception:
        with lock:
            results['err'] += 1


t0 = time.time()
threads = []
for i in range(TOTAL):
    th = threading.Thread(target=worker, args=(i,))
    threads.append(th)
    th.start()
    if len([t for t in threads if t.is_alive()]) >= THREADS:
        threads[-THREADS].join()
for th in threads:
    th.join()
elapsed = time.time() - t0

latencies.sort()
p95 = latencies[int(len(latencies) * 0.95) - 1] * 1000 if latencies else 0
print(f'并发 {THREADS} × 总量 {TOTAL}，号源容量 {capacity}')
print(f"成功 {results['ok']}，满号拦截 {results['full']}，错误 {results['err']}")
print(f'总耗时 {elapsed:.1f}s，吞吐 {TOTAL / elapsed:.1f} req/s，挂号 P95 {p95:.0f}ms')
assert results['ok'] == capacity, f"防超挂校验失败：成功数 {results['ok']} ≠ 容量 {capacity}"
print('并发防超挂校验通过 ✔')
