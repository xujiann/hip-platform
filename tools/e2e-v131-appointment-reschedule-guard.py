# -*- coding: utf-8 -*-
"""Public compatibility for medtech appointment rescheduling.

All employees, patients, paid LAB/EXAM orders and appointments are private API
fixtures. This script checks ordinary sequential role and state boundaries; it
does not claim an in-flight PG lock race. Dedicated Java/private browser tests
prove the named-holder wait, exact raw columns and current-duty recheck. The
source eligibility of book and the refund policy are separate work.
"""
import datetime
import importlib.util
from pathlib import Path

from e2elib import today_bj

_spec = importlib.util.spec_from_file_location(
    'v131_medtech_fixture', Path(__file__).with_name('e2e-v128-medtech-live-duty.py'))
_base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_base)

APPOINTMENT_KEYS = {'id', 'slot_date', 'period', 'seq_no', 'status',
                    'order_type', 'item_name', 'patient_name'}
LEGAL = ['admin', 'tech', 'nurse']


class Fixture(_base.Fixture):
    """Importable API fixture; projections never read RIS worklist."""
    def __init__(self, prefix='v131'):
        super().__init__(prefix)
        start = today_bj() + datetime.timedelta(days=30 + int(self.suffix[-3:]) % 365)
        self.dates = [(start + datetime.timedelta(days=i)).isoformat() for i in range(3)]

    def projection(self, case):
        result = _base._lab.Fixture.snapshot(self, case)
        result['cdr'] = self.api('GET', f"/cdr/patients/{case['patient']['id']}/documents")
        return result

    def projections(self):
        return {label: self.projection(case) for label, case in self.cases.items()}

    def rows(self, date, who='admin'):
        rows = self.api('GET', '/appointments?date=' + date, who=who)
        assert all(set(row) == APPOINTMENT_KEYS for row in rows)
        assert [(row['period'], row['seq_no']) for row in rows] == sorted(
            (row['period'], row['seq_no']) for row in rows)
        assert all(row['slot_date'] == date for row in rows)
        return rows

    def own_appointments(self):
        ids = {case['appointment_id'] for case in self.cases.values() if 'appointment_id' in case}
        rows = [row for date in self.dates for row in self.rows(date) if row['id'] in ids]
        assert len(rows) == len(ids), 'Every private appointment must remain present exactly once'
        return sorted(rows, key=lambda row: row['id'])

    def current(self, case):
        return next(row for row in self.own_appointments() if row['id'] == case['appointment_id'])

    def next_seq(self, date, period):
        return max([row['seq_no'] for row in self.rows(date) if row['period'] == period], default=0) + 1

    def book(self, case, who='admin', date=None, period='AM'):
        date = date or self.dates[0]
        before = self.projection(case)
        seq = self.next_seq(date, period)
        answer = self.api('POST', '/appointments', {
            'orderId': case['order']['id'], 'slotDate': date, 'period': period}, who=who)
        assert answer == {'seqNo': seq} and type(answer['seqNo']) is int
        matches = [row for row in self.rows(date) if row['patient_name'] == case['patient']['name']
                   and row['item_name'] == case['order']['itemName'] and row['order_type'] == case['kind']]
        assert len(matches) == 1
        row = matches[0]
        assert type(row['id']) is int and row['id'] > 0
        case['appointment_id'] = row['id']
        assert row == {'id': row['id'], 'slot_date': date, 'period': period,
                       'seq_no': seq, 'status': 'BOOKED', 'order_type': case['kind'],
                       'item_name': case['order']['itemName'], 'patient_name': case['patient']['name']}
        assert self.projection(case) == before, 'Booking changed public clinical or financial projections'
        return row

    def reschedule(self, case, date, period, who='tech', code=0):
        return self.api('PUT', f"/appointments/{case['appointment_id']}/reschedule",
                        {'slotDate': date, 'period': period}, who=who, code=code)

    def move(self, case, date, period, who='tech'):
        old = self.current(case)
        all_before = self.own_appointments()
        before = self.projections()
        seq = self.next_seq(date, period)
        assert self.reschedule(case, date, period, who) == {'seqNo': seq}
        expected = {**old, 'slot_date': date, 'period': period, 'seq_no': seq}
        assert self.current(case) == expected and expected['status'] == 'BOOKED'
        assert self.own_appointments() == [expected if row['id'] == old['id'] else row for row in all_before]
        assert self.projections() == before, 'Rescheduling changed public clinical or financial projections'
        return expected

    def refused(self, case, date, period, who='tech', code=4513):
        before, appointments = self.projections(), self.own_appointments()
        assert self.reschedule(case, date, period, who, code) is None
        assert self.projections() == before and self.own_appointments() == appointments

    def sibling_exam(self, original, label):
        # A second paid source for the same patient and registration, with a
        # different order kind/item so the original list's eight keys identify
        # it without requiring a new order_id response key.
        orders = self.api('POST', f"/outpatient/doctor/{original['registration']['id']}/orders", {'lines': [{
            'orderType': 'EXAM', 'itemId': self.exam_item['id'], 'qty': 1,
            'clinicalSummary': 'V131模拟同患者独立申请'}]}, who='doctor')
        assert len(orders) == 1 and orders[0]['status'] == 'CREATED'
        charge = self.api('POST', '/outpatient/charges/settle', {
            'registrationId': original['registration']['id'], 'payMethod': 'CASH'}, who='cashier')
        assert charge['status'] == 'PAID' and charge['cashierId'] == self.identities['cashier']['id']
        case = {'patient': original['patient'], 'registration': original['registration'],
                'order': orders[0], 'charge': charge, 'kind': 'EXAM', 'label': label}
        self.cases[label] = case
        return case


def main():
    f = Fixture()
    first = f.visit('三岗改约原检验', kind='LAB', register=False)
    sibling = f.sibling_exam(first, '同患者检查独立预约')
    other = f.visit('独立患者检查预约', kind='EXAM', register=False)
    for case, who in [(first, 'tech'), (sibling, 'nurse'), (other, 'admin')]:
        f.book(case, who)
    assert first['patient']['id'] == sibling['patient']['id'] != other['patient']['id']
    assert first['order']['id'] != sibling['order']['id']
    for who in ['tech', 'nurse']:
        assert f.identities[who]['deptId'] != f.lab_department['id']
    for who in LEGAL:
        f.move(first, f.dates[1], 'PM', who)
        f.move(first, f.dates[2], 'AM', who)
    # Same-slot rescheduling originally allocates max+1, including the moved
    # appointment itself. It is not an idempotent no-op.
    same = f.current(first)
    again = f.move(first, same['slot_date'], same['period'], 'nurse')
    assert again['seq_no'] > same['seq_no']
    f.move(sibling, f.dates[1], 'AM', 'tech')
    assert f.current(other)['slot_date'] == f.dates[0]
    print('[1] Original ADMIN/TECHNICIAN/NURSE cross-employee and cross-department access, same-patient independent LAB/EXAM sources, eight list keys, one seqNo key, exact max+1 and repeated same-slot behavior remain; other appointments and public non-appointment projections stay unchanged')

    for period in ['', 'am', 'NIGHT', None]:
        f.refused(first, f.dates[1], period, 'tech', 4510)
    portal = f.api('POST', '/portal/login', {
        'patientNo': other['patient']['patientNo'], 'phone': '13900001280'}, who=None)
    f.tokens['portal'] = portal['token']
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    for who in ['doctor', 'cashier', 'pharmacist', 'interface', 'quality', 'portal', 'disabled', None]:
        f.refused(first, f.dates[1], 'PM', who, 1000 if who in ['disabled', None] else 1005)
    f.set_roles('tech', ['CASHIER'])
    f.refused(first, f.dates[1], 'PM', 'tech', 1005)
    f.set_roles('tech', ['NURSE', 'CASHIER'])
    f.move(first, f.dates[1], 'PM', 'tech')
    f.set_roles('tech', ['TECHNICIAN'])
    f.move(first, f.dates[2], 'PM', 'tech')
    print('[2] Invalid/empty/case-sensitive periods retain 4510; real wrong-duty, portal, machine, disabled and anonymous tokens return no clinical payload or projected changes; sequential revocation rejects and another legal current duty remains usable')

    before, appointments = f.projections(), f.own_appointments()
    old = f.current(sibling)
    assert f.api('PUT', f"/appointments/{sibling['appointment_id']}/done", who='nurse') is None
    done = {**old, 'status': 'DONE'}
    assert f.own_appointments() == [done if row['id'] == old['id'] else row for row in appointments]
    assert f.projections() == before
    old = f.current(other)
    appointments = f.own_appointments()
    assert f.api('POST', '/appointments/batch-cancel', {'ids': [other['appointment_id']]}, who='tech') == {'cancelled': 1}
    cancelled = {**old, 'status': 'CANCELLED'}
    assert f.own_appointments() == [cancelled if row['id'] == old['id'] else row for row in appointments]
    assert f.projections() == before
    for who in LEGAL:
        for case in [sibling, other]:
            f.refused(case, f.dates[1], 'AM', who, 4513)
    # Keep the existing validation precedence even for a terminal appointment.
    f.refused(sibling, f.dates[1], 'INVALID', 'admin', 4510)
    before, appointments = f.projections(), f.own_appointments()
    f.api('PUT', '/appointments/9000000000000000/reschedule',
          {'slotDate': f.dates[1], 'period': 'AM'}, who='nurse', code=4513)
    assert f.projections() == before and f.own_appointments() == appointments
    f.move(first, f.dates[0], 'AM', 'admin')
    assert f.current(sibling) == done and f.current(other) == cancelled
    print('[3] Actual done and batch-cancel terminal states retain date/period/sequence and refuse 4513 across all three duties; invalid-period precedence and missing-ID behavior remain, and another BOOKED appointment still moves normally')
    print('e2e-v131-appointment-reschedule-guard passed: pure-public three-duty/JWT compatibility, exact original appointment projections and sequence rules, real terminal transitions, and unchanged clinical/financial projections. No SQL/config mutation, unknown financial-response retry or claimed PG contention; wait-time state/role races and complete raw ledgers are separate dedicated acceptance.')


if __name__ == '__main__':
    main()
