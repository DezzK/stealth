package dezz.stealth;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

public class HardwareKeysReceiver extends BroadcastReceiver {
    private static final String TAG = "HardwareKeysReceiver";

    private static final int MAX_EVENT_INTERVAL = 1500;
    private static final int MAX_EVENT_COUNT = 5;

    private static long lastEventTime = 0;
    private static int eventCount = 0;


    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null) {
            return;
        }

        Log.d(TAG, "Received key event: " + intent.getAction() + ", lastEventTime: " + lastEventTime + ", count: " + eventCount);

        switch (intent.getAction()) {
            case "ecarx.intent.action.ECARX_KEY_RSRC_EVENT":
            case "ecarx.intent.action.ECARX_KEY_ISRC_EVENT":
                long now = System.currentTimeMillis();
                if (now - lastEventTime > MAX_EVENT_INTERVAL) {
                    eventCount = 1;
                } else {
                    eventCount += 1;
                    if (eventCount >= MAX_EVENT_COUNT) {
                        eventCount = 0;
                        showLauncherIcon(context);
                    }
                }
                lastEventTime = now;
                break;
        }
    }

    private void showLauncherIcon(Context context) {
        PackageManager p = context.getPackageManager();
        ComponentName componentName = new ComponentName(context, MainActivity.class);
        p.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
    }
}
