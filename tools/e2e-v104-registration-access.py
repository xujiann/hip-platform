# -*- coding: utf-8 -*-
"""Registration desk roles, real patient binding and checked-in slot return via JWT."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,today_bj

t=login();suffix=str(time.time_ns())[-11:];tokens={'a':t}
for who,role in [('c','CASHIER'),('c2','CASHIER'),('n','NURSE'),('d','DOCTOR_OUTP'),('q','QUALITY'),('tech','TECHNICIAN')]:
 tokens[who]=provision_user(t,'e2e104'+who+suffix,role,'V104'+who)
def expect(code,method,path,body=None,who='a'):
 try:r=call(method,path,body,tokens[who])
 except urllib.error.HTTPError as e:
  assert e.code==403,(path,e.code);r=json.load(e)
 assert r['code']==code,(who,path,r)
 return r.get('data')
def patient(label):return expect(0,'POST','/patients',{'name':'V104'+label+suffix,'sex':'U','phone':'13800001111'})
def schedule():return expect(0,'POST','/outpatient/schedules',{'deptId':1,'scheduleDate':today_bj().isoformat(),'fee':5,'capacity':5})['id']
def pools(s,sl,expected):
 assert next(x for x in expect(0,'GET','/outpatient/schedules?date='+today_bj().isoformat()) if x['id']==s)['booked']==expected
 assert next(x for x in expect(0,'GET',f'/outpatient/schedules/{s}/slots') if x['id']==sl)['booked']==expected

base='/outpatient/registrations';p,p2=patient('甲'),patient('乙');s=schedule()
expect(0,'POST',f'/outpatient/schedules/{s}/slots',{'slots':[{'timeBegin':'08:00','timeEnd':'08:30','capacity':2}]})
sl=expect(0,'GET',f'/outpatient/schedules/{s}/slots')[0]['id']
expect(3007,'POST',base,{'patientId':-1,'scheduleId':s},'c')
r=expect(0,'POST',base,{'patientId':p['id'],'scheduleId':s},'c')['id']
for who in ['n','d','q','tech']:
 expect(1005,'GET',base+'?date='+today_bj().isoformat(),who=who)
 expect(1005,'POST',base,{'patientId':p2['id'],'scheduleId':s},who)
 expect(1005,'PUT',f'{base}/{r}/cancel',who=who)
assert any(x['id']==r for x in expect(0,'GET',base+'?date='+today_bj().isoformat(),who='c2'))
expect(0,'PUT',f'{base}/{r}/cancel',who='c2');pools(s,sl,0)
expect(3005,'PUT',f'{base}/{r}/cancel',who='c');pools(s,sl,0)

a=expect(0,'POST','/outpatient/appointments',{'patientId':p2['id'],'slotId':sl},'c')['id']
r2=expect(0,'POST',f'/outpatient/appointments/{a}/checkin',who='c2')['registrationId'];pools(s,sl,1)
expect(3114,'POST',f'/outpatient/appointments/{a}/cancel',who='c')
expect(0,'PUT',f'{base}/{r2}/cancel',who='c');pools(s,sl,0)
expect(3005,'PUT',f'{base}/{r2}/cancel',who='c2');pools(s,sl,0)
audit=next(x for x in expect(0,'GET',f'/outpatient/appointments?scheduleId={s}',who='c') if x['id']==a)
assert audit['status']=='CHECKED_IN'  # The cancellation does not rewrite the appointment history.

# The returned slot is usable again; cancel through the existing appointment path.
a2=expect(0,'POST','/outpatient/appointments',{'patientId':p['id'],'slotId':sl},'c2')['id'];pools(s,sl,1)
expect(0,'POST',f'/outpatient/appointments/{a2}/cancel',who='c');pools(s,sl,0)
tokens['p']=ok(call('POST','/portal/login',{'patientNo':p['patientNo'],'phone':'13800001111'}),'portal login')['token']
ps=schedule();expect(0,'POST','/portal/register',{'scheduleId':ps,'patientId':p2['id']},'p')
pr=next(x for x in expect(0,'GET',base+'?date='+today_bj().isoformat(),who='c') if x['patientId']==p['id'] and x['status']=='REGISTERED')
expect(1005,'PUT',f"{base}/{pr['id']}/cancel",who='p');expect(0,'PUT',f"{base}/{pr['id']}/cancel",who='c2')
for who in ['a','c','c2','n','d','q','tech']:
 expect(0,'GET','/auth/me',who=who);expect(0,'GET','/stats/overview',who=who)
print('e2e-v104-registration-access passed: staffed collaboration, role denial, patient binding, ordinary and checked-in cancellation, reusable dual pools, portal compatibility')
