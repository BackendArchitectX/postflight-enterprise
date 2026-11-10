#!/usr/bin/env python3
"""Governance workflow contracts against disposable local PostFlight state."""
import json
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:10004').rstrip('/')


def call(method, path, payload=None):
    headers = {'Accept': 'application/json'}
    if payload is not None:
        headers['Content-Type'] = 'application/json'
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=None if payload is None else json.dumps(payload).encode())
    try:
        with urllib.request.urlopen(req, timeout=12) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode())


code, d = call('POST','/api/drafts', {
    'title': 'Governance workflow regression', 'body': 'Investments involve risk. Test sample content.',
    'platforms': 'LinkedIn'
})
assert code == 201, (code,d)
draft_id=d['id']
version=d['version']

code, invalid=call('POST','/api/approvals/requests',{'draftId':draft_id,'reviewer':'QA Reviewer'})
assert code == 409, (code, invalid)

code, validation=call('POST','/api/validations',{'draftId':draft_id,'platform':'LinkedIn',
                                                'altText':'Test image description'})
assert code == 201, (code,validation)
assert validation['verdict'] != 'BLOCKED', validation

code, mismatch=call('POST','/api/approvals/requests', {
    'draftId':draft_id,'reviewer':'QA Reviewer','expectedVersion':version+1
})
assert code == 409, (code,mismatch)

code, approval=call('POST','/api/approvals/requests', {
    'draftId':draft_id,'reviewer':'QA Reviewer','expectedVersion':version,
    'note':'Risk disclosure verified; ready for human review'
})
assert code == 201, (code,approval)
assert approval['draftVersion'] == version and approval['status'] == 'Pending'
code, duplicate=call('POST','/api/approvals/requests',{
    'draftId':draft_id,'reviewer':'Another reviewer','expectedVersion':version
})
assert code == 409, (code,duplicate)

code, all_requests=call('GET','/api/approvals/requests')
assert code == 200 and any(req['id']==approval['id'] for req in all_requests)

code, reviewed=call('POST','/api/reviews/'+draft_id+'/decision',{
    'decision':'Approved','reviewer':'QA Reviewer','comment':'Approved after review'
})
assert code == 200, (code,reviewed)
code, requests_after_review=call('GET','/api/approvals/requests')
matched=next(req for req in requests_after_review if req['id']==approval['id'])
assert matched['status'] == 'Approved' and matched['resolvedAt']

# Request a second approval for the same version, then edit to supersede it.
code, pending=call('POST','/api/approvals/requests',{
    'draftId':draft_id,'reviewer':'QA Reviewer','expectedVersion':version
})
assert code == 201, (code,pending)
code, updated=call('PUT','/api/drafts/'+draft_id,{
    'body':'Investments involve risk. Updated after second approval request.',
    'expectedVersion':version
})
assert code == 200 and updated['version'] == version+1, (code,updated)
code, requests_after_edit=call('GET','/api/approvals/requests')
superseded=next(req for req in requests_after_edit if req['id']==pending['id'])
assert superseded['status'] == 'Superseded', superseded

# Policy publication uses explicit review confirmation, concurrency and immutable snapshots.
code, policy=call('GET','/api/policies')
assert code == 200 and policy['version']

code, no_approval=call('POST','/api/policies/publications',{
    'expectedVersion':policy['version'],'summary':'Reviewed local rules',
    'reviewedBy':'QA Policy Reviewer','approval':'no'
})
assert code == 400, (code,no_approval)

code, wrong_policy=call('POST','/api/policies/publications',{
    'expectedVersion':'stale-policy','summary':'Reviewed local rules',
    'reviewedBy':'QA Policy Reviewer','approval':'APPROVED'
})
assert code == 409, (code,wrong_policy)

code, publication=call('POST','/api/policies/publications',{
    'expectedVersion':policy['version'],'summary':'Reviewed rules for demo',
    'reviewedBy':'QA Policy Reviewer','approval':'APPROVED'
})
assert code == 201 and publication['externalDeployment'] is False, (code,publication)
assert len(publication['contentHash']) == 64
code, duplicate_policy=call('POST','/api/policies/publications',{
    'expectedVersion':policy['version'],'summary':'Again',
    'reviewedBy':'QA Policy Reviewer','approval':'APPROVED'
})
assert code == 409, (code,duplicate_policy)
code, snapshots=call('GET','/api/policies/publications')
assert code == 200 and any(p['id']==publication['id'] for p in snapshots)

# Workflow steps use named stages, bounded SLA, optimistic revision and recorded history.
code, settings=call('GET','/api/workflows/steps')
assert code == 200 and isinstance(settings, dict)
step='Legal review'
current=settings.get(step,{})
rev=current.get('revision',0)
path='/api/workflows/steps/Legal%20review'
code, invalid_sla=call('PUT',path,{
    'owner':'Legal QA','onFailure':'Escalate','slaHours':0,'reason':'Bad SLA',
    'expectedRevision':rev
})
assert code == 400, (code,invalid_sla)
code, edited=call('PUT',path,{
    'owner':'Legal QA','onFailure':'Block release','slaHours':12,
    'reason':'Escalate regulated marketing issues quickly','expectedRevision':rev
})
assert code == 200 and edited['revision'] == rev+1, (code,edited)
assert edited['owner']=='Legal QA' and edited['slaHours'] == 12

code, conflict=call('PUT',path,{
    'owner':'Stale reviewer','onFailure':'Escalate','slaHours':24,
    'reason':'Concurrent stale revision','expectedRevision':rev
})
assert code == 409, (code,conflict)
code, no_stage=call('PUT','/api/workflows/steps/Unknown',{
    'owner':'X','onFailure':'Escalate','slaHours':24,'reason':'Unknown'
})
assert code == 404, (code,no_stage)
code, revisions=call('GET','/api/workflows/revisions')
assert code == 200 and revisions[0]['step'] == step
assert revisions[0]['previous'].get('revision',0) == rev
code, audit=call('GET','/api/audit')
assert code == 200 and audit['chainValid'] is True
actions={e['action'] for e in audit['events']}
assert {'APPROVAL_REQUESTED','POLICY_SNAPSHOT_PUBLISHED','WORKFLOW_STEP_REVISED'}.issubset(actions)

code, bootstrap=call('GET','/api/bootstrap')
assert code == 200 and bootstrap['workflowSteps'][step]['revision'] == rev+1
assert bootstrap['policyPublications'][0]['id'] == publication['id']
assert bootstrap['approvalRequests'][0]['id'] == pending['id']
assert bootstrap['approvalRequests'][0]['status'] == 'Superseded'

print('PASS - draft-version approval requests, duplicate guard, approval and supersession')
print('PASS - immutable reviewed policy snapshot, stale version guard, explicit signoff')
print('PASS - workflow step revision history, owner, SLA, concurrency and XSS-safe data')
print('PASS - persisted bootstrap, hash-linked governance audit and no remote deployment')
