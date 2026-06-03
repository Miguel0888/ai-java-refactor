#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$ROOT_DIR/build/classes"
DIST_DIR="$ROOT_DIR/dist"
LIB_CP="$ROOT_DIR/lib/javaparser-core-3.26.4.jar:$ROOT_DIR/lib/javaparser-symbol-solver-core-3.26.4.jar"
rm -rf "$ROOT_DIR/build" "$DIST_DIR"
mkdir -p "$BUILD_DIR" "$DIST_DIR/lib"
find "$ROOT_DIR/src/main/java" -name '*.java' | sort > "$ROOT_DIR/build/sources.txt"
javac -source 1.8 -target 1.8 -cp "$LIB_CP" -d "$BUILD_DIR" @"$ROOT_DIR/build/sources.txt"
jar cfe "$DIST_DIR/ai-java-refactor.jar" de.lazyjava.refactor.cli.RefactorCli -C "$BUILD_DIR" .
cp "$ROOT_DIR/lib"/*.jar "$DIST_DIR/lib/"
cat > "$DIST_DIR/ai-java-refactor" <<'RUNNER'
#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
java -cp "$ROOT_DIR/ai-java-refactor.jar:$ROOT_DIR/lib/*" de.lazyjava.refactor.cli.RefactorCli "$@"
RUNNER
chmod +x "$DIST_DIR/ai-java-refactor"
