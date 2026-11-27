#!/usr/bin/env bash
# half-sourceG100 — TC first-aid regression tests (plain javac + java, no JUnit).
#
# Covers the v0.6.0 launcher fixes that make hand-dropped TC mods launch:
#   - argv-safe dir naming (the engine's Android entry splits argv with
#     strtok(argv," ") and cannot quote "-game <name with spaces>")
#   - gameinfo regeneration (PC originals -> rev-3 engine-valid form,
#     original preserved as gameinfo.pc.txt) via the tier2 planner, with the
#     Java fallback path, and with tier2 missing entirely
#   - VPK mount-name rules and Ep1/Ep2 fallback lines
#
# The test compiles the REAL android-app/src/.../TcFirstAid.java against the
# test doubles in doubles/ (ModScanner/Tier2Jni shadows + android.util.Log);
# android-app/src is deliberately NOT on the compile path. Signatures of the
# doubles must mirror the real classes.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$(dirname "$HERE")")"
OUT="$HERE/build"
rm -rf "$OUT" && mkdir -p "$OUT"

javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 -d "$OUT" \
  "$ROOT/android-app/src/com/z/halfsourceg100/TcFirstAid.java" \
  "$HERE/doubles/android/util/Log.java" \
  "$HERE/doubles/com/z/halfsourceg100/ModScanner.java" \
  "$HERE/doubles/com/z/halfsourceg100/Tier2Jni.java" \
  "$HERE/TestTcFirstAid.java"

java -cp "$OUT" com.z.halfsourceg100.TestTcFirstAid
rm -rf "$OUT"
