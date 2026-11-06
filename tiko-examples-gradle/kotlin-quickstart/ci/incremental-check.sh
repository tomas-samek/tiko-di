#!/usr/bin/env bash
# Guards the incremental KSP path (Gradle default ksp.incremental=true): after a clean-ish first
# build, adds a component and edits an existing one in a SCRATCH COPY, rebuilds WITHOUT clean, and
# asserts the container includes the new component wired to the edited one.
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
gradle build run

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
gradle build run | tee run.log

grep -q '^probe=probe:audit-changed$' run.log \
  || { echo "FAIL: container lacks the new component or the edited Label" >&2; exit 1; }
grep -q 'IncrementalProbe' build/generated/ksp/main/resources/META-INF/tiko/components.txt \
  || { echo "FAIL: components.txt lacks IncrementalProbe" >&2; exit 1; }
echo "OK: incremental KSP rebuild picked up the new and the changed component"
