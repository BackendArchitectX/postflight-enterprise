#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/ci-classes
find backend/src/main/java/com/postflight/core backend/src/main/java/com/postflight/local -name '*.java' | sort > build/ci-sources.txt
javac --release 17 -encoding UTF-8 -d build/ci-classes @build/ci-sources.txt
node --check web/assets/app.js
node --check web/assets/zernio-visitor.js
node --check web/assets/experience.js
export POSTFLIGHT_PUBLIC_DEMO=true
export PORT=18080
java --add-modules jdk.httpserver -Dpostflight.root="$(pwd)" -cp build/ci-classes com.postflight.local.Main > build/ci-server.log 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT
for i in $(seq 1 30); do
 if curl -fsS http://127.0.0.1:18080/api/health > build/ci-health.json; then break; fi
 sleep 1
done
curl -fsS -X POST http://127.0.0.1:18080/api/demo/preflight \
 -H 'Content-Type: application/json' \
 -d '{"platform":"linkedin","content":"Invest now! Guaranteed 20% returns and instant profits. Contact advice@demo.example today.","hasMedia":false,"altText":""}' > build/ci-risk.json
curl -fsS -X POST http://127.0.0.1:18080/api/demo/preflight \
 -H 'Content-Type: application/json' \
 -d '{"platform":"linkedin","content":"Financial planning matters. Investments involve risk and past performance is not a guarantee of future results.","hasMedia":false,"altText":""}' > build/ci-safer.json
curl -fsS http://127.0.0.1:18080/assets/experience.js > /dev/null
curl -fsS http://127.0.0.1:18080/assets/experience.css > /dev/null
wstatus=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:18080/api/drafts -H 'Content-Type: application/json' -d '{}')
kstatus=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/zernio/live)
zstatus=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:18080/api/zernio/live -H 'Content-Type: application/json' -d '{}')
if [ "$wstatus" != "403" ] || [ "$kstatus" != "400" ] || [ "$zstatus" != "405" ]; then
  echo "Security/status checks failed: $wstatus / $kstatus / $zstatus"
  exit 1
fi
python3 - <<'PY'
import json
from pathlib import Path
risk=json.loads(Path('build/ci-risk.json').read_text())
safer=json.loads(Path('build/ci-safer.json').read_text())
assert risk['status']=='BLOCKED',risk
assert risk['summary']['critical']>=1
assert risk['persisted'] is False
assert safer['status']!='BLOCKED',safer
assert len(safer['fingerprint'])==64
assert safer['publisherEnabled'] is False
print('PASS: Java 17 compilation, UI JS syntax, stateless preflight, protected writes, Zernio read-only boundary')
PY
