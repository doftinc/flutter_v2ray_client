#!/usr/bin/env bash
# Run the endpoint-resolution tests against the REAL Utilities.java in this tree.
#
# WHY THIS EXISTS. `Utilities.parseV2rayJsonFile` silently decides which protocols
# Android can lead with: it resolves the endpoint of outbounds[0], and a shape it cannot
# read makes the whole parse return null — whereupon `V2rayController.StartV2ray` does a
# bare `return`. No exception, no callback, no log. Dart's future completes and the app
# reports itself connected with no core running. That is the 2026-08-09 outage, and it
# is why Hysteria2 was documented for months as impossible on Android: the core dials it
# fine (765 KB/s from Krasnodar as a balancer member), this method could not name it.
#
# ⚠ IT COMPILES THE REAL FILE, NOT A COPY. A copy would pass forever after the original
# changed. Only android.* and the one core class it name-drops are stubbed.
#
# ⚠ DESKTOP org.json IS STRICTER THAN ANDROID'S — `getString` on an Integer throws here
# and coerces there — so the fixtures use string ports, which both read identically.
# That leaves exactly one variable: whether the endpoint is under vnext, servers, or
# named inline. See the note in Harness.java.
set -euo pipefail
cd "$(dirname "$0")"
SRC=../android/src/main/java/dev/amirzr/flutter_v2ray_client/v2ray
JSON_JAR="${JSON_JAR:-build/json.jar}"
mkdir -p build
if [ ! -s "$JSON_JAR" ]; then
  curl -sSL -o "$JSON_JAR" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
fi
rm -rf build/classes && mkdir -p build/classes
javac -nowarn -cp "$JSON_JAR" -d build/classes \
  Harness.java \
  $(find stubs -name '*.java') \
  "$SRC/utils/Utilities.java" "$SRC/utils/AppConfigs.java" "$SRC/utils/V2rayConfig.java"
java -cp "build/classes:$JSON_JAR" Harness

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
  "$SRC/core/TuicConfigRewriter.java"
java -cp "build/tclasses:$JSON_JAR" \
  dev.amirzr.flutter_v2ray_client.v2ray.core.TuicRewriteHarness

# ── autostart store ───────────────────────────────────────────────────────────────
# ⚠ THE ONE THAT DECIDES WHAT A START WE DID NOT MAKE DOES. Android hands a sticky
# restart a NULL intent and hands an always-on start a bare action intent; neither can
# carry a config, so the services read it from here. It runs when there may be no app
# process at all, which is why every unreadable, stale or foreign blob below must end in
# NO TUNNEL and NO EXCEPTION rather than in a restart loop — and why the blob is named
# JSON instead of a serialized V2rayConfig, whose readObject() would throw on the first
# start after any app update that touched the class.
echo
javac -nowarn -cp "$JSON_JAR" -d build/aclasses \
  AutoStartHarness.java \
  $(find stubs -name '*.java') \
  "$SRC/utils/AutoStartStore.java" "$SRC/utils/V2rayConfig.java"
java -cp "build/aclasses:$JSON_JAR" AutoStartHarness
