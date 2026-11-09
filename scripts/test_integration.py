#!/usr/bin/env python3
"""End-to-end contract/invariant tests for Java 17 local API. Uses stdlib only."""
import json,sys,urllib.request,urllib.error
BASE=sys.argv[1] if len(sys.argv)>1 else 'http://127.0.0.1:8098'
def req(method,path,obj=None,expected=200):
    data=json.dumps(obj).encode() if obj is not None else None
    r=urllib.request.Request(BASE+path,data=data,headers={'Content-Type':'application/json','Accept':'application/json'},method=method)
    try:
        with urllib.request.urlopen(r,timeout=4) as res: status=res.status; output=json.loads(res.read())
    except urllib.error.HTTPError as e: status=e.code; output=json.loads(e.read())
    assert status==expected, f'{method} {path} expected {expected}: {status} {output}'
    return output

health=req('GET','/api/health');assert health['publishing']=='DISABLED'
bootstrap=req('GET','/api/bootstrap');assert len(bootstrap['drafts'])>=7 and 'certificateSecret' not in bootstrap
assert bootstrap['runtime']['authentication'] is False
assert req('GET','/api/session')['authenticated'] is False
created=req('POST','/api/drafts',{'title':'Test regulated campaign','body':'Guaranteed returns on your investment!','platforms':'LinkedIn'},201)
did=created['id']; assert created['version']==1
print('✓ Draft created:',did)
validation=req('POST','/api/validations',{'draftId':did,'platform':'LinkedIn','altText':''},201)
assert validation['verdict']=='BLOCKED' and {'FIN-MKT-001','FIN-MKT-014'}.issubset({f['id'] for f in validation['findings']})
print('✓ Critical claim + mandatory disclosure detected')
review=req('POST',f'/api/reviews/{did}/decision',{'decision':'Approved'},409)
assert 'Blocking' in review['error'];print('✓ Blocking findings prevent approval')
updated=req('PUT',f'/api/drafts/{did}',{'title':'Updated regulated campaign','body':'We support your financial journey. Investing involves risk and outcomes vary.','altText':'A thoughtful financial wellness illustration.'})
assert updated['version']==2
assert req('PUT',f'/api/drafts/{did}',{'expectedVersion':1,'body':'wrong'},409)['status']==409
print('✓ Version control and optimistic conflict protection')
assert 'validation' in req('POST','/api/releases',{'draftId':did},409)['error'].lower()
green=req('POST','/api/validations',{'draftId':did,'platform':'LinkedIn','altText':updated['altText']},201)
assert green['verdict'] in ('PASS','WARN') and not green['findings'],green
print('✓ Clean validation passed')
review=req('POST',f'/api/reviews/{did}/decision',{'decision':'Approved','reviewer':'Maya Chen'})
assert review['decision']=='Approved'
release=req('POST','/api/releases',{'draftId':did},201)
assert release['signatureAlgorithm']=='HMAC-SHA256-LOCAL-DEMO'
verify=req('GET',f'/api/releases/{release["id"]}/verify'); assert verify['signatureValid'] is True
print('✓ Approved content issued and HMAC certificate verified:',release['id'])
changed=req('PUT',f'/api/drafts/{did}',{'body':'New text after approval, should invalidate approval.'})
assert changed['version']==3
assert req('POST','/api/releases',{'draftId':did},409)['status']==409
assert req('GET',f'/api/releases/{release["id"]}/verify')['signatureValid'] is True
print('✓ Draft edits invalidate new releases while preserving historical certificate')
audit=req('GET','/api/audit');assert audit['chainValid'] and len(audit['events'])>5
wf=req('POST','/api/workflows/test',{'risk':'critical'});assert len(wf['trace'])==5 and not wf['published']
policy=req('POST','/api/policies/simulate',{'strictness':4});assert policy['simulation']
incident=req('PATCH','/api/incidents/CMP-018',{'status':'Resolved'});assert incident['status']=='Resolved'
assert req('GET','/api/metrics')['totalDrafts']>=8
assert req('GET','/api/integrations')['zernio']['publishingEnabled'] is False
assert req('GET','/api/bootstrap')['reviewStatus'][did]=='Changes requested'
print('✓ Audit chain, workflow simulation, policies, incidents and metrics')
print('PASS — all Java17 local API integration scenarios completed')
