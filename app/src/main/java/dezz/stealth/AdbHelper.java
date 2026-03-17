package dezz.stealth;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AdbHelper {
    private static final String TAG = "AdbHelper";
    private static final int ADB_PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final String KEY_FILE_PREFIX = "adb_key";

    private static volatile AdbHelper instance;

    /**
     * Result of a batch ADB operation, supporting partial success.
     */
    public static class AdbResult {
        private final Set<String> succeededPackages;
        private final List<String> errors;

        AdbResult(Set<String> succeededPackages, List<String> errors) {
            this.succeededPackages = succeededPackages;
            this.errors = errors;
        }

        public Set<String> getSucceededPackages() {
            return succeededPackages;
        }

        public List<String> getErrors() {
            return errors;
        }

        public boolean isFullSuccess() {
            return errors.isEmpty();
        }

        public boolean hasAnySuccess() {
            return !succeededPackages.isEmpty();
        }
    }

    public interface AdbCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    /**
     * Extended callback that reports partial success (some commands succeeded, some failed).
     */
    public interface AdbBatchCallback {
        void onResult(AdbResult result);
        void onConnectionError(String error);
    }

    private final Executor executor = Executors.newSingleThreadExecutor();
    private final AdbCrypto crypto;

    private static final AdbBase64 ADB_BASE64 = new AdbBase64() {
        @Override
        public String encodeToString(byte[] data) {
            return Base64.encodeToString(data, Base64.DEFAULT);
        }
    };

    private AdbHelper(Context context) {
        this.crypto = loadOrGenerateKeyPair(context);
    }

    public static AdbHelper getInstance(Context context) {
        if (instance == null) {
            synchronized (AdbHelper.class) {
                if (instance == null) {
                    instance = new AdbHelper(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /**
     * Load a persisted RSA key pair, or generate and save a new one.
     */
    private static AdbCrypto loadOrGenerateKeyPair(Context context) {
        Context deviceContext = context.createDeviceProtectedStorageContext();
        File privateKey = new File(deviceContext.getFilesDir(), KEY_FILE_PREFIX);
        File publicKey = new File(deviceContext.getFilesDir(), KEY_FILE_PREFIX + ".pub");

        try {
            if (privateKey.exists() && publicKey.exists()) {
                return AdbCrypto.loadAdbKeyPair(ADB_BASE64, privateKey, publicKey);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load stored ADB key pair, regenerating", e);
        }

        try {
            AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(ADB_BASE64);
            crypto.saveAdbKeyPair(privateKey, publicKey);
            return crypto;
        } catch (Exception e) {
            throw new RuntimeException("Cannot generate ADB key pair", e);
        }
    }

    public void checkConnection(AdbCallback callback) {
        executor.execute(() -> {
            Socket socket = new Socket();
            AdbConnection connection = null;
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", ADB_PORT), CONNECT_TIMEOUT_MS);

                connection = AdbConnection.create(socket, crypto);
                connection.connect();

                // Run a simple command to verify the connection works
                AdbStream stream = connection.open("shell:echo ok");
                stream.read();

                callback.onSuccess("connected");
            } catch (Exception e) {
                callback.onError(e.getMessage());
            } finally {
                closeQuietly(connection, socket);
            }
        });
    }

    public void disableApps(List<String> packageNames, AdbBatchCallback callback) {
        executor.execute(() -> runBatchCommands(packageNames, true, callback));
    }

    public void enableApps(Set<String> packageNames, AdbBatchCallback callback) {
        executor.execute(() -> runBatchCommands(new ArrayList<>(packageNames), false, callback));
    }

    private void runBatchCommands(List<String> packageNames, boolean disable, AdbBatchCallback callback) {
        Socket socket = new Socket();
        AdbConnection connection = null;
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", ADB_PORT), CONNECT_TIMEOUT_MS);

            connection = AdbConnection.create(socket, crypto);
            connection.connect();

            Set<String> succeeded = new LinkedHashSet<>();
            List<String> errors = new ArrayList<>();

            for (String packageName : packageNames) {
                String command = disable
                        ? "pm disable-user --user 0 " + packageName
                        : "pm enable " + packageName;

                Log.d(TAG, ">> " + command);
                try {
                    AdbStream stream = connection.open("shell:" + command);
                    byte[] response = stream.read();
                    String responseText = new String(response, StandardCharsets.UTF_8).trim();
                    Log.d(TAG, "<< " + responseText);

                    // Check for error indicators in ADB response
                    String lower = responseText.toLowerCase(Locale.ROOT);
                    if (lower.contains("exception") || lower.contains("error")
                            || lower.contains("failure") || lower.contains("unknown package")) {
                        errors.add(packageName + ": " + responseText);
                    } else {
                        succeeded.add(packageName);
                    }
                } catch (Exception e) {
                    errors.add(packageName + ": " + e.getMessage());
                }
            }

            callback.onResult(new AdbResult(succeeded, errors));
        } catch (Exception e) {
            callback.onConnectionError(e.getMessage());
        } finally {
            closeQuietly(connection, socket);
        }
    }

    private static void closeQuietly(AdbConnection connection, Socket socket) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }
}
