# -*- coding: utf-8 -*-
"""医保局案件查办系统 E2E 回归（bureau/server，端口 8090）

覆盖：线索(15工作日/延期一次) → 立案(执法人员≥2/案由主体匹配) → 证据(先行登记保存/封存)
→ 回避 → 期限扣除 → 调查终结 → 告知(听证权利) → 法制审核(办案人员不得审核/未过审不得决定)
→ 决定(简易程序限额/陈述申辩不加重/文号) → 送达 → 执行(当场收缴限额/加处罚款封顶)
→ 结案(执行完毕方可) → 中止/恢复/终止 → 延期(30+集体讨论+60) → 统计督办
运行前提：bureau 后端已启动（java -jar bureau/server/target/hip-bureau-server-*.jar）
"""
import sys
import datetime

import requests

BASE = "http://127.0.0.1:8090/api"
step_no = 0


def step(name):
    global step_no
    step_no += 1
    print(f"[{step_no:02d}] {name}")


def ok(cond, msg):
    if not cond:
        print(f"    FAIL: {msg}")
        sys.exit(1)
    print(f"    PASS: {msg}")


class Api:
    def __init__(self, username):
        r = requests.post(f"{BASE}/auth/login",
                          json={"username": username, "password": "admin123"}, timeout=10)
        self.token = r.json()["data"]["token"]

    def call(self, method, path, expect_code=0, **kwargs):
        r = requests.request(method, f"{BASE}{path}", timeout=15,
                             headers={"Authorization": f"Bearer {self.token}"}, **kwargs)
        body = r.json()
        assert body["code"] == expect_code, f"{path} 期望 code={expect_code} 实得 {body['code']}: {body.get('message')}"
        return body.get("data")

    def get(self, path, **kw):
        return self.call("GET", path, **kw)

    def post(self, path, json=None, **kw):
        return self.call("POST", path, json=json, **kw)


def workdays_plus(d, n):
    while n > 0:
        d += datetime.timedelta(days=1)
        if d.weekday() < 5:
            n -= 1
    return d


def main():
    admin = Api("admin")

    step("案由字典：35 项种子")
    causes = admin.get("/bureau/causes")
    ok(len(causes) >= 35, f"案由 {len(causes)} 项")
    cause13 = next(c for c in causes if c["itemNo"] == 13)   # 定点医药机构-重复收费
    cause31 = next(c for c in causes if c["itemNo"] == 31)   # 自然人-冒名就医
    ok(cause13["subjectType"] == "PROVIDER" and cause31["subjectType"] == "INDIVIDUAL", "案由主体分类正确")

    step("线索登记：核查期限=收到日+15个工作日")
    today = datetime.date.today()
    clue = admin.post("/bureau/clues", json={
        "source": "COMPLAINT", "content": "举报某医院重复收费、超标准收费",
        "suspectName": "示范市第一医院", "suspectType": "PROVIDER",
        "receivedAt": str(today), "handler": "王办案"})
    ok(clue["deadlineAt"] == str(workdays_plus(today, 15)), f"核查期限 {clue['deadlineAt']}")

    step("线索延期：一次可，二次拒（2022）")
    admin.post(f"/bureau/clues/{clue['id']}/extend", json={"reason": "疑点数据量大需调取系统数据"})
    admin.post(f"/bureau/clues/{clue['id']}/extend", json={"reason": "再延"}, expect_code=2022)

    step("立案守卫：执法人员不足两人拒（2002）；案由主体不匹配拒（2001）")
    base_case = {
        "clueId": clue["id"], "causeId": cause13["id"], "procedureType": "NORMAL",
        "partyName": "示范市第一医院", "partyType": "PROVIDER",
        "partyCreditNo": "12345678-0", "summary": "涉嫌重复收费",
        "amountInvolved": 300000,
        "officers": [{"name": "王办案", "certNo": "YB001", "duty": "LEAD"}]}
    admin.post("/bureau/cases", json=base_case, expect_code=2002)
    admin.post("/bureau/cases", json={**base_case, "partyType": "INDIVIDUAL",
               "officers": base_case["officers"] + [{"name": "张协办", "certNo": "YB002", "duty": "MEMBER"}]},
               expect_code=2001)

    step("正式立案：案号、名称含'涉嫌'、期限=立案+90日、线索转已立案")
    case = admin.post("/bureau/cases", json={**base_case,
        "officers": base_case["officers"] + [{"name": "张协办", "certNo": "YB002", "duty": "MEMBER"}]})
    cid = case["id"]
    ok("〔" in case["caseNo"] and case["caseNo"].endswith("号"), f"案号 {case['caseNo']}")
    ok("涉嫌" in case["name"], f"案件名称 {case['name']}")
    ok(case["deadlineAt"] == str(today + datetime.timedelta(days=90)), f"办案期限 {case['deadlineAt']}")
    clues = admin.get("/bureau/clues", params={"status": "FILED"})
    ok(any(x["id"] == clue["id"] for x in clues), "线索状态已转 FILED")

    step("证据：书证+先行登记保存（7个工作日期限）→ 处理为保全")
    admin.post(f"/bureau/cases/{cid}/evidences", json={
        "type": "DOCUMENT", "name": "住院费用明细清单", "source": "该院 HIS 系统",
        "obtainedAt": str(today), "registerHold": True})
    detail = admin.get(f"/bureau/cases/{cid}")
    ev1 = detail["evidences"][0]
    ok(ev1["hold_expire_at"] == str(workdays_plus(today, 7)), f"登记保存处理期限 {ev1['hold_expire_at']}")
    admin.post(f"/bureau/cases/{cid}/evidences/{ev1['id']}/hold-disposal", json={"disposal": "PRESERVE"})

    step("证据封存：30日期限，延长一次可、二次拒（2030）")
    admin.post(f"/bureau/cases/{cid}/evidences", json={
        "type": "EDATA", "name": "收费系统数据库导出", "obtainedAt": str(today), "sealed": True})
    ev2 = admin.get(f"/bureau/cases/{cid}")["evidences"][1]
    ok(ev2["seal_expire_at"] == str(today + datetime.timedelta(days=30)), "封存期限 30 日")
    admin.post(f"/bureau/cases/{cid}/evidences/{ev2['id']}/seal?extend=true")
    admin.post(f"/bureau/cases/{cid}/evidences/{ev2['id']}/seal?extend=true", expect_code=2030)

    step("非法证据种类拒（2027）")
    admin.post(f"/bureau/cases/{cid}/evidences",
               json={"type": "GUESS", "name": "x", "obtainedAt": str(today)}, expect_code=2027)

    step("回避：补充第3人后回避可；再回避将剩1人拒（2002）")
    admin.post(f"/bureau/cases/{cid}/officers", json={"name": "李替补", "certNo": "YB003", "duty": "MEMBER"})
    officers = admin.get(f"/bureau/cases/{cid}")["officers"]
    admin.post(f"/bureau/cases/{cid}/officers/{officers[2]['id']}/avoid", json={"reason": "系当事人近亲属"})
    admin.post(f"/bureau/cases/{cid}/officers/{officers[1]['id']}/avoid", json={"reason": "利害关系"},
               expect_code=2002)

    step("询问笔录 + 期限扣除（鉴定 5 日不计入）")
    admin.post(f"/bureau/cases/{cid}/documents", json={
        "docType": "INQUIRY_RECORD", "title": "对该院医保办主任的询问笔录",
        "content": "问：……答：……", "madeAt": str(today), "maker": "王办案", "signed": True})
    admin.post(f"/bureau/cases/{cid}/exclusions", json={
        "reason": "APPRAISE", "startAt": str(today), "endAt": str(today + datetime.timedelta(days=5)),
        "note": "委托会计师事务所专项审计"})
    detail = admin.get(f"/bureau/cases/{cid}")
    ok(detail["effectiveDeadline"] == str(today + datetime.timedelta(days=95)), "有效期限含扣除 95 日")

    step("程序顺序守卫：终结前告知拒（2004）；告知前决定拒（2006）")
    admin.post(f"/bureau/cases/{cid}/notice",
               json={"content": "x", "proposedFine": 1}, expect_code=2004)
    admin.post(f"/bureau/cases/{cid}/decide",
               json={"decisionType": "PUNISH", "fineAmount": 1, "content": "x"}, expect_code=2006)

    step("调查终结报告 → REPORTED")
    admin.post(f"/bureau/cases/{cid}/report", json={
        "content": "一、当事人基本情况……二、案件来源与调查经过……三、事实与证据……四、性质……五、处理意见：罚款15万元，责令退回基金8万元"})
    ok(admin.get(f"/bureau/cases/{cid}")["caseFile"]["status"] == "REPORTED", "状态 REPORTED")

    step("处罚告知：拟罚15万（≥听证阈值10万）自动告知听证权利")
    notice = admin.post(f"/bureau/cases/{cid}/notice", json={
        "content": "拟对重复收费行为处造成损失金额1倍罚款15万元，责令退回基金8万元",
        "proposedFine": 150000, "proposedRecoup": 80000})
    ok(notice["hearingEntitled"] is True, "听证权利已告知")

    step("陈述申辩记录；未过法制审核作出决定拒（2005）")
    admin.post(f"/bureau/cases/{cid}/statement", json={
        "statement": "部分收费系信息系统重复传输所致", "statementReview": "经复核部分成立，拟维持处罚金额"})
    admin.post(f"/bureau/cases/{cid}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 150000, "recoupAmount": 80000,
        "content": "x"}, expect_code=2005)

    step("法制审核：办案人员不得审核（2038）；法制员审核通过")
    review = admin.post(f"/bureau/cases/{cid}/reviews", json={"requiredReason": "罚款数额较大（≥10万元）"})
    ok(review["deadlineAt"] == str(workdays_plus(today, 10)), "审核期限 10 个工作日")
    admin.post(f"/bureau/cases/reviews/{review['id']}",
               json={"reviewer": "王办案", "opinionType": "AGREE", "opinion": "x"}, expect_code=2038)
    admin.post(f"/bureau/cases/reviews/{review['id']}",
               json={"reviewer": "李法制", "opinionType": "AGREE", "opinion": "事实清楚、证据充分、程序合法"})

    step("不得因陈述申辩加重处罚：决定 20 万 > 告知 15 万拒（2007）")
    admin.post(f"/bureau/cases/{cid}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 200000, "recoupAmount": 80000,
        "content": "x"}, expect_code=2007)

    step("作出处罚决定：文号生成、案件名称去'涉嫌'、状态 DECIDED")
    decision = admin.post(f"/bureau/cases/{cid}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 150000, "recoupAmount": 80000,
        "content": "责令退回医保基金8万元，并处罚款15万元"})
    ok(decision["decisionNo"] and "〔" in decision["decisionNo"], f"决定书文号 {decision['decisionNo']}")
    cf = admin.get(f"/bureau/cases/{cid}")["caseFile"]
    ok("涉嫌" not in cf["name"] and cf["status"] == "DECIDED", f"名称 {cf['name']}，状态 DECIDED")

    step("结案守卫：未送达拒（2011）")
    admin.post(f"/bureau/cases/{cid}/close", json={"closeReport": "x"}, expect_code=2011)

    step("送达 → DELIVERED")
    admin.post(f"/bureau/cases/{cid}/deliver", json={
        "method": "DIRECT", "deliveredAt": str(today), "receiver": "该院法定代表人"})

    step("执行守卫：当场收缴>100拒（2010）；加处罚款超本金拒（2012）")
    admin.post(f"/bureau/cases/{cid}/executions", json={
        "kind": "FINE", "amount": 500, "paidAt": str(today), "method": "ONSITE"}, expect_code=2010)
    admin.post(f"/bureau/cases/{cid}/executions", json={
        "kind": "LATE_FEE", "amount": 200000, "paidAt": str(today), "method": "BANK"}, expect_code=2012)

    step("执行入账：罚款15万+退回基金8万；未执行完毕结案拒后完成结案")
    admin.post(f"/bureau/cases/{cid}/executions", json={
        "kind": "FINE", "amount": 150000, "paidAt": str(today), "method": "BANK"})
    admin.post(f"/bureau/cases/{cid}/close", json={"closeReport": "x"}, expect_code=2011)  # 基金未退回
    admin.post(f"/bureau/cases/{cid}/executions", json={
        "kind": "RECOUP", "amount": 80000, "paidAt": str(today), "method": "BANK"})
    closed = admin.post(f"/bureau/cases/{cid}/close", json={"closeReport": "行政处罚决定已执行完毕，罚款上缴国库，基金退回专户，准予结案归档"})
    ok(closed["status"] == "CLOSED" and closed["closeReason"] == "EXECUTED"
       and closed["archiveNo"], f"已结案归档 案卷号 {closed['archiveNo']}")

    step("简易程序：自然人罚款300超限拒（2003）；150元当场决定+当场收缴100元")
    case2 = admin.post("/bureau/cases", json={
        "causeId": cause31["id"], "procedureType": "SUMMARY",
        "partyName": "参保人陈某", "partyType": "INDIVIDUAL",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    admin.post(f"/bureau/cases/{case2['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 300, "content": "x"}, expect_code=2003)
    d2 = admin.post(f"/bureau/cases/{case2['id']}/decide", json={
        "decisionType": "PUNISH", "fineAmount": 150, "content": "使用他人医保凭证购药，当场处罚款150元"})
    ok(d2["decisionNo"], "简易程序决定书文号")
    admin.post(f"/bureau/cases/{case2['id']}/deliver", json={"method": "DIRECT", "receiver": "陈某"})
    admin.post(f"/bureau/cases/{case2['id']}/executions", json={
        "kind": "FINE", "amount": 100, "paidAt": str(today), "method": "ONSITE"})
    admin.post(f"/bureau/cases/{case2['id']}/executions", json={
        "kind": "FINE", "amount": 50, "paidAt": str(today), "method": "BANK"})
    admin.post(f"/bureau/cases/{case2['id']}/close", json={"closeReport": "执行完毕"})

    step("中止/恢复：恢复后办案期限顺延；终止解除强制措施")
    case3 = admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "示范市第二医院", "partyType": "PROVIDER",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    admin.post(f"/bureau/cases/{case3['id']}/suspend", json={"reason": "须以法院对关联合同纠纷的裁判结果为依据"})
    resumed = admin.post(f"/bureau/cases/{case3['id']}/resume")
    ok(resumed["status"] == "INVESTIGATING", "恢复调查")
    admin.post(f"/bureau/cases/{case3['id']}/evidences", json={
        "type": "DOCUMENT", "name": "台账", "obtainedAt": str(today), "sealed": True})
    term = admin.post(f"/bureau/cases/{case3['id']}/terminate", json={"reason": "法人终止且无权利义务承受人"})
    ok(term["status"] == "TERMINATED", "已终止")
    ev = admin.get(f"/bureau/cases/{case3['id']}")["evidences"][0]
    ok(ev["sealed"] is False, "终止时封存已解除（第47条）")

    step("延期：>30拒（2009）；30可；再延须集体讨论；讨论后再延30可")
    case4 = admin.post("/bureau/cases", json={
        "causeId": cause13["id"], "partyName": "示范市第三医院", "partyType": "PROVIDER",
        "officers": [{"name": "王办案", "certNo": "YB001"}, {"name": "张协办", "certNo": "YB002"}]})
    admin.post(f"/bureau/cases/{case4['id']}/extend", json={"days": 40, "reason": "x"}, expect_code=2009)
    admin.post(f"/bureau/cases/{case4['id']}/extend", json={"days": 30, "reason": "案情复杂"})
    admin.post(f"/bureau/cases/{case4['id']}/extend", json={"days": 30, "reason": "x"}, expect_code=2009)
    admin.post(f"/bureau/cases/{case4['id']}/meetings", json={
        "heldAt": str(today), "attendees": "局长、分管副局长、基金监督处长、法规处长",
        "record": "经讨论一致同意继续延期30日", "conclusion": "同意延期30日"})
    ext = admin.post(f"/bureau/cases/{case4['id']}/extend", json={"days": 30, "reason": "涉及多个法律关系"})
    ok(ext["deadlineAt"] == str(today + datetime.timedelta(days=150)), "期限 90+30+30 日")

    step("统计与督办")
    ov = admin.get("/bureau/stats/overview")
    ok(ov["caseTotal"] >= 4 and float(ov["fineDecided"]) >= 150150, f"案件 {ov['caseTotal']} 件，决定罚款 {ov['fineDecided']} 元")
    ok(float(ov["recoupDecided"]) >= 80000, f"决定追回基金 {ov['recoupDecided']} 元")
    sup = admin.get("/bureau/stats/supervision")
    ok("caseNearDeadline" in sup and "reviewOverdue" in sup, "督办看板六项预警可用")

    step("线索不予立案闭环")
    clue2 = admin.post("/bureau/clues", json={
        "source": "MONITOR", "content": "智能监控疑点：单日频繁购药", "suspectName": "参保人刘某",
        "suspectType": "INDIVIDUAL", "receivedAt": str(today)})
    rejected = admin.post(f"/bureau/clues/{clue2['id']}/reject",
                          json={"verifyResult": "经核查系代家人购药，有委托证明，不构成违法"})
    ok(rejected["status"] == "REJECTED", "不予立案")

    print(f"\n=== 医保案件查办 E2E 全部通过（{step_no} 步）===")


if __name__ == "__main__":
    main()
