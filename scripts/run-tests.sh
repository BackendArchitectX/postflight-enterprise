#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p build/classes
find backend/src/main/java/com/postflight/core backend/src/main/java/com/postflight/local -name '*.java' > build/sources.txt
javac --release 17 -encoding UTF-8 -d build/classes @build/sources.txt
TEMP="$(mktemp -d)"
PORT=8186
cleanup(){ kill "${PID:-}" 2>/dev/null || true; rm -rf "$TEMP"; }
trap cleanup EXIT
mkdir -p "$TEMP/docs" "$TEMP/data" "$TEMP/web/assets"
cp docs/seed.json "$TEMP/docs/seed.json"
cp web/index.html "$TEMP/web/index.html"
cp web/assets/* "$TEMP/web/assets/"
java --add-modules jdk.httpserver -Dpostflight.root="$TEMP" -cp "$ROOT/build/classes" com.postflight.local.Main "$PORT" > "$TEMP/server.log" 2>&1 &
PID=$!
for i in $(seq 1 40);do if curl -fsS "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1;then break;fi;sleep .2;done
python3 scripts/test_integration.py "http://127.0.0.1:$PORT"
python3 scripts/test_extensions.py "http://127.0.0.1:$PORT"
python3 scripts/test_security.py "http://127.0.0.1:$PORT"
node scripts/test_frontend_state.cjs "http://127.0.0.1:$PORT"
echo 'PASS — clean complete Java17 backend + frontend API integration suite'
