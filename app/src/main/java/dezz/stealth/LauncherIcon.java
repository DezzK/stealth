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

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Owns the launcher icon's visibility.
 * <p>
 * The icon belongs to {@link LauncherTrampolineActivity}, never to {@link MainActivity}:
 * changing the enabled state of the component backing a running task makes the system
 * restart or kill that task even with {@code DONT_KILL_APP}, so MainActivity would silently
 * exit right after it opens. The trampoline never has a live instance, so its state can
 * flip freely.
 * <p>
 * Only the app itself can bring a hidden icon back: adb shell may enable or disable whole
 * packages, but not a single component ("Shell cannot change component state").
 */
final class LauncherIcon {
    private LauncherIcon() {}

    static void show(Context context) {
        setShown(context, true);
    }

    static void hide(Context context) {
        setShown(context, false);
    }

    /** Writes only on a real change, so opening the app normally touches nothing. */
    private static void setShown(Context context, boolean shown) {
        PackageManager pm = context.getPackageManager();
        ComponentName trampoline = new ComponentName(context, LauncherTrampolineActivity.class);
        if (EnabledSetting.isEnabled(pm.getComponentEnabledSetting(trampoline)) == shown) return;
        pm.setComponentEnabledSetting(trampoline,
                shown ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
