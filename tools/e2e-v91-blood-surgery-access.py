# -*- coding: utf-8 -*-
"""Real JWTs: clinical decisions, blood administration and surgery execution scopes."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,new_patient,find_free_bed,discharge_cleanup

t=login();tokens={'admin':t};ids={};admissions=[];suffix=str(time.time_ns())[-10:]
for key,role in [('a','DOCTOR_OUTP'),('b','DOCTOR_OUTP'),('n','NURSE'),('tech','TECHNICIAN'),('q','QUALITY'),('cash','CASHIER')]:
    tokens[key]=provision_user(t,'e2e91'+key+suffix,role,'V91'+key)
    ids[key]=ok(call('GET','/auth/me',token=tokens[key]),'identity')['id']

def response(method,path,body=None,who='admin'):
    try:return call(method,path,body,tokens[who])
    except urllib.error.HTTPError as ex:
        assert ex.code==403,(path,ex.code)
        return json.load(ex)

def expect(code,method,path,body=None,who='admin'):
    result=response(method,path,body,who);assert result['code']==code,(who,path,result);return result.get('data')

def admit(owner):
    p=new_patient(t,'V91虚构'+str(time.time_ns()),sex='U');bed=find_free_bed(t)
    body={'patientId':p['id'],'deptId':1,'bedId':bed['id'],'deposit':0,'payMethod':'CASH'}
    if owner:body['doctorId']=owner
    a=ok(call('POST','/inpatient/admissions',body,t),'admission');admissions.append(a['id']);return a['id']

def surgery(adm,who='admin'):
    expect(0,'POST','/inpatient/surgeries',{'admissionId':adm,'procedureName':'V91虚构手术','anesthesiaType':'全身麻醉'},who)
    return next(r['id'] for r in expect(0,'GET','/inpatient/surgeries') if r['admission_id']==adm)

def blood(adm,who='admin'):
    expect(0,'POST','/inpatient/blood/applies',{'admissionId':adm,'product':'RBC','volumeMl':200,'reason':'V91虚构'},who)
    return next(r['id'] for r in expect(0,'GET','/inpatient/blood/applies') if r['admission_id']==adm)

try:
    own=admit(ids['a']);other=admit(ids['b']);free=admit(None)
    s=surgery(own,'a');foreign=surgery(other);bid=blood(own,'a');foreign_blood=blood(other)
    base='/surgery/intraop/'
    bodies={'tubes':{'surgeryId':s,'tubeType':'尿管'},'transfusions':{'surgeryId':s,'productType':'RBC','volumeMl':200},'events':{'surgeryId':s,'eventType':'RESCUE','detail':'V91虚构'}}
    child={kind:expect(0,'POST',base+kind,body)['id'] for kind,body in bodies.items()}
    expect(0,'POST','/anes/records',{'surgeryId':s,'phase':'INTRA','hr':80})
    before=expect(0,'GET',base+f'summary?surgeryId={s}')
    for path in [f'/inpatient/surgeries/{s}/detail',base+f'summary?surgeryId={s}',base+f'tubes?surgeryId={s}',base+f'transfusions?surgeryId={s}',base+f'events?surgeryId={s}',f'/anes/records?surgeryId={s}',f'/anes/records/pacu-status?surgeryId={s}']:
        expect(1005,'GET',path,who='b');expect(0,'GET',path,who='a');expect(0,'GET',path,who='tech')
    for kind,body in bodies.items():
        expect(9018,'POST',base+kind,body,'b')
        forged=dict(body,surgeryId=foreign)
        expect(9018,'PUT',base+kind+'/'+str(child[kind]),forged,'b')
        expect(9018,'DELETE',base+kind+'/'+str(child[kind]),who='b')
    for action,body in [('complete',{'opNote':'x','anesNote':'x'}),('cancel',{'stage':'APPLY','reason':'x'}),('timepoint',{'stage':'IN_ROOM'}),('op-info',{'roomNo':'OR91'})]:expect(9018,'PUT',f'/inpatient/surgeries/{s}/{action}',body,'b')
    expect(9018,'POST','/anes/records',{'surgeryId':s,'phase':'INTRA','hr':80},'b')
    expect(9018,'POST','/inpatient/blood/applies',{'admissionId':own,'product':'RBC','volumeMl':200},'b')
    expect(9018,'POST','/inpatient/surgeries',{'admissionId':own,'procedureName':'V91'},'b')
    assert expect(0,'GET',base+f'summary?surgeryId={s}')==before
    for path,hidden in [('/inpatient/surgeries',foreign),('/inpatient/blood/applies',foreign_blood)]:assert hidden not in [r['id'] for r in expect(0,'GET',path,who='a')]
    for who in ['a','n','tech','q','cash']:
        expect(1005,'PUT',f'/inpatient/blood/applies/{bid}/review?approve=true',who=who)
        expect(1005,'PUT',f'/inpatient/blood/applies/{bid}/issue',who=who)
    expect(0,'PUT',f'/inpatient/blood/applies/{bid}/review?approve=true')
    expect(0,'PUT',f'/inpatient/blood/applies/{bid}/issue')
    expect(1005,'PUT',f'/inpatient/blood/applies/{bid}/transfuse?note=V91',who='a')
    expect(0,'PUT',f'/inpatient/blood/applies/{bid}/transfuse?note=V91',who='n')
    expect(4533,'PUT',f'/inpatient/blood/applies/{bid}/transfuse?note=V91',who='n')
    for who in ['n','tech']:
        expect(1005,'POST','/inpatient/surgeries',{'admissionId':own,'procedureName':'V91'},who)
        expect(1005,'PUT',f'/inpatient/surgeries/{s}/complete',{'opNote':'x'},who)
        expect(1005,'PUT',f'/inpatient/surgeries/{s}/cancel',{'stage':'APPLY','reason':'x'},who)
        expect(1005,'PUT',f'/inpatient/surgeries/{s}/op-info',{'roomNo':'NO','asaGrade':'II'},who)
        expect(0,'PUT',f'/inpatient/surgeries/{s}/op-info',{'roomNo':'OR91'},who)
        expect(0,'PUT',f'/inpatient/surgeries/{s}/timepoint',{'stage':'IN_ROOM'},who)
        new=expect(0,'POST',base+'tubes',{'surgeryId':s,'tubeType':'尿管'},who)['id']
        expect(0,'PUT',base+f'tubes/{new}',{'surgeryId':s,'tubeType':'尿管','position':'V91'},who)
        expect(0,'DELETE',base+f'tubes/{new}',who=who)
    expect(0,'POST','/anes/records',{'surgeryId':s,'phase':'PACU','stewardScore':5},'tech')
    assert expect(0,'GET',f'/anes/records/pacu-status?surgeryId={s}',who='tech')['canLeave']
    expect(0,'PUT',f'/inpatient/surgeries/{s}/op-info',{'asaGrade':'II'},'a')
    expect(0,'PUT',f'/inpatient/admissions/{own}/attending-doctor',{'doctorId':ids['b']},'a')
    expect(9018,'PUT',f'/inpatient/surgeries/{s}/complete',{'opNote':'x'},'a')
    expect(1005,'GET',base+f'summary?surgeryId={s}',who='a')
    expect(0,'PUT',f'/inpatient/surgeries/{s}/complete',{'opNote':'V91虚构手术记录','anesNote':'V91虚构麻醉记录'},'b')
    expect(9946,'PUT',f'/inpatient/surgeries/{s}/complete',{'opNote':'x'},'b')
    unassigned=surgery(free,'a');blood(free,'a')
    expect(0,'PUT',f'/inpatient/surgeries/{unassigned}/cancel',{'stage':'APPLY','reason':'V91虚构'},'a')
    for who in ['a','b','n','tech']:expect(0,'GET','/auth/me',who=who)
    print('e2e-v91-blood-surgery-access passed: clinical ownership, blood duty separation, surgery/intraop/anes scopes, child-ID tampering, handoff and role usability')
finally:
    for adm in reversed(admissions):discharge_cleanup(t,adm)
