/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.stealth;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discovers a working {@link ShellTransport} (Telnet or ADB) and runs pm commands through it.
 * <p>
 * Discovery has two phases:
 * <ol>
 *     <li><b>Cache re-verify.</b> Quickly probe every endpoint that worked last time.
 *         The first one that still answers becomes the active transport, so {@code disableApps}
 *         and {@code restoreSelectedApps} are usable within ~1 second on app restart.</li>
 *     <li><b>Full scan.</b> For each IPv4 address bound to the device (loopback +
 *         every "up" non-loopback interface) scan every TCP port 1..65535, then probe
 *         each open port for ADB / Telnet support. Runs to completion regardless of
 *         whether the cache phase succeeded — so the diagnostic dialog always shows the
 *         full picture and the cache is rewritten with the current set of working ports.</li>
 * </ol>
 */
public class ShellExecutor {
    private static final String TAG = "ShellExecutor";

    /** Concurrency cap when probing ADB/Telnet on detected open ports. */
    private static final int PROTOCOL_PROBE_THREADS = 4;

    /** How many hosts get scanned in parallel during the full discovery phase. */
    private static final int PARALLEL_HOST_SCANS = 4;

    /** Range scanned by the active fallback. */
    private static final int FIRST_PORT = 1;
    private static final int LAST_PORT = 65535;

    /**
     * Upper bound on non-blocking SocketChannels in flight per active-scan batch.
     * The actual size used at runtime is computed from {@code RLIMIT_NOFILE} so we
     * stay safely under the per-process FD limit even when several hosts scan in
     * parallel — see {@link #effectiveBatchSize()}.
     */
    private static final int SCAN_BATCH_SIZE = 128;

    /** Floor on the adaptive batch size — below this, scanning takes too long to be useful. */
    private static final int MIN_BATCH_SIZE = 16;

    /**
     * FDs we leave for the rest of our own app: SharedPreferences, ART runtime,
     * the JIT, adblib's connection thread, the active transport socket, etc.
     * Real Android process at idle uses ~50; 200 is generous.
     */
    private static final int FD_RESERVE_APP = 200;

    /**
     * Of the FDs that remain after our own reserve, how much we voluntarily leave on
     * the table for kernel headroom and other processes. {@code RLIMIT_NOFILE} is
     * per-process so we don't directly compete for FDs with other apps, but socket
     * FDs cost kernel memory (~16KB skb buffers each) and use system-wide TCP
     * connection table slots — being conservative on a constrained head unit avoids
     * starving out background services during a scan.
     */
    private static final double FD_FRACTION_FOR_SCAN = 0.5;

    /**
     * Hard ceiling on the total in-flight scan channels across all parallel hosts.
     * Sized so the kernel sees at most a few hundred SYN packets at once even when
     * the per-process rlimit is generous.
     */
    private static final int MAX_CONCURRENT_SCAN_FDS = 256;

    /** Per-batch timeout in the active scan. */
    private static final int SCAN_BATCH_TIMEOUT_MS = 300;

    /**
     * Adaptive cap on per-batch channel count. Initialised on first scan from
     * {@code RLIMIT_NOFILE}; halved on the fly if a {@code SocketChannel.open()}
     * still hits {@code EMFILE} — there are FDs in use we didn't account for.
     */
    private static volatile int adaptiveBatchSize = -1;

    private static volatile ShellExecutor instance;

    /** Factory for recreating the chosen transport on each batch. Null until discovered. */
    private volatile TransportFactory activeFactory = null;

    // ── Public types ──────────────────────────────────────────────────

    /** Result of a batch operation, supporting partial success. */
    public static class BatchResult {
        private final Set<String> succeededPackages;
        private final List<String> errors;

        BatchResult(Set<String> succeededPackages, List<String> errors) {
            this.succeededPackages = succeededPackages;
            this.errors = errors;
        }

        public Set<String> getSucceededPackages() { return succeededPackages; }
        public List<String> getErrors() { return errors; }
        public boolean isFullSuccess() { return errors.isEmpty(); }
        public boolean hasAnySuccess() { return !succeededPackages.isEmpty(); }
    }

    /** One protocol probe attempt against an open port. */
    public static class ProbeAttempt {
        public final String transport;       // "ADB" or "Telnet"
        public final boolean success;
        /** Brief failure description when {@code success == false}; null otherwise. */
        public final String errorMessage;

        ProbeAttempt(String transport, boolean success, String errorMessage) {
            this.transport = transport;
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }

    /** One open port on a host with the protocol probes that have been attempted on it. */
    public static class PortResult {
        public final int port;
        /** Empty until probes start; populated as ADB/Telnet probes finish. */
        public final List<ProbeAttempt> probes;
        public final boolean isActive;

        PortResult(int port, List<ProbeAttempt> probes, boolean isActive) {
            this.port = port;
            this.probes = probes;
            this.isActive = isActive;
        }

        /** True if any probe on this port succeeded. */
        public boolean hasSupportedTransport() {
            for (ProbeAttempt a : probes) if (a.success) return true;
            return false;
        }

        /** The transport name of the successful probe (if any). */
        public String successfulTransport() {
            for (ProbeAttempt a : probes) if (a.success) return a.transport;
            return null;
        }
    }

    /** Per-host scan output: which ports were probed and whether the host is still being scanned. */
    public static class HostScanResult {
        public final String host;
        public final List<PortResult> ports;
        public final boolean scanning;

        HostScanResult(String host, List<PortResult> ports, boolean scanning) {
            this.host = host;
            this.ports = ports;
            this.scanning = scanning;
        }
    }

    public interface StatusCallback {
        /**
         * Fires whenever discovery state changes (cache probe success, port found, host done…).
         *
         * @param hosts immutable snapshot of all hosts and their results so far
         * @param finished true on the final call: every host has been fully scanned
         */
        void onUpdate(List<HostScanResult> hosts, boolean finished);
    }

    public interface BatchCallback {
        void onResult(BatchResult result);
        void onConnectionError(String error);
    }

    // ── Internals ─────────────────────────────────────────────────────

    @FunctionalInterface
    private interface TransportFactory {
        ShellTransport open() throws Exception;
    }

    /** Mutable per-host state used during a discovery cycle. */
    private static class HostScanState {
        final String host;
        final Map<Integer, PortResult> ports = new HashMap<>(); // keyed by port
        volatile boolean scanning = true;

        HostScanState(String host) { this.host = host; }

        synchronized HostScanResult snapshot() {
            List<PortResult> sorted = new ArrayList<>(ports.values());
            sorted.sort((a, b) -> Integer.compare(a.port, b.port));
            return new HostScanResult(host, sorted, scanning);
        }
    }

    /** Single-thread executor for hide/restore batch ops. Kept off the discovery path so
     *  user-triggered batches don't queue behind a multi-second discovery scan. */
    private final ExecutorService batchExecutor = Executors.newSingleThreadExecutor(daemonThreadFactory("shell-batch"));

    /** Single-thread executor for the connection discovery (cache verify + full scan). */
    private final ExecutorService discoveryExecutor = Executors.newSingleThreadExecutor(daemonThreadFactory("shell-discovery"));

    private final Context appContext;
    private final ConnectionStorage connectionStorage;

    private ShellExecutor(Context appContext) {
        this.appContext = appContext;
        this.connectionStorage = new ConnectionStorage(appContext);
    }

    /** Daemon-thread factory so the executor doesn't block JVM shutdown. */
    private static java.util.concurrent.ThreadFactory daemonThreadFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    public static ShellExecutor getInstance(Context context) {
        if (instance == null) {
            synchronized (ShellExecutor.class) {
                if (instance == null) {
                    instance = new ShellExecutor(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /** True after a working transport has been discovered by {@link #checkConnection}. */
    public boolean hasWorkingTransport() {
        return activeFactory != null;
    }

    // ── Connection check ──────────────────────────────────────────────

    public void checkConnection(StatusCallback callback) {
        discoveryExecutor.execute(() -> {
            // Reset any stale factory cached from a previous discovery cycle. Without this
            // a head-unit reboot (which changes the dynamic ADB port) would leave us
            // pointing at a now-dead endpoint until the app process restarts.
            activeFactory = null;

            // Hosts to probe — loopback first, then every IPv4 bound to an up interface
            List<String> hosts = candidateHosts();

            // Initialize per-host state (all start with scanning=true)
            Map<String, HostScanState> states = new HashMap<>();
            for (String host : hosts) {
                states.put(host, new HostScanState(host));
            }
            // Hosts the cache mentions but that aren't currently bound: still report them
            // so the user sees that "the IP we used last time isn't there now".
            for (ConnectionStorage.Endpoint e : connectionStorage.loadAll()) {
                if (!states.containsKey(e.host)) {
                    states.put(e.host, new HostScanState(e.host));
                    hosts.add(e.host);
                }
            }

            publish(callback, states, false);

            // Phase 1: re-verify cached endpoints (fast — sets activeFactory ASAP)
            verifyCachedEndpoints(states, callback);

            // Phase 2: full per-host scan, regardless of cache result. We always run it
            // so the user gets a complete diagnostic picture.
            scanAllHosts(hosts, states, callback);

            // Persist the freshly discovered set for next launch
            persistResults(states);
        });
    }

    // ── Phase 1: cache re-verify ─────────────────────────────────────

    private void verifyCachedEndpoints(Map<String, HostScanState> states, StatusCallback callback) {
        List<ConnectionStorage.Endpoint> cached = connectionStorage.loadAll();
        if (cached.isEmpty()) return;

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(cached.size(), 8), daemonThreadFactory("cache-verify"));
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (ConnectionStorage.Endpoint e : cached) {
                futures.add(pool.submit(() -> {
                    try {
                        probeKnownEndpoint(e, states, callback);
                    } catch (Throwable t) {
                        Log.e(TAG, "probeKnownEndpoint crashed for " + e.host + ":" + e.port, t);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void probeKnownEndpoint(ConnectionStorage.Endpoint e,
                                    Map<String, HostScanState> states,
                                    StatusCallback callback) {
        ProbeAttempt attempt = tryProtocol(e.host, e.port, e.transport);
        if (attempt.success) {
            // Cache hit still works — record it. Full scan will discover the same port
            // again (and won't re-probe since the slot is already non-empty).
            recordPortResult(e.host, e.port,
                    Collections.singletonList(attempt), states);
            publish(callback, states, false);
        }
        // Cache miss: full scan will repopulate this slot with its own probe results.
    }

    // ── Phase 2: full scan ───────────────────────────────────────────

    private void scanAllHosts(List<String> hosts,
                              Map<String, HostScanState> states,
                              StatusCallback callback) {
        // Scan hosts in parallel. The active-scan fallback dominates wall time when /proc
        // is filtered (real devices), and there's no shared state between hosts that would
        // benefit from sequencing. Each host wrapped in catch(Throwable) so a NIO/JVM hiccup
        // on one host can't prevent the others from being scanned.
        int parallelism = Math.min(hosts.size(), PARALLEL_HOST_SCANS);
        if (parallelism <= 0) { publish(callback, states, true); return; }
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, daemonThreadFactory("scan-host"));
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (String host : hosts) {
                futures.add(pool.submit(() -> {
                    try {
                        scanHost(host, states, callback);
                    } catch (Throwable t) {
                        Log.e(TAG, "scanHost crashed for " + host, t);
                    } finally {
                        states.get(host).scanning = false;
                        publish(callback, states, false);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        } finally {
            pool.shutdownNow();
        }
        publish(callback, states, true);
    }

    private void scanHost(String host, Map<String, HostScanState> states, StatusCallback callback) {
        // 1) Find every open port (closed ports return RST instantly on local interfaces)
        List<Integer> openPorts = scanOpenPorts(host);

        // Pre-populate slots with empty probes so the dialog can show "open, probing…"
        // for each one while we run the protocol checks. Slots already populated by the
        // cache phase (which has actual probe data) are kept untouched.
        synchronized (states.get(host)) {
            for (int port : openPorts) {
                states.get(host).ports.putIfAbsent(port,
                        new PortResult(port, Collections.emptyList(), false));
            }
        }
        publish(callback, states, false);

        // 2) Probe each open port for ADB / Telnet. Daemon factory so the workers don't
        // pin the process if a probe lingers — bounded handshake should already prevent it,
        // but defensive: stuck workers aren't worth keeping the JVM around for.
        if (openPorts.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(openPorts.size(), PROTOCOL_PROBE_THREADS),
                daemonThreadFactory("probe-" + host));
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int port : openPorts) {
                final int p = port;
                futures.add(pool.submit(() -> {
                    try {
                        probeProtocols(host, p, states, callback);
                    } catch (Throwable t) {
                        Log.e(TAG, "probeProtocols crashed for " + host + ":" + p, t);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Try ADB first (fast handshake), then Telnet on the same port. Records every attempt
     * (success or failure with diagnostic message) so the dialog can explain why a port
     * with an open socket didn't end up usable.
     */
    private void probeProtocols(String host, int port,
                                Map<String, HostScanState> states,
                                StatusCallback callback) {
        // Skip if a successful probe is already recorded (e.g. from cache phase)
        synchronized (states.get(host)) {
            PortResult existing = states.get(host).ports.get(port);
            if (existing != null && existing.hasSupportedTransport()) return;
        }

        List<ProbeAttempt> attempts = new ArrayList<>(2);
        ProbeAttempt adb = tryProtocol(host, port, ConnectionStorage.TRANSPORT_ADB);
        attempts.add(adb);
        if (!adb.success) {
            attempts.add(tryProtocol(host, port, ConnectionStorage.TRANSPORT_TELNET));
        }
        recordPortResult(host, port, attempts, states);
        publish(callback, states, false);
    }

    /**
     * Connect with the given transport and run the canonical probe command.
     * Returns a {@link ProbeAttempt} carrying success/failure and a short diagnostic
     * for the failure case (exception class + message, or unexpected response).
     */
    private ProbeAttempt tryProtocol(String host, int port, String transport) {
        if (ConnectionStorage.TRANSPORT_ADB.equals(transport)) {
            // Use a manual handshake instead of adblib — see AdbTransport.probe javadoc.
            // adblib's parseAdbMessage trusts peer-supplied payloadLength and OOMs the
            // entire process when probing a non-ADB port that returns garbage bytes.
            try {
                return AdbTransport.probe(host, port)
                        ? new ProbeAttempt(transport, true, null)
                        : new ProbeAttempt(transport, false, "Not ADB");
            } catch (Throwable t) {
                return new ProbeAttempt(transport, false, describeException(t));
            }
        }

        ShellTransport t = null;
        try {
            t = TelnetTransport.connect(host, port);
            String response = t.exec("pm path android");
            if (response.contains("package:")) {
                return new ProbeAttempt(transport, true, null);
            }
            return new ProbeAttempt(transport, false,
                    "pm path android returned: " + truncate(response, 120));
        } catch (Exception e) {
            return new ProbeAttempt(transport, false, describeException(e));
        } finally {
            if (t != null) try { t.close(); } catch (Exception ignored) {}
        }
    }

    private static String describeException(Throwable e) {
        String name = e.getClass().getSimpleName();
        String msg = e.getMessage();
        String full = (msg == null || msg.isEmpty()) ? name : name + ": " + msg;
        return truncate(full, 200);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ── Listening-port discovery via /proc/net/tcp ────────────────────

    /**
     * Find listening ports for {@code host}. Two-phase lookup:
     * <ol>
     *     <li><b>/proc/net/tcp(6)</b>: instant, accurate, lists every LISTEN socket the
     *         kernel exposes to us. Works on most car-HUD Android builds.</li>
     *     <li><b>Active port scan</b>: fallback when /proc is empty/filtered (e.g. on
     *         the Android emulator, /proc on external interfaces returns nothing).
     *         Uses non-blocking NIO so a single thread covers the whole port range
     *         without crashing the FD limit.</li>
     * </ol>
     */
    private static List<Integer> scanOpenPorts(String host) {
        long started = System.currentTimeMillis();
        InetAddress addr;
        try {
            addr = InetAddress.getByName(host);
        } catch (Exception e) {
            Log.d(TAG, "Cannot resolve host " + host + ": " + e.getMessage());
            return Collections.emptyList();
        }

        boolean isV6 = addr instanceof Inet6Address;
        String v4Hex = isV6 ? null : ipv4ToProcHex(addr);
        String v6Hex = isV6 ? ipv6ToProcHex(addr) : null;

        // Phase 1: /proc/net/tcp(6).
        // For an IPv4 host: check tcp (0.0.0.0/exact) AND tcp6 (:: any — dual-stack listeners).
        // For an IPv6 host: check tcp6 (:: any/exact). No tcp lookup, since IPv4-mapped
        //   sockets aren't reachable via raw IPv6 connect on most kernels.
        Set<Integer> ports = new TreeSet<>();
        if (!isV6 && v4Hex != null) {
            try {
                readProcTcp("/proc/net/tcp", v4Hex, false, ports);
            } catch (Throwable t) {
                Log.d(TAG, "/proc/net/tcp read error: " + t.getMessage());
            }
        }
        try {
            readProcTcp("/proc/net/tcp6", v6Hex, true, ports);
        } catch (Throwable t) {
            Log.d(TAG, "/proc/net/tcp6 read error: " + t.getMessage());
        }

        if (!ports.isEmpty()) {
            Log.d(TAG, "Found " + ports.size() + " listening ports for " + host
                    + " via /proc/net (" + (System.currentTimeMillis() - started) + " ms)");
            return new ArrayList<>(ports);
        }

        // Skip active scan for link-local IPv6 (fe80::*). Daemons that bind specifically
        // to a link-local address are extremely rare on Android — anything reachable here
        // is almost certainly listening on `::` and would be picked up by the same-host
        // /proc/net/tcp6 lookup against ::. The active scan on these addresses tends to
        // be slow and unproductive: connect() on a non-listening port can hit silent drops
        // in the IPv6 stack instead of a fast RST, so every batch hits the full timeout.
        if (addr instanceof Inet6Address && ((Inet6Address) addr).isLinkLocalAddress()) {
            Log.d(TAG, "/proc/net empty for link-local IPv6 " + host + ", skipping active scan");
            return Collections.emptyList();
        }

        // Phase 2: active scan fallback
        Log.d(TAG, "/proc/net empty for " + host + ", falling back to active scan");
        return activeScanOpenPorts(host, started);
    }

    /**
     * Active-probe every TCP port in {@code FIRST_PORT..LAST_PORT} on {@code host} using
     * non-blocking {@link SocketChannel}s and a single shared {@link Selector}.
     */
    private static List<Integer> activeScanOpenPorts(String host, long startedAt) {
        Set<Integer> open = new TreeSet<>();
        Selector selector = null;
        try {
            selector = Selector.open();
            int port = FIRST_PORT;
            int batchIndex = 0;
            while (port <= LAST_PORT) {
                int batchSize = effectiveBatchSize();
                int batchEnd = Math.min(port + batchSize - 1, LAST_PORT);
                int lastAttempted = scanBatch(host, port, batchEnd, SCAN_BATCH_TIMEOUT_MS, open, selector);
                if (lastAttempted < port) {
                    // No progress at all — couldn't even open the first channel. Either FDs
                    // are completely exhausted (rlimit so low we're already at MIN_BATCH_SIZE)
                    // or some other hard failure. Stop here with whatever we found so far.
                    Log.w(TAG, "Active scan aborted for " + host + " at port " + port
                            + ": no FDs available");
                    break;
                }
                // If FD pressure cut the batch short, resume at the next unscanned port
                // — the next iteration will see the (now-smaller) adaptive batch size.
                port = lastAttempted + 1;
                batchIndex++;
                if ((batchIndex % 32) == 0) {
                    Log.d(TAG, "Scan progress " + host + ": through port " + lastAttempted
                            + ", " + open.size() + " open so far ("
                            + (System.currentTimeMillis() - startedAt) + " ms)");
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Active scan crashed for " + host
                    + " after " + (System.currentTimeMillis() - startedAt) + " ms", t);
        } finally {
            closeQuietly(selector);
        }
        Log.d(TAG, "Active-scanned " + host + " in "
                + (System.currentTimeMillis() - startedAt) + " ms, "
                + open.size() + " open ports");
        return new ArrayList<>(open);
    }

    /**
     * Returns the per-batch channel count to use right now, lazily initialised from
     * {@code /proc/self/limits} on first call and possibly halved by {@link #onFdExhausted()}
     * if we still hit EMFILE despite the rlimit-derived ceiling.
     */
    private static int effectiveBatchSize() {
        int size = adaptiveBatchSize;
        if (size > 0) return size;
        synchronized (ShellExecutor.class) {
            if (adaptiveBatchSize > 0) return adaptiveBatchSize;
            adaptiveBatchSize = Math.max(MIN_BATCH_SIZE, computeInitialBatchSize());
            return adaptiveBatchSize;
        }
    }

    /**
     * Compute the per-host batch size from {@code RLIMIT_NOFILE}, three caps applied:
     * <ul>
     *   <li>Reserve {@link #FD_RESERVE_APP} FDs for the rest of our own app.</li>
     *   <li>Leave {@code 1 - FD_FRACTION_FOR_SCAN} of the remainder free for kernel
     *       headroom — even though rlimit is per-process, socket FDs charge against
     *       the system socket pool and TCP connection table, and being modest is the
     *       neighborly thing to do on a constrained head unit.</li>
     *   <li>Hard cap total simultaneous in-flight channels at
     *       {@link #MAX_CONCURRENT_SCAN_FDS} regardless of how generous the rlimit is.</li>
     * </ul>
     * The result is divided across {@link #PARALLEL_HOST_SCANS} parallel host scans.
     */
    private static int computeInitialBatchSize() {
        long softLimit = readSoftFdLimit();
        long fromRlimit;
        if (softLimit > 0) {
            long ourShare = Math.max(0L, softLimit - FD_RESERVE_APP);
            fromRlimit = (long) (ourShare * FD_FRACTION_FOR_SCAN);
        } else {
            fromRlimit = MAX_CONCURRENT_SCAN_FDS;
        }
        long totalBudget = Math.min(MAX_CONCURRENT_SCAN_FDS, fromRlimit);
        long perHost = totalBudget / PARALLEL_HOST_SCANS;
        int derived = (int) Math.min(SCAN_BATCH_SIZE, perHost);
        Log.d(TAG, "Initial batch size " + derived
                + " (RLIMIT_NOFILE=" + (softLimit > 0 ? softLimit : "unknown")
                + ", app reserve " + FD_RESERVE_APP
                + ", scan fraction " + FD_FRACTION_FOR_SCAN
                + ", global cap " + MAX_CONCURRENT_SCAN_FDS
                + ", parallel hosts " + PARALLEL_HOST_SCANS + ")");
        return derived;
    }

    /**
     * Read the soft RLIMIT_NOFILE for this process from {@code /proc/self/limits}.
     * Returns -1 if unreadable. Format of the relevant line, with arbitrary whitespace:
     * {@code Max open files            1024                 4096                 files}.
     */
    private static long readSoftFdLimit() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/limits"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("Max open files")) continue;
                String tail = line.substring("Max open files".length()).trim();
                String[] parts = tail.split("\\s+");
                if (parts.length == 0) return -1;
                if ("unlimited".equalsIgnoreCase(parts[0])) return Long.MAX_VALUE;
                try {
                    return Long.parseLong(parts[0]);
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        } catch (IOException ignored) {}
        return -1;
    }

    /**
     * Halve the adaptive batch size in response to a real EMFILE, bounded below by
     * {@link #MIN_BATCH_SIZE}. Idempotent against concurrent callers — both threads
     * arriving at the same observation will end up at the same floor.
     */
    private static void onFdExhausted() {
        synchronized (ShellExecutor.class) {
            int current = Math.max(MIN_BATCH_SIZE, adaptiveBatchSize);
            int reduced = Math.max(MIN_BATCH_SIZE, current / 2);
            if (reduced < current) {
                Log.w(TAG, "FD exhausted: reducing batch size " + current + " → " + reduced);
                adaptiveBatchSize = reduced;
            }
        }
    }

    /** Heuristic match for EMFILE/ENFILE — Android wraps the syscall in {@link IOException}. */
    private static boolean isFdExhausted(IOException e) {
        String m = e.getMessage();
        if (m == null) return false;
        return m.contains("Too many open files")
                || m.contains("EMFILE")
                || m.contains("ENFILE");
    }

    /**
     * Returns true if the file was opened (regardless of how many entries matched).
     *
     * @param hostHex byte-reversed hex form of the host. May be null — then only
     *                ALL-zero "any" listeners are matched.
     */
    private static boolean readProcTcp(String path, String hostHex, boolean ipv6,
                                       Set<Integer> portsOut) throws IOException {
        String anyHex = ipv6
                ? "00000000000000000000000000000000"
                : "00000000";

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) continue;
                if (!"0A".equalsIgnoreCase(parts[3])) continue; // not LISTEN
                String[] addr = parts[1].split(":");
                if (addr.length != 2) continue;
                String localHex = addr[0];

                boolean matches = anyHex.equalsIgnoreCase(localHex)
                        || (hostHex != null && hostHex.equalsIgnoreCase(localHex));
                if (!matches) continue;

                try {
                    portsOut.add(Integer.parseInt(addr[1], 16));
                } catch (NumberFormatException ignored) {}
            }
            return true;
        }
    }

    /**
     * Convert IPv4 address to the byte-reversed hex form used by {@code /proc/net/tcp}.
     * Example: {@code 127.0.0.1} → {@code 0100007F}, {@code 192.168.2.58} → {@code 3A02A8C0}.
     */
    private static String ipv4ToProcHex(InetAddress addr) {
        if (!(addr instanceof Inet4Address)) return null;
        byte[] b = addr.getAddress();
        return String.format("%02X%02X%02X%02X",
                b[3] & 0xFF, b[2] & 0xFF, b[1] & 0xFF, b[0] & 0xFF);
    }

    /**
     * Convert IPv6 address to the {@code /proc/net/tcp6} hex form: 32 hex chars,
     * 4 little-endian 32-bit words. Example: {@code ::1} → {@code 00000000000000000000000001000000}.
     */
    private static String ipv6ToProcHex(InetAddress addr) {
        if (!(addr instanceof Inet6Address)) return null;
        byte[] b = addr.getAddress(); // 16 bytes, network order
        StringBuilder sb = new StringBuilder(32);
        for (int word = 0; word < 4; word++) {
            int base = word * 4;
            // /proc encodes each 32-bit word with reversed byte order
            for (int i = 3; i >= 0; i--) {
                sb.append(String.format("%02X", b[base + i] & 0xFF));
            }
        }
        return sb.toString();
    }

    /**
     * Open one channel per port in {@code [from, to]}, register them with the shared
     * selector for {@code OP_CONNECT}, then poll up to {@code timeoutMs} for completions.
     * Channels still pending after the timeout are treated as filtered (port not open).
     *
     * @return the last port we actually attempted to open. If FD pressure forced an early
     *         exit this will be less than {@code to}, and the caller resumes scanning
     *         from {@code lastAttempted + 1} with a (likely smaller) next batch.
     */
    private static int scanBatch(String host, int from, int to, long timeoutMs,
                                  Set<Integer> openOut, Selector selector) {
        Map<SocketChannel, Integer> pending = new LinkedHashMap<>();
        int lastAttempted = from - 1;

        try {
            for (int p = from; p <= to; p++) {
                SocketChannel ch = null;
                try {
                    ch = SocketChannel.open();
                    ch.configureBlocking(false);
                    // SO_LINGER(true, 0): on close, abort with RST instead of FIN. Skips
                    // TIME_WAIT and frees the local ephemeral port immediately, preventing
                    // ephemeral-port exhaustion when scanning many open services.
                    try { ch.socket().setSoLinger(true, 0); } catch (Exception ignored) {}

                    boolean immediate = ch.connect(new InetSocketAddress(host, p));
                    if (immediate) {
                        // Loopback connect can complete synchronously — counts as open.
                        openOut.add(p);
                        closeQuietly(ch);
                    } else {
                        ch.register(selector, SelectionKey.OP_CONNECT);
                        pending.put(ch, p);
                    }
                    lastAttempted = p;
                } catch (IOException e) {
                    closeQuietly(ch);
                    if (isFdExhausted(e)) {
                        // Halve the adaptive size and bail out of this batch. The pending
                        // set is drained below so closed channels release FDs immediately.
                        onFdExhausted();
                        break;
                    }
                    // Instant ECONNREFUSED or similar — port is closed, move on.
                    lastAttempted = p;
                } catch (Exception e) {
                    // Register failure or other non-FD issues — skip and move on.
                    closeQuietly(ch);
                    lastAttempted = p;
                }
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!pending.isEmpty()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                int n;
                try {
                    n = selector.select(Math.min(left, 100));
                } catch (IOException e) {
                    break;
                }
                if (n == 0) continue;

                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    SocketChannel ch = (SocketChannel) key.channel();
                    Integer p = pending.remove(ch);
                    try {
                        if (ch.finishConnect() && p != null) {
                            openOut.add(p);
                        }
                    } catch (IOException e) {
                        // Connection refused — port closed.
                    }
                    key.cancel();
                    closeQuietly(ch);
                }
            }
        } finally {
            for (SocketChannel ch : pending.keySet()) {
                closeQuietly(ch);
            }
            // Force the selector to drop the cancelled keys before the next batch
            // re-uses it — otherwise the cancelled-keys set grows unbounded.
            try { selector.selectNow(); } catch (Exception ignored) {}
        }
        return lastAttempted;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }

    /**
     * Format a {@code host:port} pair, wrapping IPv6 hosts in brackets so the
     * port doesn't get misread as part of the address (e.g. {@code [fe80::1]:5555}).
     */
    public static String formatHostPort(String host, int port) {
        if (host != null && host.indexOf(':') >= 0) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Persist a port's probe results to {@code states}. If any of the attempts succeeded
     * and {@code activeFactory} is still null, register that transport as the active one.
     */
    private void recordPortResult(String host, int port, List<ProbeAttempt> attempts,
                                  Map<String, HostScanState> states) {
        ProbeAttempt successful = null;
        for (ProbeAttempt a : attempts) {
            if (a.success) { successful = a; break; }
        }

        boolean becameActive = false;
        if (successful != null && activeFactory == null) {
            String transport = successful.transport;
            TransportFactory factory = ConnectionStorage.TRANSPORT_TELNET.equals(transport)
                    ? () -> TelnetTransport.connect(host, port)
                    : () -> AdbTransport.connect(appContext, host, port);
            synchronized (this) {
                if (activeFactory == null) {
                    activeFactory = factory;
                    becameActive = true;
                }
            }
        }

        HostScanState state = states.get(host);
        if (state == null) return;
        synchronized (state) {
            // Don't downgrade an existing active marker.
            PortResult existing = state.ports.get(port);
            boolean isActive = becameActive || (existing != null && existing.isActive);
            state.ports.put(port, new PortResult(port, attempts, isActive));
        }
    }

    private static void publish(StatusCallback callback,
                                Map<String, HostScanState> states, boolean finished) {
        try {
            List<HostScanResult> snapshot = new ArrayList<>(states.size());
            for (HostScanState s : states.values()) {
                snapshot.add(s.snapshot());
            }
            snapshot.sort((a, b) -> a.host.compareTo(b.host));
            callback.onUpdate(snapshot, finished);
        } catch (Throwable t) {
            // Defensive: a misbehaving callback or render path mustn't kill the
            // discovery thread — just log and keep going.
            Log.e(TAG, "publish failed", t);
        }
    }

    private void persistResults(Map<String, HostScanState> states) {
        List<ConnectionStorage.Endpoint> endpoints = new ArrayList<>();
        for (HostScanState s : states.values()) {
            // Lock per host even though all probe threads are joined by now:
            // matches the access pattern used elsewhere and is robust if scanning logic
            // ever grows a late-arriving update.
            synchronized (s) {
                for (PortResult p : s.ports.values()) {
                    String transport = p.successfulTransport();
                    if (transport != null) {
                        endpoints.add(new ConnectionStorage.Endpoint(s.host, p.port, transport));
                    }
                }
            }
        }
        connectionStorage.saveAll(endpoints);
    }

    private static List<String> candidateHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        // Both v4 and v6 loopback — some daemons bind only to ::1 (e.g. ADB on certain
        // builds where bindv6only is set), invisible from a 127.0.0.1 connect.
        hosts.add("127.0.0.1");
        hosts.add("::1");
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                while (ifaces.hasMoreElements()) {
                    NetworkInterface ni = ifaces.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        // Take both IPv4 and IPv6 — vendors increasingly bind ADB to v6
                        // only, and on some Cityray builds users report it only works
                        // by entering an IPv6 address by hand.
                        hosts.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Failed to enumerate network interfaces: " + e.getMessage());
        }
        return new ArrayList<>(hosts);
    }

    // ── Batch commands ────────────────────────────────────────────────

    public void disableApps(List<String> packageNames, BatchCallback callback) {
        batchExecutor.execute(() -> runBatch(packageNames, true, callback));
    }

    public void enableApps(Set<String> packageNames, BatchCallback callback) {
        batchExecutor.execute(() -> runBatch(new ArrayList<>(packageNames), false, callback));
    }

    private void runBatch(List<String> packageNames, boolean disable, BatchCallback callback) {
        // Snapshot the factory once: discovery runs on a separate executor and may
        // null/swap activeFactory while this batch runs. A local copy keeps the operation
        // consistent even if the field changes mid-flight.
        TransportFactory factory = activeFactory;
        if (factory == null) {
            callback.onConnectionError("No connection detected");
            return;
        }

        ShellTransport transport = null;
        try {
            transport = factory.open();

            Set<String> succeeded = new java.util.LinkedHashSet<>();
            List<String> errors = new ArrayList<>();

            for (int i = 0; i < packageNames.size(); i++) {
                String packageName = packageNames.get(i);
                String command = disable
                        ? "pm disable-user --user 0 " + packageName
                        : "pm enable " + packageName;

                Log.d(TAG, ">> " + command);
                try {
                    String responseText = transport.exec(command);
                    Log.d(TAG, "<< " + responseText);

                    String lower = responseText.toLowerCase(Locale.ROOT);
                    if (lower.contains("exception") || lower.contains("error")
                            || lower.contains("failure") || lower.contains("unknown package")) {
                        errors.add(packageName + ": " + responseText);
                    } else {
                        succeeded.add(packageName);
                    }
                } catch (IOException e) {
                    errors.add(packageName + ": connection lost (" + e.getMessage() + ")");
                    for (int j = i + 1; j < packageNames.size(); j++) {
                        errors.add(packageNames.get(j) + ": connection lost");
                    }
                    break;
                } catch (Exception e) {
                    errors.add(packageName + ": " + e.getMessage());
                }
            }

            callback.onResult(new BatchResult(succeeded, errors));
        } catch (Exception e) {
            callback.onConnectionError("Failed to open transport: " + e.getMessage());
        } finally {
            if (transport != null) transport.close();
        }
    }
}
