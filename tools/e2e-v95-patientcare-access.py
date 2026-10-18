# -*- coding: utf-8 -*-
"""Followup owners, invited consult departments, and assigned examiners with real JWTs."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,new_patient,find_free_bed,discharge_cleanup,today_bj

t=login();suffix=str(time.time_ns())[-10:];tokens={'admin':t};ids={};admissions=[]
roles={'a':'DOCTOR_OUTP','b':'DOCTOR_OUTP','c':'DOCTOR_OUTP','n':'NURSE','n2':'NURSE','q':'QUALITY','tech':'TECHNICIAN','cash':'CASHIER'}
for key,role in roles.items():
    name='e2e95'+key+suffix
    tokens[key]=provision_user(t,name,role,'V95'+key)
    me=ok(call('GET','/auth/me',token=tokens[key]),'identity');ids[key]=me['id']
    if key in ['a','b','n']:
        dept=2 if key=='b' else 1
        ok(call('PUT',f"/system/users/{ids[key]}",{'username':name,'realName':'V95'+key,'deptId':dept,'roleCodes':[role]},t),'department')
    if key=='n':assert '/patientcare' in {m['path'] for m in me['menus']}

def expect(code,method,path,body=None,who='admin'):
    try:r=call(method,path,body,tokens[who])
    except urllib.error.HTTPError as e:
        assert e.code==403,(path,e.code)
        r=json.load(e)
    assert r['code']==code,(who,path,r)
    return r.get('data')

try:
    p=new_patient(t,'V95虚构'+suffix,sex='U')
    for who in ['a','b','n']:
        expect(0,'POST','/patientcare/followups',{'patientId':p['id'],'topic':'V95'+who+suffix,'dueDate':str(today_bj())},who)
    all_tasks=expect(0,'GET','/patientcare/followups',who='q')
    tasks={who:next(x for x in all_tasks if x['topic']=='V95'+who+suffix) for who in ['a','b','n']}
    for who in ['a','b','n']:
        mine=expect(0,'GET','/patientcare/followups',who=who)
        assert tasks[who]['id'] in {x['id'] for x in mine}
        assert all(x['creator_id']==ids[who] for x in mine)
    for who in ['b','n','q']:
        expect(1005,'PUT',f"/patientcare/followups/{tasks['a']['id']}/done?note=V95",who=who)
    expect(0,'PUT',f"/patientcare/followups/{tasks['a']['id']}/done?note=V95",who='a')
    expect(9901,'PUT',f"/patientcare/followups/{tasks['a']['id']}/done?note=V95",who='a')
    expect(1005,'POST','/patientcare/followups',{'patientId':p['id'],'topic':'V95 denied','dueDate':str(today_bj())},'q')

    bed=find_free_bed(t)
    a=ok(call('POST','/inpatient/admissions',{'patientId':p['id'],'deptId':1,'bedId':bed['id'],'doctorId':ids['a'],'deposit':0,'payMethod':'CASH'},t),'admit');adm=a['id'];admissions.append(adm)
    request={'admissionId':adm,'toDeptId':2,'question':'V95'+suffix}
    expect(9018,'POST','/inpatient/consults',request,'b')
    for who in ['n','q','tech','cash']:expect(1005,'POST','/inpatient/consults',request,who)
    expect(0,'POST','/inpatient/consults',request,'a')
    consult=next(x for x in expect(0,'GET','/inpatient/consults') if x['question']=='V95'+suffix)
    assert consult['id'] not in {x['id'] for x in expect(0,'GET','/inpatient/consults',who='c')}
    assert consult['id'] in {x['id'] for x in expect(0,'GET','/inpatient/consults',who='b')}
    expect(1005,'PUT',f"/inpatient/consults/{consult['id']}/complete?opinion=V95",who='a')
    for who in ['n','q']:expect(1005,'PUT',f"/inpatient/consults/{consult['id']}/complete?opinion=V95",who=who)
    # Revoking the invited department invalidates the existing token's write capability immediately.
    uname='e2e95b'+suffix
    expect(0,'PUT',f"/system/users/{ids['b']}",{'username':uname,'realName':'V95b','deptId':1,'roleCodes':['DOCTOR_OUTP']})
    expect(1005,'PUT',f"/inpatient/consults/{consult['id']}/complete?opinion=V95",who='b')
    expect(0,'PUT',f"/system/users/{ids['b']}",{'username':uname,'realName':'V95b','deptId':2,'roleCodes':['DOCTOR_OUTP']})
    expect(0,'PUT',f'/inpatient/admissions/{adm}/attending-doctor',{'doctorId':ids['c']})
    expect(9018,'POST','/inpatient/consults',request,'a')
    discharge_cleanup(t,adm);admissions.remove(adm)
    expect(9917,'POST','/inpatient/consults',request,'c')
    expect(0,'PUT',f"/inpatient/consults/{consult['id']}/complete?opinion=V95",who='b')
    expect(9903,'PUT',f"/inpatient/consults/{consult['id']}/complete?opinion=V95",who='b')

    pname='V95'+suffix
    package={'name':pname,'price':1,'items':'V95'}
    expect(1005,'POST','/exam/packages',package,'n');expect(1005,'POST','/exam/packages',package,'a')
    expect(0,'POST','/exam/packages',package,'q')
    pkg=next(x['id'] for x in expect(0,'GET','/exam/packages') if x['name']==pname)
    expect(9916,'POST',f"/exam/records?patientId={p['id']}&packageId={pkg}&examinerId={ids['n']}",who='n')
    expect(1005,'POST',f"/exam/records?patientId={p['id']}&packageId={pkg}",who='q')
    expect(0,'POST',f"/exam/records?patientId={p['id']}&packageId={pkg}",who='a')
    own=next(x for x in expect(0,'GET','/exam/records',who='a') if x['package_name']==pname)
    assert own['examiner_id']==ids['a'] and own['registrar_id']==ids['a'] and own['examiner_name']=='V95a'
    expect(0,'PUT',f"/exam/records/{own['id']}/complete?summary=V95",who='a')
    group={'unitName':'V95 group'+suffix,'packageId':pkg,'examinerId':ids['a'],'memberNames':['V95甲'+suffix,'V95乙'+suffix]}
    expect(1005,'POST','/exam/groups',group,'q')
    g=expect(0,'POST','/exam/groups',group,'n')['groupId']
    expect(1005,'GET',f'/exam/groups/{g}/records',who='n2')
    expect(1005,'GET',f'/exam/groups/{g}/records',who='b')
    members=expect(0,'GET',f'/exam/groups/{g}/records',who='n');target=members[0]['id']
    expect(1005,'PUT',f'/exam/records/{target}/complete?summary=V95',who='n')
    expect(1005,'PUT',f'/exam/records/{target}/examiner',{'examinerId':ids['b']},'a')
    expect(0,'PUT',f'/exam/records/{target}/examiner',{'examinerId':ids['b']})
    expect(1005,'PUT',f'/exam/records/{target}/complete?summary=V95',who='a')
    expect(0,'PUT',f'/exam/records/{target}/complete?summary=V95&abnormal=true',who='b')
    expect(9905,'PUT',f'/exam/records/{target}/examiner',{'examinerId':ids['a']})
    partial=next(x for x in expect(0,'GET','/exam/groups',who='a') if x['id']==g)
    assert partial['members']==1 and partial['done']==0 and partial['abnormal_cnt']==0,partial
    assert len(expect(0,'GET',f'/exam/groups/{g}/records',who='a'))==1
    assert len(expect(0,'GET',f'/exam/groups/{g}/records',who='q'))==2
    for who in tokens:
        expect(0,'GET','/auth/me',who=who);expect(0,'GET','/stats/overview',who=who)
    print('e2e-v95-patientcare-access passed: followup owners, invited live department, handoff/discharge, assigned examiners, scoped group aggregates and role usability')
finally:
    for adm in admissions:discharge_cleanup(t,adm)
