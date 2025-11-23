package dezz.stealth;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AlwaysIgnoreAppResolver {
    private static final Set<String> SYSTEM_APP_LIST = new HashSet<>(List.of(
            "android",
            "org.chromium.webview_shell",
            "com.example.drtest",
            "com.example.storagetest",
            "com.example.ui.PASTestApp",
            "log.debugtools.ecarx.logmanager",
            "com.qti.snapdragon.qdcm_ff",
            "net.easyconn",
            "com.qti.diagservices"
    ));

    public static boolean alwaysIgnoreApp(String packageName, String stealthAppPackageName) {
        return packageName.equals(stealthAppPackageName) ||
                packageName.startsWith("android.") ||
                packageName.startsWith("com.android.") ||
                packageName.startsWith("com.google.android.") ||
                packageName.startsWith("ecarx.") ||
                packageName.startsWith("com.ecarx.") ||
                packageName.startsWith("com.qualcomm.") ||
                packageName.startsWith("com.ts.") ||
                SYSTEM_APP_LIST.contains(packageName);
    }
}
