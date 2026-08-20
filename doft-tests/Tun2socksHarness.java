import dev.amirzr.flutter_v2ray_client.v2ray.core.Tun2socksArgs;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.AppConfigs;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.Utilities;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.V2rayConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * The command line that decides whether an Android device can carry a datagram.
 *
 * <h2>What this is testing and why it did not exist before</h2>
 *
 * The service passed {@code --enable-udprelay} to tun2socks. That selects badvpn's
 * <b>udpgw</b> UDP mode, whose frames only a udpgw server understands, and the address it
 * was pointed at is xray's SOCKS5 inbound, which does not speak udpgw. Every datagram the
 * device produced went into a socket nobody could parse. TCP was unaffected — SOCKS5
 * CONNECT is a different path in the same binary — so the tunnel measured 449–537 KB/s
 * while STUN, DNS-over-UDP, QUIC and every Telegram call died.
 *
 * <p>It survived because the vector was built inline inside a method that needs a live
 * {@code VpnService}. Nothing here could reach it. So the fix is two things: the right
 * flag, and a seam that lets this file read the real one off the real service.
 */
public class Tun2socksHarness {
    static int failures = 0;
    /** counted at runtime so the suite total cannot be hand-typed wrong */
    static int checks = 0;

    static void check(String name, boolean ok, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.printf("%-62s %s  %s%n", name, ok ? "PASS" : "FAIL", detail);
    }

    /** A config with the socks inbound on 10807, plus whatever marker is asked for. */
    static String cfg(String doftAndroid) {
        return "{" + (doftAndroid == null ? "" : "\"_doft_android\":" + doftAndroid + ",")
             + "\"inbounds\":[{\"protocol\":\"socks\",\"port\":10807},"
             + "{\"protocol\":\"http\",\"port\":10809}],"
             + "\"outbounds\":[{\"tag\":\"proxy\",\"protocol\":\"vless\",\"settings\":{\"vnext\":"
             + "[{\"address\":\"204.3.207.89\",\"port\":\"443\",\"users\":[{\"id\":\"u\"}]}]}},"
             + "{\"tag\":\"direct\",\"protocol\":\"freedom\"}]}";
    }

    static V2rayConfig parse(String doftAndroid) {
        return Utilities.parseV2rayJsonFile("r", cfg(doftAndroid), new ArrayList<>(), new ArrayList<>());
    }

    /** The flag that follows `--socks-server-addr`, or null. Order is part of the contract. */
    static String valueAfter(List<String> cmd, String flag) {
        int i = cmd.indexOf(flag);
        return (i < 0 || i + 1 >= cmd.size()) ? null : cmd.get(i + 1);
    }

    static int count(List<String> cmd, String flag) {
        int n = 0;
        for (String s : cmd) if (s.equals(flag)) n++;
        return n;
    }

    public static void main(String[] a) {
        // ── 1. the mode read out of the config ────────────────────────────────────────
        V2rayConfig c = parse(null);
        check("no marker at all: the FIX is what runs",
                c != null && Tun2socksArgs.UDP_SOCKS5.equals(c.TUN2SOCKS_UDP_MODE),
                "mode=" + (c == null ? "<null>" : c.TUN2SOCKS_UDP_MODE));

        c = parse("{\"udp_mode\":\"udpgw\"}");
        check("marker says udpgw: the escape hatch is honoured",
                c != null && Tun2socksArgs.UDP_UDPGW.equals(c.TUN2SOCKS_UDP_MODE),
                "mode=" + (c == null ? "<null>" : c.TUN2SOCKS_UDP_MODE));

        c = parse("{\"udp_mode\":\"socks5\"}");
        check("marker says socks5: honoured",
                c != null && Tun2socksArgs.UDP_SOCKS5.equals(c.TUN2SOCKS_UDP_MODE),
                "mode=" + (c == null ? "<null>" : c.TUN2SOCKS_UDP_MODE));

        // ⚠ A HALF-WRITTEN OR RENAMED VALUE MUST NOT REINSTATE THE BUG. Only the exact
        // word turns udpgw back on; everything else lands on the working path.
        c = parse("{\"udp_mode\":\"udpg\"}");
        check("a value one letter short does NOT reinstate udpgw",
                c != null && Tun2socksArgs.UDP_SOCKS5.equals(c.TUN2SOCKS_UDP_MODE),
                "mode=" + (c == null ? "<null>" : c.TUN2SOCKS_UDP_MODE));

        c = parse("{\"udp_mode\":\"\"}");
        check("an empty value falls to the fix",
                c != null && Tun2socksArgs.UDP_SOCKS5.equals(c.TUN2SOCKS_UDP_MODE),
                "mode=" + (c == null ? "<null>" : c.TUN2SOCKS_UDP_MODE));

        c = parse("{\"other\":1}");
        check("a marker without udp_mode falls to the fix",
                c != null && Tun2socksArgs.UDP_SOCKS5.equals(c.TUN2SOCKS_UDP_MODE),
                "mode=" + (c == null ? "<null>" : c.TUN2SOCKS_UDP_MODE));

        check("case and whitespace are normalised, not rejected",
                Tun2socksArgs.UDP_UDPGW.equals(Tun2socksArgs.normaliseUdpMode("  UDPGW \n")),
                "'  UDPGW \\n' -> " + Tun2socksArgs.normaliseUdpMode("  UDPGW \n"));
        check("null normalises to the fix (a restored blob with no key)",
                Tun2socksArgs.UDP_SOCKS5.equals(Tun2socksArgs.normaliseUdpMode(null)),
                "null -> " + Tun2socksArgs.normaliseUdpMode(null));

        // ── 2. the marker must never reach xray ───────────────────────────────────────
        // ⚠ THE STATICS-OFF CASE IS THE ONE THAT CATCHES THE REAL TRAP. parseV2rayJsonFile
        // writes `config` back from the JSON object in exactly ONE other place, inside
        // `if (AppConfigs.ENABLE_TRAFFIC_AND_SPEED_STATICS)`. Remove the key without
        // re-serialising and the removal is a no-op for every device that has statics
        // off — which is how the long-standing `policy`/`stats` removals two lines below
        // have always behaved. Testing only the statics-on path would report PASS on a
        // build that ships the marker to the core.
        boolean savedStatics = AppConfigs.ENABLE_TRAFFIC_AND_SPEED_STATICS;
        try {
            AppConfigs.ENABLE_TRAFFIC_AND_SPEED_STATICS = true;
            c = parse("{\"udp_mode\":\"udpgw\"}");
            check("marker is stripped from the core's config (statics ON)",
                    c != null && !c.V2RAY_FULL_JSON_CONFIG.contains("_doft_android"),
                    c == null ? "<null>" : "len=" + c.V2RAY_FULL_JSON_CONFIG.length());

            AppConfigs.ENABLE_TRAFFIC_AND_SPEED_STATICS = false;
            c = parse("{\"udp_mode\":\"udpgw\"}");
            check("marker is stripped from the core's config (statics OFF)",
                    c != null && !c.V2RAY_FULL_JSON_CONFIG.contains("_doft_android"),
                    c == null ? "<null>" : "len=" + c.V2RAY_FULL_JSON_CONFIG.length());
            check("...and the mode survived the strip (statics OFF)",
                    c != null && Tun2socksArgs.UDP_UDPGW.equals(c.TUN2SOCKS_UDP_MODE),
                    "mode=" + (c == null ? "<null>" : c.TUN2SOCKS_UDP_MODE));
            check("...and the outbound endpoint still parsed (statics OFF)",
                    c != null && "204.3.207.89".equals(c.CONNECTED_V2RAY_SERVER_ADDRESS),
                    c == null ? "<null>" : c.CONNECTED_V2RAY_SERVER_ADDRESS);
        } finally {
            AppConfigs.ENABLE_TRAFFIC_AND_SPEED_STATICS = savedStatics;
        }

        c = parse("{\"udp_mode\":\"udpgw\"}");
        check("the socks port is still read past the marker",
                c != null && c.LOCAL_SOCKS5_PORT == 10807,
                "port=" + (c == null ? "?" : c.LOCAL_SOCKS5_PORT));

        // ── 3. the command line itself ────────────────────────────────────────────────
        ArrayList<String> fix = Tun2socksArgs.build("/lib/libtun2socks.so", 10807, 1500,
                Tun2socksArgs.UDP_SOCKS5);
        check("default build asks for a real SOCKS5 UDP ASSOCIATE",
                fix.contains("--socks5-udp"), String.join(" ", fix));
        check("default build does NOT ask for udpgw",
                !fix.contains("--enable-udprelay"), String.join(" ", fix));

        ArrayList<String> old = Tun2socksArgs.build("/lib/libtun2socks.so", 10807, 1500,
                Tun2socksArgs.UDP_UDPGW);
        check("escape hatch restores udpgw exactly",
                old.contains("--enable-udprelay") && !old.contains("--socks5-udp"),
                String.join(" ", old));

        // ⚠ badvpn resolves the mode once while parsing options and udpgw WINS when both
        // are present, so emitting both would silently keep the broken path.
        check("exactly one UDP mode flag, fix build",
                count(fix, "--socks5-udp") + count(fix, "--enable-udprelay") == 1, "1");
        check("exactly one UDP mode flag, escape-hatch build",
                count(old, "--socks5-udp") + count(old, "--enable-udprelay") == 1, "1");

        check("binary is argv[0]",
                "/lib/libtun2socks.so".equals(fix.get(0)), fix.get(0));
        check("socks server address carries the parsed port, in order",
                "127.0.0.1:10807".equals(valueAfter(fix, "--socks-server-addr")),
                String.valueOf(valueAfter(fix, "--socks-server-addr")));
        check("tun mtu is carried, in order",
                "1500".equals(valueAfter(fix, "--tunmtu")),
                String.valueOf(valueAfter(fix, "--tunmtu")));
        check("the unix socket path the service sends the tun fd on is unchanged",
                "sock_path".equals(valueAfter(fix, "--sock-path")),
                String.valueOf(valueAfter(fix, "--sock-path")));
        check("netif address/netmask unchanged (the TUN peer badvpn answers as)",
                "26.26.26.2".equals(valueAfter(fix, "--netif-ipaddr"))
                        && "255.255.255.252".equals(valueAfter(fix, "--netif-netmask")),
                valueAfter(fix, "--netif-ipaddr") + "/" + valueAfter(fix, "--netif-netmask"));

        // A caller cannot smuggle an unknown mode past the rule.
        ArrayList<String> junk = Tun2socksArgs.build("/lib/libtun2socks.so", 10807, 1500, "??");
        check("build() normalises its own argument too",
                junk.contains("--socks5-udp") && !junk.contains("--enable-udprelay"),
                String.join(" ", junk));

        System.out.println(failures == 0 ? "ALL PASS" : (failures + " FAILED"));
        System.out.printf("RESULT tun2socks checks=%d failures=%d%n", checks, failures);
        if (failures != 0) System.exit(1);
    }
}
