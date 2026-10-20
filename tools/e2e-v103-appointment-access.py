# -*- coding: utf-8 -*-
"""Appointment desk duties, real patient binding and portal ownership through real JWT."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,today_bj
t=login();suffix=str(time.time_ns())[-11:];tokens={'a':t}
for who,role in [('c','CASHIER'),('c2','CASHIER'),('n','NURSE'),('d','DOCTOR_OUTP'),('q','QUALITY'),('tech','TECHNICIAN')]:
 tokens[who]=provision_user(t,'e2e103'+who+suffix,role,'V103'+who)
def expect(code,method,path,body=None,who='a'):
 try:r=call(method,path,body,tokens[who])
 except urllib.error.HTTPError as e:
  assert e.code==403,(path,e.code);r=json.load(e)
 assert r['code']==code,(who,path,r)
 return r.get('data')
def patient(label):return expect(0,'POST','/patients',{'name':'V103'+label+suffix,'sex':'U','phone':'13800001111'})
p,p2=patient('甲'),patient('乙')
schedule=expect(0,'POST','/outpatient/schedules',{'deptId':1,'scheduleDate':today_bj().isoformat(),'fee':5,'capacity':5})['id']
path=f'/outpatient/schedules/{schedule}/slots'
line={'slots':[{'timeBegin':'08:00','timeEnd':'08:30','capacity':2}]}
expect(1005,'POST',path,line,'c');expect(0,'POST',path,line)
slots=expect(0,'GET',path,who='c');assert len(slots)==1 and slots[0]['remaining']==2;slot=slots[0]['id']
expect(3120,'POST',path,{'slots':[{'timeBegin':'bad','timeEnd':'09:00','capacity':1}]})
expect(3120,'POST',path,line)
expect(3115,'POST','/outpatient/appointments',{'slotId':slot,'patientId':-1},'c')
expect(3116,'POST','/outpatient/appointments',{'slotId':slot,'patientId':p['id'],'source':'x'*17},'c')
assert expect(0,'GET',path)[0]['booked']==0
appt=expect(0,'POST','/outpatient/appointments',{'slotId':slot,'patientId':p['id'],'source':'窗口'},'c')['id']
for who in ['n','d','q','tech']:
 expect(1005,'GET',path,who=who);expect(1005,'GET',f'/outpatient/appointments?scheduleId={schedule}',who=who)
 expect(1005,'POST','/outpatient/appointments',{'slotId':slot,'patientId':p2['id']},who)
 for action in ['checkin','cancel']:expect(1005,'POST',f'/outpatient/appointments/{appt}/{action}',who=who)
 expect(1005,'POST',path,line,who)
expect(3112,'POST','/outpatient/appointments',{'slotId':slot,'patientId':p['id']},'c2')
reg=expect(0,'POST',f'/outpatient/appointments/{appt}/checkin',who='c2')
assert reg['registrationId'] and expect(0,'GET',path)[0]['booked']==1
expect(3114,'POST',f'/outpatient/appointments/{appt}/cancel',who='c')
second=expect(0,'POST','/outpatient/appointments',{'slotId':slot,'patientId':p2['id']},'c')['id']
expect(0,'POST',f'/outpatient/appointments/{second}/cancel',who='c2')
assert expect(0,'GET',path)[0]['booked']==1
# Portal book takes its patient solely from its token; another patient's cancellation is denied.
for who,patient_row in [('p',p),('p2',p2)]:tokens[who]=ok(call('POST','/portal/login',{'patientNo':patient_row['patientNo'],'phone':'13800001111'}),'portal login')['token']
# p remains pending after checkin: a forged body must not switch the token's eligibility.
expect(3112,'POST','/portal/appointments',{'slotId':slot,'patientId':p2['id']},'p')
# p2's previous appointment was cancelled, so use that eligible token for ownership checks.
portal=expect(0,'POST','/portal/appointments',{'slotId':slot,'patientId':p['id']},'p2')['id']
row=next(x for x in expect(0,'GET',f'/outpatient/appointments?scheduleId={schedule}',who='c') if x['id']==portal)
assert row['patient_no']==p2['patientNo']
expect(9506,'POST',f'/portal/my/appointments/{portal}/cancel',who='p')
expect(1005,'POST',f'/outpatient/appointments/{portal}/cancel',who='p2')
expect(0,'POST',f'/portal/my/appointments/{portal}/cancel',who='p2')
assert expect(0,'GET',path)[0]['booked']==1  # The checked-in registration still consumes its original slot.
for who in ['a','c','c2','n','d','q','tech']:
 expect(0,'GET','/auth/me',who=who);expect(0,'GET','/stats/overview',who=who)
print('e2e-v103-appointment-access passed: staff duties, patient objects, slot parameters, dual-pool accounting, checkin/cancel and portal ownership')
