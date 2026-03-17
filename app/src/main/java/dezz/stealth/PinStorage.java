package dezz.stealth;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class PinStorage {
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_FAILED_ATTEMPTS = "failed_attempts";
    private static final String KEY_LOCKOUT_UNTIL = "lockout_until";
    private static final int MIN_PIN_LENGTH = 3;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 5 * 60 * 1000; // 5 minutes

    private final SharedPreferences prefs;

    public PinStorage(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        this.prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_pin", Context.MODE_PRIVATE);
    }

    public boolean save(String pin) {
        if (pin == null || pin.length() < MIN_PIN_LENGTH) {
            return false;
        }
        prefs.edit()
                .putString(KEY_PIN_HASH, hash(pin))
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .remove(KEY_LOCKOUT_UNTIL)
                .commit();
        return true;
    }

    public boolean verify(String input) {
        if (input == null || !hasPin()) {
            return false;
        }

        // Check lockout
        long lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0);
        if (System.currentTimeMillis() < lockoutUntil) {
            return false;
        }

        String storedHash = prefs.getString(KEY_PIN_HASH, null);
        if (storedHash != null && storedHash.equals(hash(input))) {
            // Reset failed attempts on success
            prefs.edit()
                    .putInt(KEY_FAILED_ATTEMPTS, 0)
                    .remove(KEY_LOCKOUT_UNTIL)
                    .commit();
            return true;
        }

        // Record failed attempt
        int failed = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1;
        SharedPreferences.Editor editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, failed);
        if (failed >= MAX_FAILED_ATTEMPTS) {
            editor.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_DURATION_MS);
            editor.putInt(KEY_FAILED_ATTEMPTS, 0);
        }
        editor.commit();
        return false;
    }

    public boolean hasPin() {
        return prefs.contains(KEY_PIN_HASH);
    }

    private static String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
