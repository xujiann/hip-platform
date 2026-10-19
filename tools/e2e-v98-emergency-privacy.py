# -*- coding: utf-8 -*-
"""Actual JWTs and emergency ownership; anonymous reports never gain a hidden reporter."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,today_bj

suffix=str(time.time_ns())[-10:];tokens={'admin':login()};ids={}
for who,role in [('a','DOCTOR_OUTP'),('b','DOCTOR_OUTP'),('n','NURSE'),('n2','NURSE'),('q','QUALITY'),('tech','TECHNICIAN')]:
    tokens[who]=provision_user(tokens['admin'],'e2e98'+who+suffix,role,'V98'+who)
    ids[who]=ok(call('GET','/auth/me',token=tokens[who]),'identity')['id']
def expect(code,method,path,body=None,who='admin'):
    try:r=call(method,path,body,tokens[who])
    except urllib.error.HTTPError as e:
        assert e.code==403,(path,e.code);r=json.load(e)
    assert r['code']==code,(who,path,r)
    return r.get('data')
def triage(who='n'):
    return expect(0,'POST','/outpatient/triage',{'patientName':'V98虚构'+suffix,'level':2,'chiefComplaint':'V98虚构急诊','doctorId':ids['b'],'nurseId':ids['b']},who)['id']
def observation(t):
    expect(0,'POST','/outpatient/er-observation',{'triageId':t,'bedNo':'V98'+str(t)[-10:]},'n')
    return next(x['id'] for x in expect(0,'GET','/outpatient/er-observation',who='n') if x['triage_id']==t)
t=triage();o=observation(t);own=triage('a')
mine=next(x for x in expect(0,'GET','/outpatient/triage',who='a') if x['id']==own)
assert mine['doctorId']==ids['a'] and mine['nurseId']==ids['a']
expect(0,'PUT',f'/outpatient/triage/{t}/status?status=IN_TREATMENT',who='a')
expect(1005,'PUT',f'/outpatient/triage/{t}/status?status=IN_TREATMENT',who='b')
expect(0,'POST',f'/outpatient/er-observation/{o}/notes?note=V98nursing',who='n')
r=expect(0,'POST','/outpatient/er-rescue',{'observationId':o,'triageId':own,'measures':'V98虚构抢救'},'a')['id']
expect(9706,'PUT',f'/outpatient/triage/{t}/doctor',{'doctorId':ids['n']},'n')
expect(1005,'PUT',f'/outpatient/triage/{t}/doctor',{'doctorId':ids['b']},'a')
expect(0,'PUT',f'/outpatient/triage/{t}/doctor',{'doctorId':ids['b']},'n')
expect(1005,'GET',f'/outpatient/er-observation/{o}/notes',who='a')
expect(1005,'POST',f'/outpatient/er-observation/{o}/notes?note=V98denied',who='a')
expect(1005,'POST','/outpatient/er-rescue',{'observationId':o,'triageId':own},'a')
expect(1005,'PUT',f'/outpatient/er-rescue/{r}/end',{'outcome':'SUCCESS'},'a')
assert expect(0,'GET',f'/outpatient/er-rescue?observationId={o}',who='a')==[]
assert o not in {x['id'] for x in expect(0,'GET','/outpatient/er-observation',who='a')}
expect(1005,'PUT',f'/outpatient/er-observation/{o}/end?outcome=ADMITTED',who='n')
expect(1005,'PUT',f'/outpatient/er-rescue/{r}/end',{'outcome':'DEATH'},'n')
expect(1005,'POST','/outpatient/er-rescue',{'observationId':o,'outcome':'SUCCESS'},'n')
expect(0,'PUT',f'/outpatient/er-rescue/{r}/end',{'outcome':'SUCCESS'},'b')
expect(4566,'PUT',f'/outpatient/er-rescue/{r}/end',{'outcome':'DEATH'},'b')
expect(0,'PUT',f'/outpatient/er-observation/{o}/end?outcome=DISCHARGED',who='b')
expect(4563,'POST',f'/outpatient/er-observation/{o}/notes?note=V98denied',who='n')
expect(0,'POST','/outpatient/er-rescue',{'observationId':o,'measures':'V98历史补记'},'n')
# Preserve old post-observation rescue documentation while leaving no bed occupied.
assert all(x['note']!='V98denied' for x in expect(0,'GET',f'/outpatient/er-observation/{o}/notes',who='b'))
for who in ['q','tech']:
    for path in ['/outpatient/triage','/outpatient/er-observation','/outpatient/er-rescue']:
        expect(1005,'GET',path,who=who)

def report(who,anon,text):
    expect(0,'POST','/quality/adverse-events',{'type':'其他','level':2,'occurredOn':str(today_bj()),'description':text+suffix,'anonymous':anon,'reporterId':ids['n']},who)
report('n',False,'V98own');report('n2',False,'V98other');report('n',True,'V98anon')
rows=expect(0,'GET','/quality/adverse-events',who='n');assert len(rows)==1 and rows[0]['description']=='V98own'+suffix
mine=rows[0]['id'];assert not any(k in rows[0] for k in ['reporter_id','handler_id','created_at'])
allrows=expect(0,'GET','/quality/adverse-events',who='q');assert any(x['description']=='V98anon'+suffix and x['anonymous'] for x in allrows)
expect(1005,'PUT',f'/quality/adverse-events/{mine}/handle?note=denied',who='n')
expect(0,'PUT',f'/quality/adverse-events/{mine}/handle?note=V98reviewed',who='q')
expect(9805,'PUT',f'/quality/adverse-events/{mine}/handle?note=again',who='q')
for who in ['a','tech']:expect(1005,'GET','/quality/adverse-events',who=who)
menus=expect(0,'GET','/auth/me',who='n')['menus'];assert {'/specialty','/outpatient/triage','/quality'} <= {m['path'] for m in menus}
assert '/outpatient/triage' in {m['path'] for m in expect(0,'GET','/auth/me',who='a')['menus']}
for who in tokens:expect(0,'GET','/auth/me',who=who);expect(0,'GET','/stats/overview',who=who)
print('e2e-v98-emergency-privacy passed: real claim/transfer, emergency parent and medical-duty scopes, anonymous isolation and role menus')
