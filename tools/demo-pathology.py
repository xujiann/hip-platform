# -*- coding: utf-8 -*-
"""病理演示数据驱动脚本（v58 车道 C，2576）：**纯走真实 API，不写库、不塞种子**。

反驳者原话（2576）：「仓库零病理种子，菜单 169 目标角色 QUALITY 在系统内无任何造数路径，
空库时 QUALITY 打开质控页看到的是『报告签发量』30 行全 0 + 另两张『无数据』；
要出一条非零数据须 ADMIN 跨 9 页约 18 步、两次登录」。

**为什么不是一份 SQL 种子**：零回填 / 零伪造种子是本仓铁律（docs/种子数据分层清单.md：
演示数据从第一天起就走脚本不走迁移）。一条 insert 出来的「已签发报告」没有取材、没有切片、
没有两个人的签名、没有流转节点——它不是数据，是一张画出来的表。本脚本做的**每一步都是真实
业务操作**（登录 → 建患者 → 排班 → 挂号 → 接诊 → 开病理医嘱 → 结算 → 登记 → 核收 → 取材 →
脱水 → 包埋 → 切片 → 染色 → 特检医嘱 → 诊断 → 初签 → 复签 → 签发 → 补充报告 / 拒收），
落下的**每一行都是系统自己写的**：病理号由系统编号规则生成、时刻由服务端 now() 落、
流转节点由各端点自己打点。脚本本身不连数据库、不 import 任何 DB 驱动。

**做不到的事，如实写在这里**：脚本不能回溯日期——所有时刻都是运行当下（服务端 now()），
所以质控页的 30 天趋势只有「今天」这一根柱；想要多天的柱子，只能多天各跑一次。

调用形态逐字抄自 tools/e2elib.py、tools/e2e-v48-pathology.py、tools/e2e-v57-audit.py
（这三份已在 CI 全新库上跑通），**不猜契约**：驼峰 / 蛇形、items/rows、排班是 POST 建、
开单前须先 start、登记只认 CHARGED、diagnose 会把 outp_order 置 EXECUTED、双签须两个人。

用法：
  HIP_E2E_BASE=http://localhost:8080/api python tools/demo-pathology.py            # 默认 5 个阶梯标本 + 1 个拒收
  python tools/demo-pathology.py --count 2 --quiet                                   # CI：只打印一行成功摘要
  python tools/demo-pathology.py --base http://localhost:8083/api --count 8

参数：
  --count N   阶梯标本数（默认 5）。N 个标本从阶梯**顶端往下**轮流分配（先签发、再诊断……），
              保证 N=1、2 时也有「已签发 / 有蜡块」的数据；另**固定再加 1 个拒收标本**，
              故实际登记 N+1 个。三个附加动作（补充报告 / IHC 医嘱挂切片并完成 / 特检医嘱带原因取消）
              轮流分配到各「已签发」标本上；只有 1 个已签发时三者都落在它身上。
  --quiet     只打印一行成功摘要；任何一步失败打印响应并以非 0 退出。
  --base      API 根地址（默认读环境变量 HIP_E2E_BASE，再默认 http://localhost:8080/api）。

退出码：0 成功；1 任一业务步骤失败（响应已打印）；2 造完数后质控概览三项
（登记总量 / 蜡块产出 / 报告签发量）仍有 0——说明「造了数但质控页看不见」，这正是 2576 要钉死的。

所有患者名带时间戳后缀，脚本可在同一实例上反复跑而不撞名；复诊医师账号 demo_pathdoc 与
质控账号 demo_quality 幂等复用（口令沿用 e2elib.provision_user 约定）。
"""
import argparse
import datetime
import os
import sys
import time
import traceback
import urllib.error


def _parse_args():
    p = argparse.ArgumentParser(description='病理演示数据驱动（纯走真实 API，不写库）')
    p.add_argument('--count', type=int, default=5, help='阶梯标本数（默认 5；另固定加 1 个拒收）')
    p.add_argument('--quiet', action='store_true', help='只打印一行成功摘要')
    p.add_argument('--base', default=os.environ.get('HIP_E2E_BASE', 'http://localhost:8080/api'),
                   help='API 根地址（默认读 HIP_E2E_BASE）')
    return p.parse_args()


ARGS = _parse_args()
if ARGS.count < 1:
    print('--count 至少为 1', file=sys.stderr)
    sys.exit(1)
# e2elib 在 import 时读 HIP_E2E_BASE，故先落环境变量再 import
os.environ['HIP_E2E_BASE'] = ARGS.base

from e2elib import call, login, new_patient, ok, provision_user, today_bj  # noqa: E402

QUIET = ARGS.quiet
# stderr 也切 UTF-8：Windows 控制台默认 GBK，失败时的 traceback 里有中文步骤名（stdout 由 e2elib 已切）
try:
    sys.stderr.reconfigure(encoding='utf-8')
except Exception:  # noqa: BLE001  非终端 / 老解释器没有 reconfigure，不影响主流程
    pass
# 时间戳后缀：同 e2e-v57-audit.py 的 uniq()，避免与既有数据撞名
SUF = str(int(time.time() * 1000) % 100000000)
_last_step = ['登录']


def say(msg):
    if not QUIET:
        print(msg)


def iso(mins):
    """与 e2e-v57-audit.py 同：从当下 UTC 派生，不写墙钟字面量"""
    return (datetime.datetime.now(datetime.timezone.utc)
            + datetime.timedelta(minutes=mins)).isoformat().replace('+00:00', 'Z')


# ===========================================================================
# 业务辅助：调用形态逐字抄自 e2e-v57-audit.py（visited / pathology_order / register / grossing）
# ===========================================================================
t = None            # admin 令牌，main() 里登录后填；放模块层会让登录失败绕过下面的统一失败出口
today = today_bj()


def do(name, m, p, b=None, tok=None):
    """一步业务操作：先记步骤名（失败时能指出是哪一步），再调用并校验 code==0"""
    _last_step[0] = name
    return ok(call(m, p, b, tok or t), name)


def visited(pid, dept_id=1):
    sch = do('排班', 'POST', '/outpatient/schedules',
             {'deptId': dept_id, 'scheduleDate': today.isoformat(), 'fee': '0.00', 'capacity': 50})
    reg = do('挂号', 'POST', '/outpatient/registrations', {'patientId': pid, 'scheduleId': sch['id']})
    do('接诊', 'POST', f"/outpatient/doctor/{reg['id']}/start", {})
    return reg['id']


def pathology_order(pid, name):
    """自建一条已收费的门诊病理申请，返回 order_id。
    在待取材队列里**按 patient_name 认领自己的**（既有 GET /pathology/pending 只回
    order_id/item_name/group_no/patient_name 四键，没有 patient_id；患者名带时间戳后缀），
    不拿 pend[0]——脏库里 pend[0] 可能是别人的。"""
    rid = visited(pid)
    items = do('收费项目', 'GET', '/masterdata/charge-items')
    cand = next((i for i in items if '病理' in (i.get('name') or '') or '活检' in (i.get('name') or '')), None)
    assert cand, '主数据无病理收费项目（主数据缺项，非功能缺陷）'
    do('开病理医嘱', 'POST', f'/outpatient/doctor/{rid}/orders',
       {'lines': [{'orderType': 'EXAM', 'itemId': cand['id'], 'qty': 1}]})
    do('结算', 'POST', '/outpatient/charges/settle', {'registrationId': rid, 'payMethod': 'CASH'})
    pend = do('待取材', 'GET', '/pathology/pending')
    oid = next((p['order_id'] for p in pend if p.get('order_id') and p.get('patient_name') == name), None)
    assert oid, f'待取材队列里没有患者 {name} 刚开的病理医嘱：{pend[:3]}'
    return oid


def register(oid, part, desc, site, clin):
    """登记 → (specimenId, barcode, pathNo)。**不在这里核收**——「已登记未核收」是阶梯的一档。"""
    reg = do(f'登记部位 {part}', 'POST', '/pathology/registry/specimens',
             {'orderId': oid, 'partNo': part, 'specimenType': 'ROUTINE', 'specimenDesc': desc,
              'samplingSite': site, 'clinicalDiagnosis': clin, 'fixative': '10%中性福尔马林',
              'fixedAt': iso(-30), 'urgent': False})
    sid = reg.get('id') or reg.get('specimenId')
    bc = reg.get('barcode') or reg.get('barCode')
    pno = reg.get('pathNo') or reg.get('path_no')
    assert sid and bc and pno, f'登记返回体应带 id、条码与病理号：{reg}'
    return sid, bc, pno


def receive(sid):
    do('接收核对', 'PUT', f'/pathology/registry/specimens/{sid}/receive-check', {})


def grossing(sid, text, descs):
    """取材写大体所见（GROSSING 节点，落 path_specimen.gross_finding），返回蜡块 id 列表"""
    do('取材', 'POST', '/pathology/process/grossing',
       {'specimenId': sid, 'grossText': text, 'blocks': [{'tissueDesc': d} for d in descs]})
    blocks = do('蜡块列表', 'GET', f'/pathology/process/blocks?specimenId={sid}').get('items') or []
    assert len(blocks) >= len(descs), f'取材应产出 {len(descs)} 个蜡块：{blocks}'
    return [b['id'] for b in blocks]


def technical(sid, block_ids, idx):
    """脱水篮分组 → 逐块包埋 → HE 切片 2 张 → 逐张染色登记（quality=GOOD）。返回切片 id 列表。"""
    do('脱水篮分组', 'PUT', '/pathology/process/blocks/dehydrate-batch',
       {'batchNo': f'DH-DEMO-{SUF}-{idx}', 'blockIds': block_ids})
    for bid in block_ids:
        do('包埋', 'PUT', f'/pathology/process/blocks/{bid}/embed', {})
    sl = do('HE 切片', 'POST', '/pathology/process/slides',
            {'blockId': block_ids[0], 'count': 2, 'stainType': 'HE'})
    slide_ids = [s['id'] for s in (sl.get('slides') or [])]
    assert len(slide_ids) == 2, f'应产出 2 张 HE 切片：{sl}'
    for s in slide_ids:
        do('染色登记', 'PUT', f'/pathology/process/slides/{s}/stain', {'quality': 'GOOD'})
    return slide_ids


def ihc_attach(sid, block_id):
    """下 IHC 医嘱 → 切片挂接到医嘱（techOrderId）→ 染色登记 → 完成医嘱。返回 (医嘱 id, 挂接张数)"""
    tid = do('下 IHC 医嘱', 'POST', '/pathology/report/tech-orders',
             {'specimenId': sid, 'blockId': block_id, 'techType': 'IHC', 'techItem': 'CK7',
              'reason': 'HE 片见异型细胞，加做免疫组化明确组织来源'})['id']
    sl = do('IHC 切片挂接医嘱', 'POST', '/pathology/process/slides',
            {'blockId': block_id, 'count': 2, 'stainType': 'IHC', 'stainItem': 'CK7', 'techOrderId': tid})
    assert sl.get('techOrderId') == tid and all(s.get('tech_order_id') == tid for s in sl['slides']), \
        f'每张 IHC 切片的 tech_order_id 都须等于医嘱 id：{sl}'
    for s in sl['slides']:
        do('IHC 染色登记', 'PUT', f"/pathology/process/slides/{s['id']}/stain",
           {'quality': 'GOOD', 'stainItem': 'CK7'})
    do('完成 IHC 医嘱', 'PUT', f'/pathology/report/tech-orders/{tid}/done', {})
    return tid, len(sl['slides'])


def tech_cancel(sid, block_id):
    """下特殊染色医嘱 → 带原因取消（v57 2563：取消原因写 cancel_reason，下达原因 reason 不动）"""
    tid = do('下特殊染色医嘱', 'POST', '/pathology/report/tech-orders',
             {'specimenId': sid, 'blockId': block_id, 'techType': 'SPECIAL_STAIN', 'techItem': 'PAS',
              'reason': '排除真菌感染'})['id']
    c = do('取消特检医嘱（带原因）', 'PUT', f'/pathology/report/tech-orders/{tid}/cancel',
           {'reason': '临床补充病史已排除感染，不再需要特殊染色'})
    assert c['status'] == 'CANCELLED' and c.get('cancel_reason') and c.get('cancelled_at'), \
        f'取消返回体应带留痕三列：{c}'
    return tid


def diagnose(bc, idx):
    """既有诊断端点：grossFinding 传空 → 保留取材写的大体所见（v57 2530 契约）"""
    d = do('书写诊断', 'PUT', f'/pathology/specimens/{bc}/diagnose',
           {'grossFinding': '',
            'microFinding': f'镜下见腺体结构紊乱，细胞核增大深染，可见核分裂象（演示 {idx}）',
            'diagnosis': f'（左乳）浸润性导管癌，非特殊类型（演示 {idx}）'})
    assert d.get('grossKept') is True, f'取材已写大体所见，diagnose 传空应保留（grossKept=true）：{d}'


def double_sign_and_issue(sid, t2):
    do('初诊签名', 'PUT', f'/pathology/report/{sid}/first-sign', {})
    do('复诊签名（另一人）', 'PUT', f'/pathology/report/{sid}/second-sign', {}, tok=t2)
    issued = do('正式签发', 'PUT', f'/pathology/report/{sid}/issue', {})
    assert issued.get('reportIssuedAt') and issued.get('doubleSignComplete') is True, f'签发响应：{issued}'
    return issued


def supplement(sid):
    do('补充报告', 'POST', f'/pathology/report/{sid}/supplement',
       {'content': '免疫组化：CK7(+)、CK20(-)、ER(+80%)、PR(+60%)、HER2(1+)、Ki-67(约 20%+)',
        'reason': '免疫组化结果回报'})


# ===========================================================================
# 阶梯：从顶端往下轮流分配，N=1/2 也能出「已签发 + 有蜡块」的数
# ===========================================================================
LADDER = [
    ('ISSUED', '双签已签发'),
    ('DIAGNOSED', '已诊断未签发'),
    ('STAINED', '已切片已染色未诊断'),
    ('RECEIVED', '已核收未取材'),
    ('COLLECTED', '已登记未核收'),
]
VARIANTS = ['SUPPLEMENT', 'IHC_ATTACH', 'TECH_CANCEL']
SITES = ['左乳外上象限', '右乳内下象限', '胃窦小弯侧', '乙状结肠', '甲状腺左叶', '子宫颈 3 点']

plan = [LADDER[i % len(LADDER)] for i in range(ARGS.count)] + [('REJECTED', '已拒收')]
issued_idx = [i for i, (st, _) in enumerate(plan) if st == 'ISSUED']
variants_of = {i: [] for i in range(len(plan))}
for k, v in enumerate(VARIANTS):
    variants_of[issued_idx[k % len(issued_idx)]].append(v)


def build(idx, stage, label, variants, t2):
    name = f'演示病理{idx + 1:02d}号{SUF}'
    rec = {'idx': idx + 1, 'stage': stage, 'label': label, 'name': name, 'blocks': 0, 'slides': 0,
           'extras': [], 'last': None}
    say(f'--- 标本 {idx + 1}/{len(plan)}：{label}{"（" + "、".join(variants) + "）" if variants else ""} 患者 {name}')
    _last_step[0] = '建患者'
    pid = new_patient(t, name, sex='F' if idx % 2 == 0 else 'M')['id']
    oid = pathology_order(pid, name)
    site = SITES[idx % len(SITES)]
    sid, bc, pno = register(oid, 1, f'{site}肿物（演示）', site, '肿物待查')
    rec.update(sid=sid, barcode=bc, pathNo=pno, last='登记')
    say(f'    登记 OK 病理号 {pno} 条码 {bc}')

    if stage == 'REJECTED':
        # 不传 rejectedAt，让服务端取 now()：脚本时钟比 collected_at 早会撞 5210（同 e2e-v48）
        do('拒收', 'PUT', f'/pathology/registry/specimens/{sid}/reject',
           {'reason': '标本未固定，离体超 6 小时（演示）'})
        rec['last'] = '拒收（不删行、不改 status）'
        return rec
    if stage == 'COLLECTED':
        return rec

    receive(sid)
    rec['last'] = '接收核对'
    if stage == 'RECEIVED':
        return rec

    block_ids = grossing(sid, f'灰白灰红组织一块，3×2×1cm，切面实性质硬（演示 {idx + 1}）', ['肿物中心', '肿物周边'])
    slide_ids = technical(sid, block_ids, idx + 1)
    rec.update(blocks=len(block_ids), slides=len(slide_ids), last='HE 切片染色')
    if stage == 'STAINED':
        return rec

    if 'IHC_ATTACH' in variants:
        tid, n = ihc_attach(sid, block_ids[0])
        rec['slides'] += n
        rec['extras'].append(f'IHC 医嘱 #{tid} 挂接 {n} 张切片并完成')
    if 'TECH_CANCEL' in variants:
        tid = tech_cancel(sid, block_ids[-1])
        rec['extras'].append(f'特检医嘱 #{tid} 带原因取消')

    diagnose(bc, idx + 1)
    rec['last'] = '书写诊断'
    if stage == 'DIAGNOSED':
        return rec

    issued = double_sign_and_issue(sid, t2)
    rec['last'] = '双签签发'
    rec['issuedAt'] = issued['reportIssuedAt']
    if 'SUPPLEMENT' in variants:
        supplement(sid)
        rec['extras'].append('补充报告 1 份')
        rec['last'] = '补充报告'
    return rec


def current_state(rec):
    """回读检索端点，状态以库为准（不拿脚本自己的记忆当事实）"""
    rows = do('回读标本', 'GET', f"/pathology/registry/specimens/search?barcode={rec['barcode']}").get('items') or []
    row = next((r for r in rows if r.get('id') == rec['sid']), None)
    assert row, f"检索端点找不到刚登记的标本 {rec['barcode']}：{rows[:2]}"
    if row.get('rejected_at'):
        return '已拒收', row
    if row.get('report_issued_at'):
        return '已签发', row
    return {'COLLECTED': '已登记', 'RECEIVED': '已核收', 'DIAGNOSED': '已诊断'}.get(row.get('status'), row.get('status')), row


def qc_overview(tok):
    """质控概览（QUALITY 角色令牌，默认时间窗 = 页面打开即见的近 30 天）→ 三项汇总值"""
    body = do('质控概览（QUALITY 令牌）', 'GET', '/path-qc/indicators', tok=tok)
    by = {i['code']: i for i in body.get('indicators') or []}

    def val(code, key):
        return ((by.get(code) or {}).get('summary') or {}).get(key)
    return body, by, {
        '登记总量 WORKLOAD_REGISTER.registered': val('WORKLOAD_REGISTER', 'registered'),
        '蜡块产出 WORKLOAD_BLOCK.blocks': val('WORKLOAD_BLOCK', 'blocks'),
        '报告签发量 WORKLOAD_REPORT.issued_reports': val('WORKLOAD_REPORT', 'issued_reports'),
    }


def main():
    global t
    say(f'目标 {ARGS.base}  阶梯 {ARGS.count} + 拒收 1 = {len(plan)} 个标本  后缀 {SUF}')
    _last_step[0] = '登录'
    t = login()
    _last_step[0] = '建复诊医师账号 demo_pathdoc'
    t2 = provision_user(t, 'demo_pathdoc', 'DOCTOR_OUTP', '演示病理复诊医师')
    _last_step[0] = '建质控账号 demo_quality'
    tq = provision_user(t, 'demo_quality', 'QUALITY', '演示质控')
    records = [build(i, st, lb, variants_of[i], t2) for i, (st, lb) in enumerate(plan)]

    # ---- 汇总表：状态回读自库 ----
    rows = []
    for rec in records:
        state, row = current_state(rec)
        rows.append((rec, state, row))
    body, by, three = qc_overview(tq)

    if not QUIET:
        print('\n==== 本次造出的标本（状态回读自 GET /pathology/registry/specimens/search） ====')
        print('序号 | 阶梯目标 | 病理号 | 条码 | 库中状态 | 做到哪一步 | 蜡块/切片 | 附加')
        for rec, state, row in rows:
            print(f"{rec['idx']:>4} | {rec['label']} | {row.get('path_no') or ''} | {rec['barcode']} | "
                  f"{state} | {rec['last']} | {rec['blocks']}/{rec['slides']} | "
                  f"{'；'.join(rec['extras']) or '-'}")
        print(f"\n==== 现在质控页（菜单 169 病理质控，{body.get('from')} 至 {body.get('to')}）哪些指标有数 ====")
        for code, ind in by.items():
            if ind.get('available') is not True:
                print(f'  {code:<28} {ind.get("name")}  → available:false（缺数据源，如实标注，不凑数）')
                continue
            n = len(ind.get('rows') or [])
            summ = ind.get('summary') or {}
            nz = {k: v for k, v in summ.items() if isinstance(v, (int, float)) and v}
            print(f'  {code:<28} {ind.get("name")}  → 汇总行 {n}，非零汇总 {nz if nz else "无"}')
        print('\n==== 2576 钉死的三项 ====')
        for k, v in three.items():
            print(f'  {k} = {v}')
        print('  注意：所有时刻都是运行当下，30 天趋势图只有今天这一根柱——脚本不能也不该回溯日期。')

    zero = [k for k, v in three.items() if not (isinstance(v, (int, float)) and v > 0)]
    if zero:
        print(f'FAIL 造完数后质控概览仍有 0：{zero}  完整三项：{three}')
        sys.exit(2)

    n_by = {}
    for rec, state, _ in rows:
        n_by[state] = n_by.get(state, 0) + 1
    print(f"demo-pathology OK  specimens={len(rows)} "
          f"({' '.join(f'{k}={v}' for k, v in n_by.items())})  "
          f"path-qc registered={three['登记总量 WORKLOAD_REGISTER.registered']} "
          f"blocks={three['蜡块产出 WORKLOAD_BLOCK.blocks']} "
          f"issued_reports={three['报告签发量 WORKLOAD_REPORT.issued_reports']}  suffix={SUF}")


if __name__ == '__main__':
    try:
        main()
    except SystemExit:
        raise
    except urllib.error.HTTPError as e:
        print(f'FAIL 步骤「{_last_step[0]}」HTTP {e.code} {e.reason}：{e.read().decode("utf-8", "replace")[:2000]}')
        sys.exit(1)
    except AssertionError as e:
        print(f'FAIL 步骤「{_last_step[0]}」：{e}')
        sys.exit(1)
    except Exception:
        print(f'FAIL 步骤「{_last_step[0]}」：')
        traceback.print_exc()
        sys.exit(1)
