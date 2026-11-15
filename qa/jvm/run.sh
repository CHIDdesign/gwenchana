#!/usr/bin/env bash
# QA JVM bench: compile the REAL product sources from SRC against platform stubs and run the JUnit tests,
# one fresh JVM per test. Usage:  ./run.sh <SRC>   (or SRC=<repo root> ./run.sh)
# Env: KOTLIN_LIB  folder with kotlin-compiler-embeddable/stdlib 2.0.21, kotlinx-coroutines-core-jvm, junit 4.13.2, hamcrest-core 1.3
#      QA_SLOW=0   skip @Slow tests          QA_ONLY=<substring>  run only matching tests      QA_VERBOSE=1  product logs + traces
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${1:-${SRC:-}}"
[ -n "$SRC" ] || { echo "usage: $0 <repo root>   (or set SRC)"; exit 64; }
SRC="$(cd "$SRC" && pwd)" || exit 64

if [ -z "${KOTLIN_LIB:-}" ]; then
  for d in "${GRADLE_HOME:-/nonexistent}/lib" /opt/gradle-*/lib; do [ -f "$d/kotlin-compiler-embeddable-2.0.21.jar" ] && KOTLIN_LIB="$d" && break; done
fi
[ -n "${KOTLIN_LIB:-}" ] && [ -f "$KOTLIN_LIB/kotlin-stdlib-2.0.21.jar" ] || { echo "set KOTLIN_LIB to a folder containing kotlin-compiler-embeddable-2.0.21.jar etc."; exit 64; }
L="$KOTLIN_LIB"
jar() { local f; f="$(ls "$L"/$1 2>/dev/null | head -1)"; [ -n "$f" ] || { echo "missing $1 in KOTLIN_LIB" >&2; exit 64; }; printf '%s' "$f"; }
COMPILER_CP="$(jar 'kotlin-compiler-embeddable-2.0.21.jar'):$(jar 'kotlin-stdlib-2.0.21.jar'):$(jar 'kotlin-reflect-2.0.21.jar'):$(jar 'kotlin-script-runtime-2.0.21.jar'):$(jar 'kotlin-daemon-embeddable-2.0.21.jar'):$(jar 'annotations-*.jar'):$(jar 'trove4j-*.jar'):$(jar 'kotlinx-coroutines-core-jvm-*.jar')"
LIB_CP="$(jar 'kotlin-stdlib-2.0.21.jar'):$(jar 'kotlinx-coroutines-core-jvm-*.jar'):$(jar 'junit-4.13.2.jar'):$(jar 'hamcrest-core-1.3.jar')"

# Whole product files, unmodified. Everything else they reference is in stubs/ (platform) or stubs/kotlin/com/askthecar (excluded files).
PRODUCT_FILES=(
  app/src/main/java/com/askthecar/Crash.kt app/src/main/java/com/askthecar/Consent.kt app/src/main/java/com/askthecar/Sources.kt
  app/src/main/java/com/askthecar/Handoff.kt app/src/main/java/com/askthecar/Guide.kt app/src/main/java/com/askthecar/Report.kt
  app/src/main/java/com/askthecar/Sim.kt shared/java/com/askthecar/Recorder.kt shared/java/com/askthecar/CrashTypes.kt
  shared/java/com/askthecar/HandoffData.kt
)
PRODUCT=(); for f in "${PRODUCT_FILES[@]}"; do [ -f "$SRC/$f" ] || { echo "missing product file $SRC/$f"; exit 65; }; PRODUCT+=("$SRC/$f"); done

OUT="$HERE/build"; rm -rf "$OUT"; mkdir -p "$OUT/stubs" "$OUT/classes"
echo "== SRC $SRC"
REV="$(git -C "$SRC" rev-parse --short HEAD 2>/dev/null || echo 'not a git checkout')"
echo "== revision: $REV; product sources sha256: $(cd "$SRC" && cat "${PRODUCT_FILES[@]}" | sha256sum | cut -c1-16)"

echo "== compiling platform stubs (javac)"
javac --release 17 -nowarn -d "$OUT/stubs" $(find "$HERE/stubs/java" -name '*.java') 2>&1 | grep -v '^Picked up' || true
[ -f "$OUT/stubs/org/json/JSONObject.class" ] || { echo "stub compile failed"; exit 66; }

echo "== compiling product + kotlin stubs + tests (kotlinc 2.0.21)"
LOG="$OUT/kotlinc.log"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -nowarn \
  -cp "$LIB_CP:$OUT/stubs" -d "$OUT/classes" \
  $(find "$HERE/stubs/kotlin" -name '*.kt') "${PRODUCT[@]}" $(find "$HERE/src" -name '*.kt') >"$LOG" 2>&1
if grep -q 'error:' "$LOG" || [ ! -f "$OUT/classes/com/askthecar/CrashFlow.class" ]; then
  grep -v '^Picked up' "$LOG" | head -40; echo "COMPILE FAILED (see $LOG)"; exit 67
fi

RUN_CP="$OUT/classes:$OUT/stubs:$LIB_CP"
JOPTS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
CLASSES=(com.askthecar.qa.samples.QaSampleTest com.askthecar.qa.drive.Drv12AirbagUpgradeTest)
mapfile -t TESTS < <(java $JOPTS -cp "$RUN_CP" com.askthecar.qa.bench.QaRunnerKt --list "${CLASSES[@]}" 2>/dev/null | grep '#' | grep -- "${QA_ONLY:-}")
echo "== running ${#TESTS[@]} tests, one JVM each"
pass=0; fail=0; blocked=0; skip=0
for t in "${TESTS[@]}"; do
  line="$(java $JOPTS -cp "$RUN_CP" com.askthecar.qa.bench.QaRunnerKt "$t" 2>&1 | grep -v '^Picked up')"; rc=$?
  echo "$line"
  case "$line" in PASS*) pass=$((pass+1));; SKIP*) skip=$((skip+1));; BLOCKED*) blocked=$((blocked+1));; *) fail=$((fail+1));; esac
done
echo "== summary: PASS $pass  FAIL $fail  BLOCKED $blocked  SKIP $skip   (SRC=$SRC, $REV)"
[ "$fail" -eq 0 ]
