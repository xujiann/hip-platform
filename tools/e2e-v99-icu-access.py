# -*- coding: utf-8 -*-
"""ICU parent scopes, bedside cooperation and historical reads using actual JWTs."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,new_patient,find_free_bed,discharge_cleanup,q
t=login();tokens={'admin':t};ids={};admissions=[];suffix=str(time.time_ns())[-10:]
for who,role in [('a','DOCTOR_OUTP'),('b','DOCTOR_OUTP'),('n','NURSE'),('q','QUALITY'),('tech','TECHNICIAN'),('cash','CASHIER')]:
 tokens[who]=provision_user(t,'e2e99'+who+suffix,role,'V99'+who)
 me=ok(call('GET','/auth/me',token=tokens[who]),'identity');ids[who]=me['id']
 if who in ['a','n']:assert '/specialty' in {m['path'] for m in me['menus']}
BASE='/inpatient/icu-records'
def expect(code,method,path,body=None,who='admin'):
 try:r=call(method,path,body,tokens[who])
 except urllib.error.HTTPError as e:
  assert e.code==403,(path,e.code);r=json.load(e)
 assert r['code']==code,(who,path,r)
 return r.get('data')
def admit(owner,p=None):
 p=p or new_patient(t,'V99虚构'+str(time.time_ns()),sex='U');bed=find_free_bed(t)
 body={'patientId':p['id'],'deptId':1,'bedId':bed['id'],'deposit':0,'payMethod':'CASH'}
 if owner:body['doctorId']=ids[owner]
 adm=expect(0,'POST','/inpatient/admissions',body)['id'];admissions.append(adm);return adm
def record(adm,who,note='V99虚构观察'):
 return expect(0,'POST',BASE,{'admissionId':adm,'gcs':15,'intakeMl':700,'outputMl':300,'note':note,'recorderId':ids['b']},who)
try:
 p=new_patient(t,'V99同患者不同住院'+suffix+'_%',sex='U');own=admit('a',p);record(own,'a')
 expect(0,'POST',f'/inpatient/admissions/{own}/discharge',{'payMethod':'CASH'})
 other=admit('b',p);free=admit(None);record(other,'n');record(free,'a')
 for path in [BASE+f'?admissionId={other}',BASE+f'/balance?admissionId={other}']:expect(1005,'GET',path,who='a')
 expect(9018,'POST',BASE,{'admissionId':other,'note':'V99 denied','recorderId':ids['b']},'a')
 rows=expect(0,'GET',BASE+f'?admissionId={other}',who='b');assert len(rows)==1 and rows[0]['recorder_id']==ids['n']
 assert expect(0,'GET',BASE+f'/balance?admissionId={other}',who='n')=={'intake':700,'output':300,'balance':400}
 choices=expect(0,'GET',BASE+'/admissions?keyword='+q(p['name']),who='a');assert {x['id'] for x in choices}=={own}
 assert set(choices[0])=={'id','admission_no','patient_name','status'}
 expect(0,'PUT',f'/inpatient/admissions/{own}/attending-doctor',{'doctorId':ids['b']})
 expect(1005,'GET',BASE+f'?admissionId={own}',who='a');expect(9018,'POST',BASE,{'admissionId':own,'note':'V99 denied'},'a')
 record(other,'b','V99当前主管');expect(0,'POST',f'/inpatient/admissions/{other}/discharge',{'payMethod':'CASH'})
 assert len(expect(0,'GET',BASE+f'?admissionId={other}',who='b'))==2
 assert expect(0,'GET',BASE+f'/balance?admissionId={other}',who='b')['balance']==800
 expect(4570,'POST',BASE,{'admissionId':own},'b');expect(4570,'POST',BASE,{'admissionId':own},'n')
 historic=next(x for x in expect(0,'GET',BASE+'/admissions?keyword='+q(p['name']),who='b') if x['id']==own);assert historic['status']=='DISCHARGED'
 assert expect(0,'GET',BASE+'?admissionId=-99',who='a')==[]
 assert expect(0,'GET',BASE+'/balance?admissionId=-99',who='a')['balance']==0
 for who in ['q','tech','cash']:
  for path in [BASE+f'?admissionId={other}',BASE+f'/balance?admissionId={other}',BASE+'/admissions']:expect(1005,'GET',path,who=who)
  expect(1005,'POST',BASE,{'admissionId':other},who)
 for who in tokens:expect(0,'GET','/auth/me',who=who);expect(0,'GET','/stats/overview',who=who)
 print('e2e-v99-icu-access passed: same-patient admissions, attending transfer, nursing recording, historical reads, aggregate isolation and role usability')
finally:
 for adm in admissions:discharge_cleanup(t,adm)
