# -*- coding: utf-8 -*-
"""1.2.13 车道A 药事全链 E2E：入库验收 / 库存盘点 / 效期预警（估算口径）。
三项各至少一条断言。前提：后端运行于 localhost:8080。
"""
import datetime
from e2elib import call, login, ok, q, today_bj  # noqa: E402

t = login()
stamp = datetime.datetime.now().strftime('%H%M%S')
DRUG_KW = '阿莫西林'


def get_drug():
    return ok(call('GET', '/masterdata/drugs?keyword=' + q(DRUG_KW), token=t), '药品')[0]


did = get_drug()['id']

# ========== ③ 入库验收：待验收不入账 → 通过才加库存 / 可拒收 / 原因必填 ==========
s0 = get_drug()['stock']
si = ok(call('POST', '/inventory/stock-in', {'drugId': did, 'qty': 40, 'batchNo': 'PC' + stamp,
        'expireDate': '2028-12-31', 'supplier': '验收供应商', 'purchaseNo': 'CG' + stamp}, t), '入库登记')
assert si['acceptStatus'] == 'PENDING_ACCEPT', si
assert get_drug()['stock'] == s0, '待验收阶段不得加库存'
pending = ok(call('GET', '/inventory/stock-ins/pending', token=t), '待验收列表')
assert any(p['id'] == si['id'] for p in pending), '待验收列表应含新登记单'
ok(call('POST', f"/inventory/stock-in/{si['id']}/accept", token=t), '验收通过')
assert get_drug()['stock'] == s0 + 40, '验收通过后库存应 +40'

# 拒收：不加库存
si2 = ok(call('POST', '/inventory/stock-in', {'drugId': did, 'qty': 15, 'batchNo': 'PR' + stamp,
        'supplier': '供'}, t), '入库登记(待拒收)')
s1 = get_drug()['stock']
ok(call('POST', f"/inventory/stock-in/{si2['id']}/reject", {'reason': '外包装破损'}, t), '拒收')
assert get_drug()['stock'] == s1, '拒收不得加库存'

# 拒收原因必填 → 8012
si3 = ok(call('POST', '/inventory/stock-in', {'drugId': did, 'qty': 5, 'supplier': '供'}, t), '入库登记(空原因)')
r = call('POST', f"/inventory/stock-in/{si3['id']}/reject", {'reason': ''}, t)
assert r['code'] == 8012, f'空拒收原因应 8012: {r}'
print(f"[③ 入库验收] OK：待验收不入账→通过 +40→拒收不加库存→空原因拦截 8012")

# ========== ① 库存盘点：录实盘 → 看盈亏 → 确认调库 + STOCKTAKE 流水 ==========
book = get_drug()['stock']
take = ok(call('POST', '/inventory/stock-take', {'drugIds': [did], 'remark': 'E2E盘点' + stamp}, t), '建盘点单')
assert take['lineCount'] == 1 and take['lines'][0]['bookQty'] == book, take
counted = ok(call('POST', f"/inventory/stock-take/{take['id']}/counts",
        {'entries': [{'drugId': did, 'actualQty': book + 3}]}, t), '录实盘数')
assert counted['netDiff'] == 3 and counted['lines'][0]['diff'] == 3, counted
confirmed = ok(call('POST', f"/inventory/stock-take/{take['id']}/confirm", token=t), '确认调库')
assert confirmed['status'] == 'CONFIRMED', confirmed
assert get_drug()['stock'] == book + 3, '确认后库存应=实盘数'
txns = ok(call('GET', '/inventory/transactions?drugId=' + str(did), token=t), '流水')
assert any(x['type'] == 'STOCKTAKE' and x['qty'] == 3 for x in txns), '应写一条 STOCKTAKE 盈亏流水'
print(f"[① 库存盘点] OK：账面 {book} → 实盘 {book + 3}（盘盈 +3），确认调库 + STOCKTAKE 流水 OK")

# ========== ② 效期预警（估算口径）：近效期批次入列 + 手动巡检开单 ==========
near = (today_bj() + datetime.timedelta(days=30)).isoformat()
batch_no = 'EXP' + stamp
# 大批量确保 FEFO 分摊消耗后仍有估算在库（口径为估算，不追求精确批次）
se = ok(call('POST', '/inventory/stock-in', {'drugId': did, 'qty': 50000, 'batchNo': batch_no,
        'expireDate': near, 'supplier': '效期供应商'}, t), '入库(近效期)')
ok(call('POST', f"/inventory/stock-in/{se['id']}/accept", token=t), '验收(近效期)')
warns = ok(call('GET', '/inventory/expiry-warning?days=90', token=t), '近效期查询')
mine = [w for w in warns if w['batchNo'] == batch_no]
assert mine, f'90 天预警应含近效期批次 {batch_no}'
assert mine[0]['status'] == 'NEAR_EXPIRY' and mine[0]['estimatedRemaining'] > 0, mine[0]
assert all(w['daysToExpire'] <= 90 for w in warns), '90 天窗不应含 >90 天到期批次'
scan = ok(call('POST', '/inventory/expiry-scan', token=t), '手动巡检开单')
assert scan['opened'] >= 1, f'新近效期批次应至少开一张提醒单: {scan}'
print(f"[② 效期预警] OK：批次 {batch_no} 估算在库 {mine[0]['estimatedRemaining']}"
      f"（{mine[0]['status']}，剩 {mine[0]['daysToExpire']} 天），巡检开单 {scan['opened']} 条")

print('\n=== 药事全链 E2E 全部通过 ✔ ===')
