# -*- coding: utf-8 -*-
"""Real JWTs: collector isolation, aggregate/comment scope and read-only quality access."""
import json,time,urllib.error
from e2elib import call,ok,login,provision_user,new_patient

BASE='/patientcare/satisfaction'
t=login();suffix=str(time.time_ns())[-10:];tokens={'admin':t};ids={}
for key,role in {'a':'DOCTOR_OUTP','b':'DOCTOR_OUTP','n':'NURSE','n2':'NURSE','q':'QUALITY','cash':'CASHIER','tech':'TECHNICIAN','interface':'INTERFACE'}.items():
    # INTERFACE cannot use the employee change-password route. Complete ordinary initial
    # account setup first, then assign the final duty via the real administrator API.
    tokens[key]=provision_user(t,'e2e96'+key+suffix,'TECHNICIAN' if role=='INTERFACE' else role,'V96'+key)
    me=ok(call('GET','/auth/me',token=tokens[key]),'identity');ids[key]=me['id']
    if role=='INTERFACE':ok(call('PUT',f'/system/users/{ids[key]}',{'username':'e2e96'+key+suffix,'realName':'V96'+key,'roleCodes':['INTERFACE']},t),'interface duty')
    if key in ['a','b','n','n2','q']:assert '/patientcare' in {x['path'] for x in me['menus']}

def expect(code,method,path,body=None,who='admin'):
    try:r=call(method,path,body,tokens[who])
    except urllib.error.HTTPError as e:
        assert e.code==403,(path,e.code)
        r=json.load(e)
    assert r['code']==code,(who,path,r)
    return r.get('data')

def stats(who):return expect(0,'GET',BASE+'/stats',who=who)

before=stats('admin')['count']
p=new_patient(t,'V96虚构反馈'+suffix,sex='U')
for who,score in [('a',2),('b',1),('n',5),('n2',4)]:
    expect(0,'POST',BASE,{'patientId':p['id'],'source':'PORTAL','score':score,'comment':'V96'+who+suffix,'creatorId':ids['b'],'creator_id':ids['b']},who)
    result=stats(who)
    assert result['count']==1 and result['avgScore']==score,result
    assert [x['comment'] for x in result['recent']]==['V96'+who+suffix],result
    assert set(result)=={'count','avgScore','recent'}
    assert set(result['recent'][0])=={'score','comment','source','created_at'}
assert stats('admin')['count']==before+4 and stats('q')['count']==before+4
for who in ['q','cash','tech','interface']:
    expect(1005,'POST',BASE,{'score':5,'comment':'V96 forbidden'+suffix},who)
for who in ['cash','tech','interface']:expect(1005,'GET',BASE+'/stats',who=who)
assert stats('admin')['count']==before+4
# Anonymous patient is supported; the authenticated collector is still known.
expect(0,'POST',BASE,{'score':4,'comment':'V96 anonymous'+suffix},'a')
assert stats('a')['count']==2 and stats('a')['avgScore']==3
assert stats('a')['recent'][0]['source']=='OUTP'
for payload,code in [({},9902),({'score':0},9902),({'score':6},9902),({'score':5,'patientId':-96},9919),({'score':5,'source':'S'*17},9919),({'score':5,'comment':'评'*501},9919)]:
    expect(code,'POST',BASE,payload,'a')
assert stats('a')['count']==2
# More than twenty foreign submissions must not displace one's own older rows or influence aggregates.
for i in range(22):expect(0,'POST',BASE,{'score':1,'comment':f'V96 foreign{i}'+suffix},'b')
mine=expect(0,'GET',BASE+f'/stats?creatorId={ids["b"]}&patientId={p["id"]}&source=PORTAL',who='a')
assert mine['count']==2 and mine['avgScore']==3 and len(mine['recent'])==2,mine
foreign=stats('b');assert foreign['count']==23 and len(foreign['recent'])==20 and foreign['avgScore']==1,foreign
assert stats('q')['count']==before+27 and stats('admin')['count']==before+27
# Existing JWTs use current account duties; a normal committed role change must take effect.
name='e2e96n'+suffix
expect(0,'PUT',f'/system/users/{ids["n"]}',{'username':name,'realName':'V96n','roleCodes':['CASHIER']})
expect(1005,'GET',BASE+'/stats',who='n');expect(1005,'POST',BASE,{'score':5},'n')
expect(0,'PUT',f'/system/users/{ids["n"]}',{'username':name,'realName':'V96n','roleCodes':['NURSE']})
assert stats('n')['count']==1
for who in tokens:
    expect(0,'GET','/auth/me',who=who)
    # Its existing machine-only whitelist intentionally excludes the employee dashboard.
    expect(1005 if who=='interface' else 0,'GET','/stats/overview',who=who)
print('e2e-v96-satisfaction-access passed: real collectors, anonymous/same patient, scoped totals/averages/recent, quality read-only, role usability and no-write rejection')
