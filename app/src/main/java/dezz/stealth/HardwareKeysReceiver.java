package dezz.stealth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class HardwareKeysReceiver extends BroadcastReceiver {
    private record StartMainActivityTask(Context context) implements Runnable {

        @Override
        public void run() {
            Intent mainActivityIntent = new Intent(context, MainActivity.class);
            mainActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(mainActivityIntent);
        }
    }

    private static final String TAG = "HardwareKeysReceiver";

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Runnable startMainActivityTask = null;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null) {
            return;
        }

        Log.d(TAG, "Received key event: " + intent.getAction());

        switch (intent.getAction()) {
            case "ecarx.intent.action.ECARX_KEY_ISRC_EVENT_DOWN":
            case "ecarx.intent.action.ECARX_KEY_RSRC_EVENT_DOWN":
                if (startMainActivityTask == null) {
                    startMainActivityTask = new StartMainActivityTask(context);
                }
                mainHandler.postDelayed(startMainActivityTask, 5000);
                break;

            case "ecarx.intent.action.ECARX_KEY_ISRC_EVENT_UP":
            case "ecarx.intent.action.ECARX_KEY_RSRC_EVENT_UP":
                mainHandler.removeCallbacks(startMainActivityTask);
                break;
        }
    }
}
