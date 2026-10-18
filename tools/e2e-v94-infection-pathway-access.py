# -*- coding: utf-8 -*-
"""Real JWT catheter duties and attending-scoped clinical pathway transactions."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,new_patient,find_free_bed,discharge_cleanup

t=login();suffix=str(time.time_ns())[-10:];tokens={'admin':t};ids={};admissions=[]
for key,role in [('a','DOCTOR_OUTP'),('b','DOCTOR_OUTP'),('n','NURSE'),('q','QUALITY'),('tech','TECHNICIAN'),('cash','CASHIER')]:
    tokens[key]=provision_user(t,'e2e94'+key+suffix,role,'V94'+key)
    me=ok(call('GET','/auth/me',token=tokens[key]),'identity');ids[key]=me['id']
    if key=='a':assert '/patientcare' in {m['path'] for m in me['menus']}
    if key=='n':assert '/infection-plus' in {m['path'] for m in me['menus']}

def expect(code,method,path,body=None,who='admin'):
    try:r=call(method,path,body,tokens[who])
    except urllib.error.HTTPError as e:
        assert e.code==403,(path,e.code)
        r=json.load(e)
    assert r['code']==code,(who,path,r)
    return r.get('data')

def admit(owner):
    p=new_patient(t,'V94虚构'+str(time.time_ns()),sex='U');bed=find_free_bed(t)
    body={'patientId':p['id'],'deptId':1,'bedId':bed['id'],'deposit':0,'payMethod':'CASH'}
    if owner:body['doctorId']=owner
    a=ok(call('POST','/inpatient/admissions',body,t),'admit');admissions.append(a['id']);return a['id']

def template(label,items):
    name='V94'+suffix+label
    expect(0,'POST','/pathways',{'name':name,'icdPrefix':'V94','items':items},'q')
    return next(x['id'] for x in expect(0,'GET','/pathways') if x['name']==name),name

def enrollment(adm):return next(x for x in expect(0,'GET','/pathways/enrollments') if x['admission_id']==adm)

try:
    own=admit(ids['a']);foreign=admit(ids['b']);free=admit(None)
    tpl,name=template('normal',[])
    expect(1005,'POST','/pathways',{'name':'V94 denied','icdPrefix':'V94','items':[]},'n')
    for adm in [own,foreign,free]:expect(0,'POST',f'/pathways/{tpl}/apply/{adm}')
    e=enrollment(own);variance={'dayNo':1,'varType':'DELAY','reason':'V94'}
    for who in ['n','q','tech','cash']:
        expect(1005,'POST',f'/pathways/{tpl}/apply/{own}',who=who)
        expect(1005,'POST',f"/pathways/enrollments/{e['id']}/variances",variance,who)
        expect(1005,'PUT',f"/pathways/enrollments/{e['id']}/complete",who=who)
    expect(9018,'POST',f"/pathways/enrollments/{e['id']}/variances",variance,'b')
    expect(9018,'PUT',f"/pathways/enrollments/{e['id']}/complete",who='b')
    mine=expect(0,'GET','/pathways/enrollments',who='a')
    assert {own,free}<={x['admission_id'] for x in mine} and foreign not in {x['admission_id'] for x in mine}
    stat=next(x for x in expect(0,'GET','/pathways/variance-stats',who='a') if x['pathway_name']==name)
    assert stat['enrolled']==2,stat
    for who in ['n','q']:
        assert {own,foreign,free}<={x['admission_id'] for x in expect(0,'GET','/pathways/enrollments',who=who)}
    expect(0,'POST',f"/pathways/enrollments/{e['id']}/variances",variance,'a')
    expect(0,'PUT',f'/inpatient/admissions/{own}/attending-doctor',{'doctorId':ids['b']},'a')
    expect(9018,'PUT',f"/pathways/enrollments/{e['id']}/complete",who='a')
    expect(0,'PUT',f"/pathways/enrollments/{e['id']}/complete",who='b')
    expect(9908,'POST',f"/pathways/enrollments/{e['id']}/variances",variance,'b')
    expect(9906,'POST',f'/pathways/{tpl}/apply/{free}',who='a')
    item=next(x for x in expect(0,'GET','/masterdata/charge-items') if x.get('enabled',True) and x['category']=='TREAT')
    bad,badname=template('rollback',[{'orderType':'TREAT','itemId':item['id'],'qty':1},{'orderType':'TREAT','itemId':-94,'qty':1}])
    before=expect(0,'GET',f'/inpatient/admissions/{free}/workspace')['orders']
    expect(9007,'POST',f'/pathways/{bad}/apply/{free}',who='a')
    assert expect(0,'GET',f'/inpatient/admissions/{free}/workspace')['orders']==before
    assert not any(x['template_id']==bad for x in expect(0,'GET','/pathways/enrollments'))
    for typ in ['VENT','CVC','URINARY']:
        expect(1005,'POST','/infection-plus/catheters',{'admissionId':own,'lineType':typ},'q')
        expect(0,'POST','/infection-plus/catheters',{'admissionId':own,'lineType':typ},'n')
        expect(4752,'POST','/infection-plus/catheters',{'admissionId':own,'lineType':typ},'n')
    rows=[x for x in expect(0,'GET','/infection-plus/catheters') if x['admission_id']==own]
    line=next(x for x in rows if x['line_type']=='VENT')['id']
    for who in ['q','a','tech','cash']:
        expect(1005,'POST',f'/infection-plus/catheters/{line}/assess?keepLine=true',who=who)
        expect(1005,'PUT',f'/infection-plus/catheters/{line}/remove',who=who)
    expect(0,'POST',f'/infection-plus/catheters/{line}/assess?keepLine=true',who='n')
    expect(0,'PUT',f'/infection-plus/catheters/{line}/remove',who='n')
    expect(4753,'POST',f'/infection-plus/catheters/{line}/assess?keepLine=true',who='n')
    for who in ['n','q']:
        for path in ['catheters','rate','prevalence']:expect(0,'GET','/infection-plus/'+path,who=who)
    # Historical active lines can still be closed/reported after discharge.
    discharge_cleanup(t,own);admissions.remove(own)
    expect(4751,'POST','/infection-plus/catheters',{'admissionId':own,'lineType':'VENT'},'n')
    cvc=next(x for x in rows if x['line_type']=='CVC')['id']
    expect(0,'PUT',f'/infection-plus/catheters/{cvc}/infect?pathogen=V94',who='q')
    expect(4753,'PUT',f'/infection-plus/catheters/{cvc}/infect',who='q')
    urinary=next(x for x in rows if x['line_type']=='URINARY')['id']
    expect(0,'PUT',f'/infection-plus/catheters/{urinary}/remove',who='n')
    print('e2e-v94-infection-pathway-access passed: real JWT duties, attending scopes, handoff, rollback, duplicate/lifecycle and feedback')
finally:
    for adm in admissions:discharge_cleanup(t,adm)
