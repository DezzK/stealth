/*
 * Copyright © 2025 Dezz (https://github.com/DezzK)
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
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.LinkMovementMethod;
import android.view.View;
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

    /** Incremented on every mode switch — used to discard stale list-build results. */
    private int listGeneration = 0;

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
                    switchToRestoreMode();
                } else {
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
        binding.copyrightNoticeText.setMovementMethod(LinkMovementMethod.getInstance());

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));

        binding.hideAppsButton.setOnClickListener(v -> disableApps());
        binding.restoreAppsButton.setOnClickListener(v -> restoreSelectedApps());
        binding.pinButton.setOnClickListener(v -> pinDialogs.showSetOrChange());

        binding.tabHide.setOnClickListener(v -> {
            if (isRestoreMode) switchToHideMode();
        });
        binding.tabRestore.setOnClickListener(v -> {
            if (!isRestoreMode && appsToHideStorage.hasHiddenApps()) switchToRestoreMode();
        });
    }

    private void checkAdbStatus() {
        binding.adbStatusText.setText(R.string.adb_checking);
        binding.adbStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));

        ShellExecutor shell = ShellExecutor.getInstance(this);
        shell.checkConnection(new ShellExecutor.StatusCallback() {
            @Override
            public void onSuccess(String port) {
                postIfAlive(() -> {
                    binding.adbStatusText.setText(getString(R.string.adb_connected, port));
                    binding.adbStatusText.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.adb_ok));
                });
            }

            @Override
            public void onError(String error) {
                postIfAlive(() -> {
                    binding.adbStatusText.setText(getString(R.string.adb_error, error));
                    binding.adbStatusText.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.adb_error));
                });
            }
        });
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

        if (isRestoreMode) {
            binding.tabRestore.setBackgroundColor(ContextCompat.getColor(this, R.color.tab_active));
            binding.tabRestore.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            binding.tabRestore.setTypeface(null, Typeface.BOLD);
            binding.tabHide.setBackgroundColor(ContextCompat.getColor(this, R.color.tab_inactive));
            binding.tabHide.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            binding.tabHide.setTypeface(null, Typeface.NORMAL);
        } else {
            binding.tabHide.setBackgroundColor(ContextCompat.getColor(this, R.color.tab_active));
            binding.tabHide.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            binding.tabHide.setTypeface(null, Typeface.BOLD);
            binding.tabRestore.setBackgroundColor(ContextCompat.getColor(this, R.color.tab_inactive));
            binding.tabRestore.setTextColor(ContextCompat.getColor(this,
                    hasHiddenApps ? R.color.text_secondary : R.color.tab_disabled));
            binding.tabRestore.setTypeface(null, Typeface.NORMAL);
        }

        binding.tabRestore.setEnabled(hasHiddenApps);
    }

    private void setupHideMode() {
        binding.modeHeaderText.setText(R.string.hide_mode_header);
        binding.hideAppsButton.setVisibility(View.VISIBLE);
        binding.pinButton.setVisibility(View.VISIBLE);
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
            });
        });
    }

    // ── PIN state ──────────────────────────────────────────────────────

    private void updatePinState() {
        if (pinStorage.hasPin()) {
            binding.pinButton.setText(R.string.change_pin);
            binding.pinHintText.setVisibility(View.GONE);
        } else {
            binding.pinButton.setText(R.string.set_pin);
            binding.pinHintText.setVisibility(View.VISIBLE);
            binding.pinHintText.setText(getString(R.string.default_pin_hint, PinStorage.DEFAULT_PIN));
        }
    }

    // ── Launcher icon visibility ──────────────────────────────────────

    private void hideLauncherIcon() {
        PackageManager p = getPackageManager();
        ComponentName componentName = new ComponentName(this, MainActivity.class);
        p.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

    // ── ADB operation lifecycle ───────────────────────────────────────

    private void setAdbOperationInProgress(boolean inProgress) {
        adbOperationInProgress = inProgress;
        binding.hideAppsButton.setEnabled(!inProgress);
        binding.hideAppsButton.setAlpha(!inProgress ? 1f : 0.5f);
        binding.restoreAppsButton.setEnabled(!inProgress);
        binding.restoreAppsButton.setAlpha(!inProgress ? 1f : 0.5f);
        binding.tabHide.setEnabled(!inProgress);
        binding.tabRestore.setEnabled(!inProgress && appsToHideStorage.hasHiddenApps());
    }

    // ── Hide flow ──────────────────────────────────────────────────────

    private void disableApps() {
        if (adbOperationInProgress) return;
        if (adapter == null) {
            Toast.makeText(this, R.string.list_loading, Toast.LENGTH_SHORT).show();
            return;
        }

        // Step 1: Check PIN
        if (!pinStorage.hasPin()) {
            pinDialogs.showRequired();
            return;
        }

        // Step 2: Check permissions
        if (!permissionGate.hasAll()) {
            permissionGate.showRequiredToHide();
            return;
        }

        // Step 3: Check selection
        List<AppInfo> checkedApps = adapter.getCheckedApps();
        if (checkedApps.isEmpty()) {
            Toast.makeText(this, R.string.no_apps_selected, Toast.LENGTH_SHORT).show();
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
                    setAdbOperationInProgress(false);

                    // Save successfully hidden apps to storage (even if some failed)
                    if (result.hasAnySuccess()) {
                        Map<String, String> succeeded = new HashMap<>();
                        for (String pkg : result.getSucceededPackages()) {
                            succeeded.put(pkg, packagesToDisable.get(pkg));
                        }
                        appsToHideStorage.save(succeeded);
                        hideLauncherIcon();
                    }

                    showBatchResultToast(result, R.string.apps_hidden_successfully,
                            R.string.apps_hide_partial, R.string.apps_hide_error);
                    updateTabAppearance();
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
            Toast.makeText(this, fullSuccessRes, Toast.LENGTH_SHORT).show();
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
