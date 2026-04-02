package dezz.stealth;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class PinStorage {
    private static final String KEY_PIN_HASH = "pin_hash";
    public static final String DEFAULT_PIN = "123456";
    private static final int MIN_PIN_LENGTH = 3;

    public static final int INVALID = 0;
    public static final int TOO_SHORT = 1;
    public static final int STARTS_WITH_ZERO = 2;
    public static final int OK = 3;

    private final SharedPreferences prefs;

    public PinStorage(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        this.prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_pin", Context.MODE_PRIVATE);
    }

    public int validate(String pin) {
        if (pin == null || pin.isEmpty()) return INVALID;
        if (pin.length() < MIN_PIN_LENGTH) return TOO_SHORT;
        if (pin.charAt(0) == '0') return STARTS_WITH_ZERO;
        return OK;
    }

    public boolean save(String pin) {
        if (validate(pin) != OK) {
            return false;
        }
        prefs.edit().putString(KEY_PIN_HASH, hash(pin)).commit();
        return true;
    }

    public boolean verify(String input) {
        if (input == null) {
            return false;
        }

        // If no custom PIN is set, accept the default PIN
        String storedHash = hasPin()
                ? prefs.getString(KEY_PIN_HASH, null)
                : hash(DEFAULT_PIN);

        return storedHash != null && storedHash.equals(hash(input));
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
