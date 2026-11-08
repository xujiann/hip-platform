# -*- coding: utf-8 -*-
"""Public-API retained INP OFD documents: generate, list and retrieve exact original bytes.

Uses the v166 public fixture and full unsigned OFD-H package inspection. No SQL,
source tampering, signature bypass or non-public setup routes are used.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import urllib.error
import urllib.request

import e2elib

_spec = importlib.util.spec_from_file_location('v166_ofd_preview', Path(__file__).with_name('e2e-v166-ofd-preview.py'))
v166 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(v166)
KEYS = {'documentId', 'generatedAt', 'generatedBy', 'status', 'byteLength', 'sha256'}


class RetentionFixture(v166.Fixture):
    def request(self, method, path, who='doctor', expected_code=0, expected_status=200):
        request = urllib.request.Request(e2elib.BASE + path, method=method, headers={
            'Authorization': 'Bearer ' + self.tokens[who],
            'Accept': 'application/ofd' if path.endswith('/content') else 'application/json',
        })
        try:
            response = urllib.request.urlopen(request, timeout=180)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status, headers, body = response.status, response.headers, response.read()
        assert status == expected_status, (method, path, status, body[:200])
        assert 'no-store' in headers.get('Cache-Control', ''), dict(headers)
        if expected_code:
            assert headers.get_content_type() == 'application/json', dict(headers)
            assert headers.get('Content-Disposition') is None
            failure = json.loads(body.decode('utf-8'))
            assert set(failure) == {'code', 'message', 'data'}, failure
            assert failure['code'] == expected_code and failure['data'] is None, failure
            for private in [self.patient['name'], self.body_a, self.body_b, self.raw_a]:
                assert private not in body.decode('utf-8'), 'A refusal exposed clinical source data'
            return None
        if path.endswith('/content'):
            assert headers.get_content_type() == 'application/ofd', dict(headers)
            assert path.split('/')[-2] in headers.get('Content-Disposition', ''), dict(headers)
            assert body.startswith(b'PK\x03\x04'), 'Stored download must be an actual OFD ZIP'
            return body
        assert headers.get_content_type() == 'application/json', dict(headers)
        assert headers.get('Content-Disposition') is None
        envelope = json.loads(body.decode('utf-8'))
        assert envelope['code'] == 0, envelope
        return envelope['data']

    def document(self, base, who):
        value = self.request('POST', base, who)
        assert set(value) == KEYS, value
        assert value['generatedBy'] == self.identities[who]['id']
        assert value['status'] == 'UNSIGNED_PREVIEW'
        assert len(value['documentId']) == 32
        v166.instant(value['generatedAt'])
        assert 0 < value['byteLength'] <= 32 * 1024 * 1024
        assert len(value['sha256']) == 64
        return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default=e2elib.BASE)
    parser.add_argument('--evidence-dir', type=Path)
    args = parser.parse_args()
    e2elib.BASE = args.base.rstrip('/')
    evidence = args.evidence_dir.resolve() if args.evidence_dir else None
    if evidence:
        assert 'onedrive' not in str(evidence).lower(), 'Project evidence must remain outside OneDrive'
    fixture = RetentionFixture()
    base = fixture.version_path + '/versions/1/ofd-documents'
    before = fixture.source_projection()
    assert fixture.request('GET', base)['items'] == []
    generated = [(fixture.document(base, who), who) for who in ['doctor', 'admin']]
    assert generated[0][0]['documentId'] != generated[1][0]['documentId']
    items = fixture.request('GET', base)['items']
    assert items == [value for value, who in reversed(generated)], items
    assert all(set(item) == KEYS for item in items)
    original_bytes = {}
    proofs = []
    for item, creator in generated:
        path = base + '/' + item['documentId'] + '/content'
        body = fixture.request('GET', path)
        assert len(body) == item['byteLength']
        assert v166.sha256(body) == item['sha256']
        proof = v166.inspect_package(fixture, body, creator)
        assert proof['document_id'] == item['documentId']
        assert proof['sha256'] == item['sha256']
        original_bytes[item['documentId']] = body
        proofs.append({key: value for key, value in proof.items() if key != 'metadata'})
        assert fixture.request('GET', path, 'admin') == body, 'Retrieval must return original bytes'
    assert fixture.source_projection() == before, 'Generation/list/download changed clinical or historical sources'
    first_id = generated[0][0]['documentId']
    first_path = base + '/' + first_id + '/content'
    fixture.request('POST', base, 'other', 4036)
    fixture.request('GET', base, 'other', 4036)
    fixture.request('GET', first_path, 'other', 4036)
    fixture.request('GET', base + '/' + '0' * 32 + '/content', expected_code=5711)
    fixture.request('GET', fixture.version_path + '/versions/2/ofd-documents/' + first_id + '/content',
                    expected_code=5711)
    another = fixture.api('POST', fixture.admission_path + '/records', {
        'recordType': 'PROGRESS', 'title': fixture.prefix + '另一文书', 'content': fixture.prefix + '隔离正文',
    }, who='doctor')
    fixture.request('GET', '/emr/versions/INP/' + str(another['id']) + '/versions/1/ofd-documents/'
                    + first_id + '/content', expected_code=5711)
    assert fixture.request('GET', base)['items'] == items
    # A current attending change revokes all three routes for the original doctor.
    fixture.api('PUT', fixture.admission_path + '/attending-doctor',
                {'doctorId': fixture.identities['other']['id']})
    fixture.request('POST', base, 'doctor', 4036)
    fixture.request('GET', base, 'doctor', 4036)
    fixture.request('GET', first_path, 'doctor', 4036)
    assert fixture.request('GET', base, 'other')['items'] == items
    assert fixture.request('GET', first_path, 'other') == original_bytes[first_id]
    # Existing JWT remains authenticated after real role removal, but cannot read documents.
    actor = fixture.identities['other']
    fixture.api('PUT', '/system/users/' + str(actor['id']), {
        'username': actor['username'], 'realName': actor['realName'], 'deptId': fixture.dept,
        'roleCodes': ['NURSE'],
    })
    fixture.request('POST', base, 'other', 1005, 403)
    fixture.request('GET', base, 'other', 1005, 403)
    fixture.request('GET', first_path, 'other', 1005, 403)
    assert fixture.api('GET', '/auth/me', who='other')['roles'] == ['NURSE']
    assert fixture.request('GET', base, 'admin')['items'] == items
    assert fixture.request('GET', first_path, 'admin') == original_bytes[first_id]
    receipt = {'public_api_only': True, 'unsigned_ofdh_inspection': True,
               'record_id': fixture.record['id'], 'selected_version': 1,
               'appended_documents': [item for item, who in generated],
               'original_bytes_verified': True, 'source_projection_preserved': True,
               'cross_record_and_version_refused': 5711, 'current_object_refused': 4036,
               'current_role_refused': 1005, 'proofs': proofs}
    if evidence:
        evidence.mkdir(parents=True, exist_ok=True)
        for document_id, body in original_bytes.items():
            (evidence / (document_id + '.ofd')).write_bytes(body)
        (evidence / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS v168 retained OFD documents:', json.dumps(receipt, ensure_ascii=False))


if __name__ == '__main__':
    main()
