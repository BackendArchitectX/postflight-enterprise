#!/usr/bin/env python3
"""Evidence-backed Extended QA report. Coverage claims require browser outcome evidence."""
import argparse
import csv
import json
import pathlib
import sys
import time
from datetime import datetime, timezone

BLOCKERS = [
    ('REAL_CLERK_ROLES', 'Real Clerk OWNER/ADMIN/REVIEWER/VIEWER/OUTSIDER HTTP and browser matrix needs separate authorized disposable development identities and dedicated sandbox'),
    ('REAL_CLERK_REVOCATION', 'Real session renewal, revocation and cross-browser sign-out not instrumented'),
    ('FIREFOX_WEBKIT', 'Firefox and WebKit browser matrices have not been implemented'),
    ('VISUAL_REGRESSION', 'Deterministic screenshots, baseline approvals and visual diff assertions not implemented'),
    ('LOAD_AND_CRASH_RECOVERY', 'Dedicated bounded load, filesystem crash simulation and restart-recovery harness not implemented'),
    ('ZERNIO_LIVE', 'Real Zernio OAuth, publishing and third-party webhooks intentionally not executed; authorized sandbox required'),
    ('FULL_UI_STATE_COVERAGE', 'Current route sweep does not establish control outcome assertions for every authenticated role and conditional UI state'),
]

def load(path):
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError) as exc:
        return {'_load_error': str(exc)}

def row_signature(ctrl):
    return (ctrl.get('key',''), ctrl.get('value',''), ctrl.get('text',''))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--qa-root', required=True)
    ap.add_argument('--output', required=True)
    ap.add_argument('--started-epoch', type=float, required=True)
    ap.add_argument('--complete-exit', type=int, required=True)
    args = ap.parse_args()
    out = pathlib.Path(args.output).resolve()
    root = pathlib.Path(args.qa_root).resolve()
    out.mkdir(parents=True, exist_ok=True)
    candidates = []
    for p in root.glob('complete-*/browser-report.json'):
        if p.stat().st_mtime >= args.started_epoch - 4:
            candidates.append(p)
    baseline_path = max(candidates, key=lambda p:p.stat().st_mtime) if candidates else None
    baseline_folder = baseline_path.parent if baseline_path else None
    browser = load(baseline_path) if baseline_path else {'_load_error':'No fresh browser report produced by this Extended run'}
    api = load(baseline_folder/'extended-api.json') if baseline_folder else {'_load_error':'No fresh baseline folder'}
    accessibility = load(baseline_folder/'extended-accessibility.json') if baseline_folder else {'_load_error':'No fresh baseline folder'}
    report = {
        'schemaVersion':1, 'generatedAt':datetime.now(timezone.utc).isoformat(),
        'scope':'disposable automated QA; not production certification',
        'sources':{'browser':str(baseline_path) if baseline_path else None},
        'baselineProcessExit':args.complete_exit,
        'baseline':browser.get('coverage',{}),
        'api':{'checks':api.get('checks',[]),'errors':api.get('errors',[])},
        'accessibility':{'checks':accessibility.get('checks',[]),'errors':accessibility.get('errors',[])},
        'blocked':[{'id':key,'status':'BLOCKED','reason':reason} for key,reason in BLOCKERS],
        'status':'BLOCKED',
    }
    checks = []
    for prefix,src in (('BASELINE',browser),('EXTENDED_API',api),('AXE',accessibility)):
        for item in src.get('checks',[]):
            checks.append({'category':prefix,'name':item.get('name','?'),'status':item.get('status','UNKNOWN'),'detail':item.get('detail','')})
    if args.complete_exit != 0 or any(x['status']!='PASS' for x in checks) or any('_load_error' in x for x in (browser,api,accessibility)):
        report['status']='FAIL'
    report['checks']=checks
    report['errors']={k:src.get('_load_error') for k,src in (('browser',browser),('api',api),('accessibility',accessibility)) if '_load_error' in src}
    # One row per *rendered first-view instance*. Clicked alone is not evidence of outcome.
    rows=[]
    for route,data in browser.get('routeInventory',{}).items():
        clicked={row_signature(x.get('control',{})) for x in data.get('clicked',[])}
        nav={row_signature(x) for x in data.get('navigationControls',[])}
        dedicated={row_signature(x.get('control',{})):x.get('evidence','') for x in data.get('coveredByDedicatedTests',[])}
        untested={row_signature(x.get('control',{})) for x in data.get('notExercised',[]) if x.get('control')}
        unimplemented={row_signature(x) for x in data.get('unimplemented',[])}
        for ctrl in data.get('firstView',[]):
            sig=row_signature(ctrl)
            if sig in unimplemented: status,evidence='FAIL_IMPLEMENTATION','placeholder action'
            elif sig in untested: status,evidence='UNTESTED','explicitly not exercised'
            elif sig in dedicated: status,evidence='OUTCOME_ASSERTED',dedicated[sig]
            elif sig in nav: status,evidence='NAVIGATION_SMOKE','sidebar route check; click not business outcome'
            elif sig in clicked: status,evidence='CLICK_ONLY','element clicked; behavior assertion not individually traced'
            elif ctrl.get('disabled'): status,evidence='DISABLED_STATE','disabled UI; state transitions not covered here'
            else: status,evidence='UNMAPPED','no traced click or outcome assertion'
            rows.append({'route':route,'key':ctrl.get('key'),'value':ctrl.get('value'),'label':ctrl.get('text'),'instanceIndex':ctrl.get('index'),
                         'status':status,'evidence':evidence,'role':'PUBLIC_DEMO_ONLY','source':'browser firstView'})
    with (out/'coverage-matrix.csv').open('w',newline='',encoding='utf-8') as f:
        w=csv.DictWriter(f,fieldnames=['route','key','value','label','instanceIndex','status','evidence','role','source']);w.writeheader();w.writerows(rows)
    (out/'results.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    (out/'api-contract-report.json').write_text(json.dumps(report['api'],indent=2)+'\n',encoding='utf-8')
    (out/'performance-report.json').write_text(json.dumps({'diagnosticOnly':api.get('metrics',{}),'loadTest':'BLOCKED; metrics are not load-testing thresholds'},indent=2)+'\n',encoding='utf-8')
    findings=accessibility.get('violations',[])
    axelines=['# Accessibility report','',f'Automated findings: {len(findings)}','',
             'Axe tags: WCAG 2 A/AA and 2.1 A/AA; serious/critical violations fail the automated test.','',
             'Automated scans cannot certify WCAG compliance. Authenticated roles and screen-reader manual tests remain blocked.','']
    for v in findings:
        axelines.append(f"- **{v.get('impact','unknown')}** `{v.get('route')}`: `{v.get('id')}` — {v.get('description','')}")
    (out/'accessibility-report.md').write_text('\n'.join(axelines)+'\n',encoding='utf-8')
    sec=['# Security status','',f"Extended disposable API checks: {sum(c.get('status')=='PASS' for c in api.get('checks',[]))} PASS; {sum(c.get('status')=='FAIL' for c in api.get('checks',[]))} FAIL.",
         'This is not a penetration test or real Clerk role validation.','']
    sec += ['- **BLOCKED** '+reason for code,reason in BLOCKERS if 'CLERK' in code or 'ZERNIO' in code]
    (out/'security-report.md').write_text('\n'.join(sec)+'\n',encoding='utf-8')
    status_counts={}
    for row in rows: status_counts[row['status']]=status_counts.get(row['status'],0)+1
    summary=['# PostFlight Extended QA — evidence summary','',f"**Verdict: {report['status']}**",'',
             'A BLOCKED verdict is intentional until all mandatory categories are implemented and configured.','',
             f"Baseline exit code: {args.complete_exit}",
             f"Baseline browser assertions: {browser.get('coverage',{}).get('browserAssertionsPassed','unknown')} passed, {browser.get('coverage',{}).get('browserAssertionsFailed','unknown')} failed",
             f"Extended API: {sum(c.get('status')=='PASS' for c in api.get('checks',[]))} passed, {sum(c.get('status')=='FAIL' for c in api.get('checks',[]))} failed",
             f"Accessibility route scans: {sum(c.get('status')=='PASS' for c in accessibility.get('checks',[]))} passed, {sum(c.get('status')=='FAIL' for c in accessibility.get('checks',[]))} failed",
             f"Coverage instance classifications: {status_counts}",'','## Mandatory gaps (not silently skipped)','']
    summary += [f'- **{key} — BLOCKED:** {reason}' for key,reason in BLOCKERS]
    summary += ['','## Evidence','',f"Source browser report: {baseline_path or 'MISSING'}",'',
                'Generated: results.json, coverage-matrix.csv, api-contract-report.json, accessibility-report.md, security-report.md, performance-report.json.',
                'Playwright traces/HTML report are not generated by the legacy ad-hoc runner; see the baseline report directory for its browser screenshots.',
                'No authenticated production accounts or external social posting were used.']
    (out/'SUMMARY.md').write_text('\n'.join(summary)+'\n',encoding='utf-8')
    print(f'EXTENDED VERDICT: {report["status"]}; report: {out}')
    return 1 if report['status']=='FAIL' else 2  # Strict mode: BLOCKED cannot return 0.

if __name__=='__main__':
    sys.exit(main())
