package dev.amirzr.flutter_v2ray_client.v2ray.core;

/**
 * Which network the tunnel should declare as the one carrying it — with NO dependency on any
 * Android class, so it can be compiled and run on a laptop.
 *
 * <h2>What was wrong</h2>
 *
 * <p>The tunnel lives in its own process ({@code :RunSoLibV2RayDaemon}) and, until this class,
 * that process had <b>no connectivity awareness at all</b>: a search of the whole tree for
 * {@code ConnectivityManager}, {@code NetworkCallback}, {@code setUnderlyingNetworks} or
 * {@code bindProcessToNetwork} found exactly one hit, in the Flutter Activity — the wrong
 * process, torn down the moment the app is backgrounded, and absent entirely on an always-on or
 * sticky start where there is no Activity.
 *
 * <p>So when the user walks out of the house and Wi-Fi gives way to LTE, nothing in the daemon
 * notices. {@code VpnService.setUnderlyingNetworks} was never called, which means the framework
 * keeps attributing the tunnel to whatever it inferred — and the accounting, the metered bit and
 * the "is this VPN even connected" answer the system gives other apps are all derived from that.
 *
 * <h2>The rule, and why it is this narrow</h2>
 *
 * <p>Declare a network ONLY when the system says it has internet, is not itself a VPN, and has
 * been VALIDATED. Anything less is a guess, and a guess here is strictly worse than saying
 * nothing: {@code setUnderlyingNetworks(null)} means "follow the system default", which is the
 * behaviour we already have. Declaring a dying Wi-Fi as the carrier is how a handover gets
 * pinned to the interface that just went away.
 *
 * <p>⚠ AND IT MUST NOT TRIGGER A RE-DIAL. Reconnecting on the network-change edge was measured
 * on 2026-08-12: Wi-Fi off at +61.8 s, re-dial at +63.3 s while LTE was not usable yet, and the
 * chain then burned shadowsocks → hysteria2 → tuic → xhttp to EXHAUSTED by +137 s on a device
 * whose cellular was fine. This class answers "which network carries us", never "dial again".
 */
public final class UnderlyingNetworkPolicy {

    private UnderlyingNetworkPolicy() {}

    /**
     * Should this network be declared as the one carrying the tunnel?
     *
     * @param hasInternet the system reports NET_CAPABILITY_INTERNET
     * @param notVpn      the system reports NET_CAPABILITY_NOT_VPN — declaring OUR OWN tunnel
     *                    as the network underneath it is a loop, and the framework is not
     *                    obliged to notice
     * @param validated   the system reports NET_CAPABILITY_VALIDATED, i.e. it actually reached
     *                    something. A joined-but-unvalidated network is the captive-portal
     *                    shape and the commonest way a handover lands somewhere useless
     */
    public static boolean shouldDeclare(boolean hasInternet, boolean notVpn, boolean validated) {
        return hasInternet && notVpn && validated;
    }

    /**
     * What to do when a declared network is lost.
     *
     * <p>Always "declare nothing" — never the next candidate. The callback that reports a loss
     * does not know what replaced it, and {@code null} restores exactly today's behaviour
     * (follow the system default) until a validated network arrives and says so itself.
     */
    public static boolean clearOnLost() {
        return true;
    }
}
