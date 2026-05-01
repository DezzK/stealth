package dezz.stealth;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the runtime-permission flow for the dialer-PIN reveal feature.
 * <p>
 * The receiver requires {@code PROCESS_OUTGOING_CALLS} and {@code READ_CALL_LOG};
 * without them, hiding apps would lock the user out (no way to reveal the icon
 * via dialer). This class:
 * <ul>
 *     <li>asks for permissions once on app launch,</li>
 *     <li>shows a contextual dialog when the user tries to hide without grants,</li>
 *     <li>routes to system settings when the user previously chose "don't ask again".</li>
 * </ul>
 */
final class PermissionGate {
    static final int REQUEST_CODE = 1001;

    private static final String[] REQUIRED = {
            Manifest.permission.PROCESS_OUTGOING_CALLS,
            Manifest.permission.READ_CALL_LOG
    };

    private final Activity activity;

    PermissionGate(Activity activity) {
        this.activity = activity;
    }

    /** True if all required permissions are currently granted. */
    boolean hasAll() {
        for (String perm : REQUIRED) {
            if (ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Request any missing permissions via the system dialog. Caller must forward
     * {@link Activity#onRequestPermissionsResult} to {@link #handleResult}.
     */
    void requestMissing() {
        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED) {
            if (ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toArray(new String[0]), REQUEST_CODE);
        }
    }

    /**
     * Forward from {@code onRequestPermissionsResult}. Shows an informational
     * dialog if the user denied any permission.
     */
    void handleResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_CODE) return;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.permissions_required_title)
                        .setMessage(R.string.permissions_required_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
        }
    }

    /**
     * Show a dialog explaining why permissions are needed before hiding apps.
     * If the user previously selected "don't ask again", routes to app settings.
     */
    void showRequiredToHide() {
        if (canAskAgain()) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.permissions_required_title)
                    .setMessage(R.string.permissions_required_to_hide_message)
                    .setPositiveButton(R.string.grant_permissions, (dialog, which) -> requestMissing())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.permissions_required_title)
                    .setMessage(R.string.permissions_open_settings_message)
                    .setPositiveButton(R.string.open_settings, (dialog, which) -> openAppSettings())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    /** True if at least one missing permission can still be re-requested via the system dialog. */
    private boolean canAskAgain() {
        for (String perm : REQUIRED) {
            if (ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)) {
                return true;
            }
        }
        return false;
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
        activity.startActivity(intent);
    }
}
