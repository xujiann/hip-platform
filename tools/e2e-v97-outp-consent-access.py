# -*- coding: utf-8 -*-
"""Live outpatient parents and JWTs, including an actual unassigned-visit claim."""
import datetime,json,time,urllib.error
from e2elib import call,ok,login,new_patient,provision_user,today_bj
t=login();suffix=str(time.time_ns())[-10:];tokens={'admin':t};ids={}
for who,role in [('a','DOCTOR_OUTP'),('b','DOCTOR_OUTP'),('n','NURSE'),('q','QUALITY'),('cash','CASHIER')]:
    tokens[who]=provision_user(t,'e2e97'+who+suffix,role,'V97'+who)
    ids[who]=ok(call('GET','/auth/me',token=tokens[who]),'identity')['id']
BASE='/emr/consents';past=str(today_bj()-datetime.timedelta(days=2));today=str(today_bj())
def expect(code,method,path,body=None,who='admin'):
    try:r=call(method,path,body,tokens[who])
    except urllib.error.HTTPError as e:
        assert e.code==403,(path,e.code);r=json.load(e)
    assert r['code']==code,(who,path,r)
    return r.get('data')
p=new_patient(t,'V97虚构'+suffix,sex='U')
def visit(day,doctor):
    body={'deptId':1,'scheduleDate':day,'fee':0,'capacity':10}
    if doctor:body['doctorId']=ids[doctor]
    schedule=expect(0,'POST','/outpatient/schedules',body)
    return expect(0,'POST','/outpatient/registrations',{'patientId':p['id'],'scheduleId':schedule['id']})['id']
def draft(reg,who='admin'):
    return expect(0,'POST',BASE,{'registrationId':reg,'consentType':'SELF_PAY','title':'V97'+suffix,'content':'V97虚构内容'},who)['id']
own=visit(past,'a');other=visit(past,'b');shared=visit(today,'b');free=visit(past,None)
c=draft(own,'a');foreign=draft(other)
expect(1005,'GET',BASE+f'/{foreign}',who='a');assert expect(0,'GET',BASE+f'?registrationId={other}',who='a')==[]
expect(4036,'POST',BASE,{'registrationId':other,'consentType':'SELF_PAY','content':'V97 denied'},'a')
before=expect(0,'GET',BASE+f'/{foreign}')
expect(4036,'POST',BASE+f'/{foreign}/patient-sign',{'patientSign':'V97 denied','registrationId':own},'a')
expect(4036,'PUT',BASE+f'/{foreign}/revoke',who='a')
assert expect(0,'GET',BASE+f'/{foreign}')==before
expect(0,'POST',BASE+f'/{foreign}/patient-sign',{'patientSign':'V97虚构患者'},'n')
before=expect(0,'GET',BASE+f'/{foreign}')
expect(4036,'POST',BASE+f'/{foreign}/doctor-sign',{},'a')
expect(1005,'POST',BASE+f'/{foreign}/doctor-sign',{},'n')
assert expect(0,'GET',BASE+f'/{foreign}')==before
rows=expect(0,'GET',BASE,who='a');assert c in {x['id'] for x in rows} and foreign not in {x['id'] for x in rows}
choices=expect(0,'GET',BASE+'/registrations?date='+past,who='a');assert own in {x['id'] for x in choices} and other not in {x['id'] for x in choices}
item=next(x for x in choices if x['id']==own);assert set(item)=={'id','reg_no','patient_name','patient_no','visit_date','doctor_id','emr_doctor_id'}
assert item['doctor_id']==ids['a'] and item['patient_name']==p['name']
draft(shared,'a');claim=draft(free,'a')
expect(0,'POST',BASE+f'/{claim}/patient-sign',{'patientSign':'V97虚构患者'},'n')
# Real clinical start, not a direct SQL change: B claims a historical unassigned encounter.
expect(0,'POST',f'/outpatient/doctor/{free}/start',{},'b')
expect(1005,'GET',BASE+f'/{claim}',who='a')
expect(4036,'POST',BASE+f'/{claim}/doctor-sign',{},'a')
expect(4036,'PUT',BASE+f'/{claim}/revoke',who='a')
expect(0,'POST',BASE+f'/{claim}/doctor-sign',{},'b')
signed=expect(0,'GET',BASE+f'/{claim}');assert signed['status']=='SIGNED' and signed['doctor_id']==ids['b']
expect(9114,'POST',BASE+f'/{claim}/doctor-sign',{},'b')
expect(9114,'PUT',BASE+f'/{claim}/revoke',who='n');expect(0,'PUT',BASE+f'/{claim}/revoke')
for who in ['q','cash']:
    expect(1005,'GET',BASE+'/registrations',who=who)
    expect(1005,'POST',BASE,{'registrationId':own,'consentType':'SELF_PAY','content':'V97'},who)
for who in tokens:expect(0,'GET','/auth/me',who=who);expect(0,'GET','/stats/overview',who=who)
print('e2e-v97-outp-consent-access passed: same-patient encounters, signatures/revoke isolation, real claim, nursing cooperation, selector and role usability')
