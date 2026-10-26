# -*- coding: utf-8 -*-
"""Pure-public compatibility and overlap smoke for canonical batch cancellation.

All employees, patients, LAB/EXAM orders, charges and appointments are private API
fixtures. No SQL, shared queue consumption, financial replay or config mutation.
The ordinary parallel HTTP smoke checks the combined committed outcome only;
it does not prove an actual PostgreSQL wait or a deadlock. Dedicated Java and
private real-browser acceptance retain named-holder/lock/transaction evidence.
"""
import importlib.util
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    'v134_terminal_fixture', Path(__file__).with_name('e2e-v133-appointment-terminal-duty.py'))
_base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_base)
LEGAL = ['admin', 'tech', 'nurse']
MISSING_ID = _base.MISSING_ID


class Fixture(_base.Fixture):
    def __init__(self, prefix='v134'):
        super().__init__(prefix)


def main():
    f = Fixture()
    done = f.booked('批量既有完成', 'EXAM')
    terminal = f.booked('批量既有取消', 'LAB', 'PM')
    untouched = f.booked('非目标保持', 'EXAM')
    f.done(done)
    assert f.cancel([terminal['appointment_id']]) == {'cancelled': 1}
    old_done, old_terminal, old_untouched = f.current(done), f.current(terminal), f.current(untouched)
    for who in LEGAL:
        low = f.booked('升序低号' + who, 'LAB')
        high = f.booked('升序高号' + who, 'EXAM', 'PM')
        assert low['appointment_id'] < high['appointment_id']
        # Null entries, repeated IDs and terminal/missing rows retain the old
        # per-BOOKED-transition count. ids==null is outside this change.
        ids = [high['appointment_id'], None, low['appointment_id'], high['appointment_id'],
               MISSING_ID, done['appointment_id'], terminal['appointment_id'], None]
        assert f.cancel(ids, who) == {'cancelled': 2}
        assert f.cancel(list(reversed(ids)), who) == {'cancelled': 0}
        assert f.cancel([], who) == {'cancelled': 0}
        assert f.cancel([None, MISSING_ID, None], who) == {'cancelled': 0}
        assert f.current(done) == old_done and f.current(terminal) == old_terminal
        assert f.current(untouched) == old_untouched
    assert all(f.identities[who]['deptId'] != f.lab_department['id'] for who in ['tech', 'nurse'])
    print('[1] Original three duties/cross-department LAB/EXAM compatibility, reversed ID order, null entries, duplicates, empty/missing/terminal mixtures retain exact cancelled counts and complete non-target appointment fields; public non-appointment projections stay fixed')

    portal = f.api('POST', '/portal/login', {
        'patientNo': untouched['patient']['patientNo'], 'phone': '13900001280'}, who=None)
    f.tokens['portal'] = portal['token']
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    for who in ['doctor', 'cashier', 'pharmacist', 'interface', 'quality', 'portal', 'disabled', None]:
        code = 1000 if who in ['disabled', None] else 1005
        f.cancel([untouched['appointment_id'], None], who, code)
        f.cancel([], who, code)
    f.set_roles('tech', ['CASHIER'])
    f.cancel([untouched['appointment_id'], None], 'tech', 1005)
    f.set_roles('tech', ['NURSE', 'CASHIER'])
    assert f.cancel([untouched['appointment_id'], untouched['appointment_id'], None], 'tech') == {'cancelled': 1}
    f.set_roles('tech', ['TECHNICIAN'])
    print('[2] Wrong-duty, machine, patient, anonymous and disabled identities refuse both nonempty and empty batches without payload or projected changes; current sequential duty loss and legal mixed-duty restoration remain compatible')

    history = f.booked('真实退款历史取消', 'EXAM')
    original = f.current(history)
    receipt = f.refund(history)
    assert receipt['status'] == 'REFUNDED' and f.current(history) == original
    assert f.cancel([None, history['appointment_id'], history['appointment_id']], 'nurse') == {'cancelled': 1}
    assert f.current(history) == {**original, 'status': 'CANCELLED'}
    assert f.source(history)['status'] == 'CREATED' and f.source(history)['chargeId'] is None
    print('[3] A genuine refund preserves its complete appointment history; subsequent batch cancellation changes only that history status under the existing policy and does not recharge/execute the source or change financial facts')

    low, high = f.booked('并行低号', 'LAB'), f.booked('并行高号', 'EXAM', 'PM')
    ids = [low['appointment_id'], high['appointment_id']]
    before, appointments = f.projections(), f.own_appointments()
    eligible = set(ids)
    assert all(row['status'] == 'BOOKED' for row in appointments if row['id'] in eligible)
    # Do not use f.cancel here: each helper would compare its own intermediate
    # snapshot while the other request is still committing. Allocate both only
    # after both original responses have returned, with no automatic retry.
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(f.api, 'POST', '/appointments/batch-cancel', {'ids': ids}, 'tech')
        second = pool.submit(f.api, 'POST', '/appointments/batch-cancel', {'ids': list(reversed(ids))}, 'nurse')
        answers = [first.result(), second.result()]
    assert all(set(answer) == {'cancelled'} for answer in answers)
    assert sorted(answer['cancelled'] for answer in answers) == [0, 2], answers
    expected = [{**row, 'status': 'CANCELLED'} if row['id'] in eligible else row for row in appointments]
    assert f.own_appointments() == expected
    assert f.projections() == before, 'Combined committed batches changed a public clinical/financial projection'
    print('[4] Two ordinary HTTP requests with opposite input order retain original responses and combined committed outcome: one cancelled2, one cancelled0, only the two target statuses changed; this overlap smoke is not named-holder PG or deadlock evidence')
    print('e2e-v134-appointment-batch-lock-order passed: pure-public three-duty/JWT, null-entry/duplicate/empty/missing/mixed/reversed count compatibility, genuine refunded history and combined ordinary overlap smoke. Dedicated Java/private browser tests prove actual PG lock ordering; no SQL/config writes, global queue consumption, unknown-outcome mutation retry or per-request intermediate snapshot assertion.')


if __name__ == '__main__':
    main()
