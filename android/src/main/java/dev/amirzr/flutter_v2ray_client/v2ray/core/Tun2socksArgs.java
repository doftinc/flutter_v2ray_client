package dev.amirzr.flutter_v2ray_client.v2ray.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The tun2socks command line, with NO dependency on any Android class — so it can be
 * compiled and run on a laptop.
 *
 * <p>⚠ WHY IT IS ITS OWN CLASS. One word on this command line decides whether the device
 * can carry a datagram at all, and it lived inline in {@code V2rayVPNService.runTun2socks()},
 * which needs a VpnService to instantiate. Nothing could exercise it, and the defect below
 * survived every green suite this repo has.
 *
 * <h2>The defect</h2>
 *
 * <p>The service passed {@code --enable-udprelay}. In badvpn that selects the <b>udpgw</b>
 * UDP mode, which frames datagrams in the udpgw protocol and sends them to a udpgw server.
 * There is no udpgw server here — {@code --socks-server-addr} points at xray's SOCKS5
 * inbound on loopback, and xray does not speak udpgw. So every datagram the device
 * produced was written into a socket nobody could parse, and TCP kept working perfectly
 * because SOCKS5 CONNECT is a different code path in the same binary.
 *
 * <p>Measured on 2026-08-20, Android on the Russian line: the tunnel carried 449 KB/s of
 * TCP while the app's own STUN check got no answer from either server in 6.3 s, and the
 * node — capturing on its egress in the same minute, and relaying Telegram call media for
 * other users the whole time as a positive control — saw <b>zero</b> packets to either
 * STUN address. The datagrams never left the phone. iOS, which has no tun2socks at all
 * (sing-box owns its own TUN), was placing calls over the same node throughout.
 *
 * <p>{@code --socks5-udp} selects badvpn's other implementation: a real SOCKS5 UDP
 * ASSOCIATE per flow, which xray has always supported and which was verified end to end
 * against this fleet before this change was written.
 *
 * <h2>Why the mode is a string and not a boolean</h2>
 *
 * <p>It is carried in the config under {@code _doft_android.udp_mode}, so the old
 * behaviour can be restored from the server without a rebuild — a build is a day and a
 * store review, and this is the connect path for every Android user. {@link #UDP_UDPGW}
 * is that escape hatch and nothing else; anything unrecognised resolves to
 * {@link #UDP_SOCKS5}, because an unreadable value must not silently reinstate the bug.
 */
public final class Tun2socksArgs {

    /** Real SOCKS5 UDP ASSOCIATE per flow. The default, and the fix. */
    public static final String UDP_SOCKS5 = "socks5";

    /** badvpn's udpgw framing. Kept only as a server-flippable way back. */
    public static final String UDP_UDPGW = "udpgw";

    /** Top-level config key the Dart side writes and {@code Utilities} strips. */
    public static final String CONFIG_KEY = "_doft_android";

    private Tun2socksArgs() {}

    /**
     * Resolve a mode name from the config into one this class will act on.
     *
     * <p>⚠ FAIL TOWARDS THE FIX. Null, empty, mixed case, whitespace and outright
     * garbage all resolve to {@link #UDP_SOCKS5}. The only input that turns the udpgw
     * path back on is the exact word, which is what makes it an operator action rather
     * than an accident of a truncated config.
     */
    public static String normaliseUdpMode(String raw) {
        if (raw == null) {
            return UDP_SOCKS5;
        }
        String v = raw.trim().toLowerCase(java.util.Locale.US);
        return UDP_UDPGW.equals(v) ? UDP_UDPGW : UDP_SOCKS5;
    }

    /**
     * Build the argument vector for the tun2socks binary.
     *
     * @param binaryPath absolute path of {@code libtun2socks.so}
     * @param socksPort  the port xray's socks inbound is listening on, read from the
     *                   config by {@code Utilities.parseV2rayJsonFile}
     * @param tunMtu     the MTU handed to {@code VpnService.Builder.setMtu}
     * @param udpMode    {@link #UDP_SOCKS5} or {@link #UDP_UDPGW}; anything else is
     *                   normalised here as well, so a caller cannot bypass the rule
     */
    public static ArrayList<String> build(String binaryPath, int socksPort, int tunMtu,
            String udpMode) {
        String mode = normaliseUdpMode(udpMode);
        List<String> base = Arrays.asList(
                binaryPath,
                "--netif-ipaddr", "26.26.26.2",
                "--netif-netmask", "255.255.255.252",
                "--socks-server-addr", "127.0.0.1:" + socksPort,
                "--tunmtu", String.valueOf(tunMtu),
                "--sock-path", "sock_path");
        ArrayList<String> cmd = new ArrayList<>(base);
        // ⚠ EXACTLY ONE OF THESE, EVER. badvpn resolves the UDP mode once while parsing
        // options and `--enable-udprelay` wins when both are present, so emitting both
        // "to be safe" would silently keep the broken path.
        cmd.add(UDP_UDPGW.equals(mode) ? "--enable-udprelay" : "--socks5-udp");
        cmd.add("--loglevel");
        cmd.add("error");
        return cmd;
    }
}
