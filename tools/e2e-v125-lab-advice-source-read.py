# -*- coding: utf-8 -*-
"""Public JWT regression for the laboratory AI advice source boundary.

Clinical fixtures use the existing real-API laboratory fixture. No SQL or
configuration mutation. Uses the running bundled rule-based AI service and
does not assert the algorithm's wording.
Historical invalid result/type rows and exact outbound payload capture are the
dedicated Java tests' responsibility. This script only proves legal HTTP paths.
"""
import importlib.util
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    'v125_lab_fixture', Path(__file__).with_name('e2e-v121-lab-print-read.py'))
_lab = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_lab)


class Fixture(_lab.Fixture):
    def __init__(self, prefix='v125'):
        super().__init__(prefix)

    def advice(self, case, who='tech', code=0):
        data = self.api('GET', f"/ai/lab-advice?orderId={case['order']['id']}",
                        who=who, code=code)
        if code:
            return None
        assert data['source'] == 'ai-service', data
        assert isinstance(data['advice'], list) and data['advice']
        assert all(isinstance(row, str) and row for row in data['advice'])
        assert isinstance(data['model'], str) and data['model']
        assert type(data['abnormalCount']) is int and data['abnormalCount'] >= 0
        assert 'error' not in data, data
        return data


def main():
    f = Fixture()
    pending = f.visit('AI已付未执行')
    empty = f.visit('AI执行无结果')
    f.api('POST', f"/outpatient/exec/{empty['order']['id']}",
          {'resultText': 'V125模拟通用执行但无检验结果'}, who='tech')
    assert f.source(empty)['status'] == 'EXECUTED' and not f.results(empty)
    unpaid = f.visit('AI未付申请', paid=False)
    refunded = f.visit('AI真实退费')
    f.api('POST', f"/outpatient/charges/{refunded['charge']['id']}/refund", who='cashier')
    assert f.source(refunded)['status'] == 'CREATED'
    assert f.source(refunded)['chargeId'] is None
    for case in [pending, empty, unpaid, refunded]:
        before = f.snapshot(case)
        f.advice(case, code=9952)
        assert f.snapshot(case) == before
    f.api('GET', '/ai/lab-advice?orderId=9000000000000000', code=9952)
    print('[1] Unpaid, charged-but-unexecuted, legally refunded, missing and genuinely executed/no-result requests refuse 9952')

    manual = f.visit('AI手工发布')
    lines = [f.line('AI正常'), f.line('AI高危急', 'HH', True), f.line('AI空标记', None, True)]
    f.publish(manual, lines)
    # Appended duplicate rows stay ordinary results; the Java receiver checks
    # their exact three-key payload and id ordering independently of this AI.
    f.oru([(manual, [lines[0], lines[0]])])
    before = f.snapshot(manual)
    for who in ['admin', 'tech', 'doctor', 'nurse']:
        f.advice(manual, who)
        assert f.snapshot(manual) == before
    print('[2] All original four duties retain real manual-publication advice with duplicate and nullable result metadata')

    portal = f.api('POST', '/portal/login', {
        'patientNo': manual['patient']['patientNo'], 'phone': '13900001210'}, who=None)
    f.tokens['portal'] = portal['token']
    for who in ['cashier', 'pharmacist', 'interface', 'portal', None]:
        f.advice(manual, who, code=1000 if who is None else 1005)
        assert f.snapshot(manual) == before
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    f.advice(manual, 'disabled', code=1000)
    assert f.snapshot(manual) == before
    print('[3] Cashier, pharmacy, interface, portal, anonymous and disabled identities cannot obtain advice')

    machine, second = f.visit('AI无样本机器'), f.visit('AI独立机器申请')
    machine_lines, second_lines = [f.line('AI低危急', 'LL'), f.line('AI机器正常')], [f.line('AI另申请')]
    f.oru([(machine, machine_lines), (second, second_lines)])
    assert not f.samples(machine) and not f.samples(second)
    assert f.report(machine, lines=machine_lines)['results'] != f.report(second, lines=second_lines)['results']
    for case in [machine, second, manual]:
        before = f.snapshot(case)
        f.advice(case)
        f.api('POST', f"/outpatient/charges/{case['charge']['id']}/refund", who='cashier', code=5005)
        assert f.snapshot(case) == before
    print('[4] Legal unsampled multi-OBR machine reports remain eligible; read-only projections and executed-refund 5005 remain')
    print('e2e-v125-lab-advice-source-read passed: real four-duty JWT advice, source refusals, manual/machine compatibility and unchanged public business projections. Historical result mutations, exact external payload and AI fallback are separate Java acceptance; no frontend AI consumer or PostgreSQL lock race is claimed.')


if __name__ == '__main__':
    main()
