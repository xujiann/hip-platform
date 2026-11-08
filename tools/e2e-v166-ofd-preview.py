# -*- coding: utf-8 -*-
"""Public-API INP historical-version OFD-H preview regression.

All clinical and employee fixtures are created through normal application routes.
No source snapshot is edited through SQL. HIP_E2E_BASE / HIP_E2E_USER /
HIP_E2E_PASSWORD follow e2elib conventions. --base overrides the API base.
Binary and JSON proof files are written only when --evidence-dir is provided.
"""
import argparse
import datetime
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

import e2elib

NS = {'ofd': 'http://www.ofdspec.org/2016'}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def instant(value):
    return datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))


def xml(files, path):
    data = files[path]
    assert b'<!DOCTYPE' not in data.upper(), path
    return ET.fromstring(data)


def text(element, path):
    found = element.find(path, NS)
    assert found is not None and found.text is not None, path
    return found.text


def package_path(base, location):
    assert location and '\\' not in location and '://' not in location, location
    value = PurePosixPath(location)
    assert '..' not in value.parts, location
    return str(value).lstrip('/') if value.is_absolute() else str(PurePosixPath(base) / value)


class Fixture:
    def __init__(self):
        self.suffix = str(time.time_ns())[-13:]
        self.prefix = 'V166模拟' + self.suffix
        self.tokens = {'admin': e2elib.login()}
        self.identities = {'admin': self.api('GET', '/auth/me')}
        assert 'ADMIN' in self.identities['admin']['roles'], 'Fixture preparation requires current ADMIN'
        settings = self.api('GET', '/emr/versions/settings')
        assert settings['gate'] != 'off', 'Stored-version fixture requires the normal version recording gate'
        self.dept = self.api('POST', '/system/depts', {
            'code': 'V166C' + self.suffix, 'name': self.prefix + '临床科', 'type': 'CLINICAL', 'sortNo': 9999,
        })['id']
        self.ward = self.api('POST', '/system/depts', {
            'code': 'V166W' + self.suffix, 'name': self.prefix + '护理单元', 'type': 'NURSING', 'sortNo': 9999,
        })['id']
        for who in ['doctor', 'other']:
            name = 'v166_' + who + '_' + self.suffix
            self.tokens[who] = e2elib.provision_user(self.tokens['admin'], name, 'DOCTOR_OUTP',
                                                    self.prefix + who)
            identity = self.api('GET', '/auth/me', who=who)
            self.api('PUT', '/system/users/' + str(identity['id']), {
                'username': name, 'realName': identity['realName'], 'deptId': self.dept,
                'roleCodes': ['DOCTOR_OUTP'],
            })
            self.identities[who] = self.api('GET', '/auth/me', who=who)
            assert self.identities[who]['deptId'] == self.dept
        self.patient = self.api('POST', '/patients', {'name': self.prefix + '患者', 'sex': 'U'})
        self.api('POST', '/inpatient/beds', {'wardId': self.ward, 'count': 1})
        beds = self.api('GET', '/inpatient/beds?wardId=' + str(self.ward))
        assert len(beds) == 1 and beds[0]['status'] == 'FREE'
        self.admission = self.api('POST', '/inpatient/admissions', {
            'patientId': self.patient['id'], 'deptId': self.dept, 'bedId': beds[0]['id'],
            'doctorId': self.identities['doctor']['id'], 'deposit': 0, 'payMethod': 'CASH',
        })
        self.admission_path = '/inpatient/admissions/' + str(self.admission['id'])
        self.title_a = self.prefix + '历史病程甲'
        self.body_a = self.prefix + '历史正文甲\n第二行医嘱 <复核> & 观察，末尾校验字龘。'
        self.title_b = self.prefix + '当前病程乙'
        self.body_b = self.prefix + '当前正文乙，不可替代第一版。'
        self.record = self.api('POST', self.admission_path + '/records', {
            'recordType': 'PROGRESS', 'title': self.title_a, 'content': self.body_a,
        }, who='doctor')
        self.version_path = '/emr/versions/INP/' + str(self.record['id'])
        self.version_a = self.api('GET', self.version_path + '/versions/1')
        assert self.version_a['versionNo'] == 1
        assert self.version_a['fields']['content']['value'] == self.body_a
        assert self.version_a['fields']['title']['value'] == self.title_a
        self.raw_a = self.version_a['content']
        assert sha256(self.raw_a.encode('utf-8')) == self.version_a['contentHash']
        assert len(self.raw_a) == self.version_a['contentLen']  # The synthetic payload uses BMP characters.
        self.api('PUT', self.admission_path + '/records/' + str(self.record['id']), {
            'title': self.title_b, 'content': self.body_b,
        }, who='doctor')
        self.version_b = self.api('GET', self.version_path + '/versions/2')
        assert self.version_b['fields']['content']['value'] == self.body_b
        assert self.version_b['contentHash'] != self.version_a['contentHash']
        assert self.api('GET', self.version_path + '/versions/1') == self.version_a
        self.preview_path = self.version_path + '/versions/1/ofd-preview'

    def api(self, method, path, body=None, who='admin'):
        result = e2elib.call(method, path, body, self.tokens[who])
        assert isinstance(result, dict) and result.get('code') == 0, (method, path, result)
        return result.get('data')

    def source_projection(self):
        return {
            'workspace': self.api('GET', self.admission_path + '/workspace'),
            'records': self.api('GET', self.admission_path + '/records'),
            'versions': self.api('GET', self.version_path),
            'version1': self.api('GET', self.version_path + '/versions/1'),
            'version2': self.api('GET', self.version_path + '/versions/2'),
        }

    def export(self, who, expected_code=0, *, accept='application/ofd, application/json',
               path=None, expected_status=200, private_text=()):
        request = urllib.request.Request(e2elib.BASE + (path or self.preview_path), method='GET', headers={
            'Authorization': 'Bearer ' + self.tokens[who], 'Accept': accept,
        })
        try:
            response = urllib.request.urlopen(request, timeout=90)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status, headers, body = response.status, response.headers, response.read()
        if expected_code:
            assert status == expected_status, (who, status, body[:200])
            assert headers.get_content_type() == 'application/json'
            assert headers.get('Content-Disposition') is None
            failure = json.loads(body.decode('utf-8'))
            assert failure['code'] == expected_code and failure.get('data') is None, failure
            for private in [self.patient['name'], self.body_a, self.body_b, *private_text]:
                assert private not in body.decode('utf-8'), 'A refusal exposed clinical source data'
            return None
        assert status == 200, (who, status, body[:200])
        assert headers.get_content_type() == 'application/ofd', dict(headers)
        assert 'no-store' in headers.get('Cache-Control', '')
        expected_name = 'emr-INP-' + str(self.record['id']) + '-v1-preview.ofd'
        assert headers.get('Content-Disposition') == 'attachment; filename="' + expected_name + '"'
        assert body.startswith(b'PK\x03\x04'), 'The response must be a real OFD ZIP package'
        result = inspect_package(self, body, who)
        result['body'] = body
        return result


def inspect_package(fixture, document, who):
    with zipfile.ZipFile(io.BytesIO(document)) as archive:
        assert archive.testzip() is None, 'ZIP CRC failed'
        names = [item.filename for item in archive.infolist() if not item.is_dir()]
        assert len(names) == len(set(names)), 'Duplicate ZIP entries'
        assert all(not name.startswith('/') and '..' not in PurePosixPath(name).parts for name in names)
        files = {name: archive.read(name) for name in names}
    ofd = xml(files, 'OFD.xml')
    assert ofd.tag == '{' + NS['ofd'] + '}OFD' and ofd.get('DocType') == 'OFD-H'
    bodies = ofd.findall('ofd:DocBody', NS)
    assert len(bodies) == 1
    assert not any(re.match(r'Doc_[1-9][0-9]*/', name) for name in files)
    assert bodies[0].find('ofd:Signatures', NS) is None
    assert not any('/Signs/' in name or name.endswith(('Signature.xml', 'Signatures.xml', 'SignedValue.dat'))
                   for name in files)
    document_id = text(bodies[0], 'ofd:DocInfo/ofd:DocID')
    assert re.fullmatch(r'[0-9a-fA-F]{32}', document_id)
    document_path = package_path('', text(bodies[0], 'ofd:DocRoot'))
    assert document_path == 'Doc_0/Document.xml'
    doc = xml(files, document_path)
    attachment_path = package_path('Doc_0', text(doc, 'ofd:Attachments'))
    assert attachment_path == 'Doc_0/Attachs/Attachments.xml'
    attachment_list = xml(files, attachment_path)
    attachments = attachment_list.findall('ofd:Attachment', NS)
    assert len(attachments) == 1
    assert attachments[0].get('Name') == 'source-metadata.json'
    assert attachments[0].get('Format') == 'JSON'
    metadata_path = package_path('Doc_0/Attachs', text(attachments[0], 'ofd:FileLoc'))
    assert metadata_path == 'Doc_0/Attachs/Attach_0.json'
    metadata_bytes = files[metadata_path]
    metadata = json.loads(metadata_bytes.decode('utf-8'))
    custom = bodies[0].find("ofd:DocInfo/ofd:CustomDatas/ofd:CustomData[@Name='SourceMetadata']", NS)
    assert custom is not None and custom.text.encode('utf-8') == metadata_bytes
    assert metadata['document']['id'] == document_id
    assert metadata['document']['type'] == 'OFD-H'
    assert metadata['document']['status'] == 'UNSIGNED_PREVIEW'
    assert metadata['document']['confidentiality'] == '医疗敏感'
    assert metadata['pendingChecks']
    source = metadata['source']
    expected = fixture.version_a
    assert source['emrType'] == 'INP' and source['emrId'] == fixture.record['id']
    assert source['versionNo'] == 1 and source['versionId'] == expected['versionId']
    assert source['savedBy'] == fixture.identities['doctor']['id'] == expected['savedBy']
    assert instant(source['savedAt']) == instant(expected['savedAt'])
    assert source['sourceAction'] == expected['source']
    assert source['snapshotRaw'] == fixture.raw_a
    assert source['snapshotFields'] == json.loads(fixture.raw_a)
    assert source['contentHash'] == expected['contentHash'] == sha256(fixture.raw_a.encode('utf-8'))
    assert source['contentLen'] == expected['contentLen'] == len(fixture.raw_a)
    context = metadata['currentContext']
    assert context['patientId'] == fixture.patient['id'] and context['patientName'] == fixture.patient['name']
    assert context['patientNo'] == fixture.patient['patientNo']
    assert context['admissionId'] == fixture.admission['id']
    assert context['admissionNo'] == fixture.admission['admissionNo']
    assert context['description'] and context['capturedAt']
    assert metadata['creator']['id'] == fixture.identities[who]['id']
    instant(metadata['header']['generationTime'])
    instant(metadata['header']['modifiedTime'])
    public_resources = package_path('Doc_0', text(doc, 'ofd:CommonData/ofd:PublicRes'))
    resources = xml(files, public_resources)
    fonts = resources.findall('ofd:Fonts/ofd:Font', NS)
    assert fonts, 'The preview must embed its Chinese font'
    resource_base = str(PurePosixPath(public_resources).parent)
    if resources.get('BaseLoc'):
        resource_base = package_path(resource_base, resources.get('BaseLoc'))
    for font in fonts:
        font_path = package_path(resource_base, text(font, 'ofd:FontFile'))
        assert font_path in files and len(files[font_path]) > 1024
        assert files[font_path][:4] in (b'OTTO', b'\x00\x01\x00\x00')
    page_text = []
    pages = doc.findall('ofd:Pages/ofd:Page', NS)
    assert pages, 'The package needs actual rendered pages'
    for page in pages:
        content = xml(files, package_path('Doc_0', page.get('BaseLoc')))
        page_text.extend(node.text or '' for node in content.findall('.//ofd:TextCode', NS))
    rendered = ''.join(page_text)
    flatten = lambda value: value.replace('\r', '').replace('\n', '')
    assert flatten(fixture.body_a) in flatten(rendered), 'Historical body was truncated or replaced'
    assert fixture.title_a in rendered and '未签署预览' in rendered
    assert fixture.body_b not in rendered and fixture.title_b not in rendered
    assert fixture.body_b not in metadata_bytes.decode('utf-8')
    assert fixture.title_b not in metadata_bytes.decode('utf-8')
    return {'actor': who, 'document_id': document_id, 'sha256': sha256(document),
            'bytes': len(document), 'pages': len(pages), 'entries': len(files),
            'metadata_path': metadata_path, 'source_hash': source['contentHash'],
            'source_version_id': source['versionId'], 'metadata': metadata}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default=e2elib.BASE, help='API base, default HIP_E2E_BASE or localhost:8080/api')
    parser.add_argument('--evidence-dir', type=Path, help='Write synthetic OFD files and receipt only when specified')
    args = parser.parse_args()
    e2elib.BASE = args.base.rstrip('/')
    evidence = args.evidence_dir.resolve() if args.evidence_dir else None
    if evidence is not None:
        assert 'onedrive' not in str(evidence).lower(), 'Project evidence must remain outside OneDrive'
    fixture = Fixture()
    before = fixture.source_projection()
    previews = []
    for who in ['admin', 'doctor']:
        previews.append(fixture.export(who, accept='application/ofd' if who == 'doctor'
                                       else 'application/ofd, application/json'))
        assert fixture.source_projection() == before, 'An export changed the exposed source or version projection'
    fixture.export('other', 4036)
    fixture.export('other', 4036, accept='application/ofd')
    fixture.export('doctor', 5703, accept='application/ofd',
                   path=fixture.version_path + '/versions/999/ofd-preview')
    fixture.export('doctor', 4000, accept='application/ofd',
                   path='/emr/versions/INP/not-a-long/versions/1/ofd-preview')
    assert fixture.source_projection() == before, 'A forbidden export changed the source or versions'
    # The public attending change proves that the same original doctor's token
    # loses export access, while the new attending can read the selected old version.
    fixture.api('PUT', fixture.admission_path + '/attending-doctor',
                {'doctorId': fixture.identities['other']['id']})
    after_transfer = fixture.source_projection()
    assert after_transfer['records'] == before['records']
    assert after_transfer['versions'] == before['versions']
    assert after_transfer['version1'] == before['version1']
    assert after_transfer['version2'] == before['version2']
    fixture.export('doctor', 4036)
    fixture.export('doctor', 4036, accept='application/ofd')
    previews.append(fixture.export('other', accept='application/ofd'))
    assert fixture.source_projection() == after_transfer
    # A real unsupported display character exercises 5709 without tampering with immutable snapshots.
    unsupported_title = fixture.prefix + '模拟生成失败文书'
    unsupported_body = fixture.prefix + '模拟正文含补充平面字\U00020bb7'
    unsupported = fixture.api('POST', fixture.admission_path + '/records', {
        'recordType': 'PROGRESS', 'title': unsupported_title, 'content': unsupported_body,
    }, who='other')
    unsupported_path = '/emr/versions/INP/' + str(unsupported['id']) + '/versions/1'
    unsupported_before = fixture.api('GET', unsupported_path)
    before_failure = fixture.source_projection()
    fixture.export('other', 5709, accept='application/ofd', path=unsupported_path + '/ofd-preview',
                   private_text=(unsupported_title, unsupported_body))
    assert fixture.api('GET', unsupported_path) == unsupported_before
    assert fixture.source_projection() == before_failure
    # The same valid employee/token loses the export role, remains authenticated, and receives 403/1005.
    actor = fixture.identities['other']
    fixture.api('PUT', '/system/users/' + str(actor['id']), {
        'username': actor['username'], 'realName': actor['realName'], 'deptId': fixture.dept,
        'roleCodes': ['NURSE'],
    })
    before_role_denial = fixture.source_projection()
    fixture.export('other', 1005, accept='application/ofd', expected_status=403)
    still_logged_in = fixture.api('GET', '/auth/me', who='other')
    assert still_logged_in['id'] == actor['id'] and still_logged_in['roles'] == ['NURSE']
    assert fixture.source_projection() == before_role_denial
    receipt = {'public_api_only': True, 'record_id': fixture.record['id'],
               'admission_id': fixture.admission['id'], 'patient_id': fixture.patient['id'],
               'selected_version': 1, 'current_version': 2, 'read_only_projections': True,
               'successful_exports': len(previews), 'known_object_refusals': 4,
               'strict_accept_checks': {'successes': 2, 'object_refusals': 2, 'missing_version': 5703,
                                        'bad_parameter': 4000, 'generation_failure': 5709,
                                        'lost_role_http': 403, 'lost_role_code': 1005, 'token_still_valid': True},
               'previews': [{key: value for key, value in preview.items() if key not in ('body', 'metadata')}
                            for preview in previews]}
    if evidence is not None:
        evidence.mkdir(parents=True, exist_ok=True)
        for preview in previews:
            (evidence / (preview['actor'] + '-preview.ofd')).write_bytes(preview['body'])
            (evidence / (preview['actor'] + '-metadata.json')).write_text(
                json.dumps(preview['metadata'], ensure_ascii=False, indent=2), encoding='utf-8')
        (evidence / 'receipt.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS v166 actual OFD-H historical preview:', json.dumps(receipt, ensure_ascii=False))


if __name__ == '__main__':
    main()
