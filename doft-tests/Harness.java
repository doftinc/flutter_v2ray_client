import dev.amirzr.flutter_v2ray_client.v2ray.utils.Utilities;
import dev.amirzr.flutter_v2ray_client.v2ray.utils.V2rayConfig;
import java.util.ArrayList;

/** Runs the REAL parseV2rayJsonFile against the four outbound shapes this fleet emits. */
public class Harness {
    static int failures = 0;
    /** counted at runtime so the suite total cannot be hand-typed wrong */
    static int checks = 0;

    static String cfg(String outbound) {
        return "{\"inbounds\":[{\"protocol\":\"socks\",\"port\":10808},"
             + "{\"protocol\":\"http\",\"port\":10809}],"
             + "\"outbounds\":[" + outbound + ",{\"tag\":\"direct\",\"protocol\":\"freedom\"}]}";
    }

    static void expect(String name, String outbound, String wantAddr, String wantPort) {
        V2rayConfig c = Utilities.parseV2rayJsonFile("r", cfg(outbound), new ArrayList<>(), new ArrayList<>());
        String got = (c == null) ? "<null>" : c.CONNECTED_V2RAY_SERVER_ADDRESS + ":" + c.CONNECTED_V2RAY_SERVER_PORT;
        String want = (wantAddr == null) ? "<null>" : wantAddr + ":" + wantPort;
        boolean ok = got.equals(want);
        checks++;
        if (!ok) failures++;
        System.out.printf("%-28s %s  got=%s want=%s%n", name, ok ? "PASS" : "FAIL", got, want);
    }

    // ⚠ DESKTOP org.json IS STRICTER THAN ANDROID'S. Here `getString` on an Integer
    // throws; on Android it coerces to "8388". The pre-fix code used getString, so a
    // desktop run makes it fail for a reason the device never saw. Ports are therefore
    // written as STRINGS below, which both implementations read identically — leaving
    // exactly one variable: whether the endpoint is under vnext, servers, or inline.
    // (The fixed code uses optString, which coerces on both, so it is portable either
    // way — that is why the int-port variants above also pass.)
    public static void main(String[] a) {
        // Reality / VLESS — what Android leads with today.
        expect("vless (vnext)",
            "{\"tag\":\"proxy\",\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"204.3.207.89\",\"port\":\"443\",\"users\":[{\"id\":\"u\"}]}]}}",
            "204.3.207.89", "443");
        // Shadowsocks — the other shape the old code handled.
        expect("shadowsocks (servers)",
            "{\"tag\":\"proxy\",\"protocol\":\"shadowsocks\",\"settings\":{\"servers\":[{\"address\":\"204.3.207.89\",\"port\":\"8388\",\"method\":\"m\",\"password\":\"p\"}]}}",
            "204.3.207.89", "8388");
        // TUIC — a socks outbound at the in-process listener.
        expect("tuic (socks loopback)",
            "{\"tag\":\"proxy\",\"protocol\":\"socks\",\"settings\":{\"servers\":[{\"address\":\"127.0.0.1\",\"port\":\"0\"}]}}",
            "127.0.0.1", "0");
        // HYSTERIA2 — the shape that returned null and produced the 2026-08-09 outage.
        expect("hysteria2 (inline address)",
            "{\"tag\":\"proxy\",\"protocol\":\"hysteria\",\"settings\":{\"address\":\"204.3.207.89\",\"port\":\"8446\",\"version\":2}}",
            "204.3.207.89", "8446");
        // xhttp is VLESS, so it takes the vnext branch whatever the stream network is.
        expect("xhttp (vless over h3)",
            "{\"tag\":\"proxy\",\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"204.3.207.89\",\"port\":\"8448\",\"users\":[{\"id\":\"u\"}]}]},\"streamSettings\":{\"network\":\"xhttp\"}}",
            "204.3.207.89", "8448");
        // No endpoint anywhere must still be null — but now a LOGGED null.
        expect("no endpoint at all", "{\"tag\":\"proxy\",\"protocol\":\"freedom\"}", null, null);
        System.out.println(failures == 0 ? "\nALL PASS" : "\n" + failures + " FAILURE(S)");
        System.out.println("RESULT endpoints checks=" + checks + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
