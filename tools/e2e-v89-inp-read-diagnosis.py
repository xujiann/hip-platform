# -*- coding: utf-8 -*-
"""Inpatient read scopes and diagnostic handoff with real role-bearing JWTs."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,new_patient,find_free_bed,discharge_cleanup,today_bj

t=login();suffix=str(time.time_ns())[-10:];tokens={};ids={};admissions=[]
for key,role in [('a','DOCTOR_OUTP'),('b','DOCTOR_OUTP'),('n','NURSE'),('c','CASHIER'),('q','QUALITY')]:
    tokens[key]=provision_user(t,'e2e89'+key+suffix,role,'V89'+key)
    ids[key]=ok(call('GET','/auth/me',token=tokens[key]),'identity')['id']
item=next(x for x in ok(call('GET','/masterdata/charge-items',token=t),'items') if x['category']=='TREAT' and x.get('enabled',True))
date=str(today_bj())

def response(method,path,body=None,token=None):
    try:return call(method,path,body,token)
    except urllib.error.HTTPError as ex:
        assert ex.code==403,(path,ex.code)
        return json.load(ex)

def read(path,token):return response('GET',path,token=token)

def paths(adm):
    base=f'/inpatient/admissions/{adm}'
    return [base+'/daily-fees?date='+date,base+'/print/daily-fee?date='+date,base+'/account',base+'/transfers',base+'/interim-settlements',base+'/emr-integrity',base+'/vitals',base+'/print/temp-sheet',f'/inpatient/diagnoses?admissionId={adm}']

def admit(owner):
    p=new_patient(t,'V89虚构'+str(time.time_ns()),sex='U');bed=find_free_bed(t)
    body={'patientId':p['id'],'deptId':1,'bedId':bed['id'],'deposit':1000,'payMethod':'CASH'}
    if owner is not None:body['doctorId']=owner
    adm=ok(call('POST','/inpatient/admissions',body,t),'admit');admissions.append(adm['id'])
    return adm

def cleanup(adm):
    for row in ok(read(f'/inpatient/admissions/{adm}/workspace',t),'cleanup orders')['orders']:
        if row.get('orderNature')=='LONG' and not row.get('stopAt'):ok(call('POST',f"/inpatient/orders/{row['id']}/stop",{},t),'stop')
        elif row['status']=='CREATED':ok(call('PUT',f"/inpatient/orders/{row['id']}/cancel",token=t),'cancel')
    discharge_cleanup(t,adm)

try:
    own=admit(ids['a']);foreign=admit(ids['b']);free=admit(None)
    for adm in [own,foreign,free]:
        ok(call('POST',f"/inpatient/admissions/{adm['id']}/orders",{'lines':[{'orderType':'TREAT','itemId':item['id'],'qty':1,'orderNature':'TEMP'},{'orderType':'TREAT','itemId':item['id'],'qty':1,'orderNature':'LONG'}]},t),'orders')
    ok(call('POST','/inpatient/exec-lines/generate?date='+date,token=t),'exec lines')
    ok(call('POST',f"/inpatient/admissions/{own['id']}/vitals",{'temperature':36.5,'pulse':80},tokens['n']),'nurse vitals')
    for path in paths(own['id']):
        assert read(path,tokens['b'])['code']==1005,path
        assert read(path,tokens['a'])['code']==0,path
        assert read(path,t)['code']==0,path
        if '/diagnoses?' not in path:assert read(path,tokens['n'])['code']==0,path
    for path in paths(own['id']):
        expected=0 if '/print/daily-fee' in path or path.endswith('/account') or path.endswith('/interim-settlements') else 1005
        assert read(path,tokens['c'])['code']==expected,path
    assert read(f"/inpatient/diagnoses?admissionId={own['id']}",tokens['q'])['code']==0
    assert read(f"/inpatient/diagnoses?admissionId={own['id']}",tokens['n'])['code']==1005
    for path in ['/inpatient/admissions','/inpatient/admissions?doctorId='+str(ids['b']),'/inpatient/orders/search?deptId=1','/inpatient/orders/pending','/inpatient/exec-lines?date='+date]:
        data=ok(read(path,tokens['a']),'scoped list')
        assert foreign['admissionNo'] not in json.dumps(data),path
    for ward in ok(call('GET','/system/depts',token=t),'wards'):
        if ward['type']=='NURSING':
            beds=ok(read(f"/inpatient/beds?wardId={ward['id']}",tokens['a']),'scoped bed labels')
            assert foreign['patientName'] not in json.dumps(beds,ensure_ascii=False)
    rows=ok(read('/inpatient/admissions',tokens['a']),'admissions')
    assert {own['id'],free['id']} <= {r['id'] for r in rows}
    for token,icd in [(tokens['a'],'E11'),(tokens['q'],'N18')]:
        ok(call('POST','/inpatient/diagnoses',{'admissionId':own['id'],'icd':icd,'name':'V89诊断'},token),'diagnosis')
    before=ok(read(f"/inpatient/diagnoses?admissionId={own['id']}",t),'before')
    ok(call('PUT',f"/inpatient/admissions/{own['id']}/attending-doctor",{'doctorId':ids['b']},tokens['a']),'handoff')
    assert call('POST','/inpatient/diagnoses',{'admissionId':own['id'],'icd':'I10','name':'V89旧主管'},tokens['a'])['code']==9018
    assert ok(read(f"/inpatient/diagnoses?admissionId={own['id']}",t),'unchanged')==before
    for path in paths(own['id']):assert read(path,tokens['a'])['code']==1005,path
    ok(call('POST','/inpatient/diagnoses',{'admissionId':own['id'],'icd':'I10','name':'V89新主管'},tokens['b']),'new owner')
    assert call('POST','/inpatient/diagnoses',{'admissionId':own['id'],'icd':'I10','name':'V89重复'},tokens['q'])['code']==9015
    ok(call('POST','/inpatient/diagnoses',{'admissionId':own['id'],'icd':'K35','name':'V89编码员'},tokens['q']),'coder after duplicate')
    assert ok(call('GET','/auth/me',token=tokens['a']),'still logged in')['id']==ids['a']
    print('e2e-v89-inp-read-diagnosis passed: scoped reads/collections, existing nursing/billing/coding roles, handoff and duplicate recovery')
finally:
    for adm in reversed(admissions):cleanup(adm)
