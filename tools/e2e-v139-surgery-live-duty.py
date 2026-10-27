# -*- coding: utf-8 -*-
"""Public JWT compatibility for current surgery duties and attending scope.

Fixtures use only public APIs and private staff/department/ward/beds/patients.
Sequential role changes do not reproduce a PostgreSQL race: the Java suite and
the actual browser independently prove named-holder waits and full raw rollback.
Import is safe; no login, fixture or HTTP call runs outside main().
"""
import json
import time
import urllib.error
from e2elib import call, login, provision_user, discharge_cleanup

MISSING = 9000000000000000


class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-11:]
        self.tokens, self.users, self.identities, self.admissions, self.surgery_ids = {}, {}, {}, [], []
        self.unknown_writes, self.surgery_owners, self.admission_patients = [], {}, {}
        bootstrap = login()
        for who, role in [('admin', 'ADMIN'), ('doctor', 'DOCTOR_OUTP'), ('other', 'DOCTOR_OUTP'),
                          ('tech', 'TECHNICIAN'), ('nurse', 'NURSE'), ('quality', 'QUALITY'),
                          ('cashier', 'CASHIER'), ('interface', 'INTERFACE'), ('pharmacist', 'PHARMACIST'),
                          ('disabled', 'TECHNICIAN'), ('mover', 'TECHNICIAN')]:
            self.users[who] = 'v139_' + who + '_' + self.suffix
            # The shared provisioner is for human accounts. Complete that flow
            # before the admin assigns the existing machine-only duty.
            self.tokens[who] = provision_user(bootstrap, self.users[who], 'TECHNICIAN' if role == 'INTERFACE' else role)
            self.identities[who] = self.api('GET', '/auth/me', who=who)
            if role == 'INTERFACE':
                self.set_roles(who, ['INTERFACE'])
        self.department = self.api('POST', '/system/depts', {'code': 'V139D' + self.suffix,
            'name': 'V139模拟手术科' + self.suffix, 'type': 'CLINICAL', 'sortNo': 9999})
        self.ward = self.api('POST', '/system/depts', {'code': 'V139W' + self.suffix,
            'name': 'V139模拟病区' + self.suffix, 'type': 'WARD', 'parentId': self.department['id'], 'sortNo': 9999})
        assert self.api('POST', '/inpatient/beds', {'wardId': self.ward['id'], 'count': 3}) == {'created': 3}
        self.beds = self.api('GET', '/inpatient/beds?wardId=' + str(self.ward['id']))
        assert len(self.beds) == 3 and all(row['status'] == 'FREE' for row in self.beds)
        self.own = self.admit('本人主管', 'doctor', self.beds[0]['id'])
        self.foreign = self.admit('他人主管', 'other', self.beds[1]['id'])
        self.free = self.admit('未分配主管', None, self.beds[2]['id'])
        self.own_surgery = self.surgery(self.own, '本人', 'doctor')
        self.foreign_surgery = self.surgery(self.foreign, '他人')
        self.free_surgery = self.surgery(self.free, '未分配', 'doctor')

    def api(self, method, path, body=None, who='admin', code=0):
        def unknown():
            if method in ['POST', 'PUT', 'PATCH', 'DELETE']:
                self.unknown_writes.append({'method': method, 'path': path})
        try:
            response = call(method, path, body, self.tokens.get(who))
        except urllib.error.HTTPError as error:
            if error.code not in [401, 403]:
                unknown()
            assert (error.code, code) in [(403, 1005), (401, 1000)], (who, method, path, error.code, code)
            try:
                response = json.load(error)
            except Exception:
                unknown()
                raise
        except Exception:
            unknown()
            raise
        if not isinstance(response, dict) or 'code' not in response:
            unknown()
            raise AssertionError('Original response did not contain a known envelope')
        assert response['code'] == code, (who, method, path, code, response.get('code'), response.get('message'))
        if code:
            assert response.get('data') is None, 'Refusal disclosed a surgery payload'
        return response.get('data')

    def admit(self, label, owner, bed):
        patient = self.api('POST', '/patients', {'name': 'V139模拟' + label + self.suffix, 'sex': 'U'})
        body = {'patientId': patient['id'], 'deptId': self.department['id'], 'bedId': bed,
                'deposit': 0, 'payMethod': 'CASH'}
        if owner:
            body['doctorId'] = self.identities[owner]['id']
        admission = self.api('POST', '/inpatient/admissions', body)
        self.admissions.append(admission['id'])
        self.admission_patients[admission['id']] = patient['id']
        return admission['id']

    def surgery(self, admission, label, who='admin'):
        before = {row['id'] for row in self.api('GET', '/inpatient/surgeries')}
        result = self.api('POST', '/inpatient/surgeries', {'admissionId': admission,
            'procedureName': 'V139模拟术式' + label + self.suffix, 'anesthesiaType': '全身麻醉'}, who)
        assert set(result) <= {'warning'}
        rows = [row for row in self.api('GET', '/inpatient/surgeries') if row['id'] not in before and row['admission_id'] == admission]
        assert len(rows) == 1 and rows[0]['status'] == 'REQUESTED'
        self.surgery_ids.append(rows[0]['id'])
        self.surgery_owners[rows[0]['id']] = admission
        return rows[0]['id']

    def detail(self, sid):
        return self.api('GET', f'/inpatient/surgeries/{sid}/detail')

    def summary(self, sid):
        return self.api('GET', f'/surgery/intraop/summary?surgeryId={sid}')

    def snapshot(self, sid):
        return {'detail': self.detail(sid), 'summary': self.summary(sid),
                'anes': self.api('GET', f'/anes/records?surgeryId={sid}'),
                'pacu': self.api('GET', f'/anes/records/pacu-status?surgeryId={sid}'),
                'account': self.api('GET', f"/inpatient/admissions/{self.detail(sid)['admission_id']}/account")}

    def set_roles(self, who, roles):
        person = self.identities[who]
        answer = self.api('PUT', '/system/users/' + str(person['id']), {
            'username': self.users[who], 'realName': person['realName'],
            'deptId': person.get('deptId'), 'title': person.get('title'), 'phone': person.get('phone'), 'roleCodes': roles})
        assert answer['id'] == person['id'] and answer['enabled'] and set(answer['roleCodes']) == set(roles)
        self.identities[who] = answer

    def bodies(self, sid):
        return {'tubes': {'surgeryId': sid, 'tubeType': '尿管', 'position': '模拟位置', 'remark': 'V139模拟'},
                'transfusions': {'surgeryId': sid, 'productType': 'RBC', 'volumeMl': 200, 'isAuto': True},
                'events': {'surgeryId': sid, 'eventType': 'RESCUE', 'detail': 'V139模拟事件', 'planned': False}}

    def cleanup(self):
        if self.unknown_writes:
            # A response-loss/serialization failure is not proof that the write rolled back.
            # Preserve the original outcome and allow only a readback, never a cleanup write.
            print('Cleanup writes skipped after an unknown original response:', self.unknown_writes)
            try:
                rows = [row for row in self.api('GET', '/inpatient/surgeries') if row['admission_id'] in self.admissions]
                assert all(row['procedure_name'].startswith('V139模拟术式') and row['procedure_name'].endswith(self.suffix) for row in rows)
                print('Read-only owned surgery identities after unknown outcome:',
                      [{'id': row['id'], 'admission_id': row['admission_id'], 'status': row['status']} for row in rows])
            except Exception as error:
                print('Read-only unknown-outcome check unavailable:', type(error).__name__)
            return
        for sid in self.surgery_ids:
            row = self.detail(sid)
            assert row['id'] == sid and row['admission_id'] == self.surgery_owners[sid]
            assert row['procedure_name'].startswith('V139模拟术式') and row['procedure_name'].endswith(self.suffix)
            if row['status'] not in ['DONE', 'CANCELLED']:
                stage = 'IN_OP' if row.get('in_room_at') or row.get('start_at') else 'SCHEDULE' if row.get('scheduled_at') else 'APPLY'
                self.api('PUT', f'/inpatient/surgeries/{sid}/cancel', {'stage': stage, 'reason': 'V139模拟验收收尾'})
        for admission in reversed(self.admissions):
            actual = self.api('GET', f'/inpatient/admissions/{admission}/workspace')['admission']
            assert actual['id'] == admission and actual['patientId'] == self.admission_patients[admission]
            assert actual['bedId'] in {row['id'] for row in self.beds}
            discharge_cleanup(self.tokens['admin'], admission)


def main():
    f = Fixture()
    failed = False
    try:
        sid, foreign, free = f.own_surgery, f.foreign_surgery, f.free_surgery
        base = '/surgery/intraop/'
        # Existing medical and technical role sets remain different; source windows/status are unchanged.
        for who in ['admin', 'doctor', 'nurse', 'tech']:
            original = f.detail(sid)
            answer = f.api('PUT', f'/inpatient/surgeries/{sid}/timepoint', {'stage': 'IN_ROOM'}, who)
            assert set(answer) == {'stage', 'stageName', 'at', 'phase'} and answer['stage'] == 'IN_ROOM'
            assert f.detail(sid)['status'] == original['status'] == 'REQUESTED'
            f.api('PUT', f'/inpatient/surgeries/{sid}/op-info', {'roomNo': 'V139OR'}, who)
            bodies = f.bodies(sid)
            for kind, body in bodies.items():
                created = f.api('POST', base + kind, body, who)
                assert set(created) == {'id'} and type(created['id']) is int
                rows = f.summary(sid)[kind]
                actual = next(row for row in rows if row['id'] == created['id'])
                assert actual['surgery_id'] == sid and actual['operator_id'] == f.identities[who]['id']
                if kind == 'transfusions':
                    assert actual['product_type'] == 'RBC' and actual['is_auto'] and actual['volume_ml'] == 200
                f.api('PUT', base + kind + '/' + str(created['id']), {**body, 'surgeryId': foreign}, who)
                assert any(row['id'] == created['id'] for row in f.summary(sid)[kind])
                assert not any(row['id'] == created['id'] for row in f.summary(foreign)[kind])
                f.api('DELETE', base + kind + '/' + str(created['id']), who=who)
                assert not any(row['id'] == created['id'] for row in f.summary(sid)[kind])
        for who in ['admin', 'doctor', 'tech']:
            count = len(f.api('GET', f'/anes/records?surgeryId={sid}'))
            assert f.api('POST', '/anes/records', {'surgeryId': sid, 'phase': 'PACU', 'hr': 80, 'stewardScore': 5}, who) is None
            assert len(f.api('GET', f'/anes/records?surgeryId={sid}')) == count + 1
            assert f.api('GET', f'/anes/records/pacu-status?surgeryId={sid}') == {'latestScore': 5, 'canLeave': True}
        before = f.snapshot(sid)
        f.api('POST', '/anes/records', {'surgeryId': sid, 'phase': 'INTRA', 'hr': 80}, 'nurse', 1005)
        for who in ['nurse', 'tech']:
            f.api('POST', '/inpatient/surgeries', {'admissionId': f.own, 'procedureName': '不得写入'}, who, 1005)
            for action, body in [('complete', {'opNote': '不得写入'}), ('cancel', {'stage': 'IN_OP', 'reason': '不得写入'}),
                                 ('op-info', {'roomNo': '不得写入', 'asaGrade': 'II'})]:
                f.api('PUT', f'/inpatient/surgeries/{sid}/{action}', body, who, 1005)
        assert f.snapshot(sid) == before
        print('[1] Original operational four duties, medical two duties and anesthesia three duties retained; child source cannot follow forged body, creator/auto flag/status/PACU contracts preserved')

        children = {kind: f.api('POST', base + kind, body)['id'] for kind, body in f.bodies(foreign).items()}
        before = f.snapshot(foreign)
        for kind, body in f.bodies(foreign).items():
            f.api('POST', base + kind, body, 'doctor', 9018)
            f.api('PUT', base + kind + '/' + str(children[kind]), {**body, 'surgeryId': sid}, 'doctor', 9018)
            f.api('DELETE', base + kind + '/' + str(children[kind]), who='doctor', code=9018)
        for action, body in [('complete', {'opNote': '不得写入'}), ('cancel', {'stage': 'APPLY', 'reason': '不得写入'}),
                             ('timepoint', {'stage': 'IN_ROOM'}), ('op-info', {'roomNo': '不得写入'})]:
            f.api('PUT', f'/inpatient/surgeries/{foreign}/{action}', body, 'doctor', 9018)
        f.api('POST', '/inpatient/surgeries', {'admissionId': f.foreign, 'procedureName': '不得写入'}, 'doctor', 9018)
        f.api('POST', '/anes/records', {'surgeryId': foreign, 'phase': 'INTRA'}, 'doctor', 9018)
        assert f.snapshot(foreign) == before
        for who in ['admin', 'doctor']:
            completed = f.surgery(f.own, '合法完成' + who, who)
            f.api('PUT', f'/inpatient/surgeries/{completed}/complete', {'opNote': 'V139模拟术后记录', 'anesNote': 'V139模拟麻醉记录'}, who)
            assert f.detail(completed)['status'] == 'DONE'
            f.api('PUT', f'/inpatient/surgeries/{completed}/complete', {'opNote': '不得覆盖'}, who, 9946)
            cancelled = f.surgery(f.own, '合法取消' + who, who)
            f.api('PUT', f'/inpatient/surgeries/{cancelled}/cancel', {'stage': 'APPLY', 'reason': 'V139模拟取消'}, who)
            assert f.detail(cancelled)['status'] == 'CANCELLED'
        print('[2] Owner/unassigned clinical policy and foreign-owner9018 retained across parent/child/anes writes; legitimate medical completion/cancellation and original9946 remain')

        token = f.tokens['mover']
        f.set_roles('mover', ['NURSE']); before = f.snapshot(sid)
        f.api('POST', '/anes/records', {'surgeryId': sid, 'phase': 'INTRA'}, 'mover', 1005)
        assert f.snapshot(sid) == before
        event = f.api('POST', base + 'events', f.bodies(sid)['events'], 'mover')['id']
        f.api('DELETE', base + 'events/' + str(event), who='mover')
        f.set_roles('mover', ['QUALITY']); before = f.snapshot(sid)
        f.api('POST', base + 'events', f.bodies(sid)['events'], 'mover', 1005)
        f.api('POST', '/anes/records', {'surgeryId': sid, 'phase': 'INTRA'}, 'mover', 1005)
        assert f.snapshot(sid) == before
        f.set_roles('mover', ['DOCTOR_OUTP']); before = f.snapshot(foreign)
        f.api('POST', '/anes/records', {'surgeryId': foreign, 'phase': 'INTRA'}, 'mover', 9018)
        f.api('POST', base + 'events', f.bodies(foreign)['events'], 'mover', 9018)
        assert f.snapshot(foreign) == before
        f.api('POST', '/anes/records', {'surgeryId': free, 'phase': 'INTRA', 'hr': 79}, 'mover')
        f.set_roles('mover', ['DOCTOR_OUTP', 'NURSE'])
        event = f.api('POST', base + 'events', f.bodies(foreign)['events'], 'mover')['id']
        f.api('DELETE', base + 'events/' + str(event), who='mover')
        f.api('POST', '/anes/records', {'surgeryId': foreign, 'phase': 'INTRA'}, 'mover', 9018)
        for roles in [['ADMIN'], ['TECHNICIAN'], ['QUALITY', 'TECHNICIAN']]:
            f.set_roles('mover', roles)
            f.api('POST', '/anes/records', {'surgeryId': foreign, 'phase': 'INTRA', 'hr': 78}, 'mover')
        assert f.tokens['mover'] == token
        print('[3] Same issued JWT follows TECH→NURSE anesthesia refusal while ordinary operation remains valid; no-duty/doctor-only scope/multi-role and original-duty restoration are preserved; sequential calls do not claim PG competition')

        before = f.snapshot(sid)
        f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
        for who in ['quality', 'cashier', 'interface', 'pharmacist', 'disabled', None]:
            code = 1000 if who in ['disabled', None] else 1005
            f.api('POST', base + 'events', f.bodies(sid)['events'], who, code)
            f.api('POST', '/anes/records', {'surgeryId': sid, 'phase': 'INTRA'}, who, code)
            f.api('PUT', f'/inpatient/surgeries/{sid}/complete', {'opNote': '不得写入'}, who, code)
        assert f.snapshot(sid) == before
        for body, code in [({'surgeryId': sid, 'phase': 'INVALID'}, 4670),
                           ({'surgeryId': MISSING, 'phase': 'INTRA'}, 4671),
                           ({'surgeryId': sid, 'phase': 'PACU', 'stewardScore': 7}, 4672)]:
            f.api('POST', '/anes/records', body, code=code)
        f.api('POST', base + 'events', {'surgeryId': sid, 'eventType': 'INVALID'}, code=4924)
        f.api('PUT', f'/inpatient/surgeries/{sid}/timepoint', {'stage': 'INVALID'}, code=4908)
        assert f.snapshot(sid) == before
        print('[4] Wrong-duty/machine/anonymous/disabled refusals disclose no payload or change public projections; original missing/invalid phase/score/event/stage errors remain')
        print('e2e-v139-surgery-live-duty passed: public two/four/three-duty JWT and owner compatibility; no SQL/config mutation, unrelated patient writes, HTTP replay or claim of PostgreSQL race proof.')
    except Exception:
        failed = True
        raise
    finally:
        try:
            f.cleanup()
        except Exception as error:
            if not failed:
                raise
            print('Cleanup failed without replacing the original suite failure:', type(error).__name__, str(error))


if __name__ == '__main__':
    main()
