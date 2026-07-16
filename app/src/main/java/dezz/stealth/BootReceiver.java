/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.stealth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts {@link KeepAliveService} after a reboot when apps are currently hidden, so the
 * process stays out of the stopped state without the user having to open the (icon-less)
 * app first.
 * <p>
 * Reads the hidden-apps flag via {@link AppsToHideStorage}, which uses device-protected
 * storage, so it works at {@code LOCKED_BOOT_COMPLETED} before the user unlocks (Direct
 * Boot).
 * <p>
 * <b>Caveat:</b> a manifest receiver only fires on boot if the package is not already in
 * the stopped state at boot time. If the OEM force-stopped the app before the reboot this
 * will not run — that gap is inherent to the {@code FLAG_STOPPED} behaviour we are
 * mitigating, not a bug here.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        if (new AppsToHideStorage(context).hasHiddenApps()) {
            KeepAliveService.start(context);
        }
    }
}
