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
  # ⚠ DOWNLOAD ASIDE, THEN RENAME. Two runs starting together both saw an absent jar and
  # both wrote the same path; the loser compiled against a half-written archive. `mv` on
  # one filesystem is atomic, so the worst case is now one wasted download.
  curl -sSL -o "$JSON_JAR.$$" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar \
    && mv -f "$JSON_JAR.$$" "$JSON_JAR"
  rm -f "$JSON_JAR.$$"
fi

# ⚠⚠ EVERY OUTPUT OF THIS RUN LIVES IN A DIRECTORY THIS RUN OWNS, AND IT USED NOT TO. The
# class directories and the per-harness `.out` files were fixed names under `build/`, and
# the first thing this script did was `rm -rf` them — so two suites started at once in ONE
# checkout deleted each other's classes and overwrote each other's output. Measured:
#
#     A:  TOTAL: 332 assertions, 0 failed, 0 harness(es) broken   (rc 0)
#     B:  TOTAL: 122 assertions, 0 failed, 0 harness(es) broken   (rc 0)
#
# — while B's own RESULT lines summed to 332. Both exited 0. That is the same shape as the
# fixture paths fixed in 8556673, one level up: the fixtures were made private to the JVM
# and the BUILD TREE was left shared, so "the suite can run twice on one machine" was true
# only for sequential reruns. (CI was never affected: check-plugin-tests.sh clones into its
# own mktemp directory. It was the claim that was wrong, not the gate.)
#
# `build/json.jar` stays shared on purpose — it is a 60 KB download, it is written
# atomically above, and it is only ever read afterwards.
OUT="build/run-$$"
rm -rf "$OUT"
mkdir -p "$OUT/classes"
# The harnesses' stdout is echoed as it is read, so nothing here is worth keeping after the
# summary; leaving it behind is how a shared /tmp fills up one gate run at a time.
trap 'rm -rf "$OUT"' EXIT INT TERM

TOTAL_CHECKS=0
TOTAL_FAILURES=0
BROKEN=0

# Run one harness, echo everything it prints, and add its RESULT line to the totals.
run_harness() {
  local label="$1"; shift
  local out="$OUT/${label}.out"
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
javac -nowarn -cp "$JSON_JAR" -d "$OUT/classes" \
  Harness.java \
  $(find stubs -name '*.java') \
  "$SRC/interfaces/V2rayServicesListener.java" \
  "$SRC/core/Tun2socksArgs.java" \
  "$SRC/utils/Utilities.java" "$SRC/utils/AppConfigs.java" "$SRC/utils/V2rayConfig.java" || exit 1
run_harness endpoints java -cp "$OUT/classes:$JSON_JAR" Harness

# ── TUIC config rewrite ───────────────────────────────────────────────────────────
# ⚠ THE ONE THAT CAN LEAK. When the TUIC listener does not come up, this decides
# whether the outbound is dropped or kept. Drop a MEMBER: correct. Drop the PRIMARY and
# xray falls through to the next outbound, which is `freedom` — every byte the user
# believes is tunnelled leaves in clear. Removing the fail-closed branch makes two of
# these fail, naming `freedom` as outbounds[0].
echo
javac -nowarn -cp "$JSON_JAR" -d "$OUT/tclasses" \
  TuicRewriteHarness.java \
  stubs/android/util/Log.java \
  "$SRC/core/Tun2socksArgs.java" \
  "$SRC/core/TuicConfigRewriter.java" || exit 1
run_harness tuic java -cp "$OUT/tclasses:$JSON_JAR" \
  dev.amirzr.flutter_v2ray_client.v2ray.core.TuicRewriteHarness

# ── socket protection ──────────────────────────────────────────────────────────────
# ⚠ THE ANSWER libv2ray ACTS ON. protectSocket() in doft_protect.go refuses a TUIC dial
# when this says false, because quic-go opens the QUIC socket outside xray's dialer and
# an unprotected one carries the tunnel's own packets back into the tunnel. The shim
# answered `true` when there was no service to ask — an unprotected socket reported as
# protected. There was not one assertion about any of this, and the VpnService stub's
# hard-coded `protect() -> true` meant none could be written.
echo
javac -nowarn -cp "$JSON_JAR" -d "$OUT/pclasses" \
  ProtectorHarness.java \
  $(find stubs -name '*.java') \
  "$SRC/interfaces/V2rayServicesListener.java" \
  "$SRC/core/SocketProtector.java" || exit 1
run_harness protector java -cp "$OUT/pclasses:$JSON_JAR" \
  dev.amirzr.flutter_v2ray_client.v2ray.core.ProtectorHarness

# ── the tun2socks command line ────────────────────────────────────────────────────
# ⚠ THE ONE WORD THAT DECIDES WHETHER ANDROID CAN CARRY A DATAGRAM. The service passed
# `--enable-udprelay`, which is badvpn's udpgw framing and needs a udpgw server; the
# address it was pointed at is xray's SOCKS5 inbound, which does not speak udpgw. Every
# datagram went into a socket nobody could parse while TCP measured 449-537 KB/s, so no
# health check, no speed test and no user report could see it — only STUN, DNS-over-UDP,
# QUIC and calls died. It survived because the vector was built inline in a method that
# needs a live VpnService. Reverting Tun2socksArgs to emit `--enable-udprelay` by default
# turns this red.
echo
javac -nowarn -cp "$JSON_JAR" -d "$OUT/uclasses" \
  Tun2socksHarness.java \
  $(find stubs -name '*.java') \
  "$SRC/interfaces/V2rayServicesListener.java" \
  "$SRC/core/Tun2socksArgs.java" \
  "$SRC/utils/Utilities.java" "$SRC/utils/AppConfigs.java" "$SRC/utils/V2rayConfig.java" || exit 1
run_harness tun2socks java -cp "$OUT/uclasses:$JSON_JAR" Tun2socksHarness

# ── which network carries the tunnel ──────────────────────────────────────────────
# ⚠ THE DAEMON HAD NO CONNECTIVITY AWARENESS AT ALL. The tunnel runs in its own process and
# the only NetworkCallback in the tree lived in the Flutter Activity — wrong process, gone
# when the app is backgrounded, absent entirely on an always-on start. So a Wi-Fi -> LTE
# handover happened with nothing in the tunnel's process noticing and setUnderlyingNetworks
# was never called. Loosening any leg of the rule below turns this red.
echo
javac -nowarn -cp "$JSON_JAR" -d "$OUT/nclasses" \
  UnderlyingNetworkHarness.java \
  "$SRC/core/UnderlyingNetworkPolicy.java" || exit 1
run_harness underlying-network java -cp "$OUT/nclasses:$JSON_JAR" UnderlyingNetworkHarness

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
javac -nowarn -cp "$JSON_JAR" -d "$OUT/aclasses" \
  AutoStartHarness.java LosablePrefs.java \
  $(find stubs -name '*.java') \
  "$SRC/interfaces/V2rayServicesListener.java" \
  "$SRC/core/Tun2socksArgs.java" \
  "$SRC/utils/AutoStartStore.java" "$SRC/utils/V2rayConfig.java" || exit 1
run_harness autostart java -cp "$OUT/aclasses:$JSON_JAR" AutoStartHarness

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
javac -nowarn -cp "$JSON_JAR" -d "$OUT/sclasses" \
  ServiceHarness.java LosablePrefs.java \
  $(find stubs -name '*.java') \
  "$SRC/core/Tun2socksArgs.java" "$SRC/core/UnderlyingNetworkPolicy.java" \
  "$SRC/utils/AutoStartStore.java" "$SRC/utils/V2rayConfig.java" "$SRC/utils/AppConfigs.java" \
  "$SRC/interfaces/V2rayServicesListener.java" \
  "$SRC/services/V2rayVPNService.java" "$SRC/services/V2rayProxyOnlyService.java" || exit 1
run_harness services java -cp "$OUT/sclasses:$JSON_JAR" ServiceHarness

echo
echo "──────────────────────────────────────────────────────────────"
printf 'TOTAL: %d assertions, %d failed, %d harness(es) broken\n' \
  "$TOTAL_CHECKS" "$TOTAL_FAILURES" "$BROKEN"
if [ "$TOTAL_FAILURES" -ne 0 ] || [ "$BROKEN" -ne 0 ]; then
  exit 1
fi
