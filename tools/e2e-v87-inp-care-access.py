# -*- coding: utf-8 -*-
"""Transfer/discharge/interim billing scope with real doctor/nurse/cashier JWTs."""
import json
import time
import urllib.error
from e2elib import call, login, ok, provision_user, new_patient, find_free_bed, discharge_cleanup

t=login(); suffix=str(time.time_ns())[-10:]
a=provision_user(t,'e2e87a'+suffix,'DOCTOR_OUTP','V87主管A')
b=provision_user(t,'e2e87b'+suffix,'DOCTOR_OUTP','V87接手B')
n=provision_user(t,'e2e87n'+suffix,'NURSE','V87护士')
c=provision_user(t,'e2e87c'+suffix,'CASHIER','V87收费')
aid=ok(call('GET','/auth/me',token=a),'A身份')['id']
bid=ok(call('GET','/auth/me',token=b),'B身份')['id']
admissions=[]
item=next(x for x in ok(call('GET','/masterdata/charge-items',token=t),'收费项目') if x['category']=='TREAT' and x.get('enabled',True))


def admit(owner):
    p=new_patient(t,'V87流程'+str(time.time_ns()),sex='U'); bed=find_free_bed(t)
    body={'patientId':p['id'],'deptId':1,'bedId':bed['id'],'deposit':1000,'payMethod':'CASH'}
    if owner is not None: body['doctorId']=owner
    adm=ok(call('POST','/inpatient/admissions',body,t),'入院')['id']
    admissions.append(adm); return adm


def execute(adm,doctor):
    oid=ok(call('POST',f'/inpatient/admissions/{adm}/orders',{'lines':[{'orderType':'TREAT','itemId':item['id'],'qty':1}]},doctor),'开嘱')[0]['id']
    ok(call('PUT',f'/inpatient/orders/{oid}/execute',None,n),'护士执行')


def transfer(adm,token):
    bed=find_free_bed(t)
    return call('POST',f'/inpatient/admissions/{adm}/transfer',{'toDeptId':1,'toBedId':bed['id'],'reason':'V87验收'},token)


def snapshot(adm):
    base=f'/inpatient/admissions/{adm}'
    return [ok(call('GET',base+path,token=t),'快照') for path in ['/workspace','/transfers','/interim-settlements']]


def denied(adm,token,expected):
    before=snapshot(adm)
    for action in ['transfer','discharge','interim-settle']:
        try:
            response=transfer(adm,token) if action=='transfer' else call('POST',f'/inpatient/admissions/{adm}/{action}',{},token)
        except urllib.error.HTTPError as ex:
            assert ex.code==403 and expected==1005,ex
            response=json.load(ex)
        assert response['code']==expected,(action,response)
        assert snapshot(adm)==before,'拒绝不得改变床位、状态、医嘱、留痕或结算'


try:
    adm=admit(aid); execute(adm,a)
    denied(adm,b,9018)
    # Cashier has billing responsibility, but does not gain transfer/discharge rights.
    for action in ['transfer','discharge']:
        try: response=transfer(adm,c) if action=='transfer' else call('POST',f'/inpatient/admissions/{adm}/{action}',{},c)
        except urllib.error.HTTPError as ex: assert ex.code==403,ex
        else: assert response['code']==1005,response
    ok(call('POST',f'/inpatient/admissions/{adm}/interim-settle',{},c),'收费员中间结算')
    ok(transfer(adm,a),'原主管转床')
    ok(call('PUT',f'/inpatient/admissions/{adm}/attending-doctor',{'doctorId':bid},a),'主管移交')
    denied(adm,a,9018)
    ok(transfer(adm,b),'新主管转床');execute(adm,b)
    ok(call('POST',f'/inpatient/admissions/{adm}/interim-settle',{},b),'新主管中间结算')
    ok(transfer(adm,n),'护士转床'); execute(adm,b)
    ok(call('POST',f'/inpatient/admissions/{adm}/interim-settle',{},n),'护士中间结算')
    ok(call('POST',f'/inpatient/admissions/{adm}/discharge',{},n),'护士出院'); admissions.remove(adm)
    for owner,token,label in [(aid,a,'本人主管'),(None,a,'未归属'),(bid,t,'管理员')]:
        adm=admit(owner);execute(adm,token)
        ok(transfer(adm,token),label+'转床')
        ok(call('POST',f'/inpatient/admissions/{adm}/interim-settle',{},token),label+'中间结算')
        ok(call('POST',f'/inpatient/admissions/{adm}/discharge',{},token),label+'出院'); admissions.remove(adm)
    assert ok(call('GET','/auth/me',token=a),'拒绝后仍登录')['id']==aid
    print('e2e-v87-inp-care-access 全部通过：三入口拒绝零副作用、主管移交及护理/收费/管理员正常流程')
finally:
    for adm in reversed(admissions): discharge_cleanup(t,adm)
