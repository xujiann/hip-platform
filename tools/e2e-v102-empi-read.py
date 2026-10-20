# -*- coding: utf-8 -*-
"""Real JWT EMPI projection across list, duplicate registration and empty update."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user

admin=login();suffix=str(time.time_ns())[-11:]
actors={'ADMIN':admin}
for role in ['DOCTOR_OUTP','NURSE','CASHIER','TECHNICIAN','QUALITY']:
 actors[role]=provision_user(admin,'e2e102'+role.lower()+suffix,role,'V102'+role)
for label,roles in [('BILLING_MIX',['QUALITY','CASHIER']),('CLINICAL_MIX',['TECHNICIAN','NURSE']),('INDEX_MIX',['QUALITY','TECHNICIAN'])]:
 username='e2e102'+label.lower()+suffix
 token=provision_user(admin,username,roles[0],'V102'+label)
 uid=ok(call('GET','/auth/me',token=token),'mixed actor')['id']
 ok(call('PUT',f'/system/users/{uid}',{'username':username,'realName':'V102'+label,'roleCodes':roles},admin),'mixed duties')
 actors[label]=token
card='V102'+suffix
original=ok(call('POST','/patients',{'name':'V102虚构'+suffix,'sex':'U','birthDate':'1995-01-01','idType':'PASSPORT','idNo':card,'phone':'13800001111','address':'V102住址','insuranceType':'YB_STAFF','bloodType':'A','allergyHistory':'V102过敏'},admin),'seed')
pid=original['id'];no=original['patientNo'];keys=set(original)

def request(method,path,body=None,role='ADMIN'):
 return ok(call(method,path,body,actors[role]),role+' '+path)

def lookup(role):
 data=request('GET','/patients?keyword='+no,role=role)
 assert data['total']==1 and len(data['records'])==1,data
 return data['records'][0]

def check(row,role):
 clinical=role in ['ADMIN','DOCTOR_OUTP','NURSE','CLINICAL_MIX']
 billing=clinical or role in ['CASHIER','BILLING_MIX']
 assert set(row)==keys,(role,row)
 assert row['id']==pid and row['name']==original['name']
 assert row['phone']==('13800001111' if role=='ADMIN' else '138****1111'),(role,row)
 assert row['idNo']==(card if role=='ADMIN' else card[:4]+'*'*(len(card)-7)+card[-3:]),(role,row)
 for field in ['bloodType','allergyHistory']:assert row[field]==(original[field] if clinical else None),(role,field,row)
 for field in ['address','insuranceType']:assert row[field]==(original[field] if billing else None),(role,field,row)

for role in actors:
 check(lookup(role),role)
 request('GET','/auth/me',role=role);request('GET','/stats/overview',role=role)
for role in ['ADMIN','DOCTOR_OUTP','NURSE','CASHIER','BILLING_MIX','CLINICAL_MIX']:
 check(request('POST','/patients',{'name':'V102不得覆盖','idType':'PASSPORT','idNo':card},role),role)
 check(request('PUT',f'/patients/{pid}',{},role),role)
assert lookup('ADMIN')==original,'Read projection or no-op writes changed persisted data'
for role in ['QUALITY','TECHNICIAN','INDEX_MIX']:
 for method,path,body in [('POST','/patients',{'name':'V102拒绝'}),('PUT',f'/patients/{pid}',{})]:
  try:res=call(method,path,body,actors[role])
  except urllib.error.HTTPError as e:
   assert e.code==403;res=json.load(e)
  assert res['code']==1005,res
# Legitimate demographic registration/billing maintenance still works.
cash=request('POST','/patients',{'name':'V102收费建档'+suffix,'sex':'M','birthDate':'1999-01-01'},'CASHIER')
assert cash['id']!=pid and cash['bloodType'] is None and cash['allergyHistory'] is None
saved=request('PUT',f'/patients/{pid}',{'address':'V102收费维护'},'CASHIER')
assert saved['address']=='V102收费维护' and saved['bloodType'] is None and saved['allergyHistory'] is None
back=lookup('ADMIN');assert back['bloodType']=='A' and back['allergyHistory']=='V102过敏' and back['insuranceType']=='YB_STAFF'
print('e2e-v102-empi-read passed: role projection, mixed duties, all three exits, bypass rejection, persistence and role usability')
