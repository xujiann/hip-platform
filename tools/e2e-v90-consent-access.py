# -*- coding: utf-8 -*-
"""Real JWT consent ownership and physician-signing duties; synthetic admissions only."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,new_patient,find_free_bed,discharge_cleanup

t=login();suffix=str(time.time_ns())[-10:];tokens={};ids={};admissions=[]
for key,role in [('a','DOCTOR_OUTP'),('b','DOCTOR_OUTP'),('n','NURSE'),('q','QUALITY'),('c','CASHIER')]:
    tokens[key]=provision_user(t,'e2e90'+key+suffix,role,'V90'+key)
    ids[key]=ok(call('GET','/auth/me',token=tokens[key]),'identity')['id']

def response(method,path,body=None,token=None):
    try:return call(method,path,body,token)
    except urllib.error.HTTPError as ex:
        assert ex.code==403,(path,ex.code)
        return json.load(ex)

def create(adm,token):
    return call('POST','/emr/consents',{'admissionId':adm,'consentType':'SURGERY','content':'V90虚构知情同意内容'},token)

def admit(owner):
    p=new_patient(t,'V90虚构'+str(time.time_ns()),sex='U');bed=find_free_bed(t)
    body={'patientId':p['id'],'deptId':1,'bedId':bed['id'],'deposit':0,'payMethod':'CASH'}
    if owner:body['doctorId']=owner
    a=ok(call('POST','/inpatient/admissions',body,t),'admit');admissions.append(a['id']);return a['id']

try:
    own=admit(ids['a']);foreign=admit(ids['b']);free=admit(None)
    c=ok(create(own,tokens['a']),'owner create')['id']
    other=ok(create(foreign,t),'admin create')['id']
    assert response('GET',f'/emr/consents/{c}',token=tokens['b'])['code']==1005
    assert response('GET',f'/emr/consents?admissionId={own}',token=tokens['b'])['code']==1005
    assert create(own,tokens['b'])['code']==9018
    assert call('POST',f'/emr/consents/{c}/patient-sign',{'patientSign':'越权'},tokens['b'])['code']==9018
    assert call('PUT',f'/emr/consents/{c}/revoke',token=tokens['b'])['code']==9018
    for token in [tokens['a'],tokens['n'],t]:ok(response('GET',f'/emr/consents/{c}',token=token),'allowed read')
    rows=ok(call('GET','/emr/consents',token=tokens['a']),'scoped default')
    assert c in [r['id'] for r in rows] and other not in [r['id'] for r in rows]
    ok(call('POST',f'/emr/consents/{c}/patient-sign',{'patientSign':'V90虚构患者'},tokens['n']),'nurse records patient signature')
    for who,code in [('b',9018),('n',1005),('q',1005),('c',1005)]:
        assert response('POST',f'/emr/consents/{c}/doctor-sign',{},tokens[who])['code']==code,who
    before=ok(call('GET',f'/emr/consents/{c}',token=t),'before')
    assert before['status']=='PATIENT_SIGNED' and before['doctor_sign'] is None
    ok(call('PUT',f'/inpatient/admissions/{own}/attending-doctor',{'doctorId':ids['b']},tokens['a']),'handoff')
    assert call('POST',f'/emr/consents/{c}/doctor-sign',{},tokens['a'])['code']==9018
    assert ok(call('GET',f'/emr/consents/{c}',token=t),'unchanged')==before
    ok(call('POST',f'/emr/consents/{c}/doctor-sign',{},tokens['b']),'new owner signs')
    assert call('POST',f'/emr/consents/{c}/doctor-sign',{},tokens['b'])['code']==9114
    assert call('PUT',f'/emr/consents/{c}/revoke',token=tokens['n'])['code']==9114
    ok(call('PUT',f'/emr/consents/{c}/revoke',token=t),'admin revokes signed')
    nurse=ok(create(foreign,tokens['n']),'nurse draft')['id']
    ok(call('PUT',f'/emr/consents/{nurse}/revoke',token=tokens['n']),'nurse revoke draft')
    ok(create(free,tokens['a']),'unassigned create')
    assert call('POST','/emr/consents',{'admissionId':own,'registrationId':1,'consentType':'SURGERY','content':'x'},t)['code']==9110
    for who in ['a','b','n']:ok(call('GET','/auth/me',token=tokens[who]),'still logged in')
    print('e2e-v90-consent-access passed: scoped reads/writes, nursing workflow, physician signature, handoff and state machine')
finally:
    for adm in reversed(admissions):discharge_cleanup(t,adm)
