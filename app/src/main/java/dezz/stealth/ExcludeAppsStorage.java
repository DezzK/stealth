package dezz.stealth;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Set;

/**
 * Stores a set of package names to keep visible (excluded from hiding).
 * Implemented as a SharedPreferences map where only keys matter — boolean
 * values are arbitrary placeholders and never read.
 */
public class ExcludeAppsStorage {
    private final SharedPreferences prefs;

    public ExcludeAppsStorage(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        this.prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_exclude", Context.MODE_PRIVATE);
    }

    public void add(String packageName) {
        // The boolean value is a placeholder — only the key is read back via getAppsToKeep()
        prefs.edit().putBoolean(packageName, true).commit();
    }

    public void remove(String packageName) {
        prefs.edit().remove(packageName).commit();
    }

    public Set<String> getAppsToKeep() {
        return prefs.getAll().keySet();
    }
}
