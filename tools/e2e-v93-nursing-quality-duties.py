# -*- coding: utf-8 -*-
"""Real JWT checks for nursing execution, quality management and global report boundaries."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,new_patient,find_free_bed,discharge_cleanup,today_bj

t=login();tokens={'admin':t};ids={};admissions=[];suffix=str(time.time_ns())[-10:]
for key,role in [('n','NURSE'),('b','NURSE'),('q','QUALITY'),('d','DOCTOR_OUTP'),('tech','TECHNICIAN'),('cash','CASHIER')]:
    tokens[key]=provision_user(t,'e2e93'+key+suffix,role,'V93'+key)
    me=ok(call('GET','/auth/me',token=tokens[key]),'identity');ids[key]=me['id']
    if key in ['n','b','q']:assert '/nursing-plus' in {m['path'] for m in me['menus']}

def expect(code,method,path,body=None,who='admin'):
    try:r=call(method,path,body,tokens[who])
    except urllib.error.HTTPError as e:
        assert e.code==403,(path,e.code)
        r=json.load(e)
    assert r['code']==code,(who,path,r)
    return r.get('data')

try:
    p=new_patient(t,'V93虚构'+suffix,sex='U');bed=find_free_bed(t)
    a=ok(call('POST','/inpatient/admissions',{'patientId':p['id'],'deptId':1,'bedId':bed['id'],'deposit':0,'payMethod':'CASH'},t),'admit');adm=a['id'];admissions.append(adm)
    for method,path,body in [('PUT',f'/inpatient/admissions/{adm}/care-level?level=特级',None),('PUT',f'/inpatient/admissions/{adm}/care-level/change',{'level':'特级','reason':'V93'}),('POST','/nursing/risk-assess',{'admissionId':adm,'assessType':'BRADEN','score':12}),('POST','/nursing/shifts',{'deptId':1,'nurseName':'V93'+suffix,'shiftDate':str(today_bj()),'shiftType':'DAY'}),('POST','/nursing/shift-types',{'code':'V93'+suffix,'name':'V93','startTime':'08:00','endTime':'16:00'}),('POST','/nursing/handovers',{'deptId':1,'shiftType':'DAY','summary':'V93'+suffix})]:
        expect(1005,method,path,body,'q');expect(0,method,path,body,'n')
    trend=expect(0,'GET',f'/nursing/risk-assess?admissionId={adm}',who='q');assert trend[-1]['risk_level']=='HIGH'
    ho=next(x for x in expect(0,'GET','/nursing/handovers',who='q') if x['summary']=='V93'+suffix)
    expect(1005,'PUT',f"/nursing/handovers/{ho['id']}/receive",who='q')
    expect(4812,'PUT',f"/nursing/handovers/{ho['id']}/receive",who='n')
    expect(0,'PUT',f"/nursing/handovers/{ho['id']}/receive",who='b')
    expect(4810,'PUT',f"/nursing/handovers/{ho['id']}/receive",who='n')
    for method,path,body in [('POST','/nursing/qc-scores',{'deptId':1,'item':'V93'+suffix,'score':93}),('POST','/qc-check/plans',{'title':'V93'+suffix,'standard':'V93'})]:
        expect(1005,method,path,body,'n');expect(0,method,path,body,'q')
    plan=next(x for x in expect(0,'GET','/qc-check/plans',who='n') if x['title']=='V93'+suffix)
    score={'planId':plan['id'],'target':'V93'+suffix,'score':91}
    expect(1005,'POST','/qc-check/scores',score,'n');expect(0,'POST','/qc-check/scores',score,'q')
    assert any(x['target']=='V93'+suffix and x['score']==91 for x in expect(0,'GET',f"/qc-check/scores?planId={plan['id']}",who='n'))
    expect(0,'POST','/infection/cards',{'patientId':p['id'],'diseaseName':'V93虚构'+suffix,'cardClass':'B'},'n')
    card=next(x for x in expect(0,'GET','/infection/cards',who='q') if x['disease_name']=='V93虚构'+suffix)
    expect(1005,'PUT',f"/infection/cards/{card['id']}/review?approve=true",who='n');expect(0,'PUT',f"/infection/cards/{card['id']}/review?approve=true",who='q')
    expect(1005,'PUT',f"/infection/cards/{card['id']}/submit",who='n');expect(0,'PUT',f"/infection/cards/{card['id']}/submit",who='q')
    expect(0,'POST','/quality/adverse-events',{'type':'跌倒','level':3,'occurredOn':str(today_bj()),'description':'V93虚构'+suffix,'deptId':1,'anonymous':False},'n')
    event=next(x for x in expect(0,'GET','/quality/adverse-events',who='q') if x['description']=='V93虚构'+suffix)
    expect(1005,'PUT',f"/quality/adverse-events/{event['id']}/handle?note=V93",who='n');expect(0,'PUT',f"/quality/adverse-events/{event['id']}/handle?note=V93",who='q')
    for path in ['/nursing/qc-scores','/nursing/qc-scores/summary','/nursing/shift-types','/nursing/handovers','/inpatient/nursing/board','/nursing/postop-fever','/nursing/risk-assess/alerts',f'/nursing/temp-calendar?admissionId={adm}']:
        for who in ['n','q']:expect(0,'GET',path,who=who)
    for path in ['/quality/emr-timeliness','/quality/emr-timeliness/board','/quality/med-records','/quality/mr-workqueue',f'/inpatient/admissions/{adm}/front-page',f'/quality/mr-qc/summary/{adm}','/quality/mr-qc/stats']:
        for who in ['n','d','tech','cash']:expect(1005,'GET',path,who=who)
        expect(0,'GET',path,who='q')
    for path,header in [('/quality/mr-qc/stats.csv','月份,科室'),('/quality/mr-qc/deduct-items.csv','扣分')]:
        for who in ['n','d','tech','cash']:expect(1005,'GET',path,who=who)
        csv=call('GET',path,token=tokens['q'],raw=True);assert header in csv,csv
    discharge_cleanup(t,adm);admissions.remove(adm)
    expect(4805,'PUT',f'/inpatient/admissions/{adm}/care-level?level=特级',who='n')
    expect(4805,'PUT',f'/inpatient/admissions/{adm}/care-level/change',{'level':'特级','reason':'V93'},'n')
    expect(4780,'POST','/nursing/risk-assess',{'admissionId':adm,'assessType':'MORSE','score':50},'n')
    expect(1005,'PUT',f'/inpatient/admissions/{adm}/archive',who='n');expect(0,'PUT',f'/inpatient/admissions/{adm}/archive',who='q')
    for who in tokens:expect(0,'GET','/auth/me',who=who);expect(0,'GET','/stats/overview',who=who)
    print('e2e-v93-nursing-quality-duties passed: nursing/quality writes, handover separation, risk and care lifecycle, global JSON/CSV reports, feedback and role usability')
finally:
    for adm in admissions:discharge_cleanup(t,adm)
