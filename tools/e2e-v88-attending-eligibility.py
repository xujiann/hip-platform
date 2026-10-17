# -*- coding: utf-8 -*-
"""Real JWT admission/attending eligibility and zero-side-effect rejection."""
import time
from e2elib import call, ok, login, provision_user, new_patient, find_free_bed, discharge_cleanup

t=login(); suffix=str(time.time_ns())[-10:]
users={}
for key,role in [('a','DOCTOR_OUTP'),('b','DOCTOR_OUTP'),('n','NURSE'),('c','CASHIER')]:
    tok=provision_user(t,'e2e88'+key+suffix,role,'V88'+key)
    users[key]=ok(call('GET','/auth/me',token=tok),'identity')
    users[key]['token']=tok
admissions=[]

def snapshot():
    wards=ok(call('GET','/system/depts',token=t),'wards')
    beds=[ok(call('GET',f"/inpatient/beds?wardId={w['id']}",token=t),'beds') for w in wards if w['type']=='NURSING']
    return [ok(call('GET','/inpatient/admissions',token=t),'admissions'),beds]

def body(owner):
    p=new_patient(t,'V88虚构'+str(time.time_ns()),sex='U');bed=find_free_bed(t)
    result={'patientId':p['id'],'deptId':1,'bedId':bed['id'],'deposit':100,'payMethod':'CASH'}
    if owner is not None: result['doctorId']=owner
    return result

try:
    for who in ['n','c']:
        request=body(users[who]['id']);before=snapshot()
        for actor in [t,users['a']['token'],users['n']['token']]:
            r=call('POST','/inpatient/admissions',request,actor)
            assert r['code']==4884,r
            assert snapshot()==before,'rejection changed admissions or beds'
    ok(call('PUT',f"/system/users/{users['b']['id']}/enabled?enabled=false",token=t),'disable doctor')
    for target in [users['b']['id'],9223372036854775807,0,-1]:
        request=body(target);before=snapshot()
        assert call('POST','/inpatient/admissions',request,t)['code']==4884
        assert snapshot()==before
    ok(call('PUT',f"/system/users/{users['b']['id']}/enabled?enabled=true",token=t),'enable doctor')
    # A candidate cached while eligible must be checked again after a role change.
    old_candidates=ok(call('GET','/inpatient/doctors',token=t),'old candidates')
    assert users['b']['id'] in {d['id'] for d in old_candidates}
    profile=users['b']
    edit={'username':profile['username'],'realName':'V88b','roleCodes':['NURSE']}
    ok(call('PUT',f"/system/users/{profile['id']}",edit,t),'revoke physician role')
    request=body(profile['id']);before=snapshot()
    assert call('POST','/inpatient/admissions',request,t)['code']==4884
    assert snapshot()==before
    assert profile['id'] not in {d['id'] for d in ok(call('GET','/inpatient/doctors',token=t),'new candidates')}
    edit['roleCodes']=['DOCTOR_OUTP']
    ok(call('PUT',f"/system/users/{profile['id']}",edit,t),'restore physician role')
    for actor,owner in [(t,users['a']['id']),(users['a']['token'],users['a']['id']),(users['n']['token'],users['b']['id']),(t,None),(t,ok(call('GET','/auth/me',token=t),'admin')['id'])]:
        adm=ok(call('POST','/inpatient/admissions',body(owner),actor),'eligible admission')['id'];admissions.append(adm)
        base=f'/inpatient/admissions/{adm}'
        before=ok(call('GET',base+'/workspace',token=t),'workspace')
        for target in [users['n']['id'],users['c']['id'],0,-1,None]:
            r=call('PUT',base+'/attending-doctor',{'doctorId':target},t)
            assert r['code']==4884,r
            assert ok(call('GET',base+'/workspace',token=t),'unchanged')==before
        ok(call('PUT',base+'/attending-doctor',{'doctorId':users['b']['id']},t),'eligible handoff')
        discharge_cleanup(t,adm);admissions.remove(adm)
    candidates=ok(call('GET','/inpatient/doctors',token=users['a']['token']),'candidates')
    ids={d['id'] for d in candidates}
    assert users['a']['id'] in ids and users['b']['id'] in ids
    assert users['n']['id'] not in ids and users['c']['id'] not in ids
    print('e2e-v88-attending-eligibility passed: real doctor/nurse/admin admission, invalid target rejection, handoff and candidate parity')
finally:
    for adm in reversed(admissions): discharge_cleanup(t,adm)
