# -*- coding: utf-8 -*-
"""Committed JWT receipts: shared monotonic numbers, capacity returns, portal and checkin."""
import time
from concurrent.futures import ThreadPoolExecutor
from e2elib import call,ok,login,provision_user,today_bj
t=login();suffix=str(time.time_ns())[-11:]
c=provision_user(t,'v106cash'+suffix,'CASHIER','V106收费')
def request(method,path,body=None,token=c):return ok(call(method,path,body,token),path)
def patient(label):return request('POST','/patients',{'name':'V106'+label+suffix,'sex':'U','phone':'13800001111'},t)
day=today_bj().isoformat();base='/outpatient/schedules'
s=request('POST',base,{'deptId':1,'scheduleDate':day,'capacity':10,'fee':5},t)['id']
request('POST',f'{base}/{s}/slots',{'slots':[{'timeBegin':'08:00','timeEnd':'08:30','capacity':5}]},t)
sl=request('GET',f'{base}/{s}/slots')[0]['id']
def reg(p):return request('POST','/outpatient/registrations',{'patientId':p['id'],'scheduleId':s})
def book(p,token=c):return request('POST','/outpatient/appointments',{'patientId':p['id'],'slotId':sl},token)
def cancel(r):request('PUT',f"/outpatient/registrations/{r['id']}/cancel")
def pools(total,slots):
 assert next(x for x in request('GET',base+'?date='+day) if x['id']==s)['booked']==total
 assert request('GET',f'{base}/{s}/slots')[0]['booked']==slots
pa,pb,pc,pd=[patient(x) for x in ['普通','预约','门户','并发']]
r1=reg(pa);a2=book(pb);assert (r1['regNo'],a2['apptNo'])==(1,2);pools(2,1)
cancel(r1);r3=reg(pa);assert r3['regNo']==3;pools(2,1)
request('POST',f"/outpatient/appointments/{a2['id']}/cancel")
portal=request('POST','/portal/login',{'patientNo':pc['patientNo'],'phone':'13800001111'},t)['token']
a4=request('POST','/portal/appointments',{'slotId':sl},portal);assert a4['apptNo']==4
checked=request('POST',f"/outpatient/appointments/{a4['id']}/checkin");assert checked['regNo']==4;pools(2,1)
cancel({'id':checked['registrationId']});pools(1,0)
# Different patients compete across the two public staff issuance paths.
with ThreadPoolExecutor(max_workers=2) as pool:
 f=pool.submit(reg,pb);g=pool.submit(book,pd);rr,aa=f.result(),g.result()
assert {rr['regNo'],aa['apptNo']}=={5,6};pools(3,1)
cancel(r3);cancel(rr);request('POST',f"/outpatient/appointments/{aa['id']}/cancel");pools(0,0)
last=reg(pa);assert last['regNo']==7;cancel(last);pools(0,0)
# A different schedule has its own numbering domain.
s2=request('POST',base,{'deptId':1,'scheduleDate':day,'capacity':1,'fee':0},t)['id']
r=request('POST','/outpatient/registrations',{'patientId':pa['id'],'scheduleId':s2});assert r['regNo']==1;cancel(r)
for token in [t,c]:request('GET','/auth/me',token=token);request('GET','/stats/overview',token=token)
print('e2e-v106-outpatient-number passed: shared monotonic receipts, returned capacity, portal, checkin and mixed issuance; all synthetic occupancy returned')
