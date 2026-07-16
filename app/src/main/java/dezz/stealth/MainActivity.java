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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dezz.stealth.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private ExcludeAppsStorage excludeAppsStorage;
    private AppsToHideStorage appsToHideStorage;
    private PinStorage pinStorage;
    private PinDialogs pinDialogs;
    private PermissionGate permissionGate;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    ActivityMainBinding binding;
    private AppsAdapter adapter;
    private boolean isRestoreMode = false;
    private boolean adbOperationInProgress = false;

    /** Whether the battery-optimization nudge was already shown this session. */
    private boolean batteryPromptShown = false;

    /** Incremented on every mode switch — used to discard stale list-build results. */
    private int listGeneration = 0;

    /** Latest snapshot of host scans — refreshed live as the discovery progresses. */
    private List<ShellExecutor.HostScanResult> lastHostScans = null;
    private boolean connectionProbingFinished = false;

    /** Live reference to the connection-details dialog body when open, null otherwise. */
    private TextView connectionDetailsBody;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        excludeAppsStorage = new ExcludeAppsStorage(this);
        appsToHideStorage = new AppsToHideStorage(this);
        pinStorage = new PinStorage(this);
        pinDialogs = new PinDialogs(this, pinStorage, appsToHideStorage, this::updatePinState);
        permissionGate = new PermissionGate(this);

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        binding = ActivityMainBinding.inflate(this.getLayoutInflater());
        setContentView(binding.getRoot());

        ensureMainActivityEnabled();
        initializeViews();
        permissionGate.requestMissing();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdownNow();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionGate.handleResult(requestCode, grantResults);
    }

    @Override
    protected void onStart() {
        super.onStart();

        updatePinState();
        checkAdbStatus();

        // Run orphan detection on background thread, then update UI
        backgroundExecutor.execute(() -> {
            Map<String, String> orphans = AppListBuilder.findOrphanedApps(this, appsToHideStorage);
            if (!orphans.isEmpty()) {
                appsToHideStorage.save(orphans);
            }
            postIfAlive(() -> {
                if (appsToHideStorage.hasHiddenApps()) {
                    // Opened while hidden (e.g. relaunched after a kill) — re-arm the
                    // keep-alive service so it survives the next idle period.
                    KeepAliveService.start(getApplicationContext());
                    switchToRestoreMode();
                } else {
                    // Nothing hidden (possibly restored externally, e.g. via adb) — make
                    // sure the keep-alive service isn't left running for nothing.
                    KeepAliveService.stop(getApplicationContext());
                    switchToHideMode();
                }
            });
        });
    }

    /** Post a Runnable to the main thread, but skip if the activity is finishing/destroyed. */
    private void postIfAlive(Runnable r) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            r.run();
        });
    }

    private void initializeViews() {
        final String appVersion = VersionGetter.getAppVersionName(this);
        if (appVersion != null) {
            binding.headerText.setText(String.format("%s %s", getString(R.string.app_name), appVersion));
        }
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));

        binding.hideAppsButton.setOnClickListener(v -> disableApps());
        binding.restoreAppsButton.setOnClickListener(v -> restoreSelectedApps());
        binding.settingsButton.setOnClickListener(v -> pinDialogs.showSetOrChange());
        binding.aboutButton.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        binding.pinHintRow.setOnClickListener(v -> pinDialogs.showSetOrChange());
        binding.connectionStatusText.setOnClickListener(v -> showConnectionDetailsIfAny());

        binding.tabHide.setOnClickListener(v -> {
            if (isRestoreMode) switchToHideMode();
        });
        binding.tabRestore.setOnClickListener(v -> {
            if (!isRestoreMode && appsToHideStorage.hasHiddenApps()) switchToRestoreMode();
        });
    }

    private void showConnectionDetailsIfAny() {
        if (lastHostScans == null || lastHostScans.isEmpty()) return;

        // Custom view so we can keep a reference to the body TextView and refresh it
        // live as more probes complete in the background.
        ScrollView scrollView = new ScrollView(this);
        TextView body = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        body.setPadding(pad * 2, pad, pad * 2, pad);
        body.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        body.setTextSize(14);
        body.setLineSpacing(0, 1.2f);
        scrollView.addView(body);
        connectionDetailsBody = body;

        renderConnectionDetailsBody();

        new AlertDialog.Builder(this)
                .setTitle(R.string.connection_details_title)
                .setView(scrollView)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> connectionDetailsBody = null)
                .show();
    }

    /** Renders the dialog body from {@link #lastHostScans}. No-op if dialog is closed. */
    private void renderConnectionDetailsBody() {
        TextView target = connectionDetailsBody;
        if (target == null || lastHostScans == null) return;

        boolean anySuccess = findActiveHostPort(lastHostScans) != null;

        StringBuilder body = new StringBuilder();
        body.append(getString(anySuccess
                ? R.string.connection_details_intro_success
                : R.string.connection_details_intro));
        body.append("\n");
        for (ShellExecutor.HostScanResult host : lastHostScans) {
            boolean hasMatch = false;
            for (ShellExecutor.PortResult p : host.ports) {
                if (p.hasSupportedTransport()) { hasMatch = true; break; }
            }

            // Status emoji: ✅ found something, ❌ done & nothing, ⏳ still scanning
            String prefix;
            if (hasMatch) {
                prefix = "✅ ";
            } else if (host.scanning) {
                prefix = "⏳ ";
            } else {
                prefix = "❌ ";
            }

            body.append("\n").append(prefix).append(host.host);
            if (host.scanning) {
                body.append(" ").append(getString(R.string.connection_scanning_marker));
            }
            body.append("\n");

            if (host.ports.isEmpty()) {
                body.append("    ").append(getString(host.scanning
                        ? R.string.connection_host_searching
                        : R.string.connection_host_no_open_ports)).append("\n");
            } else {
                for (ShellExecutor.PortResult p : host.ports) {
                    appendPortBlock(body, p);
                }
            }
        }
        if (!connectionProbingFinished) {
            body.append("\n").append(getString(R.string.connection_details_in_progress));
        }
        target.setText(body.toString().trim());
    }

    private void checkAdbStatus() {
        binding.connectionStatusText.setText(R.string.connection_checking);
        binding.connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        lastHostScans = null;
        connectionProbingFinished = false;

        ShellExecutor.getInstance(this).checkConnection((hosts, finished) -> postIfAlive(() -> {
            lastHostScans = hosts;
            connectionProbingFinished = finished;
            updateConnectionStatusText();
            renderConnectionDetailsBody();
        }));
    }

    private void updateConnectionStatusText() {
        ActiveEndpoint active = findActiveHostPort(lastHostScans);
        if (active != null) {
            String label = active.transport + " " + ShellExecutor.formatHostPort(active.host, active.port);
            binding.connectionStatusText.setText(getString(R.string.connection_connected, label));
            binding.connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.adb_ok));
        } else if (connectionProbingFinished) {
            binding.connectionStatusText.setText(R.string.connection_error);
            binding.connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.adb_error));
        } else {
            binding.connectionStatusText.setText(R.string.connection_checking);
            binding.connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    /** Active endpoint snapshot (host + port + transport) used in the status line. */
    private static class ActiveEndpoint {
        final String host;
        final int port;
        final String transport;
        ActiveEndpoint(String host, int port, String transport) {
            this.host = host; this.port = port; this.transport = transport;
        }
    }

    private static ActiveEndpoint findActiveHostPort(List<ShellExecutor.HostScanResult> hosts) {
        if (hosts == null) return null;
        for (ShellExecutor.HostScanResult h : hosts) {
            for (ShellExecutor.PortResult p : h.ports) {
                String transport = p.successfulTransport();
                if (p.isActive && transport != null) {
                    return new ActiveEndpoint(h.host, p.port, transport);
                }
            }
        }
        return null;
    }

    /** Renders one port — checkmark + port number + protocol if found, cross otherwise. */
    private void appendPortBlock(StringBuilder body, ShellExecutor.PortResult p) {
        if (p.probes.isEmpty()) {
            // Still probing — neutral marker.
            body.append("    … :").append(p.port).append(" — ")
                    .append(getString(R.string.connection_port_probing)).append("\n");
            return;
        }
        String successful = p.successfulTransport();
        if (successful != null) {
            body.append("    ✓ :").append(p.port).append(" — ").append(successful);
            if (p.isActive) {
                body.append(" (").append(getString(R.string.connection_attempt_active)).append(")");
            }
        } else {
            body.append("    ✗ :").append(p.port);
        }
        body.append("\n");
    }

    // ── Mode switching ────────────────────────────────────────────────

    private void switchToHideMode() {
        isRestoreMode = false;
        updateTabAppearance();
        setupHideMode();
    }

    private void switchToRestoreMode() {
        isRestoreMode = true;
        updateTabAppearance();
        setupRestoreMode();
    }

    private void updateTabAppearance() {
        boolean hasHiddenApps = appsToHideStorage.hasHiddenApps();
        int accent = ContextCompat.getColor(this, R.color.accent);
        int transparent = ContextCompat.getColor(this, android.R.color.transparent);
        int primaryText = ContextCompat.getColor(this, R.color.text_primary);
        int secondaryText = ContextCompat.getColor(this, R.color.text_secondary);
        int disabledText = ContextCompat.getColor(this, R.color.tab_disabled);

        if (isRestoreMode) {
            binding.tabRestore.setTextColor(primaryText);
            binding.tabRestore.setTypeface(null, Typeface.BOLD);
            binding.tabRestoreIndicator.setBackgroundColor(accent);

            binding.tabHide.setTextColor(secondaryText);
            binding.tabHide.setTypeface(null, Typeface.NORMAL);
            binding.tabHideIndicator.setBackgroundColor(transparent);
        } else {
            binding.tabHide.setTextColor(primaryText);
            binding.tabHide.setTypeface(null, Typeface.BOLD);
            binding.tabHideIndicator.setBackgroundColor(accent);

            binding.tabRestore.setTextColor(hasHiddenApps ? secondaryText : disabledText);
            binding.tabRestore.setTypeface(null, Typeface.NORMAL);
            binding.tabRestoreIndicator.setBackgroundColor(transparent);
        }

        binding.tabRestore.setEnabled(hasHiddenApps);
    }

    private void setupHideMode() {
        binding.modeHeaderText.setText(R.string.hide_mode_header);
        binding.hideAppsButton.setVisibility(View.VISIBLE);
        binding.restoreAppsButton.setVisibility(View.GONE);
        updatePinState();

        // Building the list does N PackageManager IPC calls — run off the UI thread.
        // Generation counter prevents stale results from overwriting newer ones when
        // the user switches modes rapidly.
        final int gen = ++listGeneration;
        backgroundExecutor.execute(() -> {
            List<AppInfo> appsList = AppListBuilder.hidableApps(this, excludeAppsStorage);
            postIfAlive(() -> {
                if (gen != listGeneration) return;
                adapter = new AppsAdapter(appsList, excludeAppsStorage);
                binding.recyclerView.setAdapter(adapter);
                updateEmptyState(appsList.isEmpty(), R.string.empty_hide_list);
            });
        });
    }

    private void setupRestoreMode() {
        binding.modeHeaderText.setText(R.string.restore_mode_header);
        binding.hideAppsButton.setVisibility(View.GONE);
        binding.restoreAppsButton.setVisibility(View.VISIBLE);
        updatePinState();

        // hiddenApps() may also clean up stale storage entries.
        final int gen = ++listGeneration;
        backgroundExecutor.execute(() -> {
            List<AppInfo> hiddenApps = AppListBuilder.hiddenApps(this, appsToHideStorage);
            postIfAlive(() -> {
                if (gen != listGeneration) return;
                if (hiddenApps.isEmpty()) {
                    // Nothing actually disabled — fall back to hide mode
                    switchToHideMode();
                    return;
                }
                adapter = new AppsAdapter(hiddenApps, null);
                binding.recyclerView.setAdapter(adapter);
                updateEmptyState(false, 0);
            });
        });
    }

    private void updateEmptyState(boolean isEmpty, int messageRes) {
        if (isEmpty) {
            binding.emptyStateText.setText(messageRes);
            binding.emptyStateText.setVisibility(View.VISIBLE);
        } else {
            binding.emptyStateText.setVisibility(View.GONE);
        }
    }

    // ── PIN state ──────────────────────────────────────────────────────

    private void updatePinState() {
        if (pinStorage.hasPin()) {
            binding.pinHintRow.setVisibility(View.GONE);
        } else {
            binding.pinHintRow.setVisibility(View.VISIBLE);
            binding.pinHintText.setText(getString(R.string.default_pin_hint, PinStorage.DEFAULT_PIN));
        }
    }

    // ── Launcher icon visibility ──────────────────────────────────────

    private void hideLauncherIcon() {
        PackageManager p = getPackageManager();
        // Toggle the trampoline, not MainActivity. Disabling MainActivity directly makes
        // the system restart/kill the running activity even with DONT_KILL_APP — so when
        // DialerCodeReceiver re-enables it, the activity dies right after the user opens
        // it (no Java exception, just looks like the app silently exits).
        ComponentName launcher = new ComponentName(this, LauncherTrampolineActivity.class);
        p.setComponentEnabledSetting(launcher, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

    /**
     * Migration safety net: re-enable MainActivity in case an older build had disabled
     * it directly. New code never disables MainActivity (only the alias), so on legacy
     * installs we may inherit a DISABLED state that prevents the activity from launching.
     */
    private void ensureMainActivityEnabled() {
        try {
            PackageManager p = getPackageManager();
            ComponentName cn = new ComponentName(this, MainActivity.class);
            int state = p.getComponentEnabledSetting(cn);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                p.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
            }
        } catch (Exception ignored) {}
    }

    // ── ADB operation lifecycle ───────────────────────────────────────

    private void setAdbOperationInProgress(boolean inProgress) {
        adbOperationInProgress = inProgress;
        float dimAlpha = inProgress ? 0.5f : 1f;
        boolean enabled = !inProgress;

        // Action buttons
        binding.hideAppsButton.setEnabled(enabled);
        binding.hideAppsButton.setAlpha(dimAlpha);
        binding.restoreAppsButton.setEnabled(enabled);
        binding.restoreAppsButton.setAlpha(dimAlpha);
        binding.settingsButton.setEnabled(enabled);
        binding.settingsButton.setAlpha(dimAlpha);
        binding.pinHintRow.setEnabled(enabled);

        // Tabs
        binding.tabHide.setEnabled(enabled);
        binding.tabRestore.setEnabled(enabled && appsToHideStorage.hasHiddenApps());

        // List + progress: dim the list and intercept touches with overlay
        binding.recyclerView.setAlpha(dimAlpha);
        binding.listOverlay.setVisibility(inProgress ? View.VISIBLE : View.GONE);
        binding.progressBar.setVisibility(inProgress ? View.VISIBLE : View.GONE);
    }

    // ── Hide flow ──────────────────────────────────────────────────────

    private void disableApps() {
        if (adbOperationInProgress) return;
        if (adapter == null) {
            Toast.makeText(this, R.string.list_loading, Toast.LENGTH_SHORT).show();
            return;
        }

        // Step 1: Check ADB/Telnet connection — fail fast, before showing any other dialogs
        if (!ShellExecutor.getInstance(this).hasWorkingTransport()) {
            Toast.makeText(this, R.string.no_connection_detected, Toast.LENGTH_LONG).show();
            return;
        }

        // Step 2: Check PIN
        if (!pinStorage.hasPin()) {
            pinDialogs.showRequired();
            return;
        }

        // Step 3: Check permissions
        if (!permissionGate.hasAll()) {
            permissionGate.showRequiredToHide();
            return;
        }

        // Step 4: Check selection
        List<AppInfo> checkedApps = adapter.getCheckedApps();
        if (checkedApps.isEmpty()) {
            Toast.makeText(this, R.string.no_apps_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        // Step 5: Nudge the user to whitelist us from battery optimization so the keep-alive
        // service survives longer between openings. Best-effort and non-blocking — shown at
        // most once per session; on "Later" we just fall through and hide anyway.
        if (!batteryPromptShown && !BatteryOptimization.isIgnoring(this)) {
            batteryPromptShown = true;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.battery_opt_title)
                    .setMessage(R.string.battery_opt_message)
                    .setPositiveButton(R.string.battery_opt_grant, (dialog, which) -> BatteryOptimization.request(this))
                    .setNegativeButton(R.string.later, (dialog, which) -> disableApps())
                    .show();
            return;
        }

        Map<String, String> packagesToDisable = new HashMap<>();
        StringBuilder appNames = new StringBuilder();
        for (AppInfo app : checkedApps) {
            packagesToDisable.put(app.getPackageName(), app.getAppName());
            if (appNames.length() > 0) appNames.append("\n");
            appNames.append("• ").append(app.getAppName());
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.hide_confirm_title)
                .setMessage(getString(R.string.hide_confirm_message, appNames.toString()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> performDisableApps(packagesToDisable))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void performDisableApps(Map<String, String> packagesToDisable) {
        setAdbOperationInProgress(true);
        List<String> packageNames = new ArrayList<>(packagesToDisable.keySet());

        ShellExecutor shell = ShellExecutor.getInstance(this);
        shell.disableApps(packageNames, new ShellExecutor.BatchCallback() {
            @Override
            public void onResult(ShellExecutor.BatchResult result) {
                postIfAlive(() -> {
                    // Save successfully hidden apps to storage (even if some failed)
                    if (result.hasAnySuccess()) {
                        Map<String, String> succeeded = new HashMap<>();
                        for (String pkg : result.getSucceededPackages()) {
                            succeeded.put(pkg, packagesToDisable.get(pkg));
                        }
                        appsToHideStorage.save(succeeded);
                    }

                    showBatchResultToast(result, R.string.apps_hidden_successfully,
                            R.string.apps_hide_partial, R.string.apps_hide_error);

                    if (result.hasAnySuccess()) {
                        // Spinner stays visible until the activity is gone — the user
                        // is done here. Hide the launcher icon, then close the activity
                        // ourselves so there's no awkward "app still usable for a few
                        // seconds before vanishing" window.
                        hideLauncherIcon();
                        // Start the keep-alive service so the OEM is less likely to
                        // force-stop us and kill the dialer-PIN reveal path.
                        KeepAliveService.start(getApplicationContext());
                        finish();
                    } else {
                        // Nothing succeeded — let the user try again
                        setAdbOperationInProgress(false);
                        updateTabAppearance();
                    }
                });
            }

            @Override
            public void onConnectionError(String error) {
                postIfAlive(() -> {
                    setAdbOperationInProgress(false);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.apps_hide_error, error),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ── Restore flow ───────────────────────────────────────────────────

    private void restoreSelectedApps() {
        if (adbOperationInProgress) return;
        if (adapter == null) {
            Toast.makeText(this, R.string.list_loading, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ShellExecutor.getInstance(this).hasWorkingTransport()) {
            Toast.makeText(this, R.string.no_connection_detected, Toast.LENGTH_LONG).show();
            return;
        }

        List<AppInfo> checkedApps = adapter.getCheckedApps();
        if (checkedApps.isEmpty()) {
            Toast.makeText(this, R.string.no_apps_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        Set<String> packagesToEnable = new HashSet<>();
        for (AppInfo app : checkedApps) {
            packagesToEnable.add(app.getPackageName());
        }

        setAdbOperationInProgress(true);

        ShellExecutor shell = ShellExecutor.getInstance(this);
        shell.enableApps(packagesToEnable, new ShellExecutor.BatchCallback() {
            @Override
            public void onResult(ShellExecutor.BatchResult result) {
                postIfAlive(() -> {
                    setAdbOperationInProgress(false);

                    if (result.hasAnySuccess()) {
                        appsToHideStorage.removeAll(result.getSucceededPackages());
                    }

                    showBatchResultToast(result, R.string.apps_restored_successfully,
                            R.string.apps_restore_partial, R.string.apps_restore_error);

                    if (appsToHideStorage.hasHiddenApps()) {
                        switchToRestoreMode();
                    } else {
                        // Nothing hidden any more — no need to keep the process pinned.
                        KeepAliveService.stop(getApplicationContext());
                        switchToHideMode();
                    }
                });
            }

            @Override
            public void onConnectionError(String error) {
                postIfAlive(() -> {
                    setAdbOperationInProgress(false);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.apps_restore_error, error),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showBatchResultToast(ShellExecutor.BatchResult result,
                                      int fullSuccessRes, int partialRes, int errorRes) {
        if (result.isFullSuccess()) {
            // LENGTH_LONG so the restore "reboot recommended" hint stays on screen long
            // enough to read. Hide success message is short but a slightly longer toast
            // there is harmless (and the activity finishes right after anyway).
            Toast.makeText(this, fullSuccessRes, Toast.LENGTH_LONG).show();
        } else if (result.hasAnySuccess()) {
            Toast.makeText(this,
                    getString(partialRes,
                            result.getSucceededPackages().size(),
                            result.getErrors().size()),
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this,
                    getString(errorRes, String.join("\n", result.getErrors())),
                    Toast.LENGTH_LONG).show();
        }
    }
}
