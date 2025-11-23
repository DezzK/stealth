package dezz.stealth;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AlwaysIgnoreAppResolver {
    private static final Set<String> SYSTEM_APP_LIST = new HashSet<>(List.of(
            "android",
            "org.chromium.webview_shell"
    ));

    public static boolean alwaysIgnoreApp(String packageName, String stealthAppPackageName) {
        return packageName.equals(stealthAppPackageName) ||
                packageName.startsWith("com.android.") ||
                packageName.startsWith("com.google.android.") ||
                SYSTEM_APP_LIST.contains(packageName);
    }
}
