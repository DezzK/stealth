package dezz.stealth;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class DialerCodeReceiver extends BroadcastReceiver {
    private static final String TAG = "DialerCodeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_NEW_OUTGOING_CALL.equals(intent.getAction())) {
            return;
        }

        String phoneNumber = getResultData();
        if (phoneNumber == null) {
            phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
        }

        if (phoneNumber == null) {
            return;
        }

        // Strip non-digit characters to get the raw PIN
        String digits = phoneNumber.replaceAll("[^0-9]", "");

        PinStorage pinStorage = new PinStorage(context);
        if (pinStorage.verify(digits)) {
            Log.d(TAG, "PIN matched — showing launcher icon");
            // Cancel the outgoing call
            setResultData(null);
            // MainActivity shows the icon itself when it opens; showing it here too means
            // the icon still comes back if the ROM blocks this background activity start.
            LauncherIcon.show(context);
            launchMainActivity(context);
        }
    }

    private void launchMainActivity(Context context) {
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(launchIntent);
        } catch (ActivityNotFoundException e) {
            // MainActivity is still disabled by a v0.3–v0.8 hide that survived the upgrade.
            // The trampoline's migration re-enables it and opens it.
            context.startActivity(new Intent(context, LauncherTrampolineActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
