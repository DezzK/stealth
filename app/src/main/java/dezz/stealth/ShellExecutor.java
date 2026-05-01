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

    // Telnet candidates (tried first — needed for devices with dynamic ADB port like Geely Cityray)
    private static final String[] TELNET_HOSTS = {"127.0.0.1", "android.local"};
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

    public interface StatusCallback {
        void onSuccess(String message);
        void onError(String error);
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

    // ── Connection check ──────────────────────────────────────────────

    public void checkConnection(StatusCallback callback) {
        executor.execute(() -> {
            List<String> errors = new ArrayList<>();

            // 1. Try Telnet first (verify pm actually works via "pm path android")
            for (String host : TELNET_HOSTS) {
                TransportFactory factory = () -> TelnetTransport.connect(host, TELNET_PORT);
                String description = tryTransport(
                        factory,
                        t -> t.exec("pm path android").contains("package:"),
                        errors,
                        "telnet " + host);
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
                        errors,
                        "adb port " + port);
                if (description != null) {
                    activeFactory = factory;
                    callback.onSuccess(description);
                    return;
                }
            }

            callback.onError(String.join("; ", errors));
        });
    }

    /**
     * Try to open and probe a transport. Returns describe() on success, null on failure
     * (errors are appended to the {@code errors} list).
     */
    private String tryTransport(TransportFactory factory, ProbeFn probe, List<String> errors, String label) {
        ShellTransport transport = null;
        try {
            transport = factory.open();
            if (probe.test(transport)) {
                return transport.describe();
            }
            errors.add(label + ": pm not available");
        } catch (Exception e) {
            String msg = e.getMessage();
            Log.d(TAG, label + " failed: " + msg);
            errors.add(label + ": " + (msg != null ? msg : "failed"));
        } finally {
            if (transport != null) transport.close();
        }
        return null;
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
