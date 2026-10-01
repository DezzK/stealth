package dezz.stealth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Shell transport over Telnet protocol (raw TCP with IAC negotiation).
 * <p>
 * Reliably handles both echo-on and echo-off Telnet servers by using
 * {@code lastIndexOf} on the end-of-command marker, draining any trailing
 * prompt with a short timeout, and stripping the echoed command line when
 * detected.
 */
public class TelnetTransport implements ShellTransport {
    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int BANNER_DRAIN_MS = 500;
    private static final int TRAILING_DRAIN_MS = 200;
    private static final String END_MARKER = "__DONE__";

    /**
     * Hard ceiling on the banner phase. A real Telnet server emits a short banner and then
     * goes quiet, so {@link #BANNER_DRAIN_MS} of silence ends the drain. A port that streams
     * bytes continuously (some non-Telnet services do) never produces that gap, so the
     * idle-gap break never fires — without this ceiling {@code drainBanner} reads forever,
     * wedges the discovery thread, and holds the remote port open the entire time. That exact
     * trap is what probing a chatty, adb-coupled service port looks like in the field.
     */
    private static final int BANNER_DEADLINE_MS = 3000;

    /**
     * Hard ceiling on a single {@link #exec} call. Bounds total read time even when the peer
     * dribbles a byte more often than {@link #READ_TIMEOUT_MS}, which would otherwise keep the
     * idle-gap timeout from ever firing.
     */
    private static final int EXEC_DEADLINE_MS = 10000;

    /** Ceiling on one response so a peer that never emits the marker can't grow the buffer without bound. */
    private static final int MAX_RESPONSE_BYTES = 1 << 16; // 64 KiB

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String host;
    private final int port;

    private TelnetTransport(Socket socket, InputStream in, OutputStream out, String host, int port) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.host = host;
        this.port = port;
    }

    /**
     * Connect via Telnet, handle initial IAC negotiation and drain banner.
     * <p>
     * The peer must say something first. A real telnetd/shell greets us with IAC
     * negotiation, a banner or a prompt (at the latest in response to a bare newline).
     * If nothing at all comes back we are most likely talking to some vendor daemon
     * (MCU bridge, diagnostics, …) that merely accepts TCP — and we refuse to push
     * shell commands at it.
     */
    public static TelnetTransport connect(String host, int port) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            boolean greeted = drainBanner(socket, in, out);
            if (!greeted) {
                // Silent peer — a shell without a banner still answers a newline with a prompt.
                out.write('\n');
                out.flush();
                greeted = drainBanner(socket, in, out);
            }
            if (!greeted) {
                throw new IOException("No Telnet banner or prompt");
            }

            return new TelnetTransport(socket, in, out, host, port);
        } catch (Exception e) {
            try { socket.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public String describe() {
        return "telnet " + ShellExecutor.formatHostPort(host, port);
    }

    @Override
    public String exec(String command) throws Exception {
        socket.setSoTimeout(READ_TIMEOUT_MS);

        String fullCommand = command + " ; echo " + END_MARKER + "\n";
        out.write(fullCommand.getBytes(StandardCharsets.UTF_8));
        out.flush();

        return readResponseUntilMarker();
    }

    @Override
    public void close() {
        try { socket.close(); } catch (Exception ignored) {}
    }

    // ── Banner drain ──────────────────────────────────────────────────

    /**
     * Drain initial Telnet banner and IAC negotiation. Reads until {@value BANNER_DRAIN_MS}ms
     * of silence — more robust than a fixed sleep on slow servers.
     *
     * @return true if the peer sent at least one byte (IAC, banner or prompt).
     */
    private static boolean drainBanner(Socket socket, InputStream in, OutputStream out) throws Exception {
        socket.setSoTimeout(BANNER_DRAIN_MS);
        long deadline = System.currentTimeMillis() + BANNER_DEADLINE_MS;
        byte[] buf = new byte[4096];
        boolean received = false;
        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                // Continuous chatter with no idle gap — not a usable Telnet shell. Fail fast
                // instead of reading forever and holding the port open.
                throw new IOException("Telnet banner did not settle within " + BANNER_DEADLINE_MS + "ms");
            }
            int len;
            try {
                len = in.read(buf);
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
            if (len == -1) break;
            if (len > 0) received = true;
            handleIacInBuffer(buf, len, out);
            // Banner text is discarded
        }
        return received;
    }

    /**
     * Walk a freshly-read buffer, replying to any IAC WILL/DO with WONT/DONT.
     * Banner content (non-IAC bytes) is ignored.
     */
    private static void handleIacInBuffer(byte[] buf, int len, OutputStream out) throws Exception {
        for (int i = 0; i < len; i++) {
            int b = buf[i] & 0xFF;
            if (b == 0xFF && i + 2 < len) {
                int cmd = buf[i + 1] & 0xFF;
                int opt = buf[i + 2] & 0xFF;
                if (cmd == 0xFB || cmd == 0xFD) {
                    out.write(new byte[]{(byte) 0xFF, (byte) (cmd == 0xFB ? 0xFC : 0xFE), (byte) opt});
                    out.flush();
                }
                i += 2;
            }
        }
    }

    // ── Response reading ──────────────────────────────────────────────

    /**
     * Read until the end marker is observed, then briefly drain any trailing
     * prompt bytes so they don't leak into the next exec().
     */
    private String readResponseUntilMarker() throws Exception {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        byte[] markerBytes = END_MARKER.getBytes(StandardCharsets.US_ASCII);
        boolean markerSeen = false;
        long deadline = System.currentTimeMillis() + EXEC_DEADLINE_MS;

        while (true) {
            if (System.currentTimeMillis() >= deadline || collected.size() > MAX_RESPONSE_BYTES) {
                // Overran the command deadline or the response ceiling — stop reading and parse
                // whatever arrived. A peer that never emits the marker can't trap us here.
                break;
            }
            int len;
            try {
                len = in.read(buf);
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
            if (len == -1) break;

            for (int i = 0; i < len; i++) {
                int b = buf[i] & 0xFF;
                if (b == 0xFF) {
                    // IAC sequence — handle inline, do not append to output
                    if (i + 2 < len) {
                        int cmd = buf[i + 1] & 0xFF;
                        int opt = buf[i + 2] & 0xFF;
                        if (cmd == 0xFB || cmd == 0xFD) {
                            out.write(new byte[]{(byte) 0xFF, (byte) (cmd == 0xFB ? 0xFC : 0xFE), (byte) opt});
                            out.flush();
                        }
                        i += 2;
                    }
                    // IAC at buffer boundary is a rare edge case — drop the lone 0xFF
                } else if (b != 0x00 && b != '\r') {
                    collected.write(b);
                }
            }

            // Once the marker appears, switch to a short timeout to capture
            // any trailing prompt and stop quickly afterwards.
            if (!markerSeen && containsSequence(collected, markerBytes)) {
                markerSeen = true;
                socket.setSoTimeout(TRAILING_DRAIN_MS);
            }
        }

        return parseResponse(new String(collected.toByteArray(), StandardCharsets.UTF_8));
    }

    /**
     * Strip the end marker, optional echoed command, and trailing prompt
     * to recover just the command output.
     */
    private static String parseResponse(String full) {
        // Use lastIndexOf to find the actual marker (the echoed command line
        // contains an earlier occurrence which would mislead indexOf).
        int markerPos = full.lastIndexOf(END_MARKER);
        if (markerPos < 0) {
            return full.trim();
        }

        String result = full.substring(0, markerPos);
        if (result.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }

        // If the first line is the echoed command, strip it. The echoed line
        // always ends with "echo __DONE__" because that's the suffix we append
        // to every command before sending it.
        String echoSuffix = "echo " + END_MARKER;
        int firstNewline = result.indexOf('\n');
        if (firstNewline >= 0) {
            String firstLine = result.substring(0, firstNewline);
            if (firstLine.endsWith(echoSuffix)) {
                result = result.substring(firstNewline + 1);
            }
        } else if (result.endsWith(echoSuffix)) {
            // Single-line response that's only the echo (real output was empty)
            result = "";
        }

        return result.trim();
    }

    private static boolean containsSequence(ByteArrayOutputStream haystack, byte[] needle) {
        byte[] data = haystack.toByteArray();
        if (data.length < needle.length) return false;
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
