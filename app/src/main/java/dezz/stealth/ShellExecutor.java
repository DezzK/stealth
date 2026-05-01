package dezz.stealth;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Discovers a working {@link ShellTransport} (Telnet or ADB) and runs pm commands through it.
 * Transport-agnostic: each transport encapsulates its own protocol details.
 */
public class ShellExecutor {
    private static final String TAG = "ShellExecutor";

    // Telnet on the head unit itself — used by devices with a dynamic ADB port (e.g. Geely Cityray).
    // Only loopback is meaningful here: stock Android can't resolve .local (mDNS) addresses anyway.
    private static final String TELNET_HOST = "127.0.0.1";
    private static final int TELNET_PORT = 23;

    // ADB candidates (fallback)
    private static final int[] ADB_PORTS = {5555, 7777};

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
     * One probed endpoint and the error from probing it. Carried in the callback so the
     * UI layer can format/localize the message rather than parsing a raw concatenated string.
     */
    public static class ConnectionAttempt {
        public final String label;     // e.g. "Telnet 127.0.0.1:23" or "ADB 127.0.0.1:5555"
        public final String rawError;  // raw exception/probe message, may be null

        ConnectionAttempt(String label, String rawError) {
            this.label = label;
            this.rawError = rawError;
        }
    }

    public interface StatusCallback {
        void onSuccess(String description);
        void onError(List<ConnectionAttempt> attempts);
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

    private final Executor executor = Executors.newSingleThreadExecutor();
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
            List<ConnectionAttempt> attempts = new ArrayList<>();

            // 1. Try Telnet first (verify pm actually works via "pm path android")
            {
                TransportFactory factory = () -> TelnetTransport.connect(TELNET_HOST, TELNET_PORT);
                String description = tryTransport(
                        factory,
                        t -> t.exec("pm path android").contains("package:"),
                        attempts,
                        "Telnet " + TELNET_HOST + ":" + TELNET_PORT);
                if (description != null) {
                    activeFactory = factory;
                    callback.onSuccess(description);
                    return;
                }
            }

            // 2. Fall back to ADB
            for (int port : ADB_PORTS) {
                TransportFactory factory = () -> AdbTransport.connect(appContext, port);
                String description = tryTransport(
                        factory,
                        t -> { t.exec("echo ok"); return true; },
                        attempts,
                        "ADB 127.0.0.1:" + port);
                if (description != null) {
                    activeFactory = factory;
                    callback.onSuccess(description);
                    return;
                }
            }

            callback.onError(attempts);
        });
    }

    /**
     * Try to open and probe a transport. Returns describe() on success, null on failure
     * (a {@link ConnectionAttempt} describing the failure is appended to {@code attempts}).
     */
    private String tryTransport(TransportFactory factory, ProbeFn probe,
                                List<ConnectionAttempt> attempts, String label) {
        ShellTransport transport = null;
        try {
            transport = factory.open();
            if (probe.test(transport)) {
                return transport.describe();
            }
            attempts.add(new ConnectionAttempt(label, "pm not available"));
        } catch (Exception e) {
            Log.d(TAG, label + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            attempts.add(new ConnectionAttempt(label, classifyFailure(e)));
        } finally {
            if (transport != null) transport.close();
        }
        return null;
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
