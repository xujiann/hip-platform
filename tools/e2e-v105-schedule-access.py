# -*- coding: utf-8 -*-
"""Real JWT schedule management and disabled-schedule occupancy/return compatibility."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,today_bj
t=login();suffix=str(time.time_ns())[-11:];tokens={'a':t};ids={}
for who,role in [('a2','ADMIN'),('revoked','ADMIN'),('c','CASHIER'),('d','DOCTOR_OUTP'),('n','NURSE'),('q','QUALITY'),('tech','TECHNICIAN')]:
 tokens[who]=provision_user(t,'e2e105'+who+suffix,role,'V105'+who);ids[who]=ok(call('GET','/auth/me',token=tokens[who]),'identity')['id']
def expect(code,method,path,body=None,who='a'):
 try:r=call(method,path,body,tokens[who])
 except urllib.error.HTTPError as e:
  assert e.code in (401,403),(path,e.code);r=json.load(e)
 assert r['code']==code,(who,path,r)
 return r.get('data')
base='/outpatient/schedules';day=today_bj().isoformat()
def catalog(who='a',all_rows=False):return expect(0,'GET',base+'?date='+day+('&includeDisabled=true' if all_rows else ''),who=who)
def row(s):return next(x for x in catalog(all_rows=True) if x['id']==s)
def patient(label):return expect(0,'POST','/patients',{'name':'V105'+label+suffix,'sex':'U','phone':'13800001111'})
body={'deptId':1,'scheduleDate':day,'fee':5,'capacity':5}
first=expect(0,'POST',base,body)['id']
s=expect(0,'POST',base,dict(body,id=first,booked=4,enabled=False,createdAt='1970-01-01T00:00:00Z'))['id']
assert s!=first and row(s)['booked']==0 and row(s)['enabled']
expect(3101,'POST',base,dict(body,shift='BAD'));expect(3101,'POST',base,dict(body,fee=0.001))
expect(3103,'POST',base,dict(body,deptId=-1));expect(3104,'POST',base,dict(body,doctorId=ids['c']))
doctor_schedule=expect(0,'POST',base,dict(body,doctorId=ids['d'],shift='AM',regType='EXPERT'))
assert doctor_schedule['doctorId']==ids['d'] and doctor_schedule['shift']=='AM'
for who in ['c','d','n','q','tech']:
 catalog(who);expect(1005,'POST',base,body,who);expect(1005,'PUT',f'{base}/{s}/enabled?enabled=false',who=who)
 expect(1005,'GET',base+'?date='+day+'&includeDisabled=true',who=who)
expect(0,'PUT',f"/system/users/{ids['revoked']}/enabled?enabled=false")
expect(1000,'POST',base,body,'revoked');expect(1000,'PUT',f'{base}/{s}/enabled?enabled=false',who='revoked')
expect(1000,'GET',base+'?date='+day+'&includeDisabled=true',who='revoked')
expect(3102,'PUT',base+'/-1/enabled?enabled=false')

pa,pb=patient('普通'),patient('预约')
expect(0,'POST',f'{base}/{s}/slots',{'slots':[{'timeBegin':'08:00','timeEnd':'08:30','capacity':2}]})
sl=expect(0,'GET',f'{base}/{s}/slots')[0]['id']
def pools(total,slot):
 assert row(s)['booked']==total
 assert expect(0,'GET',f'{base}/{s}/slots')[0]['booked']==slot
reg=expect(0,'POST','/outpatient/registrations',{'patientId':pa['id'],'scheduleId':s},'c')['id']
a=expect(0,'POST','/outpatient/appointments',{'patientId':pb['id'],'slotId':sl},'c')['id'];pools(2,1)
expect(0,'PUT',f'{base}/{s}/enabled?enabled=false',who='a2');assert not row(s)['enabled'];pools(2,1)
assert not any(x['id']==s for x in catalog('c'))
tokens['p']=ok(call('POST','/portal/login',{'patientNo':pa['patientNo'],'phone':'13800001111'}),'portal')['token']
assert not any(x['id']==s for x in expect(0,'GET','/portal/schedules?date='+day,who='p'))
expect(3003,'POST','/outpatient/registrations',{'patientId':pb['id'],'scheduleId':s},'c')
expect(3111,'POST','/outpatient/appointments',{'patientId':pa['id'],'slotId':sl},'c');pools(2,1)
expect(0,'PUT',f'/outpatient/registrations/{reg}/cancel',who='c');pools(1,1)
expect(0,'POST',f'/outpatient/appointments/{a}/cancel',who='c');pools(0,0)
expect(0,'PUT',f'{base}/{s}/enabled?enabled=true');assert row(s)['enabled'] and any(x['id']==s for x in catalog('c'))

# A checked-in registration can still return its original slot after a later stop.
a2=expect(0,'POST','/outpatient/appointments',{'patientId':pb['id'],'slotId':sl},'c')['id']
r2=expect(0,'POST',f'/outpatient/appointments/{a2}/checkin',who='c')['registrationId'];pools(1,1)
expect(0,'PUT',f'{base}/{s}/enabled?enabled=false');pools(1,1)
expect(0,'PUT',f'/outpatient/registrations/{r2}/cancel',who='c');pools(0,0)
expect(0,'PUT',f'{base}/{s}/enabled?enabled=true',who='a2');pools(0,0)
assert row(s)['fee']==5 and row(s)['capacity']==5
for who in ['a','a2','c','d','n','q','tech']:
 expect(0,'GET','/auth/me',who=who);expect(0,'GET','/stats/overview',who=who)
print('e2e-v105-schedule-access passed: enabled administrator management, input whitelist, object qualification, catalog isolation, disable/enable preserves counts and allows normal/appointment/checkin returns')
