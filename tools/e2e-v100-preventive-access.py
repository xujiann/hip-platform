# -*- coding: utf-8 -*-
"""Preventive recording ownership, surveillance duties and real actor attribution."""
import json,time,urllib.error
from datetime import timedelta
from e2elib import call,ok,login,provision_user,new_patient,find_free_bed,discharge_cleanup,today_bj

t=login();tokens={'admin':t};ids={};suffix=str(time.time_ns())[-10:];admission=None;discharged=False
for who,role in [('n','NURSE'),('b','NURSE'),('q','QUALITY'),('doctor','DOCTOR_OUTP'),('tech','TECHNICIAN'),('cash','CASHIER')]:
 tokens[who]=provision_user(t,'e2e100'+who+suffix,role,'V100'+who)
 me=ok(call('GET','/auth/me',token=tokens[who]),'identity');ids[who]=me['id']
 if who in ['n','q']:assert '/mgmt' in {m['path'] for m in me['menus']}
def expect(code,method,path,body=None,who='admin'):
 try:r=call(method,path,body,tokens[who])
 except urllib.error.HTTPError as e:
  assert e.code==403,(path,e.code);r=json.load(e)
 assert r['code']==code,(who,path,r)
 return r.get('data')
p=new_patient(t,'V100保健及院感虚构'+suffix,sex='U');pid=p['id']
def record(who,text):return expect(0,'POST','/phc/records',{'patientId':pid,'recordType':'EDUCATION','content':text,'recorderId':ids['b']},who)
try:
 record('n','V100本人'+suffix);record('b','V100他人'+suffix)
 for who in ['n','b']:
  rows=expect(0,'GET',f'/phc/records?patientId={pid}',who=who)
  assert len(rows)==1 and rows[0]['recorder_id']==ids[who] and rows[0]['recorder_name']=='V100'+who,rows
  assert expect(0,'GET','/phc/stats',who=who)==[{'record_type':'EDUCATION','cnt':1}]
 for who in ['q','admin']:assert len(expect(0,'GET',f'/phc/records?patientId={pid}',who=who))==2
 expect(1005,'POST','/phc/records',{'patientId':pid,'recordType':'EDUCATION','content':'越岗'},'q')
 expect(4612,'POST','/phc/records',{'patientId':pid,'recordType':'EDUCATION','content':'x','occurredDate':'bad'},'n')
 expect(4611,'POST','/phc/records',{'patientId':-1,'recordType':'EDUCATION','content':'x'},'n')
 bed=find_free_bed(t);admission=expect(0,'POST','/inpatient/admissions',{'patientId':pid,'deptId':1,'bedId':bed['id'],'deposit':0,'payMethod':'CASH'})['id']
 body={'admissionId':admission,'site':'呼吸道','confirmedOn':str(today_bj()),'note':'V100院感'+suffix,'reporterId':ids['b']}
 expect(0,'POST','/quality/infections',body,'n')
 rows=expect(0,'GET','/quality/infections',who='q')['cases'];row=next(x for x in rows if x['note']==body['note'])
 assert row['reporter_id']==ids['n'] and row['reporter_name']=='V100n',row
 expect(0,'POST',f'/inpatient/admissions/{admission}/discharge',{'payMethod':'CASH'});discharged=True
 body['note']='V100出院追溯'+suffix;expect(0,'POST','/quality/infections',body,'q')
 rows=expect(0,'GET','/quality/infections',who='n')['cases'];row=next(x for x in rows if x['note']==body['note']);assert row['reporter_id']==ids['q']
 body['confirmedOn']=str(today_bj()+timedelta(days=1));expect(9806,'POST','/quality/infections',body,'n')
 body['admissionId']=-1;body['confirmedOn']=str(today_bj());expect(9003,'POST','/quality/infections',body,'n')
 for who in ['doctor','tech','cash']:
  for path in ['/phc/records','/phc/stats','/quality/infections']:expect(1005,'GET',path,who=who)
  expect(1005,'POST','/phc/records',{'patientId':pid,'recordType':'EDUCATION','content':'越岗'},who)
  expect(1005,'POST','/quality/infections',body,who)
 for who in tokens:expect(0,'GET','/auth/me',who=who);expect(0,'GET','/stats/overview',who=who)
 print('e2e-v100-preventive-access passed: real JWTs, per-recorder detail/stats, quality read-only, role usability, actor spoofing and retrospective infection reporting')
finally:
 if admission is not None and not discharged:discharge_cleanup(t,admission)
