# -*- coding: utf-8 -*-
"""Public JWT compatibility for pathology's current-duty write checks.

Only public APIs create private staff, visits, billing and pathology facts. Role
changes here happen between requests: the dedicated Java suite proves actual
PostgreSQL waits and rollback after an in-flight employee loses a duty. These
sequential HTTP calls do not claim to reproduce that database race. No SQL,
shared configuration changes, mutation retries or fabricated clinical rows.
"""
import importlib.util
from decimal import Decimal
from pathlib import Path

from e2elib import login, provision_user, q

_spec = importlib.util.spec_from_file_location(
    'v137_staff_fixture', Path(__file__).with_name('e2e-v121-lab-print-read.py'))
_staff = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_staff)

TECHNICAL = ['admin', 'tech', 'doctor']
SIGNERS = ['admin', 'doctor']
ACTIONS = ['collect', 'register', 'manual', 'receive', 'receive-check', 'reject',
           'diagnose', 'first-sign', 'second-sign', 'issue', 'supplement',
           'gross', 'revise', 'dehydrate', 'embed', 'slides', 'stain', 'batch',
           'new-tech', 'done', 'cancel']


class Fixture(_staff.Fixture):
    """Importable fixture for the independent, isolated browser acceptance."""
    def __init__(self, prefix='v137'):
        super().__init__(prefix)
        for who, role in [('doctor2', 'DOCTOR_OUTP'), ('mover', 'TECHNICIAN'), ('quality', 'QUALITY')]:
            self.users[who] = prefix + '_' + who + '_' + self.suffix
            self.tokens[who] = provision_user(self.tokens['admin'], self.users[who], role)
            self.identities[who] = self.api('GET', '/auth/me', who=who)
        code = 'V137PATH' + self.suffix
        imported = self.api('POST', '/masterdata/charge-items/import', text=
            'code,name,category,unit,price,fee_category_code,self_pay,exec_dept_code\n' +
            f'{code},V137模拟病理{self.suffix},EXAM,次,1.00,,,{self.lab_department["code"]}\n')
        assert imported['imported'] == 1 and imported['errorCount'] == 0
        self.path_item = next(row for row in self.api('GET', '/masterdata/charge-items?keyword=' + self.suffix)
                              if row['code'] == code)
        # Original cross-department clinical collaboration remains available.
        for who, role in [('tech', 'TECHNICIAN'), ('doctor', 'DOCTOR_OUTP'), ('doctor2', 'DOCTOR_OUTP')]:
            self.set_roles(who, [role], self.department['id'])
            assert self.identities[who]['deptId'] != self.lab_department['id']

    def set_roles(self, who, roles, dept_id=None):
        person = self.identities[who]
        result = self.api('PUT', f"/system/users/{person['id']}", {
            'username': self.users[who], 'realName': self.users[who],
            'deptId': person.get('deptId') if dept_id is None else dept_id,
            'roleCodes': roles})
        assert result['id'] == person['id'] and result['enabled']
        assert set(result['roleCodes']) == set(roles)
        self.identities[who] = result
        return result

    def visit(self, label, paid=True, registered=True, manual=False):
        patient = self.api('POST', '/patients', {
            'name': 'V137模拟' + label + self.suffix, 'sex': 'U', 'phone': '13900001370'})
        registration = self.api('POST', '/outpatient/registrations', {
            'patientId': patient['id'], 'scheduleId': self.schedule['id']}, who='cashier')
        self.api('POST', f"/outpatient/doctor/{registration['id']}/start", who='doctor')
        orders = self.api('POST', f"/outpatient/doctor/{registration['id']}/orders", {'lines': [{
            'orderType': 'EXAM', 'itemId': self.path_item['id'], 'qty': 1,
            'specimenType': '模拟组织', 'samplingSite': '模拟部位'}]}, who='doctor')
        assert len(orders) == 1 and orders[0]['status'] == 'CREATED'
        case = {'patient': patient, 'registration': registration, 'order': orders[0], 'label': label}
        if paid:
            case['charge'] = self.api('POST', '/outpatient/charges/settle', {
                'registrationId': registration['id'], 'payMethod': 'CASH'}, who='cashier')
            assert case['charge']['status'] == 'PAID'
            assert Decimal(str(case['charge']['totalAmount'])) == Decimal('1.00')
        self.cases[label] = case
        if registered:
            self.register(case, manual=manual)
            if not manual:
                self.write(case, 'receive-check')
        return case

    def register(self, case, who='tech', manual=False, part=1):
        body = {'orderId': case['order']['id'], 'partNo': part, 'specimenType': 'ROUTINE',
                'specimenDesc': 'V137模拟送检组织', 'samplingSite': 'V137模拟部位'}
        if manual:
            body.update({'applyDept': 'V137模拟临床', 'applyDoctor': 'V137模拟送检人',
                         'note': 'V137模拟实物救济'})
        result = self.api('POST', '/pathology/registry/specimens' + ('/manual' if manual else ''), body, who=who)
        assert result['source'] == 'OUTP' and result['partNo'] == part and result['manual'] is manual
        assert result['status'] == ('RECEIVED' if manual else 'COLLECTED')
        case['specimen'] = result
        return result

    def specimens(self, case):
        data = self.api('GET', '/pathology/registry/specimens/search?patientName=' + q(case['patient']['name']))
        assert data['truncated'] is False
        return sorted(data['items'], key=lambda row: row['id'])

    def tech_rows(self, case):
        result = self.api('GET', f"/pathology/report/tech-orders?specimenId={case['specimen']['id']}")
        assert result['truncated'] is False
        return [{key: value for key, value in row.items() if key != 'hours_since_ordered'} for row in result['items']]

    def snapshot(self, case):
        specimens = self.specimens(case)
        stored = {}
        for row in specimens:
            sid = row['id']
            report = self.api('GET', f'/pathology/report/{sid}/reports')
            slides = self.api('GET', f'/pathology/process/slides/search?specimenId={sid}')
            tech = self.api('GET', f'/pathology/report/tech-orders?specimenId={sid}')
            assert not slides['truncated'] and not tech['truncated']
            stored[str(sid)] = {
                'report': {key: report[key] for key in ['specimen', 'primary', 'supplements', 'process']},
                'gross': self.api('GET', f'/pathology/process/grossing/{sid}'),
                'slides': slides['items'],
                'tech': [{key: value for key, value in item.items() if key != 'hours_since_ordered'} for item in tech['items']],
                'nodes': self.api('GET', f'/pathology/process/trail/{sid}')['nodes']}
        return {'source': self.source(case), 'specimens': specimens, 'stored': stored,
                'charges': [row for row in self.api('GET', '/outpatient/charges/recent', who='cashier')
                            if row['registrationId'] == case['registration']['id']],
                'execution': self.api('GET', '/outpatient/exec/reports?registrationId=' + str(case['registration']['id']))}

    def write(self, case, action, who='tech', code=0, body=None):
        s = case.get('specimen', {'id': 9000000000000000, 'barcode': 'V137MISSING'})
        sid, barcode = s['id'], s['barcode']
        bid = case.get('block', {}).get('id', 9000000000000000)
        slid = case.get('slide', {}).get('id', 9000000000000000)
        tid = case.get('tech', {}).get('id', 9000000000000000)
        registration = {'orderId': case['order']['id'], 'partNo': 2, 'specimenType': 'ROUTINE', 'specimenDesc': 'V137模拟登记'}
        routes = {
            'collect': ('POST', '/pathology/specimens?orderId=' + str(case['order']['id']) + '&specimenDesc=' + q('V137模拟旧入口组织'), None),
            'register': ('POST', '/pathology/registry/specimens', registration),
            'manual': ('POST', '/pathology/registry/specimens/manual', {**registration,
                'applyDept': 'V137模拟临床', 'applyDoctor': 'V137模拟送检人', 'note': 'V137模拟实物救济'}),
            'receive': ('PUT', f'/pathology/specimens/{barcode}/receive', None),
            'receive-check': ('PUT', f'/pathology/registry/specimens/{sid}/receive-check', {}),
            'reject': ('PUT', f'/pathology/registry/specimens/{sid}/reject', {'reason': 'V137模拟拒收依据'}),
            'diagnose': ('PUT', f'/pathology/specimens/{barcode}/diagnose', {
                'grossFinding': 'V137模拟诊断大体', 'microFinding': 'V137模拟镜下', 'diagnosis': 'V137模拟病理诊断'}),
            'first-sign': ('PUT', f'/pathology/report/{sid}/first-sign', None),
            'second-sign': ('PUT', f'/pathology/report/{sid}/second-sign', None),
            'issue': ('PUT', f'/pathology/report/{sid}/issue', None),
            'supplement': ('POST', f'/pathology/report/{sid}/supplement', {'content': 'V137模拟补充诊断', 'reason': 'V137模拟追加依据'}),
            'gross': ('POST', '/pathology/process/grossing', {'specimenId': sid,
                'grossText': 'V137模拟首次大体', 'blocks': [{'tissueDesc': 'V137模拟第一块'}, {'tissueDesc': 'V137模拟第二块'}]}),
            'revise': ('PUT', f'/pathology/process/grossing/{sid}/fields', {'grossText': 'V137模拟修订大体'}),
            'dehydrate': ('PUT', '/pathology/process/blocks/dehydrate-batch', {'batchNo': 'V137B' + self.suffix, 'blockIds': [bid], 'remark': 'V137模拟分篮'}),
            'embed': ('PUT', f'/pathology/process/blocks/{bid}/embed', None),
            'slides': ('POST', '/pathology/process/slides', {'blockId': bid, 'count': 2, 'stainType': 'HE'}),
            'stain': ('PUT', f'/pathology/process/slides/{slid}/stain', {'quality': 'GOOD'}),
            'batch': ('PUT', '/pathology/process/slides/batch-complete', {'slideIds': [slid], 'quality': 'GOOD'}),
            'new-tech': ('POST', '/pathology/report/tech-orders', {'specimenId': sid, 'techType': 'RESAMPLE', 'reason': 'V137模拟补取材依据'}),
            'done': ('PUT', f'/pathology/report/tech-orders/{tid}/done', None),
            'cancel': ('PUT', f'/pathology/report/tech-orders/{tid}/cancel', {'reason': 'V137模拟取消依据'}),
        }
        method, path, default = routes[action]
        return self.api(method, path, default if body is None else body, who=who, code=code)

    def refused(self, case, action, who='tech', code=1005, body=None):
        before = self.snapshot(case)
        self.write(case, action, who, code, body)
        assert self.snapshot(case) == before, ('Refusal changed public stored projections', action, who, code)

    def node(self, case, name, who):
        nodes = self.api('GET', f"/pathology/process/trail/{case['specimen']['id']}")['nodes']
        rows = [row for row in nodes if row['node'] == name]
        assert rows and rows[-1]['operator_id'] == self.identities[who]['id'] and rows[-1]['occurred_at'], (name, rows)
        return rows[-1]


def main():
    f = Fixture()
    security = f.visit('岗位边界')
    security['block'] = f.write(security, 'gross')['blocks'][0]
    security['slide'] = f.write(security, 'slides')['slides'][0]
    security['tech'] = f.write(security, 'new-tech')
    for who in ['nurse', 'cashier', 'pharmacist', 'quality', 'interface', None]:
        before = f.snapshot(security)
        for action in ACTIONS:
            f.write(security, action, who, 1000 if who is None else 1005)
        assert f.snapshot(security) == before, ('Rejected duty changed facts', who)
    f.api('PUT', f"/system/users/{f.identities['disabled']['id']}/enabled?enabled=false")
    before = f.snapshot(security)
    for action in ACTIONS:
        f.write(security, action, 'disabled', 1000)
    assert f.snapshot(security) == before
    # The original token is retained across these ordinary role changes. Actual
    # in-flight revocation and transaction rollback are proved outside this script.
    f.set_roles('mover', ['NURSE'])
    before = f.snapshot(security)
    for action in ACTIONS:
        f.write(security, action, 'mover', 1005)
    assert f.snapshot(security) == before
    for action in ['first-sign', 'second-sign', 'issue', 'supplement']:
        f.refused(security, action, 'tech')
    print('[1] Twenty-one write entries retain JWT wrong-duty, machine, anonymous, disabled and between-request revocation gates; all public stored projections remain unchanged')

    for who in TECHNICAL:
        legacy = f.visit('旧采集核收' + who, registered=False)
        before_source = f.source(legacy)
        barcode = f.write(legacy, 'collect', who)['barcode']
        legacy['specimen'] = next(row for row in f.specimens(legacy) if row['barcode'] == barcode)
        f.write(legacy, 'receive', who)
        assert f.specimens(legacy)[0]['status'] == 'RECEIVED'
        assert f.source(legacy) == before_source
        f.refused(legacy, 'receive', who, 4551)

        case = f.visit('完整技术链' + who, registered=False)
        f.register(case, who)
        f.write(case, 'receive-check', who)
        f.node(case, 'RECEIVE', who)
        before_source = f.source(case)
        before_charges = f.snapshot(case)['charges']
        gross = f.write(case, 'gross', who)
        assert gross['totalBlockCount'] == 2 and gross['grossRevisionSeq'] == 1
        case['block'] = gross['blocks'][0]
        f.node(case, 'GROSSING', who)
        f.refused(case, 'gross', who, 5223)
        revised = f.write(case, 'revise', who)
        assert revised['grossFinding'] == 'V137模拟修订大体' and revised['revisionSeq'] == 2
        f.write(case, 'dehydrate', who); f.node(case, 'DEHYDRATE', who)
        assert f.write(case, 'embed', who)['embeddedBy'] == f.identities[who]['id']
        f.node(case, 'EMBED', who)
        f.refused(case, 'embed', who, 5241)
        slides = f.write(case, 'slides', who)['slides']
        assert [row['slide_no'] for row in slides] == [1, 2]
        case['slide'] = slides[0]
        assert f.write(case, 'stain', who)['stainedBy'] == f.identities[who]['id']
        f.node(case, 'STAIN', who)
        case['slide'] = slides[1]
        assert f.write(case, 'batch', who)['completed'] == 1
        f.refused(case, 'stain', who, 5245)
        case['tech'] = f.write(case, 'new-tech', who)
        f.node(case, 'TECH_ORDER', who)
        # Existing warn policy still permits finishing without appended blocks.
        done = f.write(case, 'done', who)
        assert done['techDoneGate'] == 'warn' and done['sampledBlockCount'] == 0
        assert done['warnings'] and not done['stainedComplete']
        f.node(case, 'TECH_DONE', who)
        f.refused(case, 'done', who, 5268)
        case['tech'] = f.write(case, 'new-tech', who)
        f.refused(case, 'cancel', who, 5271, {'reason': '  '})
        assert f.write(case, 'cancel', who)['cancelled_by'] == f.identities[who]['id']
        f.node(case, 'TECH_CANCEL', who)
        assert f.source(case) == before_source and f.snapshot(case)['charges'] == before_charges
        f.write(case, 'diagnose', who)
        report = f.api('GET', f"/pathology/report/{case['specimen']['id']}/reports")
        assert report['primary']['pathologistId'] == f.identities[who]['id']
        assert report['primary']['reportIssuedAt'] is None and f.source(case) == before_source
        f.refused(case, 'diagnose', who, 4553)
    print('[2] Original three technical duties retain legacy collection/receipt, registry receipt, grossing/revision, blocks/slides/staining, technical-order completion/cancellation and first diagnosis; original errors and financial isolation remain')

    for who in SIGNERS:
        case = f.visit('报告双签' + who)
        f.write(case, 'diagnose', 'tech')
        before_money = f.snapshot(case)['charges']
        first = f.write(case, 'first-sign', who)
        assert first['firstSignerId'] == f.identities[who]['id']
        f.refused(case, 'second-sign', who, 5263)
        second = f.write(case, 'second-sign', 'doctor2')
        assert second['secondSignerId'] == f.identities['doctor2']['id']
        issued = f.write(case, 'issue', who)
        assert issued['doubleSignComplete'] and issued['orderExecuted'] and issued['partsPending'] == 0
        f.node(case, 'ISSUE', who)
        report_before = f.api('GET', f"/pathology/report/{case['specimen']['id']}/reports")
        supplement = f.write(case, 'supplement', who)
        assert supplement['seqNo'] == 1
        report_after = f.api('GET', f"/pathology/report/{case['specimen']['id']}/reports")
        assert report_after['primary'] == report_before['primary']
        assert report_after['supplements'][0]['signer_id'] == f.identities[who]['id']
        assert f.source(case)['status'] == 'EXECUTED' and f.snapshot(case)['charges'] == before_money
        f.refused(case, 'issue', who, 5261)
    print('[3] ADMIN/DOCTOR_OUTP retain signing, different-person double signature, issue and append-only supplements; original report and paid financial facts are preserved')

    # Multi-duty staff and ordinary role interchange remain valid collaborators.
    f.set_roles('mover', ['PHARMACIST', 'DOCTOR_OUTP'])
    multi = f.visit('多岗位兼容')
    f.write(multi, 'diagnose', 'mover')
    f.write(multi, 'first-sign', 'mover')
    f.set_roles('mover', ['TECHNICIAN'])
    physical = f.visit('未付实物救济', paid=False, registered=False)
    before_source = f.source(physical)
    f.register(physical, 'mover', manual=True)
    physical['block'] = f.write(physical, 'gross', 'mover')['blocks'][0]
    f.write(physical, 'new-tech', 'mover')
    f.write(physical, 'reject', 'mover')
    f.node(physical, 'REJECT', 'mover')
    f.refused(physical, 'gross', 'mover', 5221)
    assert f.source(physical) == before_source and f.source(physical)['status'] == 'CREATED'
    assert f.snapshot(physical)['charges'] == []
    for who in ['admin', 'tech', 'doctor', 'nurse', 'cashier', 'pharmacist', 'quality', 'mover']:
        f.api('GET', '/stats/overview', who=who)
    print('[4] Multi-duty/current three-duty interchange and unpaid physical-specimen rescue retain original behavior; all ordinary employee home pages remain usable')
    print('e2e-v137-pathology-live-duty passed: 21 public JWT write boundaries, original technical/signing duties, actors/processes, role compatibility, physical rescue, append-only reports and zero projected refusal writes. Actual lock-wait revocation/rollback is independently proved by Java and browser acceptance.')


if __name__ == '__main__':
    main()
