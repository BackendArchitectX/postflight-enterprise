#!/usr/bin/env bash
# Full isolated browser/API QA for GitHub Actions (no Docker daemon required).
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p build/qa-classes qa-reports/ci-complete
find backend/src/main/java/com/postflight/core backend/src/main/java/com/postflight/local -name '*.java' | sort > build/qa-sources.txt
javac --release 17 -encoding UTF-8 -d build/qa-classes @build/qa-sources.txt
node --check web/assets/app.js
node --check web/assets/experience.js
node --check web/assets/zernio-visitor.js
node --check scripts/qa/full_browser_audit.cjs
python3 scripts/qa/check_scheduler_security.py
node --check web/assets/operational-workflows.js
node --check web/assets/governance-workflows.js

temp_root="$(mktemp -d)"
demo_pid=""
edit_pid=""
cleanup(){
  if [ -n "$demo_pid" ]; then kill "$demo_pid" 2>/dev/null || true; fi
  if [ -n "$edit_pid" ]; then kill "$edit_pid" 2>/dev/null || true; fi
  rm -rf "$temp_root"
}
trap cleanup EXIT
for mode in demo edit; do
  mkdir -p "$temp_root/$mode/docs" "$temp_root/$mode/data"
  cp docs/seed.json "$temp_root/$mode/docs/seed.json"
  cp -R web "$temp_root/$mode/web"
done
POSTFLIGHT_PUBLIC_DEMO=true PORT=10003 java --add-modules jdk.httpserver -Dpostflight.root="$temp_root/demo" -cp build/qa-classes com.postflight.local.Main 10003 > qa-reports/ci-complete/demo.log 2>&1 &
demo_pid=$!
POSTFLIGHT_PUBLIC_DEMO=false PORT=10004 java --add-modules jdk.httpserver -Dpostflight.root="$temp_root/edit" -cp build/qa-classes com.postflight.local.Main 10004 > qa-reports/ci-complete/edit.log 2>&1 &
edit_pid=$!
for port in 10003 10004; do
  for i in $(seq 1 50); do
    if curl --fail --silent "http://127.0.0.1:$port/api/health" >/dev/null; then break; fi
    sleep .3
  done
  curl --fail --silent "http://127.0.0.1:$port/api/health" > "qa-reports/ci-complete/health-$port.json"
done
python3 scripts/test_integration.py http://127.0.0.1:10004
python3 scripts/test_extensions.py http://127.0.0.1:10004
python3 scripts/test_security.py http://127.0.0.1:10004
python3 scripts/test_operational_workflows.py http://127.0.0.1:10004
python3 scripts/test_governance_workflows.py http://127.0.0.1:10004
node scripts/test_frontend_state.cjs http://127.0.0.1:10004

deps="$temp_root/npm"
mkdir -p "$deps"
npm install --prefix "$deps" --no-audit --no-fund --no-save playwright@1.65.0
export NODE_PATH="$deps/node_modules"
export PF_QA_DEMO="http://127.0.0.1:10003"
export PF_QA_EDIT="http://127.0.0.1:10004"
export PF_QA_OUTPUT="$PWD/qa-reports/ci-complete"
node scripts/qa/full_browser_audit.cjs
