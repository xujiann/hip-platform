# -*- coding: utf-8 -*-
"""Real staff/portal JWTs share pending-visit eligibility and commit only one mixed winner."""
import time
from concurrent.futures import ThreadPoolExecutor
from e2elib import call,ok,login,provision_user,today_bj
t=login();suffix=str(time.time_ns())[-11:];c=provision_user(t,'v107cash'+suffix,'CASHIER','V107资格验收')
def req(method,path,body=None,token=c):return ok(call(method,path,body,token),path)
day=today_bj().isoformat();base='/outpatient/schedules';s=req('POST',base,{'deptId':1,'scheduleDate':day,'capacity':10,'fee':5},t)['id']
req('POST',f'{base}/{s}/slots',{'slots':[{'timeBegin':'08:00','timeEnd':'08:30','capacity':5}]},t)
sl=req('GET',f'{base}/{s}/slots')[0]['id']
def patient(label):return req('POST','/patients',{'name':'V107'+label+suffix,'sex':'U','phone':'13800001111'},t)
def reg(p):return call('POST','/outpatient/registrations',{'patientId':p['id'],'scheduleId':s},c)
def book(p):return call('POST','/outpatient/appointments',{'patientId':p['id'],'slotId':sl},c)
def portal(p):return req('POST','/portal/login',{'patientNo':p['patientNo'],'phone':'13800001111'},t)['token']
def cancel_reg(r):req('PUT',f"/outpatient/registrations/{r['id']}/cancel")
def cancel_appt(a):req('POST',f"/outpatient/appointments/{a['id']}/cancel")
def pools(total,slots):
 row=next(x for x in req('GET',base+'?date='+day) if x['id']==s);assert row['booked']==total
 assert req('GET',f'{base}/{s}/slots')[0]['booked']==slots
p=patient('双向');pt=portal(p)
r=ok(reg(p),'ordinary');assert r['regNo']==1
assert book(p)['code']==3112
assert call('POST','/portal/appointments',{'slotId':sl},pt)['code']==3112;pools(1,0)
cancel_reg(r);a=req('POST','/portal/appointments',{'slotId':sl},pt);assert a['apptNo']==2
assert reg(p)['code']==3002;pools(1,1)
checked=req('POST',f"/outpatient/appointments/{a['id']}/checkin");assert checked['regNo']==2
assert book(p)['code']==3112 and reg(p)['code']==3002;pools(1,1)
cancel_reg({'id':checked['registrationId']});a=ok(book(p),'rebook after checked-in registration return');assert a['apptNo']==3;cancel_appt(a);pools(0,0)
for n,use_portal in [(4,False),(5,True)]:
 q=patient('竞争'+str(n));qt=portal(q)
 def booking():return call('POST','/portal/appointments',{'slotId':sl},qt) if use_portal else book(q)
 with ThreadPoolExecutor(max_workers=2) as pool:
  f=pool.submit(reg,q);g=pool.submit(booking);rr,aa=f.result(),g.result()
 assert (rr['code']==0)+(aa['code']==0)==1,(rr,aa)
 assert (rr['code']==3002)+(aa['code']==3112)==1,(rr,aa)
 if rr['code']==0:assert rr['data']['regNo']==n;cancel_reg(rr['data'])
 else:assert aa['data']['apptNo']==n;cancel_appt(aa['data'])
 pools(0,0)
# Same patient in a different schedule remains eligible.
s2=req('POST',base,{'deptId':1,'scheduleDate':day,'capacity':2,'fee':0},t)['id']
r1=ok(reg(p),'first schedule');r2=req('POST','/outpatient/registrations',{'patientId':p['id'],'scheduleId':s2});assert r1['regNo']==6 and r2['regNo']==1
cancel_reg(r1);cancel_reg(r2);pools(0,0)
for tok in [t,c]:req('GET','/auth/me',token=tok);req('GET','/stats/overview',token=tok)
print('e2e-v107-registration-eligibility passed: staff/portal bidirectional rejection, own checkin, return and rebook, one mixed winner, independent schedules; all synthetic occupancy returned')
