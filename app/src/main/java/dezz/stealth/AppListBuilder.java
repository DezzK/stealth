package dezz.stealth;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import androidx.core.content.res.ResourcesCompat;

import java.util.ArrayList;
import java.util.Comparator;
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

        result.sort(Comparator.comparing(a -> a.getAppName().toLowerCase()));
        return result;
    }

    /**
     * Apps offered on the Restore tab.
     * <ul>
     *   <li>If we have a record of what we hid, exactly those apps, pre-checked.</li>
     *   <li>If we have no record (app reinstalled or its data cleared while apps were hidden,
     *       or the process died mid-hide before saving), every disabled app we could have
     *       hidden — all <b>unchecked</b>, so the user picks what to bring back.</li>
     * </ul>
     * Without a record we can't tell our apps from ones the firmware or dealer disabled on
     * purpose, so nothing untracked is ever pre-checked or saved: one tap on Restore once
     * re-enabled a dealer-disabled package that then nagged the driver every few seconds.
     * <p>
     * As a side effect, drops storage entries for apps that were re-enabled or uninstalled.
     */
    static List<AppInfo> restorableApps(Context context, AppsToHideStorage storage) {
        List<AppInfo> result = trackedHiddenApps(context, storage);
        if (result.isEmpty()) {
            result = untrackedDisabledApps(context);
        }
        result.sort(Comparator.comparing(a -> a.getAppName().toLowerCase()));
        return result;
    }

    /** Apps recorded in storage as hidden by us and still disabled, pre-checked. */
    private static List<AppInfo> trackedHiddenApps(Context context, AppsToHideStorage storage) {
        PackageManager pm = context.getPackageManager();
        Map<String, String> tracked = storage.load();
        List<AppInfo> result = new ArrayList<>();
        List<String> toRemoveFromStorage = new ArrayList<>();

        // Hoisted out of the loop — same fallback drawable for every uninstalled package
        Drawable fallbackIcon = ResourcesCompat.getDrawable(
                context.getResources(), android.R.drawable.sym_def_app_icon, context.getTheme());

        for (Map.Entry<String, String> entry : tracked.entrySet()) {
            String packageName = entry.getKey();

            if (isAppEnabled(pm, packageName)) {
                toRemoveFromStorage.add(packageName);
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

            result.add(new AppInfo(packageName, appName, icon, true));
        }

        if (!toRemoveFromStorage.isEmpty()) {
            storage.removeAll(toRemoveFromStorage);
        }
        return result;
    }

    /**
     * Every disabled app the Hide tab could have offered (same {@link AlwaysIgnoreAppResolver}
     * filter), unchecked — we don't know who disabled them.
     */
    private static List<AppInfo> untrackedDisabledApps(Context context) {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(
                PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS);
        String currentPackageName = context.getPackageName();

        List<AppInfo> result = new ArrayList<>();
        for (ApplicationInfo appInfo : packages) {
            if (AlwaysIgnoreAppResolver.alwaysIgnoreApp(appInfo, currentPackageName)) continue;
            if (isAppEnabled(pm, appInfo.packageName)) continue;
            result.add(new AppInfo(
                    appInfo.packageName,
                    appInfo.loadLabel(pm).toString(),
                    appInfo.loadIcon(pm),
                    false));
        }
        return result;
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
