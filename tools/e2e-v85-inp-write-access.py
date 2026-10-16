# -*- coding: utf-8 -*-
"""Inpatient physician decisions and ownership handoff, with real JWT identities."""
import time
import urllib.error
from e2elib import call, login, ok, provision_user, new_patient, find_free_bed, discharge_cleanup

t=login();suffix=str(time.time_ns())[-10:]
a=provision_user(t,'e2e85a'+suffix,'DOCTOR_OUTP','V85主管A')
b=provision_user(t,'e2e85b'+suffix,'DOCTOR_OUTP','V85接手B')
n=provision_user(t,'e2e85n'+suffix,'NURSE','V85护士')
aid=ok(call('GET','/auth/me',token=a),'A身份')['id']
bid=ok(call('GET','/auth/me',token=b),'B身份')['id']
p=new_patient(t,'V85归属'+suffix,sex='U');bed=find_free_bed(t)
adm=ok(call('POST','/inpatient/admissions',{'patientId':p['id'],'deptId':1,'bedId':bed['id'],'doctorId':aid,'deposit':1000,'payMethod':'CASH'},t),'入院')['id']
base=f'/inpatient/admissions/{adm}'
item=next(x for x in ok(call('GET','/masterdata/charge-items',token=t),'项目') if x['category']=='TREAT' and x.get('enabled',True))

def opened(nature,token):
    return ok(call('POST',base+'/orders',{'lines':[{'orderType':'TREAT','itemId':item['id'],'qty':1,'orderNature':nature}]},token),'开医嘱')[0]['id']

def denied(method,path,body,token,code):
    r=call(method,path,body,token);assert r['code']==code,(path,r)

def nurse_denied(method,path,body):
    try:r=call(method,path,body,n)
    except urllib.error.HTTPError as ex:assert ex.code==403,ex
    else:assert r['code']==1005,(path,r)

try:
    temp=opened('TEMP',a);longer=opened('LONG',a)
    before=ok(call('GET',base+'/workspace',token=t),'写前快照')
    actions=[('PUT',base+'/attending-doctor',{'doctorId':bid}),('PUT',base+'/discharge-diag',{'icd':'Z00','name':'越权诊断'}),
             ('POST',base+'/orders',{'lines':[]}),('PUT',f'/inpatient/orders/{temp}/cancel',None),('POST',f'/inpatient/orders/{longer}/stop',{})]
    for method,path,body in actions:
        denied(method,path,body,b,9018);nurse_denied(method,path,body)
    after=ok(call('GET',base+'/workspace',token=t),'拒绝后快照')
    assert before==after,'拒绝不得改变主管、医嘱或金额'
    denied('POST',base+'/records',{'recordType':'PROGRESS','content':'尝试接管后书写'},b,9130)
    ok(call('PUT',base+'/discharge-diag',{'icd':'Z00','name':'本人诊断'},a),'本人改诊断')
    ok(call('PUT',base+'/attending-doctor',{'doctorId':bid},a),'原主管移交')
    denied('POST',base+'/orders',{'lines':[]},a,9018)
    ok(call('POST',base+'/orders',{'lines':[]},b),'新主管可开嘱')
    ok(call('PUT',f'/inpatient/orders/{temp}/cancel',None,b),'新主管作废')
    ok(call('POST',f'/inpatient/orders/{longer}/stop',{},b),'新主管停嘱')
    execute=opened('TEMP',b)
    ok(call('PUT',f'/inpatient/orders/{execute}/execute',None,n),'护士执行')
    ok(call('POST',base+'/vitals',{'temperature':36.5,'pulse':80},n),'护士体征')
    ok(call('PUT',base+'/attending-doctor',{'doctorId':aid},t),'管理员跨病案移交')
    assert ok(call('GET','/auth/me',token=b),'拒绝后登录')['id']==bid
    print('e2e-v85-inp-write-access 全部通过：主管防接管、五入口拒绝零副作用、移交生效及护士执行/体征正常')
finally:
    for row in ok(call('GET',base+'/workspace',token=t),'清理前医嘱')['orders']:
        if row.get('orderNature')=='LONG' and not row.get('stopAt'):
            call('POST',f"/inpatient/orders/{row['id']}/stop",{},t)
        elif row['status']=='CREATED':call('PUT',f"/inpatient/orders/{row['id']}/cancel",None,t)
    discharge_cleanup(t,adm)
