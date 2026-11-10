#!/usr/bin/env python3
"""Run without network and without touching the real PostFlight data directory."""
from pathlib import Path
from tempfile import TemporaryDirectory
from discover_controls import scan
import csv
import json

with TemporaryDirectory(prefix='postflight-audit-selftest-') as temp:
    root = Path(temp)
    (root/'web/assets').mkdir(parents=True)
    (root/'backend/src/main/java').mkdir(parents=True)
    (root/'web/assets/app.js').write_text(
        'const names=[["Mission Control","overview","layout"]];'
        '`<button data-action="createDraft">Create</button>`;'
        'btn("Invite member","invite")', encoding='utf-8')
    (root/'backend/src/main/java/A.java').write_text(
        'if(path.equals("/api/health")){}', encoding='utf-8')
    report = scan(root, root/'reports')
    assert report['controls_verified'] == 0
    assert report['files_scanned'] == 2
    assert 'overview' in report['static_route_slugs']
    with (root/'reports/CONTROL_INVENTORY.csv').open(encoding='utf-8', newline='') as file:
        controls = list(csv.DictReader(file))
    assert any(r['kind']=='data-action' and r['value']=='createDraft' for r in controls)
    assert all(r['verification'] == 'NOT_TESTED' for r in controls)
    with (root/'reports/API_CONTRACT_MATRIX.csv').open(encoding='utf-8', newline='') as file:
        endpoints = list(csv.DictReader(file))
    assert any(r['literal_path']=='/api/health' for r in endpoints)
    assert json.loads((root/'reports/DISCOVERY_MANIFEST.json').read_text())['controls_verified'] == 0
print('PASS: static inventory test fixture and honest zero-verified-coverage reporting')
