#!/usr/bin/env python3
"""Conservative static inventory. NOT an exhaustive interactive/browser audit.

Discover literal DOM data attributes, common button helpers, and API path
literals. Dynamic/role-dependent elements require Playwright verification.
"""
import argparse
import csv
import json
import re
import subprocess
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

DATA_ATTR = re.compile(r'''data-([a-z][a-z0-9-]*)\s*=\s*(["'])(.*?)\2''', re.S)
BUTTON_HELPER = re.compile(r'''\b(?:btn|button)\(\s*(?:(?:[A-Za-z_$][\w$]*|[A-Za-z_$][\w$]*\.[A-Za-z_$][\w$]*)\s*,\s*)?(["'])(.*?)\1''', re.S)
API_LITERAL = re.compile(r'''(["'])(/api/[A-Za-z0-9_./{}:$-]+)\1''')
JS_ROUTES = re.compile(r'''\[\s*(["'])([^"']+)\1\s*,\s*(["'])([a-z][a-z0-9-]+)\3''')


def git_sha(root):
    try:
        return subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'],
                                       text=True, stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def scan(root, output):
    root, output = root.resolve(), output.resolve()
    if not (root / 'web').is_dir() or not (root / 'backend').is_dir():
        raise ValueError('Not a PostFlight checkout (expected web/ and backend/)')
    output.mkdir(parents=True, exist_ok=True)
    rows, api, routes = [], {}, set()
    sources = sorted(p for folder in ('web', 'backend/src/main/java')
                     for p in (root / folder).rglob('*')
                     if p.is_file() and p.suffix in ('.html', '.js', '.java'))
    for file in sources:
        rel = file.relative_to(root).as_posix()
        content = file.read_text(encoding='utf-8')
        for match in DATA_ATTR.finditer(content):
            attr, raw = match.group(1), match.group(3)
            if attr not in {'action', 'route', 'workspace', 'platform', 'policy-tab',
                            'validation-tab', 'workflow-tab', 'review-tab', 'media-tab',
                            'finding', 'flow-node', 'incident', 'release', 'toggle',
                            'timeframe', 'review-select', 'live-action'}:
                continue
            rows.append(dict(file=rel, line=content.count('\n', 0, match.start()) + 1,
                             kind='data-' + attr, value=raw[:160],
                             dynamic='YES' if '${' in raw else 'NO',
                             verification='NOT_TESTED'))
        if file.suffix == '.js':
            for match in BUTTON_HELPER.finditer(content):
                label = match.group(2)
                if len(label) <= 120 and '\n' not in label and '${' not in label:
                    rows.append(dict(file=rel, line=content.count('\n', 0, match.start()) + 1,
                                     kind='helper-button-label', value=label,
                                     dynamic='UNKNOWN', verification='NOT_TESTED'))
            if rel == 'web/assets/app.js':
                for m in JS_ROUTES.finditer(content):
                    routes.add(m.group(4))
        for match in API_LITERAL.finditer(content):
            endpoint = match.group(2)
            info = api.setdefault(endpoint, {'frontend': set(), 'backend': set()})
            info['backend' if rel.startswith('backend/') else 'frontend'].add(rel)
    dedup = {(r['file'], r['line'], r['kind'], r['value']): r for r in rows}
    rows = sorted(dedup.values(), key=lambda r: (r['file'], r['line'], r['kind']))
    with (output / 'CONTROL_INVENTORY.csv').open('w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=['file','line','kind','value','dynamic','verification'])
        w.writeheader(); w.writerows(rows)
    with (output / 'API_CONTRACT_MATRIX.csv').open('w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=['literal_path','frontend_sources','backend_sources','mapping_status'])
        w.writeheader()
        for path, source in sorted(api.items()):
            w.writerow({'literal_path': path,
                        'frontend_sources': '; '.join(sorted(source['frontend'])),
                        'backend_sources': '; '.join(sorted(source['backend'])),
                        'mapping_status': 'STATIC_ONLY; dynamic routes not resolved'})
    manifest = {'generated_utc': datetime.now(timezone.utc).isoformat(),
                'git_sha': git_sha(root),
                'scope': 'STATIC DISCOVERY ONLY: NOT FUNCTIONAL COVERAGE',
                'files_scanned': len(sources),
                'static_control_occurrences': len(rows),
                'unique_static_control_signatures': len({(r['kind'],r['value']) for r in rows}),
                'static_api_literals': len(api),
                'static_route_slugs': sorted(routes),
                'controls_verified': 0,
                'limitations': [
                    'Dynamic DOM, event delegation, menus opened on interaction, and Clerk roles not executed',
                    'Button helper regex is heuristic; some labels are not actual rendered controls',
                    'API endpoint literals may be partial; route params and dynamic concatenation not resolved',
                    'No authenticated browser, security, accessibility, performance or recovery testing performed'
                ]}
    (output / 'DISCOVERY_MANIFEST.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(json.dumps({k: manifest[k] for k in ('files_scanned','static_control_occurrences','static_api_literals','controls_verified')}))
    return manifest


if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--repo', type=Path, default=Path('.'))
    ap.add_argument('--out', type=Path, required=True)
    ns = ap.parse_args()
    scan(ns.repo, ns.out)
