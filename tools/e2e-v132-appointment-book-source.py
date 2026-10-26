# -*- coding: utf-8 -*-
"""Public medtech-book source and original API compatibility.

Private employees, patients and paid LAB/EXAM sources are created with public
APIs only. These sequential refund/recharge checks do not prove PG contention;
named-holder source/employee waits and the 4514 unique loser are dedicated Java
acceptance. No SQL, shared-config changes, RIS worklist registration or automatic
replay of unknown financial requests is used here. Booking before a refund keeps
the appointment history and does not create a new refund prohibition.
"""
import importlib.util
from decimal import Decimal
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    'v132_appointment_fixture', Path(__file__).with_name('e2e-v131-appointment-reschedule-guard.py'))
_base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_base)

LEGAL = ['admin', 'tech', 'nurse']
PENDING_KEYS = {'order_id', 'order_type', 'item_name', 'group_no', 'patient_name'}


class Fixture(_base.Fixture):
    """Importable pure API fixture; all snapshots omit RIS auto-registration."""
    def __init__(self, prefix='v132'):
        super().__init__(prefix)

    def appointments_for(self, case):
        # The original list intentionally has no order_id key. Private patient
        # and item/kind identify each fixture without changing its contract.
        return [row for date in self.dates for row in self.rows(date)
                if row['patient_name'] == case['patient']['name']
                and row['item_name'] == case['order']['itemName'] and row['order_type'] == case['kind']]

    def pending(self, who='admin'):
        rows = self.api('GET', '/appointments/pending', who=who)
        assert all(set(row) == PENDING_KEYS for row in rows)
        assert [row['order_id'] for row in rows] == sorted([row['order_id'] for row in rows], reverse=True)
        own_ids = {case['order']['id'] for case in self.cases.values()}
        return [row for row in rows if row['order_id'] in own_ids]

    def refused_book(self, case, who='tech', code=4510, period='AM'):
        before, appointments = self.projections(), self.own_appointments()
        unbooked = self.appointments_for(case)
        assert self.api('POST', '/appointments', {
            'orderId': case['order']['id'], 'slotDate': self.dates[1], 'period': period},
            who=who, code=code) is None
        assert self.projections() == before and self.own_appointments() == appointments
        assert self.appointments_for(case) == unbooked, 'Refusal created or modified a public appointment'

    def refund(self, case):
        charge = case['charge']
        receipt = self.api('POST', f"/outpatient/charges/{charge['id']}/refund", who='cashier')
        assert receipt['id'] == charge['id'] and receipt['status'] == 'REFUNDED'
        assert receipt['refundBy'] == self.identities['cashier']['id'] and receipt['refundedAt']
        assert self.source(case)['status'] == 'CREATED' and self.source(case)['chargeId'] is None
        return receipt

    def recharge(self, case):
        previous = case['charge']
        receipt = self.api('POST', '/outpatient/charges/settle', {
            'registrationId': case['registration']['id'], 'payMethod': 'CASH'}, who='cashier')
        assert receipt['id'] != previous['id'] and receipt['status'] == 'PAID'
        assert Decimal(str(receipt['totalAmount'])) == Decimal('1.00')
        assert receipt['cashierId'] == self.identities['cashier']['id']
        assert self.source(case)['status'] == 'CHARGED' and self.source(case)['chargeId'] == receipt['id']
        history = self.api('GET', '/outpatient/charges/recent', who='cashier')
        assert next(row for row in history if row['chargeId'] == previous['id'])['status'] == 'REFUNDED'
        case['charge'] = receipt
        return receipt


def main():
    f = Fixture()
    lab = f.visit('预约三岗检验', kind='LAB', register=False)
    sibling = f.sibling_exam(lab, '同患者独立检查')
    nurse_case = f.visit('预约三岗另一患者', kind='LAB', register=False)
    recovery = f.visit('退款后未预约来源', kind='EXAM', register=False)
    history = f.visit('先预约后退款历史', kind='LAB', register=False)
    unpaid = f.visit('独立未收费来源', kind='EXAM', paid=False, register=False)
    executed = f.visit('独立通用已执行来源', kind='LAB', register=False)
    for case, who in [(lab, 'admin'), (sibling, 'tech'), (nurse_case, 'nurse')]:
        assert f.source(case)['status'] == 'CHARGED'
        f.book(case, who)
        for actor in LEGAL:
            f.refused_book(case, actor, 4511)
    assert lab['patient']['id'] == sibling['patient']['id'] != nurse_case['patient']['id']
    for who in ['tech', 'nurse']:
        assert f.identities[who]['deptId'] != f.lab_department['id']
    for who in LEGAL:
        pending = f.pending(who)
        assert {row['order_id'] for row in pending} == {
            recovery['order']['id'], history['order']['id'], executed['order']['id']}
    print('[1] Original three duties, cross-department cooperation, same-patient separate LAB/EXAM sources, exact max+1/seqNo, eight list keys and five pending keys remain; existing same-order UNIQUE behavior is retained as 4511')

    for period in ['', 'am', 'NIGHT', None]:
        f.refused_book(recovery, 'tech', 4510, period)
    for who in LEGAL:
        f.refused_book(unpaid, who, 4510)
    f.api('POST', f"/outpatient/exec/{executed['order']['id']}",
          {'resultText': 'V132模拟已执行来源'}, who='tech')
    assert f.source(executed)['status'] == 'EXECUTED'
    f.refused_book(executed, 'nurse', 4510)
    before, appointments = f.projections(), f.own_appointments()
    f.api('POST', '/appointments', {'orderId': 9000000000000000,
          'slotDate': f.dates[0], 'period': 'AM'}, who='tech', code=4510)
    assert f.projections() == before and f.own_appointments() == appointments
    portal = f.api('POST', '/portal/login', {
        'patientNo': recovery['patient']['patientNo'], 'phone': '13900001280'}, who=None)
    f.tokens['portal'] = portal['token']
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    for who in ['doctor', 'cashier', 'pharmacist', 'interface', 'quality', 'portal', 'disabled', None]:
        f.refused_book(recovery, who, 1000 if who in ['disabled', None] else 1005)
    f.set_roles('tech', ['CASHIER'])
    f.refused_book(recovery, 'tech', 1005)
    f.set_roles('tech', ['NURSE', 'CASHIER'])
    print('[2] Invalid periods, unpaid/executed/missing sources keep 4510; real wrong-duty, portal, machine, disabled, anonymous and sequentially revoked employees receive no booking payload or projected business changes')

    appointments = f.own_appointments()
    assert not f.appointments_for(recovery)
    f.refund(recovery)
    assert f.own_appointments() == appointments and not f.appointments_for(recovery)
    for who in LEGAL:
        f.refused_book(recovery, who, 4510)
    assert recovery['order']['id'] not in {row['order_id'] for row in f.pending()}
    f.recharge(recovery)
    assert recovery['order']['id'] in {row['order_id'] for row in f.pending()}
    # TECHNICIAN was changed to another legal current duty, NURSE+CASHIER.
    restored = f.book(recovery, 'tech', f.dates[1], 'PM')
    f.refused_book(recovery, 'nurse', 4511)
    assert f.appointments_for(recovery) == [restored]
    f.set_roles('tech', ['TECHNICIAN'])
    print('[3] Genuine refund before booking leaves no public appointment and rejects 4510; one genuine recharge restores eligibility and creates exactly one appointment, including another legal current-duty conversion; duplicate retains 4511')

    original = f.book(history, 'nurse', f.dates[0], 'PM')
    appointments = f.own_appointments()
    f.refund(history)
    assert f.own_appointments() == appointments and f.appointments_for(history) == [original]
    # Preserve validation order: refunded source is ineligible before the
    # existing-appointment check; after recharge the original appointment wins.
    f.refused_book(history, 'tech', 4510)
    assert history['order']['id'] not in {row['order_id'] for row in f.pending()}
    f.recharge(history)
    for who in LEGAL:
        f.refused_book(history, who, 4511)
    assert f.own_appointments() == appointments and f.appointments_for(history) == [original]
    assert history['order']['id'] not in {row['order_id'] for row in f.pending()}
    print('[4] Booking before real refund preserves every original public appointment field and does not forbid refund; recharge retains the same appointment and 4511, without deletion, overwrite or new booking')
    print('e2e-v132-appointment-book-source passed: pure-public source eligibility, original three-duty/sequence/list contracts, genuine refund/recharge and retained appointment history with unchanged projections during booking/refusal. Dedicated Java acceptance covers specified PG waits, 4514 unique contention and raw full-column ledgers; no SQL/config mutation, financial replay or claim that sequential API calls prove PG concurrency.')


if __name__ == '__main__':
    main()
