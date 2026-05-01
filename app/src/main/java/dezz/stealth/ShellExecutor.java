package dezz.stealth;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Discovers a working {@link ShellTransport} (Telnet or ADB) and runs pm commands through it.
 * Transport-agnostic: each transport encapsulates its own protocol details.
 * <p>
 * Discovery probes Telnet (port 23) and ADB (5555/7777) on every up IPv4 interface
 * (loopback + each external interface) in parallel. The status callback is invoked
 * incrementally as probes finish, so the UI can:
 * <ul>
 *     <li>flip the connection indicator the moment the first probe succeeds, while</li>
 *     <li>still build up the full diagnostic list for the details dialog (which can
 *         live-update if it's already open).</li>
 * </ul>
 */
public class ShellExecutor {
    private static final String TAG = "ShellExecutor";

    private static final int TELNET_PORT = 23;
    private static final int[] ADB_PORTS = {5555, 7777};

    /** Soft cap on the probe pool — devices with many virtual interfaces won't blow up. */
    private static final int MAX_PARALLEL_PROBES = 16;

    private static volatile ShellExecutor instance;

    /** Factory for recreating the chosen transport on each batch. Null until discovered. */
    private volatile TransportFactory activeFactory = null;

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

    /**
     * One probed endpoint and the outcome of probing it.
     * <p>
     * {@code rawError == null} marks a successful probe. Among multiple successes, exactly
     * one will have {@code isActive == true} — that's the transport actually in use for
     * batch operations. The others "would have worked too" but lost the CAS race.
     */
    public static class ConnectionAttempt {
        public final String label;     // e.g. "Telnet 127.0.0.1:23" or "ADB 127.0.0.1:5555"
        public final String rawError;  // null = success; non-null = raw failure message
        public final boolean isActive; // true only for the transport currently in use

        ConnectionAttempt(String label, String rawError, boolean isActive) {
            this.label = label;
            this.rawError = rawError;
            this.isActive = isActive;
        }

        public boolean isSuccess() { return rawError == null; }
    }

    /**
     * Receives incremental discovery results.
     */
    public interface StatusCallback {
        /**
         * Fires every time a probe completes (and once at the end with {@code finished == true}).
         *
         * @param attempts immutable snapshot, sorted by label, of every probe that has
         *                 completed so far. May contain at most one success entry — if
         *                 present, it is the active transport.
         * @param finished true on the final call: every probe has completed.
         */
        void onUpdate(List<ConnectionAttempt> attempts, boolean finished);
    }

    public interface BatchCallback {
        void onResult(BatchResult result);
        void onConnectionError(String error);
    }

    @FunctionalInterface
    private interface TransportFactory {
        ShellTransport open() throws Exception;
    }

    @FunctionalInterface
    private interface ProbeFn {
        boolean test(ShellTransport t) throws Exception;
    }

    /** A single (label, factory, probe) tuple representing one endpoint to try. */
    private static class Candidate {
        final String label;
        final TransportFactory factory;
        final ProbeFn probe;

        Candidate(String label, TransportFactory factory, ProbeFn probe) {
            this.label = label;
            this.factory = factory;
            this.probe = probe;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Context appContext;

    private ShellExecutor(Context appContext) {
        this.appContext = appContext;
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
        executor.execute(() -> {
            List<Candidate> candidates = buildCandidates();
            if (candidates.isEmpty()) {
                callback.onUpdate(Collections.emptyList(), true);
                return;
            }
            startProbing(candidates, callback);
        });
    }

    /**
     * Build the full candidate set: every (host × transport × port) combo.
     * Hosts include loopback plus every IPv4 address bound to an "up" non-loopback interface.
     */
    private List<Candidate> buildCandidates() {
        List<String> hosts = candidateHosts();
        List<Candidate> result = new ArrayList<>();
        for (String host : hosts) {
            // Telnet on port 23
            result.add(new Candidate(
                    "Telnet " + host + ":" + TELNET_PORT,
                    () -> TelnetTransport.connect(host, TELNET_PORT),
                    t -> t.exec("pm path android").contains("package:")));
            // ADB on each known port
            for (int port : ADB_PORTS) {
                result.add(new Candidate(
                        "ADB " + host + ":" + port,
                        () -> AdbTransport.connect(appContext, host, port),
                        t -> { t.exec("echo ok"); return true; }));
            }
        }
        return result;
    }

    /**
     * IPv4 addresses to probe: loopback first, then every IPv4 bound to an "up" interface.
     * Some daemons bind only to a specific external IP rather than 0.0.0.0, so we have to try them.
     */
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

    /**
     * Submit every candidate to a probe pool and run them all to completion. The status
     * callback fires after each probe (sorted snapshot of all completed probes so far);
     * {@code activeFactory} is set as soon as the first probe succeeds.
     * <p>
     * This is fire-and-forget: the calling thread (the single executor) returns
     * immediately after submission so subsequent disable/restore operations don't queue
     * behind a long discovery.
     */
    private void startProbing(List<Candidate> candidates, StatusCallback callback) {
        int total = candidates.size();
        int poolSize = Math.min(total, MAX_PARALLEL_PROBES);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);

        AtomicReferenceArray<ConnectionAttempt> slots = new AtomicReferenceArray<>(total);
        AtomicInteger remaining = new AtomicInteger(total);
        AtomicReference<TransportFactory> winnerFactory = new AtomicReference<>();

        for (int i = 0; i < total; i++) {
            final int idx = i;
            final Candidate c = candidates.get(i);
            pool.submit(() -> {
                ShellTransport transport = null;
                try {
                    transport = c.factory.open();
                    if (c.probe.test(transport)) {
                        // First success captures the active factory; later successes are
                        // recorded as "also works" entries (isActive=false) and don't
                        // override the chosen one.
                        boolean isActive = winnerFactory.compareAndSet(null, c.factory);
                        if (isActive) {
                            activeFactory = c.factory;
                        }
                        slots.set(idx, new ConnectionAttempt(c.label, null, isActive));
                    } else {
                        slots.set(idx, new ConnectionAttempt(c.label, "pm not available", false));
                    }
                } catch (Exception e) {
                    Log.d(TAG, c.label + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    slots.set(idx, new ConnectionAttempt(c.label, classifyFailure(e), false));
                } finally {
                    if (transport != null) {
                        try { transport.close(); } catch (Exception ignored) {}
                    }
                    boolean finished = remaining.decrementAndGet() == 0;
                    callback.onUpdate(snapshotSorted(slots), finished);
                    if (finished) {
                        pool.shutdown();
                    }
                }
            });
        }
    }

    /**
     * Snapshot the slots array, drop unfilled slots, and sort by label so the UI sees
     * a stable order regardless of probe completion order.
     */
    private static List<ConnectionAttempt> snapshotSorted(AtomicReferenceArray<ConnectionAttempt> slots) {
        List<ConnectionAttempt> out = new ArrayList<>(slots.length());
        for (int i = 0; i < slots.length(); i++) {
            ConnectionAttempt a = slots.get(i);
            if (a != null) out.add(a);
        }
        out.sort((x, y) -> x.label.compareTo(y.label));
        return out;
    }

    /**
     * Map a connection exception to a stable English keyword that the UI layer can
     * pattern-match for localization. Exception types are more reliable than message
     * text — e.g. {@link java.net.UnknownHostException} on Android often carries just
     * the hostname as its message.
     */
    private static String classifyFailure(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "Unknown host";
        if (e instanceof java.net.SocketTimeoutException) return "Connection timeout";
        if (e instanceof java.net.ConnectException) return "Connection refused";
        if (e instanceof java.io.IOException) {
            String msg = e.getMessage();
            return msg != null ? msg : "I/O error";
        }
        String msg = e.getMessage();
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    // ── Batch commands ────────────────────────────────────────────────

    public void disableApps(List<String> packageNames, BatchCallback callback) {
        executor.execute(() -> runBatch(packageNames, true, callback));
    }

    public void enableApps(Set<String> packageNames, BatchCallback callback) {
        executor.execute(() -> runBatch(new ArrayList<>(packageNames), false, callback));
    }

    private void runBatch(List<String> packageNames, boolean disable, BatchCallback callback) {
        if (activeFactory == null) {
            callback.onConnectionError("No connection detected");
            return;
        }

        ShellTransport transport = null;
        try {
            transport = activeFactory.open();

            Set<String> succeeded = new LinkedHashSet<>();
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
                    // Connection died mid-batch — abort remaining packages with a
                    // single explanation rather than spamming the same error N times.
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
