package dezz.stealth;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import androidx.core.content.res.ResourcesCompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@link AppInfo} lists from {@link PackageManager}. All methods do
 * many IPC calls per N installed apps and must be invoked off the UI thread.
 */
final class AppListBuilder {
    private AppListBuilder() {}

    /**
     * Apps eligible for hiding: enabled, non-system, not the stealth app itself.
     * Items are pre-checked unless the user previously marked them as "keep visible".
     */
    static List<AppInfo> hidableApps(Context context, ExcludeAppsStorage excludeStorage) {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        String currentPackageName = context.getPackageName();
        Set<String> appsToKeep = excludeStorage.getAppsToKeep();

        List<AppInfo> result = new ArrayList<>();
        for (ApplicationInfo appInfo : packages) {
            if (AlwaysIgnoreAppResolver.alwaysIgnoreApp(appInfo, currentPackageName)) continue;
            if (!appInfo.enabled) continue;

            // Checked = "will be hidden". Apps in appsToKeep are unchecked.
            result.add(new AppInfo(
                    appInfo.packageName,
                    appInfo.loadLabel(pm).toString(),
                    appInfo.loadIcon(pm),
                    !appsToKeep.contains(appInfo.packageName)));
        }

        result.sort(Comparator.comparing(AppInfo::getAppName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * Apps currently hidden via storage (pre-checked) plus {@code orphans} — disabled apps
     * this app never recorded (unchecked, the user opts in explicitly). As a side effect,
     * cleans up storage entries for apps that have been re-enabled or uninstalled.
     */
    static List<AppInfo> hiddenApps(Context context, AppsToHideStorage storage,
                                    Map<String, String> orphans) {
        PackageManager pm = context.getPackageManager();
        Map<String, String> tracked = new HashMap<>(storage.load());
        Set<String> untracked = new java.util.HashSet<>();
        for (Map.Entry<String, String> o : orphans.entrySet()) {
            if (!tracked.containsKey(o.getKey())) {
                tracked.put(o.getKey(), o.getValue());
                untracked.add(o.getKey());
            }
        }
        List<AppInfo> result = new ArrayList<>();
        List<String> toRemoveFromStorage = new ArrayList<>();

        // Hoisted out of the loop — same fallback drawable for every uninstalled package
        Drawable fallbackIcon = ResourcesCompat.getDrawable(
                context.getResources(), android.R.drawable.sym_def_app_icon, context.getTheme());

        for (Map.Entry<String, String> entry : tracked.entrySet()) {
            String packageName = entry.getKey();

            if (isAppEnabled(pm, packageName)) {
                if (!untracked.contains(packageName)) toRemoveFromStorage.add(packageName);
                continue;
            }

            // Load real app name and icon even for disabled apps
            String appName = entry.getValue();
            Drawable icon = fallbackIcon;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
                appName = ai.loadLabel(pm).toString();
                icon = ai.loadIcon(pm);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Use stored name and default icon as fallback
            }

            // Orphans start unchecked: they may have been disabled on purpose by the
            // user or the vendor, so restoring them must be an explicit choice.
            result.add(new AppInfo(packageName, appName, icon, !untracked.contains(packageName)));
        }

        if (!toRemoveFromStorage.isEmpty()) {
            storage.removeAll(toRemoveFromStorage);
        }

        result.sort(Comparator.comparing(AppInfo::getAppName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * Detects third-party apps in the {@code DISABLED_USER} state that are not tracked in
     * storage (orphans). That is the exact state {@code pm disable-user} — our hide
     * command — leaves behind, so this recovers from lost storage (reinstall, cleared
     * data) after apps were hidden. Apps disabled via plain {@code pm disable} or
     * {@code DISABLED_UNTIL_USED} (vendor / store decisions) are deliberately ignored.
     * <p>
     * Orphans are never written to storage automatically: they are offered in the
     * restore list unchecked, and only become tracked if the user restores them.
     */
    static Map<String, String> findOrphanedApps(Context context, AppsToHideStorage storage) {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(
                PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS);
        String currentPackageName = context.getPackageName();
        Map<String, String> known = storage.load();

        Map<String, String> orphans = new HashMap<>();
        for (ApplicationInfo appInfo : packages) {
            if (AlwaysIgnoreAppResolver.alwaysIgnoreApp(appInfo, currentPackageName)) continue;
            if (known.containsKey(appInfo.packageName)) continue;
            if (isDisabledByUser(pm, appInfo.packageName)) {
                orphans.put(appInfo.packageName, appInfo.loadLabel(pm).toString());
            }
        }
        return orphans;
    }

    private static boolean isDisabledByUser(PackageManager pm, String packageName) {
        try {
            return pm.getApplicationEnabledSetting(packageName)
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isAppEnabled(PackageManager pm, String packageName) {
        try {
            int setting = pm.getApplicationEnabledSetting(packageName);
            return setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        } catch (IllegalArgumentException e) {
            // Package not found (uninstalled) — treat as not restorable, remove from storage
            return true;
        }
    }
}
