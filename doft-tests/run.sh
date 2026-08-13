#!/usr/bin/env bash
# Run the Android-side tests against the REAL sources in this tree.
#
# WHY THIS EXISTS. `Utilities.parseV2rayJsonFile` silently decides which protocols
# Android can lead with: it resolves the endpoint of outbounds[0], and a shape it cannot
# read makes the whole parse return null — whereupon `V2rayController.StartV2ray` does a
# bare `return`. No exception, no callback, no log. Dart's future completes and the app
# reports itself connected with no core running. That is the 2026-08-09 outage, and it
# is why Hysteria2 was documented for months as impossible on Android: the core dials it
# fine (765 KB/s from Krasnodar as a balancer member), this method could not name it.
#
# ⚠ IT COMPILES THE REAL FILES, NOT COPIES. A copy would pass forever after the original
# changed. Only android.* and the one core class it name-drops are stubbed.
#
# ⚠ DESKTOP org.json IS STRICTER THAN ANDROID'S — `getString` on an Integer throws here
# and coerces there — so the fixtures use string ports, which both read identically.
# That leaves exactly one variable: whether the endpoint is under vnext, servers, or
# named inline. See the note in Harness.java.
#
# ⚠ THE ASSERTION COUNT AT THE BOTTOM IS DERIVED, NOT TYPED. Every harness ends with a
# machine-readable `RESULT <name> checks=<n> failures=<n>` line that it computes by
# counting its own check() calls at runtime, and this script sums those lines. An earlier
# report of this suite claimed "108 assertions pass" when it printed 90; a number nobody
# can hand-edit is the fix. A harness that dies before printing its RESULT line is
# counted as a failure, not as zero assertions.
set -uo pipefail
cd "$(dirname "$0")"
SRC=../android/src/main/java/dev/amirzr/flutter_v2ray_client/v2ray
JSON_JAR="${JSON_JAR:-build/json.jar}"
mkdir -p build
if [ ! -s "$JSON_JAR" ]; then
  curl -sSL -o "$JSON_JAR" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
fi
rm -rf build/classes build/tclasses build/aclasses build/sclasses && mkdir -p build/classes

TOTAL_CHECKS=0
TOTAL_FAILURES=0
BROKEN=0

# Run one harness, echo everything it prints, and add its RESULT line to the totals.
run_harness() {
  local label="$1"; shift
  local out="build/${label}.out"
  if ! "$@" >"$out" 2>&1; then
    : # a non-zero exit is expected when a harness reports failures; the RESULT line rules
  fi
  cat "$out"
  local line checks fails
  line="$(grep -E '^RESULT ' "$out" | tail -1)"
  if [ -z "$line" ]; then
    echo "!! $label printed no RESULT line — it died before finishing. Counted as broken."
    BROKEN=$((BROKEN + 1))
    return
  fi
  checks="$(printf '%s\n' "$line" | sed -n 's/.*checks=\([0-9]*\).*/\1/p')"
  fails="$(printf '%s\n' "$line" | sed -n 's/.*failures=\([0-9]*\).*/\1/p')"
  TOTAL_CHECKS=$((TOTAL_CHECKS + checks))
  TOTAL_FAILURES=$((TOTAL_FAILURES + fails))
}

# ── endpoint resolution ───────────────────────────────────────────────────────────
javac -nowarn -cp "$JSON_JAR" -d build/classes \
  Harness.java \
  $(find stubs -name '*.java') \
  "$SRC/interfaces/V2rayServicesListener.java" \
  "$SRC/utils/Utilities.java" "$SRC/utils/AppConfigs.java" "$SRC/utils/V2rayConfig.java" || exit 1
run_harness endpoints java -cp "build/classes:$JSON_JAR" Harness

# ── TUIC config rewrite ───────────────────────────────────────────────────────────
# ⚠ THE ONE THAT CAN LEAK. When the TUIC listener does not come up, this decides
# whether the outbound is dropped or kept. Drop a MEMBER: correct. Drop the PRIMARY and
# xray falls through to the next outbound, which is `freedom` — every byte the user
# believes is tunnelled leaves in clear. Removing the fail-closed branch makes two of
# these fail, naming `freedom` as outbounds[0].
echo
javac -nowarn -cp "$JSON_JAR" -d build/tclasses \
  TuicRewriteHarness.java \
  stubs/android/util/Log.java \
  "$SRC/core/TuicConfigRewriter.java" || exit 1
run_harness tuic java -cp "build/tclasses:$JSON_JAR" \
  dev.amirzr.flutter_v2ray_client.v2ray.core.TuicRewriteHarness

# ── autostart store ───────────────────────────────────────────────────────────────
# ⚠ THE ONE THAT DECIDES WHAT A START WE DID NOT MAKE DOES. Android hands a sticky
# restart a NULL intent and hands an always-on start a bare action intent; neither can
# carry a config, so the services read it from here. It runs when there may be no app
# process at all, which is why every unreadable, stale or foreign blob below must end in
# NO TUNNEL and NO EXCEPTION rather than in a restart loop — and why the blob is named
# JSON instead of a serialized V2rayConfig, whose readObject() would throw on the first
# start after any app update that touched the class.
#
# ⚠ AND WHY LosablePrefs EXISTS. Its apply() can be lost and its commit() cannot, which
# is the only way to see that a budget charged with apply() is not charged at all when
# the attempt it is bounding kills the process.
echo
javac -nowarn -cp "$JSON_JAR" -d build/aclasses \
  AutoStartHarness.java LosablePrefs.java \
  $(find stubs -name '*.java') \
  "$SRC/interfaces/V2rayServicesListener.java" \
  "$SRC/utils/AutoStartStore.java" "$SRC/utils/V2rayConfig.java" || exit 1
run_harness autostart java -cp "build/aclasses:$JSON_JAR" AutoStartHarness

# ── the two services themselves ───────────────────────────────────────────────────
# ⚠ AN EARLIER ROUND SHIPPED THESE WITH NO TEST OVER THEM. Reverting both service files
# to 84424a2 left every assertion above green, because they only ever exercised the
# key-value store underneath. These compile and run the REAL V2rayVPNService and
# V2rayProxyOnlyService against stubbed Android, and each case names a state the shipped
# code could reach and must not: a null intent answered with suicide, an always-on start
# answered the same way, a NULL return from builder.establish() treated as a working
# tunnel, consent lost between prepare() and the core's startup() callback answered with
# a bare return, a revoke that leaves the credential blob restorable, and
# `this.onDestroy()` used as a stop (it cleans up and leaves the service alive).
# Reverting either service file turns this run red.
echo
javac -nowarn -cp "$JSON_JAR" -d build/sclasses \
  ServiceHarness.java LosablePrefs.java \
  $(find stubs -name '*.java') \
  "$SRC/utils/AutoStartStore.java" "$SRC/utils/V2rayConfig.java" "$SRC/utils/AppConfigs.java" \
  "$SRC/interfaces/V2rayServicesListener.java" \
  "$SRC/services/V2rayVPNService.java" "$SRC/services/V2rayProxyOnlyService.java" || exit 1
run_harness services java -cp "build/sclasses:$JSON_JAR" ServiceHarness

echo
echo "──────────────────────────────────────────────────────────────"
printf 'TOTAL: %d assertions, %d failed, %d harness(es) broken\n' \
  "$TOTAL_CHECKS" "$TOTAL_FAILURES" "$BROKEN"
if [ "$TOTAL_FAILURES" -ne 0 ] || [ "$BROKEN" -ne 0 ]; then
  exit 1
fi
