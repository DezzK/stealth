package dezz.stealth;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Set;

public class AppsToHideStorage {
    private SharedPreferences prefs;

    public AppsToHideStorage(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        this.prefs = deviceContext.getSharedPreferences(context.getPackageName() + "_apps_to_hide", Context.MODE_PRIVATE);
    }

    public void save(List<String> packageNames) {
        SharedPreferences.Editor editor = prefs.edit().clear();

        for (String app : packageNames) {
            editor.putBoolean(app, true);
        }

        editor.apply();
    }

    public Set<String> load() {
        return prefs.getAll().keySet();
    }
}
