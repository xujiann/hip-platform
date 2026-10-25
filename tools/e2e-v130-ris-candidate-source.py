# -*- coding: utf-8 -*-
"""Public API compatibility for RIS candidate-source serialization.

Private API fixtures only; no SQL, configuration mutation or automatic retry of
unknown-outcome financial requests. These sequential checks do not prove a PG
wait or refund/INSERT competition. Dedicated Java/private-browser acceptance
covers the named-holder race and complete raw E/non-E ledgers. Public absence
means absence from the active read projection, not proof of physical row absence.
"""
import importlib.util
from decimal import Decimal
from pathlib import Path
from urllib.parse import urlencode

_spec = importlib.util.spec_from_file_location(
    'v130_medtech_fixture', Path(__file__).with_name('e2e-v128-medtech-live-duty.py'))
_base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_base)
login = _base.login
QUEUE_KEYS = _base.WORKLIST_KEYS
ROLES = ['admin', 'tech', 'doctor', 'nurse']


class Fixture(_base.Fixture):
    """Importable public fixture; candidate() deliberately does NOT read RIS."""
    def __init__(self, prefix='v130'):
        super().__init__(prefix)
        # Mixed names pin the original precedence: 心电 before 镜, 镜 before
        # 超声/彩超, then GENERAL. The inherited US item covers plain 超声.
        names = {'ECG': '心电镜超声', 'ENDO': '镜彩超', 'US_COLOR': '彩超', 'GENERAL': '普放'}
        imported = self.api('POST', '/masterdata/charge-items/import', text=
            'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' + ''.join(
                f'V130{mode}{self.suffix},V130模拟{name}{self.suffix},EXAM,次,1.00,,,{self.lab_department["code"]}\n'
                for mode, name in names.items()))
        assert imported['imported'] == len(names) and imported['errorCount'] == 0
        items = self.api('GET', '/masterdata/charge-items?keyword=' + self.suffix)
        self.mode_items = {'US': self.exam_item}
        self.mode_items.update({mode: next(row for row in items if row['code'] == 'V130' + mode + self.suffix)
                                for mode in names})

    def candidate(self, label, mode='US', paid=True):
        assert mode in self.mode_items
        original = self.exam_item
        self.exam_item = self.mode_items[mode]
        try:
            case = super().visit(label, kind='EXAM', paid=paid, register=False)
        finally:
            self.exam_item = original
        case['expected_modality'] = 'US' if mode == 'US_COLOR' else mode
        case['expected_item_name'] = self.mode_items[mode]['name']
        return case

    def projection(self, case):
        # Call the original base snapshot directly: facts() would itself read
        # worklist and silently pre-register our candidate before the test step.
        result = _base._lab.Fixture.snapshot(self, case)
        result['cdr'] = self.api('GET', f"/cdr/patients/{case['patient']['id']}/documents")
        return result

    def projections(self):
        return {label: self.projection(case) for label, case in self.cases.items()}

    def queue(self, who='admin', modality=None, code=0):
        path = '/ris/worklist' + ('' if modality is None else '?' + urlencode({'modality': modality}))
        rows = self.api('GET', path, who=who, code=code)
        if code:
            assert rows is None
            return None
        assert all(set(row) == QUEUE_KEYS for row in rows)
        groups = {case['order']['groupNo'] for case in self.cases.values()}
        own = [row for row in rows if row['group_no'] in groups]
        assert all(type(row['id']) is int and row['id'] > 0 for row in own)
        assert [row['id'] for row in own] == sorted(row['id'] for row in own)
        assert len({row['group_no'] for row in own}) == len(own)
        return own

    def register(self, case, who='admin'):
        rows = [row for row in self.queue(who) if row['group_no'] == case['order']['groupNo']]
        assert len(rows) == 1
        case['exam_id'] = rows[0]['id']
        return rows[0]


def report_body(label='保留草稿'):
    return {'findings': '  V130模拟原所见' + label + '  ', 'impression': 'V130模拟原结论' + label}


def assert_reads(f, baseline):
    before = f.projections()
    for who in ROLES:
        assert f.queue(who) == baseline
        for modality in ['ECG', 'ENDO', 'US', 'GENERAL', 'UNKNOWN', 'us', '']:
            assert f.queue(who, modality) == [row for row in baseline if row['modality'] == modality]
    assert f.projections() == before, 'Worklist reads changed a non-E clinical or financial projection'


def main():
    f = Fixture()
    early = f.candidate('筛前登记后退出候选', 'US')
    before = f.projection(early)
    assert f.worklist(early, 'doctor', 'ECG') == []
    assert f.projection(early) == before
    # Generic execution now removes O from the CHARGED candidate SELECT. If
    # the prior ECG-filtered read did not register its US E, the next read can
    # no longer create one, so this is a discriminating before-filter check.
    f.api('POST', f"/outpatient/exec/{early['order']['id']}", {'resultText': 'V130模拟通用执行'}, who='tech')
    assert f.source(early)['status'] == 'EXECUTED'
    row = f.register(early, 'doctor')
    assert row['status'] == 'REGISTERED' and row['order_status'] == 'EXECUTED' and row['modality'] == 'US'

    registered = []
    for who, mode in [('admin', 'ECG'), ('tech', 'ENDO'), ('nurse', 'GENERAL'), ('admin', 'US_COLOR')]:
        case = f.candidate('四模态' + mode, mode)
        before = f.projection(case)
        row = f.register(case, who)
        assert row['status'] == 'REGISTERED' and row['order_status'] == 'CHARGED'
        assert row['modality'] == case['expected_modality'] and row['item_name'] == case['expected_item_name']
        assert row['patient_name'] == case['patient']['name']
        assert row['clinical_summary'] == 'V128模拟摘要' and row['exam_purpose'] == 'V128模拟目的'
        assert row['notice'] == 'V128模拟注意' and row['urgent'] is True
        assert row['findings'] is None and row['impression'] is None and row['diagnosis'] is None
        assert f.projection(case) == before, 'Automatic E registration changed non-E projections'
        registered.append(case)
    unpaid = f.candidate('独立未付申请', paid=False)
    lab = f.visit('非EXAM申请', kind='LAB', register=False)
    initial = f.queue()
    assert {row['id'] for row in initial} == {case['exam_id'] for case in [early, *registered]}
    assert all(row['group_no'] not in {unpaid['order']['groupNo'], lab['order']['groupNo']} for row in initial)
    assert f.source(unpaid)['status'] == 'CREATED' and f.source(unpaid)['chargeId'] is None
    assert f.source(lab)['status'] == 'CHARGED'
    assert_reads(f, initial)
    print('[1] Original four cross-department duties, fourteen keys, ordered unique rows, four modality precedence and exact/empty filters remain; the ECG-hidden US registration survives after generic execution excludes O from candidates')

    reported = next(case for case in registered if case['expected_modality'] == 'GENERAL')
    body = report_body(f.suffix)
    before = f.projections()
    f.write(reported, 'report', who='tech', body=body)
    assert f.projections() == before, 'Draft report must not execute O or change results, alerts, CDR or money'
    original_row = {**next(row for row in initial if row['id'] == reported['exam_id']), 'status': 'REPORTED', **body}
    saved = [original_row if row['id'] == reported['exam_id'] else row for row in initial]
    assert f.queue() == saved
    assert_reads(f, saved)
    portal = f.api('POST', '/portal/login', {
        'patientNo': reported['patient']['patientNo'], 'phone': '13900001280'}, who=None)
    f.tokens['portal'] = portal['token']
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    before = f.projections()
    for who in ['cashier', 'pharmacist', 'interface', 'quality', 'portal', 'disabled', None]:
        f.queue(who=who, code=1000 if who in ['disabled', None] else 1005)
    assert f.queue() == saved and f.projections() == before
    print('[2] Existing draft text/NULL fields and REGISTERED/EXECUTED facts are not overwritten; wrong-duty/patient/machine/disabled/anonymous callers receive no queue payload or business changes')

    old_charge = reported['charge']
    receipt = f.api('POST', f"/outpatient/charges/{old_charge['id']}/refund", who='cashier')
    assert receipt['id'] == old_charge['id'] and receipt['status'] == 'REFUNDED'
    assert receipt['refundBy'] == f.identities['cashier']['id'] and receipt['refundedAt']
    assert f.source(reported)['status'] == 'CREATED' and f.source(reported)['chargeId'] is None
    hidden = [row for row in saved if row['id'] != reported['exam_id']]
    assert_reads(f, hidden)
    assert f.source(unpaid)['status'] == 'CREATED'
    # Unknown financial responses are never retried by this script.
    new_charge = f.api('POST', '/outpatient/charges/settle', {
        'registrationId': reported['registration']['id'], 'payMethod': 'CASH'}, who='cashier')
    assert new_charge['id'] != old_charge['id'] and new_charge['status'] == 'PAID'
    assert Decimal(str(new_charge['totalAmount'])) == Decimal('1.00')
    assert new_charge['cashierId'] == f.identities['cashier']['id']
    assert f.source(reported)['status'] == 'CHARGED' and f.source(reported)['chargeId'] == new_charge['id']
    bills = f.api('GET', '/outpatient/charges/recent', who='cashier')
    assert next(row for row in bills if row['chargeId'] == old_charge['id'])['status'] == 'REFUNDED'
    reported['charge'] = new_charge
    assert f.queue() == saved, 'Recharge must restore the same existing E and every public report field'
    assert_reads(f, saved)
    before = f.projections()
    f.api('POST', f"/outpatient/charges/{early['charge']['id']}/refund", who='cashier', code=5005)
    assert f.projections() == before and f.queue() == saved and f.source(unpaid)['status'] == 'CREATED'
    print('[3] Real refund hides the retained E; real recharge restores its same ID/status/report text, preserves the original refunded bill and leaves the separate unpaid source unpaid; executed-refund 5005 remains')
    print('e2e-v130-ris-candidate-source passed: pure-public four-duty/mode/registration-before-filter and existing-report compatibility, true refund/recharge recovery and unchanged non-E projections during reads. No SQL/config changes, unknown-financial-response retry or claimed PG contention. Raw no-E proofs, source races, clock/actor attribution and complete ledgers are dedicated Java/private-browser acceptance.')


if __name__ == '__main__':
    main()
