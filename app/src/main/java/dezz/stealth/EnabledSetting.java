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

import android.content.pm.PackageManager;

/** Reads {@link PackageManager} enabled-setting values for packages and components. */
final class EnabledSetting {
    private EnabledSetting() {}

    /**
     * Whether an enabled-setting means "enabled". DEFAULT defers to the manifest, where
     * packages and components are enabled unless they declare otherwise — and every one
     * this app checks does not.
     */
    static boolean isEnabled(int setting) {
        return setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    }
}
