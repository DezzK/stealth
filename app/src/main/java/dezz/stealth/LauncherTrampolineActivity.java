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

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * Invisible launcher entry. Exists solely so we have a separate, long-lived component
 * to toggle for hiding/showing the launcher icon without touching {@link MainActivity}.
 * <p>
 * Why not toggle MainActivity directly? When the running activity's component has its
 * enabled state changed, the system tends to restart or finish that activity even with
 * DONT_KILL_APP. The trampoline never has a live foreground instance (it finishes as
 * soon as it hands off), so its enabled state can flip freely.
 */
public class LauncherTrampolineActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Migration safety net: legacy builds disabled MainActivity directly to hide
        // the icon. After upgrading, the trampoline is the launcher entry but the
        // legacy DISABLED state on MainActivity may still be in place — start would
        // fail silently. Reset MainActivity to default (enabled) before launching.
        try {
            PackageManager p = getPackageManager();
            ComponentName main = new ComponentName(this, MainActivity.class);
            int state = p.getComponentEnabledSetting(main);
            if (state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    && state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                p.setComponentEnabledSetting(main,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        PackageManager.DONT_KILL_APP);
            }
        } catch (Exception ignored) {}

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }
}
