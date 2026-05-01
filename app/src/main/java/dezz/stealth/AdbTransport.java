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

/**
 * Shell transport over the ADB protocol (adblib).
 * <p>
 * Encapsulates all ADB-specific concerns: the RSA key pair (lazily generated
 * once and cached for the app's lifetime), Base64 encoder, and the handshake.
 */
public class AdbTransport implements ShellTransport {
    private static final String TAG = "AdbTransport";
    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final String KEY_FILE_PREFIX = "adb_key";

    private static final AdbBase64 ADB_BASE64 = data -> Base64.encodeToString(data, Base64.DEFAULT);

    /** Shared key pair — generated once, reused across all AdbTransport instances. */
    private static volatile AdbCrypto sharedCrypto;

    private final Socket socket;
    private final AdbConnection connection;
    private final String host;
    private final int port;

    private AdbTransport(Socket socket, AdbConnection connection, String host, int port) {
        this.socket = socket;
        this.connection = connection;
        this.host = host;
        this.port = port;
    }

    /**
     * Connect to ADB on the given host and port and perform the ADB handshake.
     * The RSA key pair is loaded or generated on first call and cached.
     */
    public static AdbTransport connect(Context context, String host, int port) throws Exception {
        AdbCrypto crypto = getOrCreateCrypto(context);

        Socket socket = new Socket();
        AdbConnection connection = null;
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            connection = AdbConnection.create(socket, crypto);
            connection.connect();
            return new AdbTransport(socket, connection, host, port);
        } catch (Exception e) {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) {}
            }
            try { socket.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public String describe() {
        return "ADB " + host + ":" + port;
    }

    @Override
    public String exec(String command) throws Exception {
        AdbStream stream = connection.open("shell:" + command);
        byte[] response = stream.read();
        return new String(response, StandardCharsets.UTF_8).trim();
    }

    @Override
    public void close() {
        try { connection.close(); } catch (Exception ignored) {}
        try { socket.close(); } catch (Exception ignored) {}
    }

    // ── Key pair management ───────────────────────────────────────────

    private static AdbCrypto getOrCreateCrypto(Context context) {
        AdbCrypto local = sharedCrypto;
        if (local == null) {
            synchronized (AdbTransport.class) {
                local = sharedCrypto;
                if (local == null) {
                    local = loadOrGenerateKeyPair(context.getApplicationContext());
                    sharedCrypto = local;
                }
            }
        }
        return local;
    }

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
}
