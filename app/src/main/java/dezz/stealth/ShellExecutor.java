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

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private static final int FIRST_PORT = 1;
    private static final int LAST_PORT = 65535;

    /** Per-port connect timeout during the open-port scan. */
    private static final int SCAN_PORT_TIMEOUT_MS = 200;

    /** Hard ceiling on a single host's open-port scan, regardless of completion. */
    private static final int SCAN_HOST_BUDGET_MS = 8000;

    /** Worker count for the per-host port scan. Local interfaces RST closed ports
     *  in microseconds, so the bottleneck is OS thread overhead, not network. */
    private static final int PORT_SCAN_THREADS = 64;

    /** Concurrency cap when probing ADB/Telnet on detected open ports. */
    private static final int PROTOCOL_PROBE_THREADS = 4;

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

    /** One open port on a host with an optional matched transport. */
    public static class PortResult {
        public final int port;
        /** "ADB" / "Telnet" if a supported protocol matched, null if the port was open
         *  but neither ADB nor Telnet handshake completed. */
        public final String transport;
        public final boolean isActive;

        PortResult(int port, String transport, boolean isActive) {
            this.port = port;
            this.transport = transport;
            this.isActive = isActive;
        }

        public boolean hasSupportedTransport() { return transport != null; }
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

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(cached.size(), 8));
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (ConnectionStorage.Endpoint e : cached) {
                futures.add(pool.submit(() -> probeKnownEndpoint(e, states, callback)));
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
        ShellTransport transport = null;
        try {
            if (ConnectionStorage.TRANSPORT_TELNET.equals(e.transport)) {
                transport = TelnetTransport.connect(e.host, e.port);
                if (!transport.exec("pm path android").contains("package:")) return;
            } else if (ConnectionStorage.TRANSPORT_ADB.equals(e.transport)) {
                transport = AdbTransport.connect(appContext, e.host, e.port);
                transport.exec("echo ok");
            } else {
                return;
            }
            recordSuccess(e.host, e.port, e.transport, states);
            publish(callback, states, false);
        } catch (Exception ex) {
            // cache hit doesn't work any more — that's fine, full scan will handle it
        } finally {
            if (transport != null) try { transport.close(); } catch (Exception ignored) {}
        }
    }

    // ── Phase 2: full scan ───────────────────────────────────────────

    private void scanAllHosts(List<String> hosts,
                              Map<String, HostScanState> states,
                              StatusCallback callback) {
        // Scan hosts sequentially — caps peak threads at ~64 (one host's port pool at a time)
        // instead of 64 × N. Loopback comes first by candidate ordering, so an active
        // local transport is discovered before any external interface is touched.
        for (String host : hosts) {
            try {
                scanHost(host, states, callback);
            } finally {
                states.get(host).scanning = false;
                publish(callback, states, false);
            }
        }
        publish(callback, states, true);
    }

    private void scanHost(String host, Map<String, HostScanState> states, StatusCallback callback) {
        // 1) Find every open port (closed ports return RST instantly on local interfaces)
        List<Integer> openPorts = scanOpenPorts(host);

        // Pre-populate slots so the dialog can show "open port — protocol unknown" while
        // we still probe each one. Existing slots from the cache phase keep their state.
        synchronized (states.get(host)) {
            for (int port : openPorts) {
                states.get(host).ports.putIfAbsent(port, new PortResult(port, null, false));
            }
        }
        publish(callback, states, false);

        // 2) Probe each open port for ADB / Telnet
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(Math.max(1, openPorts.size()), PROTOCOL_PROBE_THREADS));
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int port : openPorts) {
                final int p = port;
                futures.add(pool.submit(() -> probeProtocols(host, p, states, callback)));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Try ADB first (fast handshake), then Telnet. Records the first successful protocol
     * in {@code states}; CAS-wins also become the {@code activeFactory}.
     */
    private void probeProtocols(String host, int port,
                                Map<String, HostScanState> states,
                                StatusCallback callback) {
        // Skip if already filled in (e.g. from cache phase)
        synchronized (states.get(host)) {
            PortResult existing = states.get(host).ports.get(port);
            if (existing != null && existing.transport != null) return;
        }

        // ADB
        if (tryProtocol(host, port, ConnectionStorage.TRANSPORT_ADB, states, callback)) return;
        // Telnet
        tryProtocol(host, port, ConnectionStorage.TRANSPORT_TELNET, states, callback);
    }

    private boolean tryProtocol(String host, int port, String transport,
                                Map<String, HostScanState> states, StatusCallback callback) {
        ShellTransport t = null;
        try {
            if (ConnectionStorage.TRANSPORT_ADB.equals(transport)) {
                t = AdbTransport.connect(appContext, host, port);
                t.exec("echo ok");
            } else {
                t = TelnetTransport.connect(host, port);
                if (!t.exec("pm path android").contains("package:")) return false;
            }
            recordSuccess(host, port, transport, states);
            publish(callback, states, false);
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (t != null) try { t.close(); } catch (Exception ignored) {}
        }
    }

    // ── Open-port scan ────────────────────────────────────────────────

    /** Connect-scan {@code FIRST_PORT}..{@code LAST_PORT} on {@code host}. */
    private static List<Integer> scanOpenPorts(String host) {
        ExecutorService pool = Executors.newFixedThreadPool(PORT_SCAN_THREADS);
        long deadline = System.currentTimeMillis() + SCAN_HOST_BUDGET_MS;
        List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>(LAST_PORT);
        try {
            for (int p = FIRST_PORT; p <= LAST_PORT; p++) {
                final int port = p;
                futures.add(pool.submit(() -> isPortOpen(host, port) ? port : -1));
            }
            List<Integer> open = new ArrayList<>();
            for (java.util.concurrent.Future<Integer> f : futures) {
                try {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        f.cancel(true);
                        continue;
                    }
                    Integer p = f.get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (p > 0) open.add(p);
                } catch (Exception e) {
                    f.cancel(true);
                }
            }
            return open;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), SCAN_PORT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void recordSuccess(String host, int port, String transport,
                               Map<String, HostScanState> states) {
        TransportFactory factory = ConnectionStorage.TRANSPORT_TELNET.equals(transport)
                ? () -> TelnetTransport.connect(host, port)
                : () -> AdbTransport.connect(appContext, host, port);

        boolean becameActive = (activeFactory == null);
        if (becameActive) {
            synchronized (this) {
                if (activeFactory == null) {
                    activeFactory = factory;
                } else {
                    becameActive = false;
                }
            }
        }

        HostScanState state = states.get(host);
        if (state == null) return;
        synchronized (state) {
            // Don't downgrade an existing active marker.
            PortResult existing = state.ports.get(port);
            boolean isActive = becameActive || (existing != null && existing.isActive);
            state.ports.put(port, new PortResult(port, transport, isActive));
        }
    }

    private static void publish(StatusCallback callback,
                                Map<String, HostScanState> states, boolean finished) {
        List<HostScanResult> snapshot = new ArrayList<>(states.size());
        for (HostScanState s : states.values()) {
            snapshot.add(s.snapshot());
        }
        snapshot.sort((a, b) -> a.host.compareTo(b.host));
        callback.onUpdate(snapshot, finished);
    }

    private void persistResults(Map<String, HostScanState> states) {
        List<ConnectionStorage.Endpoint> endpoints = new ArrayList<>();
        for (HostScanState s : states.values()) {
            // Lock per host even though all probe threads are joined by now:
            // matches the access pattern used elsewhere and is robust if scanning logic
            // ever grows a late-arriving update.
            synchronized (s) {
                for (PortResult p : s.ports.values()) {
                    if (p.hasSupportedTransport()) {
                        endpoints.add(new ConnectionStorage.Endpoint(s.host, p.port, p.transport));
                    }
                }
            }
        }
        connectionStorage.saveAll(endpoints);
    }

    private static List<String> candidateHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add("127.0.0.1");
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                while (ifaces.hasMoreElements()) {
                    NetworkInterface ni = ifaces.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (a instanceof Inet4Address) {
                            hosts.add(a.getHostAddress());
                        }
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
