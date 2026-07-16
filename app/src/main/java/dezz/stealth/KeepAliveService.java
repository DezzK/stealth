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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * Sticky foreground service whose only job is to keep the app's process out of the
 * "stopped" state ({@code ApplicationInfo.FLAG_STOPPED}).
 * <p>
 * While apps are hidden the launcher icon is gone, so there is no way for the user to
 * relaunch the app. If the head unit's aggressive OEM power-management force-stops the
 * process, the package enters the stopped state and the manifest-declared
 * {@link DialerCodeReceiver} stops receiving {@code NEW_OUTGOING_CALL} broadcasts — the
 * platform stamps {@code FLAG_EXCLUDE_STOPPED_PACKAGES} on broadcasts by default and
 * telecom does not set {@code FLAG_INCLUDE_STOPPED_PACKAGES} — so the dialer-PIN reveal
 * silently dies with no recovery path. A running foreground service makes the process far
 * less likely to be reaped.
 * <p>
 * <b>Best-effort:</b> some head-unit ROMs still kill foreground services. This lowers the
 * frequency of the failure; it is not a guarantee. Callers: {@link MainActivity} (on
 * hide / on open while hidden) and {@link BootReceiver} (after reboot).
 * <p>
 * {@code directBootAware} (see manifest) so it can be restarted at
 * {@code LOCKED_BOOT_COMPLETED}, consistent with the rest of the app's Direct Boot design.
 */
public class KeepAliveService extends Service {
    private static final String CHANNEL_ID = "stealth_keepalive";
    private static final int NOTIFICATION_ID = 42;

    /**
     * Start the service (no-op if already running). Safe to call from a background
     * context: the app targets SDK 28, so it is exempt from the Android 12+ ban on
     * starting a foreground service from the background.
     */
    static void start(Context context) {
        // minSdk 28 (> O), so startForegroundService is always available.
        context.startForegroundService(new Intent(context, KeepAliveService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, KeepAliveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must call startForeground promptly (within ~5s of startForegroundService) or
        // the system throws — so do it first thing.
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        createChannel();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_icon)
                .setContentTitle(getString(R.string.keepalive_channel_name))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    /** minSdk 28 (> O), so notification channels are always required. */
    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keepalive_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }
}
