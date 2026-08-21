import dev.amirzr.flutter_v2ray_client.v2ray.core.UnderlyingNetworkPolicy;

/**
 * Which network the tunnel declares as the one carrying it.
 *
 * ⚠ WHY THIS MATTERS ENOUGH TO TEST. Until this policy existed the daemon process had NO
 * connectivity awareness at all — the only NetworkCallback in the tree was in the Flutter
 * Activity, which is a different process, is gone when the app is backgrounded, and does not
 * exist on an always-on or sticky start. So `setUnderlyingNetworks` was never called once, and
 * a Wi-Fi -> LTE handover happened with nothing in the tunnel's own process noticing.
 *
 * The rule is deliberately narrow, and every case below is a way of getting it wrong.
 */
public class UnderlyingNetworkHarness {
    static int failures = 0;
    /** counted at runtime so the suite total cannot be hand-typed wrong */
    static int checks = 0;

    static void check(String name, boolean ok, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.printf("%-64s %s  %s%n", name, ok ? "PASS" : "FAIL", detail);
    }

    public static void main(String[] a) {
        // internet + not-vpn + validated ⇒ the only shape worth declaring.
        check("a validated, non-VPN network with internet is declared",
                UnderlyingNetworkPolicy.shouldDeclare(true, true, true), "");

        // ⚠ UNVALIDATED IS THE CAPTIVE-PORTAL SHAPE, and it is the commonest way a handover
        // lands somewhere useless: joined, associated, routable, and reaching nothing.
        check("a network that has NOT validated is not declared",
                !UnderlyingNetworkPolicy.shouldDeclare(true, true, false), "");

        // ⚠ DECLARING OUR OWN TUNNEL AS THE NETWORK UNDERNEATH IT IS A LOOP, and the framework
        // is not obliged to notice. NET_CAPABILITY_NOT_VPN is what keeps it out.
        check("a VPN network is never declared as the carrier",
                !UnderlyingNetworkPolicy.shouldDeclare(true, false, true), "");

        check("a network with no internet capability is not declared",
                !UnderlyingNetworkPolicy.shouldDeclare(false, true, true), "");

        // Every partial combination, so a future edit cannot loosen one leg quietly.
        int declared = 0;
        for (int i = 0; i < 8; i++) {
            boolean net = (i & 1) != 0, notVpn = (i & 2) != 0, val = (i & 4) != 0;
            if (UnderlyingNetworkPolicy.shouldDeclare(net, notVpn, val)) declared++;
        }
        check("exactly ONE of the eight capability combinations declares",
                declared == 1, "declared=" + declared);

        // ⚠ A LOSS CLEARS, IT NEVER PROMOTES. The callback that reports a loss does not know
        // what replaced it; null means "follow the system default", which is the behaviour
        // that shipped for years. Guessing the next candidate is how a handover gets pinned
        // to the interface that just went away.
        check("a lost network clears the declaration rather than guessing the next",
                UnderlyingNetworkPolicy.clearOnLost(), "");

        System.out.println(failures == 0 ? "ALL PASS" : (failures + " FAILED"));
        System.out.printf("RESULT underlying-network checks=%d failures=%d%n", checks, failures);
        if (failures != 0) System.exit(1);
    }
}
