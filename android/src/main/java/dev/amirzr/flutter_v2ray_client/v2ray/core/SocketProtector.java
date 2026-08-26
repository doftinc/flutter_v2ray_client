package dev.amirzr.flutter_v2ray_client.v2ray.core;

import dev.amirzr.flutter_v2ray_client.v2ray.interfaces.V2rayServicesListener;

/**
 * The one answer the Go side acts on: "is this socket safe to send from?"
 *
 * <p>⚠ IT MUST FAIL CLOSED. libv2ray's {@code protectSocket()} (doft_protect.go) refuses
 * the dial when this returns false, and that refusal is the whole safety property for
 * TUIC: quic-go opens its own UDP socket, outside xray's dialer, so this shim is the only
 * thing standing between the packets that CARRY the tunnel and the tunnel itself.
 *
 * <p>The shipped version answered TRUE when there was no service to ask:
 *
 * <pre>
 *     if (v2rayServicesListener != null) return v2rayServicesListener.onProtect((int) fd);
 *     return true;
 * </pre>
 *
 * which reports an UNPROTECTED socket as protected — the one answer that turns a refusal
 * the balancer would retry into a tunnel that looks connected and carries nothing.
 * {@code shutdown()} sets that field to null, so a TUIC re-dial racing a core shutdown
 * lands exactly there. An honest {@code false} costs one dial.
 *
 * <p>It lives outside {@code V2rayCoreManager} for the same reason
 * {@link TuicConfigRewriter} does: that class pulls in the AAR and a VpnService, so
 * nothing could exercise it. Nothing ever had — there was not one assertion about socket
 * protection anywhere in this repo, and the VpnService stub's {@code protect()} returned
 * a hard-coded true, so no test could have expressed one.
 */
public final class SocketProtector {

    private SocketProtector() {}

    /** True only when a VpnService actually accepted responsibility for this descriptor. */
    public static boolean protect(final V2rayServicesListener listener, final long fd) {
        if (listener == null) {
            // No service to ask. The socket is not protected, and saying otherwise is
            // the failure mode this class exists to prevent.
            return false;
        }
        if (fd < 0 || fd > Integer.MAX_VALUE) {
            // gomobile hands us a long; VpnService.protect takes an int. A descriptor
            // that does not narrow cleanly cannot be protected, and narrowing it anyway
            // would protect a DIFFERENT socket.
            return false;
        }
        return listener.onProtect((int) fd);
    }
}
