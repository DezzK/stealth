package dezz.stealth;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

/**
 * Encapsulates the PIN input dialog UI. Validation rules and persistence live in
 * {@link PinStorage}; this class only handles the dialog flow and toasts.
 */
final class PinDialogs {
    private final Context context;
    private final PinStorage pinStorage;
    private final AppsToHideStorage appsToHideStorage;
    private final Runnable onPinChanged;

    PinDialogs(Context context, PinStorage pinStorage, AppsToHideStorage appsToHideStorage,
               Runnable onPinChanged) {
        this.context = context;
        this.pinStorage = pinStorage;
        this.appsToHideStorage = appsToHideStorage;
        this.onPinChanged = onPinChanged;
    }

    /**
     * "Set PIN" / "Change PIN" dialog triggered from the dedicated button.
     * Shows the warning variant if apps are currently hidden.
     */
    void showSetOrChange() {
        boolean changingPin = pinStorage.hasPin();
        @StringRes int title = changingPin ? R.string.change_pin_title : R.string.set_pin_title;
        @StringRes int message = (changingPin && appsToHideStorage.hasHiddenApps())
                ? R.string.pin_dialog_message_warn_hidden
                : R.string.pin_dialog_message;
        showInputDialog(title, message);
    }

    /**
     * Pre-flight dialog when the user tries to hide apps without a PIN set.
     */
    void showRequired() {
        showInputDialog(R.string.set_pin_title, R.string.pin_required_dialog_message);
    }

    private void showInputDialog(@StringRes int title, @StringRes int message) {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.pin_hint);
        input.setTextSize(24);

        FrameLayout container = new FrameLayout(context);
        int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);

        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String pin = input.getText().toString().trim();
                    int validation = pinStorage.validate(pin);
                    if (validation == PinStorage.OK && pinStorage.save(pin)) {
                        Toast.makeText(context, R.string.pin_set_successfully, Toast.LENGTH_SHORT).show();
                        onPinChanged.run();
                    } else if (validation == PinStorage.STARTS_WITH_ZERO) {
                        Toast.makeText(context, R.string.pin_no_leading_zero, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(context, R.string.pin_too_short, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
