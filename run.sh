#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/classes
find backend/src/main/java/com/postflight/core backend/src/main/java/com/postflight/local -name '*.java' > build/sources.txt
javac --release 17 -encoding UTF-8 -d build/classes @build/sources.txt
java --add-modules jdk.httpserver -Dpostflight.root="$(pwd)" -cp build/classes com.postflight.local.Main "${1:-8080}"
