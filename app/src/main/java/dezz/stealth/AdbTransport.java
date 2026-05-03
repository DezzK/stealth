package dezz.stealth;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Shell transport over the ADB protocol (adblib).
 * <p>
 * Encapsulates all ADB-specific concerns: the RSA key pair (lazily generated
 * once and cached for the app's lifetime), Base64 encoder, and the handshake.
 */
public class AdbTransport implements ShellTransport {
    private static final String TAG = "AdbTransport";
    private static final int CONNECT_TIMEOUT_MS = 1000;
    /**
     * Bound on the ADB handshake (CNXN/AUTH exchange). Without this, adblib waits
     * {@code Long.MAX_VALUE} — a port that accepts TCP but doesn't speak ADB
     * (Telnet banner, HTTP, etc.) would otherwise hang the probe thread forever.
     * Generous to accommodate slow car head units that may take a few seconds to
     * complete authentication with a previously-authorized key.
     */
    private static final int HANDSHAKE_TIMEOUT_MS = 10000;
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
            // Bounded handshake: if the peer doesn't speak ADB, this returns false instead
            // of blocking forever in adblib's read loop. The connection thread keeps reading
            // garbage but our finally below closes the socket which kicks it out.
            if (!connection.connect(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS, false)) {
                throw new IOException("ADB handshake timeout");
            }
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
        return "ADB " + ShellExecutor.formatHostPort(host, port);
    }

    // ── Safe probe (no adblib) ────────────────────────────────────────

    /** ADB protocol command codes (little-endian on the wire). */
    private static final int ADB_CMD_CNXN = 0x4e584e43; // "CNXN"
    private static final int ADB_CMD_AUTH = 0x48545541; // "AUTH"
    private static final int ADB_HEADER_LEN = 24;
    private static final int PROBE_READ_TIMEOUT_MS = 2000;

    /**
     * Manual ADB handshake check that does NOT use adblib. Used during port discovery
     * where the peer's identity is unknown.
     * <p>
     * Why not adblib? Its {@code parseAdbMessage} blindly does
     * {@code new byte[header.payloadLength]} on whatever the peer sends. A non-ADB
     * peer (Telnet banner, HTTP, random TCP service) makes the parser interpret
     * garbage as a huge {@code payloadLength} and the {@link OutOfMemoryError} kills
     * the entire process — adblib's connection thread catches {@code Exception}, not
     * {@code Throwable}, so the OOM propagates uncaught.
     * <p>
     * This implementation reads exactly {@value #ADB_HEADER_LEN} bytes and checks the
     * ADB header invariant {@code magic == command ^ 0xFFFFFFFF}. No allocation
     * depends on peer-supplied lengths, so a hostile/garbage peer can't OOM us.
     *
     * @return true iff the peer responded with a valid ADB CNXN or AUTH header.
     */
    public static boolean probe(String host, int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(PROBE_READ_TIMEOUT_MS);

            // Build a minimal CNXN packet with a benign banner. adbd will respond
            // with AUTH (signature challenge) or CNXN (already authorized).
            byte[] banner = "host::\0".getBytes(StandardCharsets.US_ASCII);
            ByteBuffer hdr = ByteBuffer.allocate(ADB_HEADER_LEN + banner.length)
                    .order(ByteOrder.LITTLE_ENDIAN);
            hdr.putInt(ADB_CMD_CNXN);
            hdr.putInt(0x01000000);                 // version
            hdr.putInt(4096);                        // maxData
            hdr.putInt(banner.length);
            int checksum = 0;
            for (byte b : banner) checksum += b & 0xFF;
            hdr.putInt(checksum);
            hdr.putInt(ADB_CMD_CNXN ^ 0xFFFFFFFF);   // magic
            hdr.put(banner);

            OutputStream out = socket.getOutputStream();
            out.write(hdr.array());
            out.flush();

            // Read exactly 24 bytes — no peer-driven allocation.
            byte[] resp = new byte[ADB_HEADER_LEN];
            InputStream in = socket.getInputStream();
            int total = 0;
            while (total < ADB_HEADER_LEN) {
                int n = in.read(resp, total, ADB_HEADER_LEN - total);
                if (n < 0) return false;
                total += n;
            }

            ByteBuffer rb = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN);
            int respCommand = rb.getInt();
            rb.position(20);
            int respMagic = rb.getInt();

            // ADB invariant: magic is the bitwise complement of command.
            if (respMagic != (respCommand ^ 0xFFFFFFFF)) return false;
            return respCommand == ADB_CMD_AUTH || respCommand == ADB_CMD_CNXN;
        } catch (Exception e) {
            return false;
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
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
