#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

if [ -x "$ROOT_DIR/gradlew" ]; then
    GRADLE_CMD=("$ROOT_DIR/gradlew")
else
    GRADLE_CMD=(gradle)
fi

"${GRADLE_CMD[@]}" clean build installDist

rm -rf "$ROOT_DIR/dist"
mkdir -p "$ROOT_DIR/dist"
cp -R "$ROOT_DIR/build/install/ai-java-refactor/." "$ROOT_DIR/dist/"
