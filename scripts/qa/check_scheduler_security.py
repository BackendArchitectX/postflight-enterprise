#!/usr/bin/env python3
"""Offline safety contract for PostFlight's scheduled and privileged workflows.
Does not invoke GitHub APIs, create issues or mutate the repository.
"""
from pathlib import Path
import re
import subprocess

root=Path(__file__).resolve().parents[2]
paths=[
    root/'.github/workflows/complete-qa.yml',
    root/'.github/workflows/product-workflows.yml',
    root/'.github/workflows/ci-guardian.yml',
    root/'.github/workflows/ci-heartbeat.yml',
]
contents={p.name:p.read_text(encoding='utf-8') for p in paths}

for name,text in contents.items():
    assert 'pull_request_target:' not in text, f'{name}: unsafe pull_request_target'
    assert re.search(r'^permissions:',text,re.M), f'{name}: missing explicit token permissions'
    assert 'timeout-minutes:' in text, f'{name}: missing bounded runtime'
    assert 'concurrency:' in text, f'{name}: missing concurrency protection'
    for action in re.findall(r'^\s*uses:\s*(\S+)',text,re.M):
        assert re.search(r'@[0-9a-f]{40}$',action), f'{name}: unpinned action: {action}'

qa=contents['complete-qa.yml']
assert "cron: '30 2 * * *'" in qa, '08:00 IST daily QA missing'
assert 'branches: [main, feat/zernio-visitor-readonly]' in qa, 'main push test missing'
assert 'pull_request:' in qa and 'workflow_dispatch:' in qa
assert 'postflight-complete-qa' in qa, 'diagnostic artifacts missing'
assert 'retention-days: 14' in qa, 'artifact retention missing'
assert 'playwright@1.65.0' in qa, 'browser install is not pinned'
guardian=contents['ci-guardian.yml']
assert 'workflow_run:' in guardian and 'types: [completed]' in guardian
assert 'actions: write' in guardian and 'issues: write' in guardian
assert 'run_attempt' in guardian and 'attempt===1' in guardian
assert guardian.count('reRunFailedJobs')==1, 'retry must have exactly one callsite'
assert 'state: ' in guardian and 'state_reason:' in guardian, 'recovery handling incomplete'
assert 'run.head_repository.full_name' in guardian, 'fork authorization guard missing'
assert not re.search(r'uses:\s*actions/checkout@',guardian), 'privileged workflow may not check out PR code'
assert not re.search(r'(?i)auto.?merge|mergepullrequest|createorupdatefilecontents',guardian), 'privileged auto-merge or mutation forbidden'
heartbeat=contents['ci-heartbeat.yml']
assert "cron: '40 3,6 * * *'" in heartbeat
assert 'listWorkflowRuns' in heartbeat and 'event:' in heartbeat
assert 'ageHours<=25' in heartbeat
assert not re.search(r'uses:\s*actions/checkout@',heartbeat)
assert 'issues: write' in heartbeat and 'actions: read' in heartbeat

# The GitHub Script code is parsed in its async-function context. A syntax error in
# the YAML block otherwise would only become visible after the workflow is merged.
for workflow in ('ci-guardian.yml','ci-heartbeat.yml'):
    source=contents[workflow]
    match=re.search(r'^(\s*)script:\s*\|\s*$',source,re.M)
    assert match, f'{workflow}: missing inline script'
    lines=source[match.end():].splitlines()
    code=[]
    indent=len(match.group(1))+2
    for line in lines:
        if line.strip() and (len(line)-len(line.lstrip(' ')))<indent:
            break
        code.append(line[indent:] if len(line)>=indent else '')
    result=subprocess.run(['node','--check','-'],input='async function check(){\n'
        +'\n'.join(code)+'\n}\n',text=True,capture_output=True,check=False)
    assert result.returncode==0, f'{workflow}: JS syntax invalid: {result.stderr}'

print('PASS - all GitHub actions pinned to commits and explicit least-privilege permissions')
print('PASS - daily 08:00 IST QA, main/PR triggers and missed-schedule watchdog')
print('PASS - one bounded retry, issue lifecycle, no privileged checkout or automated merges')
print('PASS - privileged workflow JavaScript parsed successfully')
