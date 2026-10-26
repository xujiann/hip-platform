# -*- coding: utf-8 -*-
"""Public compatibility for medtech appointment done and batch cancel.

Only public APIs create private employees, patients, paid LAB/EXAM orders and
appointments. Sequential identity changes do not prove an in-flight PG wait;
dedicated Java tests cover named-holder waits, current duties and full rollback.
CALLED needs the global today's queue API, which cannot isolate private fixtures,
so this public script does not consume other suites' queues or SQL-invent states.
Existing refunded appointment history remains operable without new source gates.
"""
import importlib.util
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    'v133_appointment_fixture', Path(__file__).with_name('e2e-v132-appointment-book-source.py'))
_base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_base)

LEGAL = ['admin', 'tech', 'nurse']
MISSING_ID = 9000000000000000


class Fixture(_base.Fixture):
    def __init__(self, prefix='v133'):
        super().__init__(prefix)

    def booked(self, label, kind='LAB', period='AM'):
        case = self.visit(label, kind=kind, register=False)
        self.book(case, 'admin', self.dates[0], period)
        return case

    def done(self, case, who='tech', code=0):
        before, appointments = self.projections(), self.own_appointments()
        old = self.current(case)
        assert self.api('PUT', f"/appointments/{case['appointment_id']}/done", who=who, code=code) is None
        expected = appointments if code else [
            {**row, 'status': 'DONE'} if row['id'] == old['id'] else row for row in appointments]
        assert self.own_appointments() == expected, 'Done must preserve every other public appointment field'
        assert self.projections() == before, 'Done changed public non-appointment clinical/financial projections'

    def cancel(self, ids, who='tech', code=0):
        before, appointments = self.projections(), self.own_appointments()
        target = set(ids)
        eligible = {row['id'] for row in appointments if row['id'] in target and row['status'] == 'BOOKED'}
        answer = self.api('POST', '/appointments/batch-cancel', {'ids': ids}, who=who, code=code)
        if code:
            assert answer is None
            expected = appointments
        else:
            assert answer == {'cancelled': len(eligible)}
            expected = [{**row, 'status': 'CANCELLED'} if row['id'] in eligible else row for row in appointments]
        assert self.own_appointments() == expected, 'Batch cancel changed a non-target or non-BOOKED public field'
        assert self.projections() == before, 'Batch cancel changed public non-appointment clinical/financial projections'
        return answer


def main():
    f = Fixture()
    done_cases = {who: f.booked('三岗完成' + who, 'EXAM' if who == 'tech' else 'LAB') for who in LEGAL}
    cancel_cases = {who: f.booked('三岗取消' + who, 'LAB' if who == 'tech' else 'EXAM', 'PM') for who in LEGAL}
    for who in ['tech', 'nurse']:
        assert f.identities[who]['deptId'] != f.lab_department['id']
    for who in LEGAL:
        f.done(done_cases[who], who)
        f.done(done_cases[who], who, 4512)
        assert f.cancel([cancel_cases[who]['appointment_id']], who) == {'cancelled': 1}
        assert f.cancel([cancel_cases[who]['appointment_id']], who) == {'cancelled': 0}
        assert f.cancel([], who) == {'cancelled': 0}
        assert f.cancel([MISSING_ID], who) == {'cancelled': 0}
    before, appointments = f.projections(), f.own_appointments()
    f.api('PUT', f'/appointments/{MISSING_ID}/done', who='nurse', code=4512)
    assert f.projections() == before and f.own_appointments() == appointments
    f.done(cancel_cases['nurse'], 'admin', 4512)
    print('[1] Original ADMIN/TECHNICIAN/NURSE cross-department LAB/EXAM completion/cancellation, eight list keys, exact status-only updates, done4512 and cancelled counts including empty/missing/repeated terminal requests remain; public clinical/financial projections are unchanged')

    first = f.booked('批次混合一', 'LAB')
    second = f.booked('批次混合二', 'EXAM', 'PM')
    done_id, cancelled_id = done_cases['admin']['appointment_id'], cancel_cases['admin']['appointment_id']
    # The same BOOKED ID occurs twice; only its first actual transition counts.
    ids = [first['appointment_id'], first['appointment_id'], MISSING_ID,
           done_id, second['appointment_id'], cancelled_id]
    assert f.cancel(ids, 'nurse') == {'cancelled': 2}
    assert f.cancel(list(reversed(ids)), 'tech') == {'cancelled': 0}
    print('[2] Mixed batches update exactly the two BOOKED targets once, preserve complete DONE/CANCELLED/missing targets, retain count-only response and do not modify other appointment fields or public business projections')

    guarded_done = f.booked('权限完成保留', 'EXAM')
    guarded_cancel = f.booked('权限取消保留', 'LAB', 'PM')
    portal = f.api('POST', '/portal/login', {
        'patientNo': guarded_done['patient']['patientNo'], 'phone': '13900001280'}, who=None)
    f.tokens['portal'] = portal['token']
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    for who in ['doctor', 'cashier', 'pharmacist', 'interface', 'quality', 'portal', 'disabled', None]:
        code = 1000 if who in ['disabled', None] else 1005
        f.done(guarded_done, who, code)
        f.cancel([guarded_cancel['appointment_id'], first['appointment_id']], who, code)
        f.cancel([], who, code)
    f.set_roles('tech', ['CASHIER'])
    f.done(guarded_done, 'tech', 1005)
    f.cancel([guarded_cancel['appointment_id']], 'tech', 1005)
    f.set_roles('tech', ['NURSE', 'CASHIER'])
    f.done(guarded_done, 'tech')
    assert f.cancel([guarded_cancel['appointment_id']], 'tech') == {'cancelled': 1}
    f.set_roles('tech', ['TECHNICIAN'])
    print('[3] Real wrong-duty, machine, patient, disabled and anonymous JWTs refuse both writes and even empty batches without payload or projected changes; sequential revocation refuses, another current legal duty and mixed roles remain usable')

    refunded_done = f.booked('已退款预约完成', 'EXAM')
    refunded_cancel = f.booked('已退款预约取消', 'LAB', 'PM')
    original_done, original_cancel = f.current(refunded_done), f.current(refunded_cancel)
    f.refund(refunded_done)
    assert f.current(refunded_done) == original_done
    f.done(refunded_done, 'nurse')
    assert f.current(refunded_done) == {**original_done, 'status': 'DONE'}
    f.refund(refunded_cancel)
    assert f.current(refunded_cancel) == original_cancel
    assert f.cancel([refunded_cancel['appointment_id']], 'tech') == {'cancelled': 1}
    assert f.current(refunded_cancel) == {**original_cancel, 'status': 'CANCELLED'}
    assert f.source(refunded_done)['status'] == f.source(refunded_cancel)['status'] == 'CREATED'
    print('[4] Genuine refunds retain original appointment histories, which can still be completed or cancelled under the original terminal-write policy; those actions neither recharge/execute the source nor change financial facts')
    print('e2e-v133-appointment-terminal-duty passed: pure-public three-duty/JWT compatibility, precise terminal/count/empty/duplicate/mixed contracts, original appointment columns and refunded history with unchanged non-appointment projections. Dedicated Java tests prove named-holder waits, CALLED and complete raw rollback; no SQL/config mutation, global queue consumption, financial replay or claim that sequential calls prove PG competition.')


if __name__ == '__main__':
    main()
