# -*- coding: utf-8 -*-
"""Real JWT role separation, author ownership and signed nursing-record immutability."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,new_patient,find_free_bed,discharge_cleanup

t=login();tokens={'admin':t};ids={};admissions=[];suffix=str(time.time_ns())[-10:]
for key,role in [('a','NURSE'),('b','NURSE'),('q','QUALITY'),('d','DOCTOR_OUTP'),('tech','TECHNICIAN'),('cash','CASHIER')]:
    tokens[key]=provision_user(t,'e2e92'+key+suffix,role,'V92'+key)
    me=ok(call('GET','/auth/me',token=tokens[key]),'identity');ids[key]=me['id']
    if key in ['a','b','q']:assert '/inpatient/nursing-record' in {m['path'] for m in me['menus']},me

def expect(code,method,path,body=None,who='admin'):
    try:r=call(method,path,body,tokens[who])
    except urllib.error.HTTPError as e:
        assert e.code==403,(path,e.code)
        r=json.load(e)
    assert r['code']==code,(who,path,r)
    return r.get('data')

def admit():
    p=new_patient(t,'V92虚构'+str(time.time_ns()),sex='U');bed=find_free_bed(t)
    a=ok(call('POST','/inpatient/admissions',{'patientId':p['id'],'deptId':1,'bedId':bed['id'],'deposit':0,'payMethod':'CASH'},t),'admit')
    admissions.append(a['id']);return a['id']

def body(adm,observation='V92虚构护理观察'):
    return {'admissionId':adm,'recordKind':'ROUNDS','observation':observation,'measure':'V92虚构措施'}

try:
    adm,other=admit(),admit()
    first=expect(0,'POST','/nursing/records',body(adm),who='a')['id']
    for who in ['b','admin','q']:
        rows=expect(0,'GET',f'/nursing/records?admissionId={adm}',who=who)
        row=next(x for x in rows if x['id']==first);assert row['nurse_id']==ids['a'] and not row['signed'],row
        expect(0,'GET',f'/nursing/records/gate-check?admissionId={adm}',who=who)
        printed=expect(0,'GET',f'/inpatient/admissions/{adm}/print/nursing-record',who=who);assert any(x['id']==first for x in printed['rows'])
        expect(1005,'PUT',f'/nursing/records/{first}',body(other,'forged'),who)
    expect(1005,'POST','/nursing/records',body(adm),'q')
    expect(1005,'POST',f'/nursing/records/{first}/sign',who='q')
    expect(4804,'POST',f'/nursing/records/{first}/sign',who='b')
    original=next(x for x in expect(0,'GET',f'/nursing/records?admissionId={adm}') if x['id']==first)
    assert original['observation']=='V92虚构护理观察' and not original['signed']
    expect(0,'PUT',f'/nursing/records/{first}',body(other,'V92本人修改'),who='a')
    assert any(x['id']==first and x['observation']=='V92本人修改' for x in expect(0,'GET',f'/nursing/records?admissionId={adm}'))
    assert not any(x['id']==first for x in expect(0,'GET',f'/nursing/records?admissionId={other}'))
    expect(0,'POST',f'/nursing/records/{first}/sign',who='a')
    expect(4803,'PUT',f'/nursing/records/{first}',body(adm,'signed tamper'),who='a')
    expect(4808,'POST',f'/nursing/records/{first}/sign',who='a')
    # Each existing writer still has a complete positive path, including administrators.
    for who in ['b','admin']:
        own=expect(0,'POST','/nursing/records',body(other),who)['id']
        expect(0,'PUT',f'/nursing/records/{own}',body(other,'V92本人补录'),who)
        expect(0,'POST',f'/nursing/records/{own}/sign',who=who)
    draft=expect(0,'POST','/nursing/records',body(adm),who='a')['id']
    for who in ['d','tech','cash']:
        expect(1005,'GET',f'/nursing/records?admissionId={adm}',who=who)
        expect(1005,'POST','/nursing/records',body(adm),who)
        expect(1005,'PUT',f'/nursing/records/{draft}',body(adm),who)
        expect(1005,'POST',f'/nursing/records/{draft}/sign',who=who)
    discharge_cleanup(t,adm);admissions.remove(adm)
    expect(4805,'POST','/nursing/records',body(adm),who='a')
    # Existing unsigned drafts remain amendable/signable after discharge; no new policy is imposed.
    expect(0,'PUT',f'/nursing/records/{draft}',body(adm,'V92出院后本人草稿补录'),who='a')
    expect(0,'POST',f'/nursing/records/{draft}/sign',who='a')
    for who in ['a','q']:
        printed=expect(0,'GET',f'/inpatient/admissions/{adm}/print/nursing-record',who=who)
        assert any(x['id']==draft and x['signed'] for x in printed['rows'])
    for who in tokens:
        expect(0,'GET','/auth/me',who=who);expect(0,'GET','/stats/overview',who=who)
    print('e2e-v92-nursing-record-integrity passed: quality read-only, author writes, frozen signatures, body-ID tampering, discharged compatibility and role usability')
finally:
    for adm in admissions:discharge_cleanup(t,adm)

