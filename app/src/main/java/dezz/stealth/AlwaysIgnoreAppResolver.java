package dezz.stealth;

import android.content.pm.ApplicationInfo;

public final class AlwaysIgnoreAppResolver {
    public static boolean alwaysIgnoreApp(ApplicationInfo appInfo, String stealthAppPackageName) {
        return ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) ||
                appInfo.packageName.equals(stealthAppPackageName) ||
                appInfo.packageName.startsWith("com.ecarx.");
    }
}
