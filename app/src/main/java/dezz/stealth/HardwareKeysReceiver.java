package dezz.stealth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class HardwareKeysReceiver extends BroadcastReceiver {
    private static final String TAG = "HardwareKeysReceiver";

    private long downPressed = 0;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null) {
            return;
        }

        Log.d(TAG, "Received key event: " + intent.getAction());

        switch (intent.getAction()) {
            case "ecarx.intent.action.ECARX_KEY_ISRC_EVENT_DOWN":
            case "ecarx.intent.action.ECARX_KEY_RSRC_EVENT_DOWN":
                downPressed = System.currentTimeMillis();
                break;

            case "ecarx.intent.action.ECARX_KEY_ISRC_EVENT_UP":
            case "ecarx.intent.action.ECARX_KEY_RSRC_EVENT_UP":
                if (downPressed == 0) {
                    return;
                }
                long elapsed = System.currentTimeMillis() - downPressed;

                if (elapsed > 10000) {
                    Log.d(TAG, "Key pressed for " + elapsed + "ms");

                    Intent mainActivityIntent = new Intent(context, MainActivity.class);
                    mainActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(mainActivityIntent);
                }

                downPressed = 0;

                break;
        }
    }
}
