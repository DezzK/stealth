package dezz.stealth;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class AppsToHideStorage {
    private SharedPreferences prefs;

    public AppsToHideStorage(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        this.prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_apps_to_hide", Context.MODE_PRIVATE);
    }

    /**
     * Merges newly hidden apps into existing storage (does NOT replace).
     */
    public void save(Map<String, String> packageToName) {
        SharedPreferences.Editor editor = prefs.edit();

        for (Map.Entry<String, String> entry : packageToName.entrySet()) {
            editor.putString(entry.getKey(), entry.getValue());
        }

        editor.commit();
    }

    public Map<String, String> load() {
        Map<String, String> result = new HashMap<>();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                result.put(entry.getKey(), (String) value);
            } else {
                // Backward compatibility: old boolean entries get package name as display name
                result.put(entry.getKey(), entry.getKey());
            }
        }
        return result;
    }

    public void removeAll(Collection<String> packageNames) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String packageName : packageNames) {
            editor.remove(packageName);
        }
        editor.commit();
    }

    public boolean hasHiddenApps() {
        return !prefs.getAll().isEmpty();
    }
}
