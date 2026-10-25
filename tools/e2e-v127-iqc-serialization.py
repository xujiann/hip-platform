# -*- coding: utf-8 -*-
"""Public API compatibility for IQC transactional serialization.

Creates only test employees and QC rows. No SQL/configuration mutation, patient
fixture or algorithm extension. Real database waits and the raw operator/12-column
ledger are proved by Java/private acceptance; this script checks public contracts.
The virtual patient-subject role is covered by Java; this script does not create
a patient or invent a persisted PORTAL employee role.
"""
import json
import time
import urllib.error
from urllib.parse import urlencode

from e2elib import call, login, provision_user

RESPONSE_KEYS = {'zScore', 'inControl', 'rule'}
LATEST_KEYS = {'item_code', 'level', 'lot_no', 'measured_value', 'z_score',
               'rule_broken', 'in_control', 'measured_at'}
LJ_KEYS = {'measured_at', 'measured_value', 'target_value', 'sd', 'z_score',
           'rule_broken', 'in_control'}


class Fixture:
    def __init__(self, prefix='v127', minimal=False):
        self.suffix = str(time.time_ns())[-11:]
        self.prefix, self.series = prefix, set()
        self.users = {'admin': prefix + '_admin_' + self.suffix}
        self.tokens = {'admin': provision_user(login(), self.users['admin'], 'ADMIN')}
        self.identities = {'admin': self.api('GET', '/auth/me')}
        roles = [('tech', 'TECHNICIAN')]
        if not minimal:
            roles += [('doctor', 'DOCTOR_OUTP'), ('nurse', 'NURSE'),
                      ('cashier', 'CASHIER'), ('pharmacist', 'PHARMACIST'),
                      ('interface', 'INTERFACE'), ('quality', 'QUALITY'),
                      ('disabled', 'TECHNICIAN')]
        for who, role in roles:
            name = prefix + '_' + who + '_' + self.suffix
            self.users[who] = name
            temporary_role = 'TECHNICIAN' if role == 'INTERFACE' else role
            self.tokens[who] = provision_user(self.tokens['admin'], name, temporary_role)
            person = self.api('GET', '/auth/me', who=who)
            assert not person.get('mustChangePassword', False)
            self.identities[who] = person
            if temporary_role != role:
                result = self.api('PUT', f"/system/users/{person['id']}", {
                    'username': name, 'realName': name, 'roleCodes': [role]})
                assert result['roleCodes'] == [role] and result['enabled']
                self.tokens[who] = login(name, 'Work@1234')

    def api(self, method, path, body=None, who='admin', code=0):
        try:
            result = call(method, path, body, self.tokens.get(who))
        except urllib.error.HTTPError as error:
            assert (code, error.code) in [(1000, 401), (1005, 403)], (path, code, error.code)
            result = json.load(error)
        assert result['code'] == code, (path, code, result.get('code'), result.get('message'))
        if code:
            assert result.get('data') is None
        return result.get('data')

    def lj(self, item, level):
        rows = self.api('GET', '/lis/qc/lj?' + urlencode({'itemCode': item, 'level': level}))
        assert all(set(row) == LJ_KEYS for row in rows)
        return rows

    def snapshot(self):
        latest = self.api('GET', '/lis/qc/latest')
        assert all(set(row) == LATEST_KEYS for row in latest)
        return {'latest': [row for row in latest if (row['item_code'], row['level']) in self.series],
                'series': {(item, level): self.lj(item, level) for item, level in sorted(self.series)}}

    def qc(self, body, rule='', control=True, z=0, who='tech', code=0):
        pair = body.get('itemCode'), body.get('level')
        if all(isinstance(value, str) and value.strip() for value in pair):
            self.series.add(pair)
        before = self.snapshot()
        data = self.api('POST', '/lis/qc', body, who, code)
        after = self.snapshot()
        if code:
            assert after == before, 'Refused QC write changed public records'
            return None
        assert set(data) == RESPONSE_KEYS
        assert data == {'zScore': z, 'inControl': control, 'rule': rule}
        expected = {'measured_value': body['measuredValue'], 'target_value': body['targetValue'],
                    'sd': body['sd'], 'z_score': z, 'rule_broken': rule or None, 'in_control': control}
        rows = after['series'][pair]
        assert rows[:-1] == before['series'][pair] and len(rows) == len(before['series'][pair]) + 1
        assert {key: rows[-1][key] for key in expected} == expected
        assert isinstance(rows[-1]['measured_at'], str)
        for other in self.series - {pair}:
            assert after['series'][other] == before['series'][other]
        latest = [row for row in after['latest'] if (row['item_code'], row['level']) == pair]
        assert len(latest) == 1
        assert latest[0] == {'item_code': pair[0], 'level': pair[1], 'lot_no': body['lotNo'],
                             'measured_value': body['measuredValue'], 'z_score': z,
                             'rule_broken': rule or None, 'in_control': control,
                             'measured_at': rows[-1]['measured_at']}
        return data


def body(item, value=2.5, level='L1', lot='V127', target=0, sd=1):
    return {'itemCode': item, 'level': level, 'lotNo': lot, 'targetValue': target,
            'sd': sd, 'measuredValue': value}


def main():
    f = Fixture()
    base = 'Q127' + f.suffix
    for who in ['admin', 'tech', 'doctor', 'nurse']:
        f.qc(body(base + who[0], 1), z=1, who=who)
    print('[1] Original four duties retain exact three-key responses and public latest/LJ fields')

    item = base + 'S'
    cases = [(2.5, '1-2s(警告)', True, 2.5),
             (2.5, '2-2s', False, 2.5),
             (-2.5, '1-2s(警告)', True, -2.5),
             (-2.5, '2-2s', False, -2.5),
             (0, '', True, 0),
             (2.5, '1-2s(警告)', True, 2.5),
             (4, '1-3s', False, 4),
             (2.5, '2-2s', False, 2.5),
             (2, '', True, 2),
             (2.5, '1-2s(警告)', True, 2.5),
             (-3, '1-2s(警告)', True, -3),
             (-3, '2-2s', False, -3),
             (3.0001, '1-3s', False, 3),
             (2.0004, '2-2s', False, 2),
             (2.5, '1-2s(警告)', True, 2.5)]
    for index, (value, rule, control, score) in enumerate(cases):
        f.qc(body(item, value, lot='批次' + str(index)), rule, control, score)
    print('[2] Original strict thresholds, 1-3s priority, sign/normal resets, stored z precision and cross-lot history remain')

    f.qc(body(item, 2.5, level='L2'), '1-2s(警告)', True, 2.5)
    f.qc(body(base + 'X', 2.5), '1-2s(警告)', True, 2.5)
    f.qc(body(' ' + base + ' ', 1, level=' L1 ', lot=' 批号 '), z=1)
    f.qc(body(base + ':B', 2.5, level='C'), '1-2s(警告)', True, 2.5)
    f.qc(body(base, 2.5, level='B:C'), '1-2s(警告)', True, 2.5)
    print('[3] Exact item/level partition and raw item/level/lot text are preserved; calls append rather than deduplicate')

    valid = body(base + 'V')
    for key in ['itemCode', 'level', 'lotNo', 'targetValue', 'measuredValue']:
        bad = dict(valid); bad[key] = None
        f.qc(bad, code=7109)
    for key in ['itemCode', 'level', 'lotNo']:
        bad = dict(valid); bad[key] = '  '
        f.qc(bad, code=7109)
    for value in [None, 0, -1]:
        bad = dict(valid); bad['sd'] = value
        f.qc(bad, code=7110)
    for who in ['cashier', 'pharmacist', 'interface', 'quality', None]:
        f.qc(valid, who=who, code=1000 if who is None else 1005)
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    f.qc(valid, who='disabled', code=1000)
    print('[4] Required fields, positive SD and wrong/anonymous/disabled identity refusals leave public QC data unchanged')
    print('e2e-v127-iqc-serialization passed: real four-duty QC writes, original rules/partitions and strict response/latest/LJ contracts. No patient data, algorithm extension, PostgreSQL contention, raw operator proof or true patient-subject token is claimed here; dedicated Java/private acceptance covers those boundaries.')


if __name__ == '__main__':
    main()
