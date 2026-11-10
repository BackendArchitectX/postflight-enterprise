#!/usr/bin/env python3
"""Regression check for the observed unexpanded workspace heading."""
from pathlib import Path
root = Path(__file__).resolve().parents[2]
file = root/'web/assets/app.js'
source = file.read_text(encoding='utf-8')
assert "panel('Projects in ${esc(state.workspace)}'" not in source, (
    'BUG: literal workspace interpolation remains; heading displays ${esc(state.workspace)}')
assert "panel('Projects in '+esc(state.workspace)" in source, (
    'Expected workspace title to be escaped before inserting into HTML')
print('PASS: workspace heading uses escaped runtime workspace name')
