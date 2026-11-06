#!/usr/bin/env bash
# Guards the incremental KSP path (Gradle default ksp.incremental=true): after a clean-ish first
# build, adds a component and edits an existing one in a SCRATCH COPY, rebuilds WITHOUT clean, and
# asserts the container includes the new component wired to the edited one, AND that KSP really ran
# incrementally: -Pksp.incremental.log=true makes KSP write build/kspCaches/main/logs/kspDirtySet.log
# (an "All Files" list and a "Dirty:" list); the dirty set must be a strict subset of all sources and
# Gradle must not report a forced full rebuild of :kspKotlin. The flag is a task input, so it is set on
# BOTH builds (toggling it between them would itself force a full rebuild).
# Usage: ci/incremental-check.sh   (env GRADLE_ARGS adds flags, e.g. "--offline")
set -euo pipefail

src="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
tar -C "$src" --exclude=./build --exclude=./.gradle --exclude=./.kotlin --exclude=./ci -cf - . | tar -C "$work" -xf -
cd "$work"
pkg=src/main/kotlin/io/tiko/examples/kotlin
# shellcheck disable=SC2086
gradle() { ./gradlew --no-daemon --console=plain ${GRADLE_ARGS:-} "$@"; }

echo "== first build (cold) =="
gradle -Pksp.incremental.log=true build run

echo "== mutate: new component + edited existing component =="
cat > "$pkg/IncrementalProbe.kt" <<'KT'
package io.tiko.examples.kotlin

import io.tiko.annotations.Component

@Component
class IncrementalProbe(private val label: Label) {
    fun tag(): String = "probe:" + label.text()
}
KT
sed -i 's/"audit"/"audit-changed"/' "$pkg/Label.kt"
sed -i 's/container.get(EventLog::class.java).chain()/container.get(EventLog::class.java).chain().also { println("probe=" + container.get(IncrementalProbe::class.java).tag()) }/' "$pkg/Main.kt"
grep -q IncrementalProbe "$pkg/Main.kt" || { echo "sed on Main.kt did not apply" >&2; exit 2; }

echo "== incremental rebuild (no clean) =="
gradle -Pksp.incremental.log=true --info build run | tee run.log

grep -q '^probe=probe:audit-changed$' run.log \
  || { echo "FAIL: container lacks the new component or the edited Label" >&2; exit 1; }
grep -q 'IncrementalProbe' build/generated/ksp/main/resources/META-INF/tiko/components.txt \
  || { echo "FAIL: components.txt lacks IncrementalProbe" >&2; exit 1; }
dirty_log=build/kspCaches/main/logs/kspDirtySet.log
[ -f "$dirty_log" ] || { echo "FAIL: $dirty_log missing" >&2; exit 1; }
echo "== kspDirtySet.log =="
cat "$dirty_log"
if grep -q 'require a full rebuild for incremental task' run.log; then
  grep 'require a full rebuild' run.log >&2
  echo "FAIL: :kspKotlin fell back to a full rebuild" >&2
  exit 1
fi
read -r all dirty <<<"$(awk '
  /^All Files/ {sec="all"; next}
  /^Dirty:/ {sec="dirty"; next}
  /^[A-Za-z]/ {sec=""; next}
  /^  / {if (sec=="all") a++; if (sec=="dirty") d++}
  END {print a+0, d+0}' "$dirty_log")"
echo "dirty set: $dirty of $all sources"
[ "$dirty" -gt 0 ] && [ "$dirty" -lt "$all" ]   || { echo "FAIL: dirty set ($dirty) is not a strict, non-empty subset of $all sources" >&2; exit 1; }
grep -q 'IncrementalProbe.kt' <<<"$(awk '/^Dirty:/{f=1;next} /^[A-Za-z]/{f=0} f' "$dirty_log")"   || { echo "FAIL: new file not in the dirty set" >&2; exit 1; }
echo "OK: incremental KSP rebuild (dirty $dirty of $all) picked up the new and the changed component"
