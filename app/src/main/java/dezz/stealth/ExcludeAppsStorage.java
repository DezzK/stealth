package dezz.stealth;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Set;

public class ExcludeAppsStorage {
    private SharedPreferences prefs;

    public ExcludeAppsStorage(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        this.prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_exclude", Context.MODE_PRIVATE);
    }

    public void add(String packageName) {
        prefs.edit().putBoolean(packageName, true).commit();
    }

    public void remove(String packageName) {
        prefs.edit().remove(packageName).commit();
    }

    public Set<String> getAppsToKeep() {
        return prefs.getAll().keySet();
    }
}
