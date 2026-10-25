# -*- coding: utf-8 -*-
"""Real-API regression for micro/AST writes across refunds and publication.

No SQL or configuration mutations. Raw sample/micro history remains readable;
only the write eligibility changes. Deterministic database waits and historical
source/relation fixtures are covered by the dedicated Java acceptance.
"""
import importlib.util
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    'v126_lab_fixture', Path(__file__).with_name('e2e-v121-lab-print-read.py'))
_lab = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_lab)
login = _lab.login

MICRO_KEYS = {'id', 'sample_id', 'order_id', 'specimen', 'organism', 'colony_count',
              'gram', 'reporter_id', 'reported_at', 'ast'}
AST_KEYS = {'antibiotic', 'method', 'mic_value', 'sir'}


class Fixture(_lab.Fixture):
    def __init__(self, prefix='v126'):
        super().__init__(prefix)

    def received(self, label, receive=True):
        case = self.visit(label)
        case['barcode'] = self.api('POST', f"/lis/samples?orderId={case['order']['id']}", who='nurse')['barcode']
        if receive:
            self.api('PUT', f"/lis/samples/{case['barcode']}/receive", who='tech')
        sample = self.samples(case)
        assert len(sample) == 1 and sample[0]['status'] == ('RECEIVED' if receive else 'COLLECTED')
        case['sample_id'] = sample[0]['id']
        return case

    def micros(self, case):
        return self.api('GET', f"/lis/micro?orderId={case['order']['id']}")

    def snapshot(self, case):
        return {**super().snapshot(case), 'micros': self.micros(case),
                'cdr': self.api('GET', f"/cdr/patients/{case['patient']['id']}/documents")}

    def micro(self, case, body, who='tech', code=0):
        before = self.snapshot(case)
        answer = self.api('POST', f"/lis/micro/{case['barcode']}", body, who=who, code=code)
        assert answer is None, 'Keep the original R<Void> response'
        after = self.snapshot(case)
        if code:
            assert after == before, 'Refused micro write changed a public business projection'
            return None
        old = before.pop('micros')
        rows = after.pop('micros')
        assert after == before, 'Micro must not execute the order, publish the sample, change money, results, alerts or CDR'
        assert rows[:-1] == old and len(rows) == len(old) + 1
        new = rows[-1]
        assert set(new) == MICRO_KEYS and type(new['id']) is int and new['id'] > 0
        assert not old or new['id'] > old[-1]['id']
        assert new['sample_id'] == case['sample_id'] and new['order_id'] == case['order']['id']
        assert new['reporter_id'] == self.identities[who]['id'] and isinstance(new['reported_at'], str)
        assert {key: new[key] for key in ['specimen', 'organism', 'colony_count', 'gram']} == {
            'specimen': body.get('specimen'), 'organism': body['organism'],
            'colony_count': body.get('colonyCount'), 'gram': body.get('gram')}
        assert all(set(row) == AST_KEYS for row in new['ast'])
        assert new['ast'] == [{'antibiotic': row['antibiotic'], 'method': row.get('method'),
                               'mic_value': row.get('micValue'), 'sir': row['sir']}
                              for row in body.get('ast') or []]
        return new

    def micro_queue(self, case):
        return [row for row in self.api('GET', '/lis/micro/samples')
                if row['order_id'] == case['order']['id']]


def body(organism='V126模拟培养'):
    return {'specimen': '模拟痰', 'organism': organism, 'gram': 'NEG', 'colonyCount': '+++',
            'ast': [{'antibiotic': '模拟头孢', 'method': 'MIC', 'micValue': '1', 'sir': 'S'},
                    {'antibiotic': '模拟青霉', 'method': None, 'micValue': None, 'sir': 'R'}]}


def main():
    f = Fixture()
    original = f.received('微生物原历史')
    for who in ['admin', 'tech', 'doctor', 'nurse']:
        f.micro(original, body('V126模拟' + who), who)
    # The original endpoint appends, including repeated organisms and empty AST.
    duplicate = body('V126模拟重复菌')
    f.micro(original, duplicate); f.micro(original, duplicate)
    f.micro(original, {'organism': 'V126模拟无药敏', 'ast': None})
    f.micro(original, {'organism': 'V126模拟空药敏', 'ast': []})
    assert f.source(original)['status'] == 'CHARGED' and f.samples(original)[0]['status'] == 'RECEIVED'
    history = f.micros(original)
    f.micro(original, {'organism': '  '}, code=7107)
    f.micro(original, {'organism': 'V126模拟非法', 'ast': [{'antibiotic': '模拟药', 'sir': 'X'}]}, code=7108)
    f.api('POST', '/lis/micro/V126-NO-SUCH-BARCODE', body(), code=7106)
    collected = f.received('尚未核收', receive=False)
    f.micro(collected, body(), code=7106)
    print('[1] Four original duties retain exact append-only cultures/ordered AST, repeated organisms and nullable/empty AST; original validations remain read-only')

    before_sample = f.samples(original)
    old_bill = original['charge']
    refunded = f.api('POST', f"/outpatient/charges/{old_bill['id']}/refund", who='cashier')
    assert refunded['status'] == 'REFUNDED' and refunded['id'] == old_bill['id']
    assert f.source(original)['status'] == 'CREATED' and f.source(original)['chargeId'] is None
    # This queue projection includes O.status; the physical S and other fields
    # stay unchanged, while the actual refund must change only order_status.
    assert f.samples(original) == [{**row, 'order_status': 'CREATED'} for row in before_sample]
    assert f.micros(original) == history
    assert f.micro_queue(original)[0]['id'] == original['sample_id'], 'Physical history stays visible'
    f.micro(original, body('V126模拟退款旧模态'), code=7106)
    new_bill = f.api('POST', '/outpatient/charges/settle', {
        'registrationId': original['registration']['id'], 'payMethod': 'CASH'}, who='cashier')
    assert new_bill['status'] == 'PAID' and new_bill['id'] != old_bill['id']
    original['charge'] = new_bill
    assert f.source(original)['status'] == 'CHARGED' and f.samples(original) == before_sample
    assert f.micros(original) == history
    f.micro(original, body('V126模拟重收恢复'))
    print('[2] Real refund retains the received sample and every prior culture/AST but blocks writes; genuine recharge restores the same sample without replacing history')

    portal = f.api('POST', '/portal/login', {
        'patientNo': original['patient']['patientNo'], 'phone': '13900001210'}, who=None)
    f.tokens['portal'] = portal['token']
    for who in ['cashier', 'pharmacist', 'interface', 'portal', None]:
        f.micro(original, body(), who, code=1000 if who is None else 1005)
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    f.micro(original, body(), 'disabled', code=1000)
    print('[3] Cashier/pharmacy/interface/portal, anonymous and disabled identities cannot append clinical micro facts')

    history = f.micros(original)
    f.api('POST', f"/lis/samples/{original['barcode']}/publish", {'results': [f.line('模拟正式发布')]}, who='tech')
    assert f.source(original)['status'] == 'EXECUTED' and not f.samples(original)
    assert not f.micro_queue(original) and f.micros(original) == history
    f.micro(original, body('V126模拟发布旧模态'), code=7106)
    f.api('POST', f"/outpatient/charges/{new_bill['id']}/refund", who='cashier', code=5005)
    executed = f.received('通用执行后仍核收')
    f.api('POST', f"/outpatient/exec/{executed['order']['id']}",
          {'resultText': 'V126模拟通用执行'}, who='tech')
    assert f.source(executed)['status'] == 'EXECUTED' and f.samples(executed)[0]['status'] == 'RECEIVED'
    f.micro(executed, body('V126模拟已执行合法培养'))
    print('[4] Publication closes later micro writes; legitimate EXECUTED/RECEIVED input remains, and original executed-refund 5005 is unchanged')
    print('e2e-v126-lis-micro-source-guard passed: real four-duty JWT writes, refund/recharge same-sample recovery, original history, publication boundary, append-only AST and unchanged public business projections. Deterministic PG contention and historical source/relation changes are separate Java evidence.')


if __name__ == '__main__':
    main()
