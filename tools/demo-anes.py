# -*- coding: utf-8 -*-
"""麻醉与手术质控演示造数 + 自检（v81 车道 B）：**纯走真实 API，不写库、不塞种子**。

为什么要有它：菜单 112「麻醉质控」（/anes-qc）的六条 ★（1424 首台准点 / 1425 台数·时长·接台 /
1426 取消四阶段 / 1428 择期·急诊·日间 / 1436 年龄段×性别 / 1450 不良事件按名称）在全新库上零手术数据，
开发库 146 台里也只有 3 台录了类别与开台时间——不造数，「演示可行性」镜头必推翻。

**每一步都是真实业务操作**（同 demo-pathology.py 的纪律，零回填 / 零伪造种子）：
建患者 → 办入院 → 申请手术（带排台时间）→ 术中信息（手术间 / 级别 / ASA / 切口 / 类别）→
四个时间点（入室 / 开台 / 结束 / 出室，`at` 显式补录到过去 1–7 天）→ 完成手术 → 出院结算；
取消四阶段各一台走 `PUT /inpatient/surgeries/{id}/cancel`；不良事件走 `POST /quality/adverse-events`。
脚本不连数据库、不 import 任何 DB 驱动。

**造出的数据形状**（日期 = 业务今天往前 1–7 天，北京时区；每天 3–4 台，两间手术间）：
  · 手术间「演示1号间」每天 2 台（首台 + 接台），「演示2号间」每天 1–2 台 → 共 24 台完成手术，
    24 名演示患者各一台；首台 14 个：准点 7 / 延迟 4（全是择期，超阈值 25 分钟）/ 提前 3；
    同间相邻台次形成 10 个接台对（接台间隔 25–45 分钟）。
  · 择期 / 急诊 / 日间三类都有；首台不排急诊。
  · 取消四阶段各一台：排程阶段（D-5）/ 入室前（D-3）/ 术中（D-1，已入室已开台）/ 申请阶段。
    1426 按**取消发生日**归集：前三台的取消时刻用取消端点的 `at` 补录到当天（排台前 2 小时 / 排台前 10 分钟 /
    开台后约 20 分钟），故落在 D-5 / D-3 / D-1；**申请阶段那台没有排台时间**——申请即取消，取消时刻取运行当刻，
    故它落在**今天**而不是过去 1–7 天（这是 API 的真实形态，不是偷懒）。
  · 演示住院按患者编号奇偶分属外科门诊、内科门诊两个临床科室（1424 首台科室不止一个）。
  · 患者年龄覆盖 1436 的六个年龄段（<1 / 1–17 / 18–44 / 45–59 / 60–74 / ≥75），每段男女都有。
  · 不良事件 5 条、4 种名称（管路滑脱 ×2 / 跌倒 / 给药错误 / 压疮），其中 1 条已处理。
    1450 是**全院**不良事件口径（qc_adverse_event 无手术关联列），这里只是让窗口里有数。

**入院时间不能回溯**：入院 / 出院都发生在运行当下，手术时间点在其前 1–7 天。质控六条都不读入院日期，
不影响任何一个数；但穿透明细里若同时看住院号的入院日，会看到「先手术后入院」，讲解时如实说明。
每名演示患者造完即出院结算（全院只有 10 张种子床位，不出院会把后续 E2E 的空床耗尽）。

**幂等**：先按「演示麻醉」前缀扫过去 30 天（= 质控页默认统计区间）的手术间排程视图，
以「患者姓名 + 术式 + 是否取消/取消阶段」为键，已存在就跳过；不良事件按描述前缀「【演示麻醉】」从 1450 明细里认领。
同一天重跑全部跳过；批次在 30 天窗口内都不会重造；批次整体滑出 30 天窗口后重跑会按新的「今天」再造一批。
半途失败后重跑会续上：已建未完成的手术补齐缺的时间点与完成动作，未出院的演示住院补办出院。

**末尾自检（同时就是一套 E2E）**：调 `/api/anes-qc/indicators` 与 `/detail` 对六条各取一次，
既断言汇总的关键计数 > 0，也从穿透明细里认领演示数据本身（不让别的 E2E 的数据替它过关）。
任一不满足以退出码 2 失败。

用法：
  python tools/demo-anes.py                    # 后端在 HIP_E2E_BASE（默认 http://localhost:8080/api）
  python tools/demo-anes.py --quiet            # CI：只打印一行摘要
  python tools/demo-anes.py --base http://localhost:8087/api

退出码：0 成功；1 任一业务步骤失败（响应已打印）；2 造数完成但自检不过。
"""
import argparse
import datetime as _dt
import os
import re
import sys
import traceback
import urllib.error


def _parse_args():
    p = argparse.ArgumentParser(description='麻醉与手术质控演示造数（纯走真实 API，幂等）+ 六条指标自检')
    p.add_argument('--quiet', action='store_true', help='只打印一行摘要')
    p.add_argument('--base', default=os.environ.get('HIP_E2E_BASE', 'http://localhost:8080/api'),
                   help='API 根地址（默认读 HIP_E2E_BASE）')
    return p.parse_args()


ARGS = _parse_args()
# e2elib 在 import 时读 HIP_E2E_BASE，故先落环境变量再 import
os.environ['HIP_E2E_BASE'] = ARGS.base

from e2elib import call, login, ok, q, today_bj  # noqa: E402

try:
    sys.stderr.reconfigure(encoding='utf-8')
except Exception:  # noqa: BLE001  非终端 / 老解释器没有 reconfigure，不影响主流程
    pass

QUIET = ARGS.quiet
BJ = _dt.timezone(_dt.timedelta(hours=8))
PREFIX = '演示麻醉'
MARK = '【演示麻醉】'
ROOM_A, ROOM_B = '演示1号间', '演示2号间'
TODAY = today_bj()
# 扫描 / 自检窗口 = 质控页默认统计区间（截止日前推 29 天，见 AnesQcView.defaultRange 与 AnesQcController.parseWindow）
WIN_FROM = TODAY - _dt.timedelta(days=29)
_last_step = ['登录']
t = None


def say(msg):
    if not QUIET:
        print(msg)


def do(name, m, p, b=None):
    """一步业务操作：先记步骤名（失败时能指出是哪一步），再调用并校验 code==0"""
    _last_step[0] = name
    return ok(call(m, p, b, t), name)


# ===========================================================================
# 造数规格（全部相对「业务今天」派生，不写墙钟日期字面量）
# ===========================================================================
# 患者编号 → (性别, 年龄(岁), 额外天数, 术式, 类别, 麻醉方式, 手术级别, ASA, 切口, 手术时长分钟)
# 生日 = 今天往前「年龄」年再往前「额外天数」天：额外 60 天保证过去 1–7 天里年龄不跨段；婴儿直接按天数。
PATIENTS = {
    1:  ('M', 0, 250, '腹股沟斜疝修补术', 'DAY', '全身麻醉', '二级', 'I', 'Ⅰ类', 45),
    2:  ('F', 0, 160, '先天性肥厚性幽门狭窄幽门环肌切开术', 'ELECTIVE', '全身麻醉', '三级', 'II', 'Ⅰ类', 60),
    3:  ('F', 6, 60, '扁桃体及腺样体切除术', 'DAY', '全身麻醉', '二级', 'I', 'Ⅱ类', 50),
    4:  ('M', 14, 60, '腹腔镜阑尾切除术', 'EMERGENCY', '全身麻醉', '二级', 'I', 'Ⅲ类', 55),
    5:  ('F', 26, 60, '腹腔镜卵巢囊肿剥除术', 'ELECTIVE', '全身麻醉', '三级', 'I', 'Ⅱ类', 80),
    6:  ('M', 39, 60, '膝关节镜半月板部分切除术', 'DAY', '椎管内麻醉', '二级', 'I', 'Ⅰ类', 60),
    7:  ('F', 48, 60, '甲状腺腺叶切除术', 'ELECTIVE', '全身麻醉', '三级', 'II', 'Ⅰ类', 90),
    8:  ('M', 56, 60, '腹腔镜胆囊切除术', 'ELECTIVE', '全身麻醉', '三级', 'II', 'Ⅱ类', 70),
    9:  ('F', 63, 60, '乳腺癌改良根治术', 'ELECTIVE', '全身麻醉', '四级', 'II', 'Ⅰ类', 150),
    10: ('M', 71, 60, '经尿道前列腺电切术', 'ELECTIVE', '椎管内麻醉', '三级', 'III', 'Ⅱ类', 75),
    11: ('F', 78, 60, '股骨颈骨折人工股骨头置换术', 'EMERGENCY', '椎管内麻醉', '四级', 'III', 'Ⅰ类', 100),
    12: ('M', 86, 60, '白内障超声乳化摘除联合人工晶体植入术', 'DAY', '局部麻醉', '二级', 'III', 'Ⅰ类', 30),
    13: ('M', 33, 60, '腹腔镜腹股沟疝修补术', 'ELECTIVE', '全身麻醉', '三级', 'I', 'Ⅰ类', 70),
    14: ('F', 41, 60, '剖宫产术', 'EMERGENCY', '椎管内麻醉', '三级', 'II', 'Ⅱ类', 60),
    15: ('M', 52, 60, '腰椎间盘髓核摘除术', 'ELECTIVE', '全身麻醉', '三级', 'II', 'Ⅰ类', 120),
    16: ('F', 58, 60, '腹腔镜全子宫切除术', 'ELECTIVE', '全身麻醉', '四级', 'II', 'Ⅱ类', 150),
    17: ('M', 67, 60, '腹腔镜结肠癌根治术', 'ELECTIVE', '全身麻醉', '四级', 'III', 'Ⅱ类', 210),
    18: ('F', 69, 60, '全膝关节置换术', 'ELECTIVE', '椎管内麻醉', '四级', 'III', 'Ⅰ类', 120),
    19: ('M', 80, 60, '股骨粗隆间骨折闭合复位髓内钉内固定术', 'EMERGENCY', '神经阻滞', '三级', 'III', 'Ⅰ类', 90),
    20: ('F', 9, 60, '前臂骨折闭合复位克氏针内固定术', 'EMERGENCY', '全身麻醉', '二级', 'I', 'Ⅰ类', 40),
    21: ('M', 45, 60, '混合痔切除术', 'DAY', '椎管内麻醉', '二级', 'I', 'Ⅱ类', 40),
    22: ('F', 35, 60, '乳腺纤维腺瘤切除术', 'DAY', '局部麻醉', '一级', 'I', 'Ⅰ类', 35),
    23: ('M', 29, 60, '手部外伤清创缝合术', 'EMERGENCY', '神经阻滞', '一级', 'I', 'Ⅲ类', 45),
    24: ('F', 74, 60, '腹腔镜胆总管探查取石术', 'ELECTIVE', '全身麻醉', '四级', 'III', 'Ⅱ类', 180),
    25: ('M', 61, 60, '腹腔镜胃癌根治术', 'ELECTIVE', '全身麻醉', '四级', 'III', 'Ⅱ类', 240),
    26: ('F', 50, 60, '腹腔镜胆囊切除术', 'ELECTIVE', '全身麻醉', '三级', 'II', 'Ⅱ类', 70),
}

# 完成手术的排台：(往前第几天, 手术间, 患者编号, 首台判定)。同一天同一间按列出顺序接台；
# 首台判定只对该间当天第一台有意义：on 准点 / late 延迟（超阈值）/ early 提前。急诊一律不排首台。
SLOTS = [
    (7, ROOM_A, 5, 'on'), (7, ROOM_A, 4, None), (7, ROOM_B, 8, 'late'),
    (6, ROOM_A, 9, 'early'), (6, ROOM_A, 11, None), (6, ROOM_B, 3, 'on'), (6, ROOM_B, 22, None),
    (5, ROOM_A, 10, 'late'), (5, ROOM_A, 14, None), (5, ROOM_B, 1, 'on'),
    (4, ROOM_A, 7, 'on'), (4, ROOM_A, 19, None), (4, ROOM_B, 6, 'early'), (4, ROOM_B, 21, None),
    (3, ROOM_A, 15, 'late'), (3, ROOM_A, 20, None), (3, ROOM_B, 2, 'on'),
    (2, ROOM_A, 17, 'on'), (2, ROOM_A, 23, None), (2, ROOM_B, 16, 'late'), (2, ROOM_B, 12, None),
    (1, ROOM_A, 13, 'early'), (1, ROOM_A, 24, None), (1, ROOM_B, 18, 'on'),
]
TURNOVER_MINUTES = [25, 32, 40, 28, 45]   # 接台间隔轮流取，派生「接台时长」

# 取消四阶段各一台：(阶段, 患者编号, 往前第几天, 手术间, 排台时刻, 取消原因)。
# 排程阶段 / 入室前那两台的患者随后另一天做完了同一术式（7 号 D-4、17 号 D-2），是「取消—改期—完成」的真实形态。
CANCELS = [
    ('SCHEDULE', 7, 5, ROOM_B, (13, 30), '术前甲功复查 FT3 偏高，内分泌科会诊后改期'),
    ('PRE_IN', 17, 3, ROOM_B, (14, 0), '入室前血压 190/110 mmHg，降压后改期'),
    ('IN_OP', 25, 1, ROOM_B, (13, 0), '麻醉诱导后出现过敏性休克，抢救后终止手术'),
    ('APPLY', 26, None, None, None, '患者及家属商议后拒绝手术，撤回申请'),
]

# 不良事件：(往前第几天, 名称, 分级 1-4, 描述, 是否当场处理)
ADVERSE = [
    (6, '管路滑脱', 3, '术后翻身时胃管部分脱出，已重新置管并加固', True),
    (4, '跌倒', 3, '术后首次下床如厕时跌倒，查体无骨折', False),
    (3, '给药错误', 2, '术前预防性抗菌药给药时间晚于切皮前 60 分钟', False),
    (2, '管路滑脱', 4, '苏醒室患者躁动，自行拔除导尿管', False),
    (1, '压疮', 3, '长时间手术后骶尾部 1 期压力性损伤', False),
]


def pname(n):
    return f'{PREFIX}{n:02d}'


def day_of(k):
    return TODAY - _dt.timedelta(days=k)


def at(day, hh, mm):
    return _dt.datetime(day.year, day.month, day.day, hh, mm, tzinfo=BJ)


def iso(dt):
    return dt.isoformat(timespec='seconds')


def birth_of(years, extra_days):
    y = TODAY.year - years
    try:
        base = TODAY.replace(year=y)
    except ValueError:          # 2 月 29 日
        base = TODAY.replace(year=y, day=28)
    return base - _dt.timedelta(days=extra_days)


def plan_done(on_time_minutes):
    """把 SLOTS 展开成带四个时间点的完成手术清单（同间同日按顺序接台）"""
    on_off = max(0, min(10, on_time_minutes // 3))
    offsets = {'on': on_off, 'late': on_time_minutes + 25, 'early': -12}
    plans, prev_out, ti = [], {}, 0
    for k, room, pid, pattern in SLOTS:
        day = day_of(k)
        dur = PATIENTS[pid][9]
        key = (k, room)
        first = key not in prev_out
        if first:
            sched = at(day, 8, 0)
            start = sched + _dt.timedelta(minutes=offsets[pattern])
            in_room = start - _dt.timedelta(minutes=20)
        else:
            in_room = prev_out[key] + _dt.timedelta(minutes=TURNOVER_MINUTES[ti % len(TURNOVER_MINUTES)])
            ti += 1
            # 接台的排台时刻 = 入室时刻向下取整到半点（接台不参与首台准点判定）
            sched = in_room.replace(minute=0 if in_room.minute < 30 else 30)
            start = in_room + _dt.timedelta(minutes=18)
        end = start + _dt.timedelta(minutes=dur)
        out = end + _dt.timedelta(minutes=12)
        prev_out[key] = out
        plans.append({'pid': pid, 'k': k, 'room': room, 'pattern': pattern if first else None,
                      'sched': sched, 'IN_ROOM': in_room, 'START': start, 'END': end, 'OUT_ROOM': out})
    return plans


# ===========================================================================
# 查询辅助
# ===========================================================================
def existing_surgeries():
    """扫过去 30 天的手术间排程视图，收集演示患者的手术：(患者姓名, 术式) → [行]。
    排程视图按 coalesce(入室, 排台) 归日，二者皆空的按建单日——申请阶段取消的那台也扫得到。"""
    found = {}
    for i in range(30):
        d = (TODAY - _dt.timedelta(days=i)).isoformat()
        for bucket in do(f'手术间排程视图 {d}', 'GET', f'/inpatient/surgeries/room-board?date={d}'):
            for s in bucket.get('surgeries') or []:
                if str(s.get('patient_name') or '').startswith(PREFIX):
                    found.setdefault((s['patient_name'], s['procedure_name']), []).append(s)
    return found


def find_patients():
    rows = do('检索演示患者', 'GET', f"/patients?keyword={q(PREFIX)}&page=0&size=200")['records']
    return {p['name']: p for p in rows if str(p.get('name') or '').startswith(PREFIX)}


def in_hospital(patient):
    rows = do('在院检索', 'GET', f"/inpatient/admissions?keyword={q(patient['name'])}")
    return next((a for a in rows if a.get('patientId') == patient['id']), None)


DEPTS = {}


def free_bed():
    wards = [d for d in DEPTS.values() if d.get('type') == 'NURSING']
    wards.sort(key=lambda d: 0 if d.get('code') == 'W_SURG' else 1)   # 外科病区优先
    for w in wards:
        bed = next((b for b in do('查床', 'GET', f"/inpatient/beds?wardId={w['id']}") if b['status'] == 'FREE'), None)
        if bed:
            return bed
    raise AssertionError('无空床（请先清理在院测试数据）')


def new_surgery_id(adm_id, proc):
    """POST /inpatient/surgeries 不回 id（v17 冻结契约），按本住院 + 术式 + 未完成未取消认领最新一条"""
    rows = do('手术列表', 'GET', '/inpatient/surgeries')
    cand = [s['id'] for s in rows if s.get('admission_id') == adm_id and s.get('procedure_name') == proc
            and s.get('status') in ('REQUESTED', 'SCHEDULED')]
    assert cand, f'刚申请的手术「{proc}」在列表里认领不到'
    return max(cand)


def op_info(sid, spec, room):
    _sex, _y, _d, _proc, kind, _anes, level, asa, incision, _dur = spec
    do('术中信息', 'PUT', f'/inpatient/surgeries/{sid}/op-info',
       {'roomNo': room, 'surgeryLevel': level, 'asaGrade': asa, 'incisionType': incision, 'surgeryKind': kind})


# ===========================================================================
# 造数
# ===========================================================================
STATS = {'patients_new': 0, 'surgeries_new': 0, 'surgeries_resumed': 0, 'cancels_new': 0,
         'skipped': 0, 'admissions': 0, 'discharges': 0, 'events_new': 0}


def ensure_patient(pid, patients):
    name = pname(pid)
    if name in patients:
        return patients[name]
    sex, years, extra, *_ = PATIENTS[pid]
    p = do(f'建档 {name}', 'POST', '/patients',
           {'name': name, 'sex': sex, 'idType': 'OTHER', 'birthDate': birth_of(years, extra).isoformat()})
    patients[name] = p
    STATS['patients_new'] += 1
    say(f'  建档 {name}（{sex}，{years} 岁{"" if years else f"/{extra} 天"}，{p.get("patientNo")}）')
    return p


def ensure_admission(patient, ctx):
    if ctx.get('adm'):
        return ctx['adm']
    cur = in_hospital(patient)
    if cur:
        ctx['adm'] = cur
        say(f"  {patient['name']} 已在院（{cur.get('admissionNo')}），续用")
        return cur
    bed = free_bed()
    # v81 审阅修补（D12）：演示住院按患者编号奇偶分属外科、内科两个临床科室——1424「首台手术患者所属科室」
    # 此前只出现一个科室。种子里只有这两个临床科室；缺内科时回落外科（已建的住院不改）。
    clinical = [d for d in DEPTS.values() if d.get('type') == 'CLINICAL']
    surg = DEPTS.get('OUTP_SURG') or clinical[0]
    med = DEPTS.get('OUTP_MED') or surg
    digits = ''.join(ch for ch in patient['name'] if ch.isdigit())
    dept = med if digits and int(digits) % 2 == 0 else surg
    adm = do(f"入院 {patient['name']}", 'POST', '/inpatient/admissions',
             {'patientId': patient['id'], 'deptId': dept['id'], 'bedId': bed['id'],
              'diagName': '择期/急诊手术', 'deposit': 500, 'payMethod': 'CASH'})
    STATS['admissions'] += 1
    say(f"  入院 {patient['name']} → {adm.get('admissionNo')}（{dept.get('name')}，{bed.get('bedNo')} 床）")
    ctx['adm'] = adm
    return adm


def do_done(plan, patient, rows, ctx):
    spec = PATIENTS[plan['pid']]
    proc = spec[3]
    alive = [r for r in rows if r.get('status') != 'CANCELLED']
    if any(r.get('status') == 'DONE' for r in alive):
        STATS['skipped'] += 1
        say(f"  [跳过] {patient['name']} {proc}（已完成）")
        return
    if alive:   # 半途失败留下的未完成台次：续上
        sid = max(r['id'] for r in alive)
        STATS['surgeries_resumed'] += 1
        say(f"  [续做] {patient['name']} {proc}（#{sid}）")
    else:
        adm = ensure_admission(patient, ctx)
        r = do(f'申请手术 {proc}', 'POST', '/inpatient/surgeries',
               {'admissionId': adm['id'], 'procedureName': proc, 'anesthesiaType': spec[5],
                'scheduledAt': iso(plan['sched'])})
        sid = new_surgery_id(adm['id'], proc)
        STATS['surgeries_new'] += 1
        warn = f"（{r['warning']}）" if isinstance(r, dict) and r.get('warning') else ''
        say(f"  申请 {patient['name']} {proc} #{sid} 排台 {plan['sched']:%m-%d %H:%M}{warn}")
    op_info(sid, spec, plan['room'])
    cur = do('读手术详情', 'GET', f'/inpatient/surgeries/{sid}/detail')
    col = {'IN_ROOM': 'in_room_at', 'START': 'start_at', 'END': 'end_at', 'OUT_ROOM': 'out_room_at'}
    for stage in ('IN_ROOM', 'START', 'END', 'OUT_ROOM'):
        if cur.get(col[stage]) is None:
            do(f'打点 {stage}', 'PUT', f'/inpatient/surgeries/{sid}/timepoint', {'stage': stage, 'at': iso(plan[stage])})
    do('完成手术', 'PUT', f'/inpatient/surgeries/{sid}/complete',
       {'opNote': f'{MARK}{proc}，手术顺利', 'anesNote': f'{MARK}{spec[5]}，麻醉平稳，苏醒完全'})
    tag = {'on': '首台·准点', 'late': '首台·延迟', 'early': '首台·提前', None: '接台'}[plan['pattern']]
    say(f"    {plan['room']} {plan['IN_ROOM']:%m-%d} 入室 {plan['IN_ROOM']:%H:%M} 开台 {plan['START']:%H:%M} "
        f"结束 {plan['END']:%H:%M} 出室 {plan['OUT_ROOM']:%H:%M}（{tag}，{spec[4]}）→ 已完成")


def do_cancel(c, patient, rows, ctx):
    stage, pid, k, room, hm, reason = c
    spec = PATIENTS[pid]
    proc = spec[3]
    if any(r.get('cancel_stage') == stage for r in rows):
        STATS['skipped'] += 1
        say(f"  [跳过] {patient['name']} {proc}（{stage} 阶段取消已存在）")
        return
    adm = ensure_admission(patient, ctx)
    body = {'admissionId': adm['id'], 'procedureName': proc, 'anesthesiaType': spec[5]}
    sched = at(day_of(k), *hm) if k else None
    if sched:
        body['scheduledAt'] = iso(sched)
    do(f'申请手术 {proc}', 'POST', '/inpatient/surgeries', body)
    sid = new_surgery_id(adm['id'], proc)
    if room:
        op_info(sid, spec, room)
    if stage == 'IN_OP':
        do('打点 IN_ROOM', 'PUT', f'/inpatient/surgeries/{sid}/timepoint',
           {'stage': 'IN_ROOM', 'at': iso(sched + _dt.timedelta(minutes=5))})
        do('打点 START', 'PUT', f'/inpatient/surgeries/{sid}/timepoint',
           {'stage': 'START', 'at': iso(sched + _dt.timedelta(minutes=25))})
    # v81 审阅修补（D6）：取消时刻补录到真实发生日——排程阶段在排台前 2 小时、入室前在排台前 10 分钟、
    # 术中在开台后 20 分钟；申请阶段那台未排台，取消时刻取当前（不传 at）。1426 按取消日归集即落在这几天。
    body = {'stage': stage, 'reason': reason}
    cancel_at = None
    if sched is not None:
        cancel_at = {'SCHEDULE': sched - _dt.timedelta(hours=2), 'PRE_IN': sched - _dt.timedelta(minutes=10),
                     'IN_OP': sched + _dt.timedelta(minutes=45)}.get(stage)
    if cancel_at is not None:
        body['at'] = iso(cancel_at)
    do(f'取消手术（{stage}）', 'PUT', f'/inpatient/surgeries/{sid}/cancel', body)
    STATS['cancels_new'] += 1
    when = f'{sched:%m-%d %H:%M}' if sched else '今天（未排台）'
    if cancel_at is not None:
        when += f'，取消于 {cancel_at:%m-%d %H:%M}'
    say(f"  取消 {patient['name']} {proc} #{sid}：{stage}，{when}，{reason}")


def build_surgeries(on_time_minutes):
    patients = find_patients()
    found = existing_surgeries()
    plans = plan_done(on_time_minutes)
    by_pid = {}
    for p in plans:
        by_pid.setdefault(p['pid'], {'done': [], 'cancel': []})['done'].append(p)
    for c in CANCELS:
        by_pid.setdefault(c[1], {'done': [], 'cancel': []})['cancel'].append(c)
    for pid in sorted(by_pid):
        say(f'· {pname(pid)}')
        patient = ensure_patient(pid, patients)
        rows = found.get((patient['name'], PATIENTS[pid][3]), [])
        ctx = {}
        for plan in by_pid[pid]['done']:        # 先做完成台，再做取消台（同术式改期的两台共用一个键）
            do_done(plan, patient, rows, ctx)
        for c in by_pid[pid]['cancel']:
            do_cancel(c, patient, rows, ctx)
        adm = ctx.get('adm') or in_hospital(patient)   # 含上次半途失败留下的在院记录
        if adm:
            do(f"出院结算 {patient['name']}", 'POST', f"/inpatient/admissions/{adm['id']}/discharge?payMethod=CASH", {})
            STATS['discharges'] += 1
            say(f"  出院结算 {adm.get('admissionNo')}（释放床位）")


def build_adverse_events():
    det = do('1450 明细（认领已有演示事件）', 'GET',
             f'/anes-qc/detail?indicator=1450&from={WIN_FROM}&to={TODAY}')
    mine = {it['description']: it for it in det['items'] if str(it.get('description') or '').startswith(MARK)}
    dept = DEPTS.get('W_SURG') or DEPTS.get('OUTP_SURG')
    for k, typ, level, desc, handle in ADVERSE:
        full = MARK + desc
        if full in mine:
            STATS['skipped'] += 1
            say(f'  [跳过] 不良事件「{typ}」{desc}')
        else:
            do(f'上报不良事件 {typ}', 'POST', '/quality/adverse-events',
               {'type': typ, 'level': level, 'occurredOn': day_of(k).isoformat(),
                'deptId': dept['id'] if dept else None, 'description': full, 'anonymous': False})
            STATS['events_new'] += 1
            say(f'  上报不良事件「{typ}」{level} 级 {day_of(k)}：{desc}')
    if any(h for *_x, h in ADVERSE):
        det = do('1450 明细（回读）', 'GET', f'/anes-qc/detail?indicator=1450&from={WIN_FROM}&to={TODAY}')
        for k, typ, level, desc, handle in ADVERSE:
            it = next((x for x in det['items'] if x.get('description') == MARK + desc), None)
            if handle and it and it.get('status') == 'NEW':
                do('处理不良事件', 'PUT', f"/quality/adverse-events/{it['event_id']}/handle?note={q('已加固管路并组织科内复盘')}")
                say(f'  已处理「{typ}」{desc}')


# ===========================================================================
# 自检：六条指标各取一次，汇总计数 > 0 且穿透明细里认得出演示数据
# ===========================================================================
def _ts(v):
    """detail 回的时间戳形如 2026-09-05T03:01:39.716+00:00；小数位不定长，统一补到 6 位再解析（3.10 兼容）"""
    if v is None:
        return None
    s = str(v).replace('Z', '+00:00')
    m = re.match(r'^(.*T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(.*)$', s)
    if m:
        s = m.group(1) + ('.' + (m.group(2) + '000000')[:6] if m.group(2) else '') + m.group(3)
    return _dt.datetime.fromisoformat(s)


def _n(v):
    """计数列可能是 int / Decimal 串行化后的 float；整数值一律还原成 int，摘要里不印 1.0"""
    if v is None:
        return 0
    f = float(v)
    return int(f) if f.is_integer() else f


def self_check():
    frm, to = WIN_FROM.isoformat(), TODAY.isoformat()
    body = do('自检·指标总表', 'GET', f'/anes-qc/indicators?from={frm}&to={to}')
    m = body['onTimeMinutes']
    ind = {i['code']: i for i in body['indicators']}
    fails, facts = [], {}

    def need(cond, msg):
        if not cond:
            fails.append(msg)

    def detail(code, extra=''):
        return do(f'自检·{code} 明细', 'GET', f'/anes-qc/detail?indicator={code}&from={frm}&to={to}{extra}')

    def demo(items):
        return [x for x in items if str(x.get('patient_name') or '').startswith(PREFIX)]

    # ---- 1424 首台准点 ----
    s24 = ind['1424'].get('summary') or {}
    for k in ('first_cases', 'on_time', 'delayed', 'elective_first_cases', 'elective_delayed'):
        facts[f'1424.{k}'] = s24.get(k)
        need(_n(s24.get(k)) > 0, f'1424 汇总 {k} 应 > 0，实得 {s24.get(k)}')
    d24 = demo(detail('1424')['items'])
    kinds = {'准点': 0, '延迟': 0, '提前': 0}
    for x in d24:
        st, sc = _ts(x.get('start_at')), _ts(x.get('scheduled_at'))
        if st is None or sc is None:
            continue
        if st < sc:
            kinds['提前'] += 1
        elif st <= sc + _dt.timedelta(minutes=m):
            kinds['准点'] += 1
        else:
            kinds['延迟'] += 1
    facts['1424.演示首台(准点/延迟/提前)'] = f"{kinds['准点']}/{kinds['延迟']}/{kinds['提前']}"
    for k, v in kinds.items():
        need(v > 0, f'1424 明细里演示首台「{k}」应 ≥ 1（阈值 {m} 分钟），实得 {v}')

    # ---- 1425 台数 / 手术时长 / 接台 ----
    r25 = ind['1425'].get('rows') or []
    for k in ('cases', 'timed_cases', 'total_op_minutes', 'turnovers', 'total_turnover_minutes'):
        facts[f'1425.{k}'] = _n(round(sum(_n(r.get(k)) for r in r25), 1))
        need(facts[f'1425.{k}'] > 0, f'1425 各日 {k} 之和应 > 0，实得 {facts[f"1425.{k}"]}')
    cov = body.get('coverage') or {}
    facts['coverage.with_room_times'] = cov.get('with_room_times')
    need(_n(cov.get('with_room_times')) > 0, '覆盖率 with_room_times（入室出室齐全）应 > 0')
    det25 = detail('1425')
    d25 = [x for x in demo(det25['items']) if x.get('turnover_minutes') is not None]
    facts['1425.演示接台明细行'] = len(d25)
    need(d25 or det25.get('truncated'), '1425 明细里应有演示台次带接台分钟')

    # ---- 1426 取消四阶段 ----
    r26 = {r.get('cancel_stage'): _n(r.get('cases')) for r in ind['1426'].get('rows') or []}
    d26 = {x.get('cancel_stage') for x in demo(detail('1426')['items'])}
    for st in ('APPLY', 'SCHEDULE', 'PRE_IN', 'IN_OP'):
        facts[f'1426.{st}'] = r26.get(st, 0)
        need(r26.get(st, 0) > 0, f'1426 汇总阶段 {st} 应 > 0')
        need(st in d26, f'1426 明细里应有演示取消台次（{st}）')

    # ---- 1428 择期 / 急诊 / 日间 ----
    r28 = {r.get('surgery_kind'): _n(r.get('cases')) for r in ind['1428'].get('rows') or []}
    for kd in ('ELECTIVE', 'EMERGENCY', 'DAY'):
        facts[f'1428.{kd}'] = r28.get(kd, 0)
        need(r28.get(kd, 0) > 0, f'1428 汇总类别 {kd} 应 > 0')
        if r28.get(kd, 0) > 0:      # 窗内不存在的分桶取值返 4944，先判汇总再穿透
            b = detail('1428', f'&bucket={kd}')
            need(demo(b['items']) or b.get('truncated'), f'1428 分桶 {kd} 穿透应认得出演示台次')

    # ---- 1436 年龄段 × 性别 ----
    r36 = [r for r in ind['1436'].get('rows') or [] if _n(r.get('cases')) > 0]
    bands = {r['age_band'] for r in r36 if not str(r['age_band']).startswith('未知')}
    sexes = {r['sex'] for r in r36}
    facts['1436.年龄段'] = len(bands)
    facts['1436.行'] = '; '.join(f"{r['age_band']}{r['sex']}={_n(r['cases'])}" for r in r36)
    need(len(bands) >= 4, f'1436 至少四个年龄段有数，实得 {sorted(bands)}')
    need({'男', '女'} <= sexes, f'1436 男女都应有数，实得 {sorted(sexes)}')
    demo_bands, demo_sexes = set(), set()
    for r in r36:
        b = detail('1436', '&bucket=' + q(f"{r['age_band']} / {r['sex']}", safe=''))
        if demo(b['items']):
            demo_bands.add(r['age_band'])
            demo_sexes.add(r['sex'])
    facts['1436.演示覆盖年龄段'] = len(demo_bands)
    need(len(demo_bands) >= 4 and {'男', '女'} <= demo_sexes,
         f'1436 分桶穿透认得出的演示年龄段 {sorted(demo_bands)}、性别 {sorted(demo_sexes)} 不足四段或缺一性别')

    # ---- 1450 不良事件按名称 ----
    r50 = {r.get('event_type'): _n(r.get('events')) for r in ind['1450'].get('rows') or []}
    facts['1450'] = ', '.join(f'{k}={v}' for k, v in r50.items())
    need(sum(1 for v in r50.values() if v > 0) >= 2, f'1450 至少两种名称有数，实得 {r50}')
    d50 = {x.get('event_type') for x in detail('1450')['items'] if str(x.get('description') or '').startswith(MARK)}
    need(len(d50) >= 2, f'1450 明细里认得出的演示事件名称应 ≥ 2，实得 {sorted(d50)}')

    return fails, facts


def main():
    global t
    t = login()
    for d in do('科室', 'GET', '/system/depts'):
        DEPTS[d['code']] = d
    m = do('读首台准点阈值', 'GET', f'/anes-qc/indicators?from={TODAY}&to={TODAY}&indicator=1424')['onTimeMinutes']
    say(f'== 麻醉质控演示造数（业务今天 {TODAY}，首台准点阈值 {m} 分钟，延迟台次超阈值 25 分钟）==')
    build_surgeries(m)
    say('· 不良事件（全院口径，1450）')
    build_adverse_events()
    say('== 自检（统计区间 %s 至 %s，同质控页默认）==' % (WIN_FROM, TODAY))
    fails, facts = self_check()
    for k, v in facts.items():
        say(f'  {k} = {v}')
    made = (f"新建患者 {STATS['patients_new']}、手术 {STATS['surgeries_new']}（续做 {STATS['surgeries_resumed']}）、"
            f"取消 {STATS['cancels_new']}、不良事件 {STATS['events_new']}、跳过 {STATS['skipped']}、"
            f"入院/出院 {STATS['admissions']}/{STATS['discharges']}")
    if fails:
        print('demo-anes 自检失败：')
        for f in fails:
            print('  ✘ ' + f)
        print('  ' + made)
        sys.exit(2)
    print(f"demo-anes OK：{made}；1424 首台 {facts['1424.first_cases']}（延迟 {facts['1424.delayed']}）、"
          f"1425 接台 {facts['1425.turnovers']}、1426 四阶段 {facts['1426.APPLY']}/{facts['1426.SCHEDULE']}/"
          f"{facts['1426.PRE_IN']}/{facts['1426.IN_OP']}、1428 择/急/日 {facts['1428.ELECTIVE']}/"
          f"{facts['1428.EMERGENCY']}/{facts['1428.DAY']}、1436 年龄段 {facts['1436.年龄段']}、"
          f"1450 {facts['1450']}")


if __name__ == '__main__':
    try:
        main()
    except urllib.error.HTTPError as e:
        print(f"demo-anes 失败于「{_last_step[0]}」：HTTP {e.code} {e.read().decode('utf-8', 'replace')[:300]}")
        sys.exit(1)
    except (AssertionError, KeyError, TypeError, ValueError) as e:
        print(f"demo-anes 失败于「{_last_step[0]}」：{e}")
        if not QUIET:
            traceback.print_exc()
        sys.exit(1)
