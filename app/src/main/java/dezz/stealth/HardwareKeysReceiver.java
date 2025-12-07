package dezz.stealth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class HardwareKeysReceiver extends BroadcastReceiver {
    private static final String TAG = "HardwareKeysReceiver";

    private static final int MAX_EVENT_INTERVAL = 500;
    private static final int MAX_EVENT_COUNT = 5;

    private static long lastEventTime = 0;
    private static int eventCount = 0;


    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null) {
            return;
        }

        Log.d(TAG, "Received key event: " + intent.getAction());

        switch (intent.getAction()) {
            case "ecarx.intent.action.ECARX_KEY_RSRC_EVENT":
            case "ecarx.intent.action.ECARX_KEY_ISRC_EVENT":
                long now = System.currentTimeMillis();
                if (now - lastEventTime > MAX_EVENT_INTERVAL) {
                    eventCount = 1;
                } else {
                    eventCount += 1;
                    if (eventCount >= MAX_EVENT_COUNT) {
                        Intent mainActivityIntent = new Intent(context, MainActivity.class);
                        mainActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(mainActivityIntent);
                    }
                }
                lastEventTime = now;
                break;
        }
    }
}
