package dev.amirzr.flutter_v2ray_client.v2ray.core;
// Same package as SocketProtector, exactly as TuicRewriteHarness sits beside the rewriter.

import dev.amirzr.flutter_v2ray_client.v2ray.interfaces.V2rayServicesListener;
import android.app.Service;

/**
 * The socket protector had NO test of any kind, and the VpnService stub could not have
 * carried one: its {@code protect()} was a hard-coded {@code true}.
 *
 * <p>What is being protected here is not a nicety. libv2ray refuses a TUIC dial when this
 * answers false (doft_protect.go), because quic-go opens the QUIC socket outside xray's
 * dialer and an unprotected one carries the tunnel's own packets back into the tunnel.
 * The shipped shim answered {@code true} when there was no service to ask.
 */
public class ProtectorHarness {
    static int failures = 0;
    /** counted at runtime so the suite total cannot be hand-typed wrong */
    static int checks = 0;

    static void check(String name, boolean ok, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.printf("%-58s %s  %s%n", name, ok ? "PASS" : "FAIL", detail);
    }

    /** A listener that records what it was asked and answers what it is told to. */
    static final class Fake implements V2rayServicesListener {
        boolean answer = true;
        int calls = 0;
        int lastFd = -777;
        public boolean onProtect(int socket) { calls++; lastFd = socket; return answer; }
        public Service getService() { return null; }
        public void startService() {}
        public void stopService() {}
    }

    public static void main(String[] a) {
        Fake f = new Fake();

        // 1. The answer is the SERVICE's answer, both ways round.
        f.answer = true;
        check("service says yes -> protected", SocketProtector.protect(f, 7L), "true");
        f.answer = false;
        check("service says NO  -> refused (the dial must fail)",
                !SocketProtector.protect(f, 7L), "false");

        // 2. The descriptor arrives unchanged — protecting the wrong fd is the same
        //    outcome as protecting none.
        f.answer = true;
        f.lastFd = -777;
        SocketProtector.protect(f, 4242L);
        check("the fd is passed through unchanged", f.lastFd == 4242, "fd=" + f.lastFd);

        // 3. ⚠ THE REGRESSION. No service to ask means the socket is NOT protected.
        //    The shipped shim returned true here, which is how an unprotected QUIC
        //    socket gets reported as safe to send from. `shutdown()` nulls that field,
        //    so a TUIC re-dial racing a core shutdown lands exactly on this line.
        check("no listener -> REFUSED, never a cheerful true",
                !SocketProtector.protect(null, 7L), "false");

        // 4. gomobile hands a long; VpnService.protect takes an int. A descriptor that
        //    does not narrow cleanly must not be narrowed anyway — that would protect a
        //    different socket and report success.
        f.calls = 0;
        check("fd above int range -> refused",
                !SocketProtector.protect(f, ((long) Integer.MAX_VALUE) + 1L), "false");
        check("fd above int range -> the service is never asked", f.calls == 0,
                "calls=" + f.calls);
        f.calls = 0;
        check("negative fd -> refused", !SocketProtector.protect(f, -1L), "false");
        check("negative fd -> the service is never asked", f.calls == 0, "calls=" + f.calls);

        System.out.printf("RESULT protector checks=%d failures=%d%n", checks, failures);
        if (failures > 0) System.exit(1);
    }
}
