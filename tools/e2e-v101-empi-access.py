# -*- coding: utf-8 -*-
"""EMPI real JWT duties, input allowlist, masked edits and partial update preservation."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user

t=login();tokens={'admin':t};suffix=str(time.time_ns())[-11:]
for who,role in [('n','NURSE'),('d','DOCTOR_OUTP'),('q','QUALITY'),('tech','TECHNICIAN'),('cash','CASHIER')]:
 tokens[who]=provision_user(t,'e2e101'+who+suffix,role,'V101'+who)
 if who in ['n','d']:assert '/patient/registry' in {m['path'] for m in ok(call('GET','/auth/me',token=tokens[who]),'menu')['menus']}
def expect(code,method,path,body=None,who='admin'):
 try:r=call(method,path,body,tokens[who])
 except urllib.error.HTTPError as e:
  assert e.code==403,(path,e.code);r=json.load(e)
 assert r['code']==code,(who,path,r)
 return r.get('data')
def lookup(no,who='admin'):
 rows=expect(0,'GET','/patients?keyword='+no,who=who)['records'];assert len(rows)==1,rows;return rows[0]
card='V101'+suffix
p=expect(0,'POST','/patients',{'name':'V101原始'+suffix,'sex':'U','idType':'PASSPORT','idNo':card,'phone':'13800001111','insuranceType':'YB_STAFF','bloodType':'A','allergyHistory':'V101原过敏'})
pid=p['id'];no=p['patientNo']
forged=expect(0,'POST','/patients',{'id':pid,'patientNo':'FORGED','active':False,'createdAt':'1970-01-01T00:00:00Z','name':'V101新建'+suffix,'sex':'U','phone':'13900002222'},'n')
assert forged['id']!=pid and forged['patientNo']!='FORGED';assert lookup(forged['patientNo'])['id']==forged['id']
original=lookup(no);assert original['name']==p['name'] and original['phone']=='13800001111' and original['allergyHistory']=='V101原过敏'
again=expect(0,'POST','/patients',{'name':'V101不得覆盖','idType':'PASSPORT','idNo':card,'phone':'13900002222'},'n')
assert again['id']==pid and again['name']==p['name'] and again['phone']=='138****1111'
for who in ['q','tech']:
 expect(1005,'POST','/patients',{'name':'V101越岗'},who)
 expect(1005,'PUT',f'/patients/{pid}',{'allergyHistory':'无'},who)
 assert lookup(no,who)['phone']=='138****1111'
expect(1005,'POST','/patients',{'name':'V101收费临床越岗','allergyHistory':'无'},'cash')
expect(1005,'PUT',f'/patients/{pid}',{'bloodType':'O','allergyHistory':'无'},'cash')
expect(0,'PUT',f'/patients/{pid}',{'address':'V101仅地址'},'n');assert lookup(no)['insuranceType']=='YB_STAFF'
expect(0,'PUT',f'/patients/{pid}',{'address':'V101收费地址','insuranceType':'YB_RESIDENT'},'cash');assert lookup(no)['allergyHistory']=='V101原过敏'
expect(2004,'PUT',f'/patients/{pid}',{'phone':'138****1111'},'n');expect(2005,'PUT',f'/patients/{pid}',{'phone':'13900002222'},'n')
updated=expect(0,'PUT',f'/patients/{pid}',{'allergyHistory':'V101护士维护'},'n');assert updated['phone']=='138****1111' and updated['insuranceType']=='YB_RESIDENT'
expect(0,'PUT',f'/patients/{pid}',{'bloodType':'AB'},'d');assert lookup(no)['bloodType']=='AB'
cash=expect(0,'POST','/patients',{'name':'V101收费建档'+suffix,'sex':'M','birthDate':'1999-01-01'},'cash');assert lookup(cash['patientNo'])['id']==cash['id']
expect(2002,'PUT','/patients/-1',{'address':'V101 missing'},'n')
for who in tokens:expect(0,'GET','/auth/me',who=who);expect(0,'GET','/stats/overview',who=who)
print('e2e-v101-empi-access passed: patient ID spoofing, role duties, identity/masking, idempotent registration, partial insurance preservation and role usability')
