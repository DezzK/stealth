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

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import dezz.stealth.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private ExcludeAppsStorage excludeAppsStorage;
    private AppsToHideStorage appsToHideStorage;
    private PinStorage pinStorage;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Executor backgroundExecutor = Executors.newSingleThreadExecutor();

    ActivityMainBinding binding;
    private AppsAdapter adapter;
    private boolean isRestoreMode = false;
    private boolean adbOperationInProgress = false;

    private static final int REQUEST_PERMISSIONS = 1001;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.PROCESS_OUTGOING_CALLS,
            Manifest.permission.READ_CALL_LOG
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        excludeAppsStorage = new ExcludeAppsStorage(this);
        appsToHideStorage = new AppsToHideStorage(this);
        pinStorage = new PinStorage(this);

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        binding = ActivityMainBinding.inflate(this.getLayoutInflater());
        setContentView(binding.getRoot());

        initializeViews();
        requestRequiredPermissions();
    }

    private void requestRequiredPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.permissions_required_title)
                        .setMessage(R.string.permissions_required_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        updatePinState();
        checkAdbStatus();

        // Run orphan detection on background thread, then update UI
        backgroundExecutor.execute(() -> {
            Map<String, String> orphans = findOrphanedApps();
            if (!orphans.isEmpty()) {
                appsToHideStorage.save(orphans);
            }
            mainHandler.post(() -> {
                // Default to restore tab if there are hidden apps, otherwise hide tab
                if (appsToHideStorage.hasHiddenApps()) {
                    switchToRestoreMode();
                } else {
                    switchToHideMode();
                }
            });
        });
    }

    private void checkAdbStatus() {
        binding.adbStatusText.setText(R.string.adb_checking);
        binding.adbStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));

        ShellExecutor shell = ShellExecutor.getInstance(this);
        shell.checkConnection(new ShellExecutor.StatusCallback() {
            @Override
            public void onSuccess(String port) {
                mainHandler.post(() -> {
                    binding.adbStatusText.setText(getString(R.string.adb_connected, port));
                    binding.adbStatusText.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.adb_ok));
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    binding.adbStatusText.setText(getString(R.string.adb_error, error));
                    binding.adbStatusText.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.adb_error));
                });
            }
        });
    }

    /**
     * Detects disabled third-party apps not tracked in storage (orphans).
     * Runs on a background thread — do NOT touch Views from here.
     */
    private Map<String, String> findOrphanedApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(
                PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS);
        String currentPackageName = getPackageName();
        Map<String, String> knownHidden = appsToHideStorage.load();

        Map<String, String> orphans = new HashMap<>();
        for (ApplicationInfo appInfo : packages) {
            if (AlwaysIgnoreAppResolver.alwaysIgnoreApp(appInfo, currentPackageName)) {
                continue;
            }
            if (knownHidden.containsKey(appInfo.packageName)) {
                continue;
            }
            if (!isAppEnabled(pm, appInfo.packageName)) {
                orphans.put(appInfo.packageName, appInfo.loadLabel(pm).toString());
            }
        }
        return orphans;
    }

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

    private boolean hasRequiredPermissions() {
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void showPinDialog() {
        boolean changingPin = pinStorage.hasPin();
        String title = changingPin
                ? getString(R.string.change_pin_title)
                : getString(R.string.set_pin_title);

        int messageResId = (changingPin && appsToHideStorage.hasHiddenApps())
                ? R.string.pin_dialog_message_warn_hidden
                : R.string.pin_dialog_message;

        showPinInputDialog(title, messageResId);
    }

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
            // Restore tab active
            binding.tabRestore.setBackgroundColor(ContextCompat.getColor(this, R.color.tab_active));
            binding.tabRestore.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            binding.tabRestore.setTypeface(null, Typeface.BOLD);

            // Hide tab inactive
            binding.tabHide.setBackgroundColor(ContextCompat.getColor(this, R.color.tab_inactive));
            binding.tabHide.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            binding.tabHide.setTypeface(null, Typeface.NORMAL);
        } else {
            // Hide tab active
            binding.tabHide.setBackgroundColor(ContextCompat.getColor(this, R.color.tab_active));
            binding.tabHide.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            binding.tabHide.setTypeface(null, Typeface.BOLD);

            // Restore tab inactive or disabled
            if (hasHiddenApps) {
                binding.tabRestore.setBackgroundColor(ContextCompat.getColor(this, R.color.tab_inactive));
                binding.tabRestore.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            } else {
                binding.tabRestore.setBackgroundColor(ContextCompat.getColor(this, R.color.tab_inactive));
                binding.tabRestore.setTextColor(ContextCompat.getColor(this, R.color.tab_disabled));
            }
            binding.tabRestore.setTypeface(null, Typeface.NORMAL);
        }

        // Restore tab is only clickable when there are hidden apps
        binding.tabRestore.setEnabled(hasHiddenApps);
    }

    private void setupHideMode() {
        binding.modeHeaderText.setText(R.string.hide_mode_header);
        binding.hideAppsButton.setVisibility(View.VISIBLE);
        binding.pinButton.setVisibility(View.VISIBLE);
        binding.restoreAppsButton.setVisibility(View.GONE);
        updatePinState();

        List<AppInfo> appsList = getAppsList();
        adapter = new AppsAdapter(appsList, excludeAppsStorage);
        binding.recyclerView.setAdapter(adapter);
    }

    private void setupRestoreMode() {
        // Build the list first — getHiddenAppsList() may clean up storage
        // if apps were re-enabled externally, leaving nothing to restore
        List<AppInfo> hiddenApps = getHiddenAppsList();

        if (hiddenApps.isEmpty()) {
            // Nothing actually disabled — fall back to hide mode
            switchToHideMode();
            return;
        }

        binding.modeHeaderText.setText(R.string.restore_mode_header);
        binding.hideAppsButton.setVisibility(View.GONE);
        binding.restoreAppsButton.setVisibility(View.VISIBLE);
        updatePinState();

        adapter = new AppsAdapter(hiddenApps, null);
        binding.recyclerView.setAdapter(adapter);
    }

    private void hideLauncherIcon() {
        PackageManager p = getPackageManager();
        ComponentName componentName = new ComponentName(this, MainActivity.class);
        p.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

    private void setAdbOperationInProgress(boolean inProgress) {
        adbOperationInProgress = inProgress;
        binding.hideAppsButton.setEnabled(!inProgress);
        binding.hideAppsButton.setAlpha(!inProgress ? 1f : 0.5f);
        binding.restoreAppsButton.setEnabled(!inProgress);
        binding.restoreAppsButton.setAlpha(!inProgress ? 1f : 0.5f);
        binding.tabHide.setEnabled(!inProgress);
        binding.tabRestore.setEnabled(!inProgress && appsToHideStorage.hasHiddenApps());
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
        binding.pinButton.setOnClickListener(v -> showPinDialog());

        binding.tabHide.setOnClickListener(v -> {
            if (isRestoreMode) {
                switchToHideMode();
            }
        });

        binding.tabRestore.setOnClickListener(v -> {
            if (!isRestoreMode && appsToHideStorage.hasHiddenApps()) {
                switchToRestoreMode();
            }
        });
    }

    private List<AppInfo> getAppsList() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        String currentPackageName = getPackageName();
        Set<String> selectedApps = excludeAppsStorage.getAppsToKeep();

        List<AppInfo> appInfoList = new ArrayList<>();

        for (ApplicationInfo appInfo : packages) {
            if (AlwaysIgnoreAppResolver.alwaysIgnoreApp(appInfo, currentPackageName)) {
                continue;
            }

            // Only show enabled apps in the hide list
            if (!appInfo.enabled) {
                continue;
            }

            // Checked = "will be hidden". Apps in selectedApps are kept (unchecked).
            AppInfo info = new AppInfo(
                    appInfo.packageName,
                    appInfo.loadLabel(pm).toString(),
                    appInfo.loadIcon(pm),
                    !selectedApps.contains(appInfo.packageName)
            );
            appInfoList.add(info);
        }

        // Sort by app name
        appInfoList.sort(Comparator.comparing(a -> a.getAppName().toLowerCase()));

        return appInfoList;
    }

    private List<AppInfo> getHiddenAppsList() {
        PackageManager pm = getPackageManager();
        Map<String, String> hiddenApps = appsToHideStorage.load();
        List<AppInfo> appInfoList = new ArrayList<>();
        List<String> toRemoveFromStorage = new ArrayList<>();

        for (Map.Entry<String, String> entry : hiddenApps.entrySet()) {
            String packageName = entry.getKey();

            // Only show apps that are actually disabled on the system
            if (isAppEnabled(pm, packageName)) {
                toRemoveFromStorage.add(packageName);
                continue;
            }

            // Load real app name and icon even for disabled apps
            String appName = entry.getValue();
            Drawable icon = ResourcesCompat.getDrawable(getResources(), android.R.drawable.sym_def_app_icon, getTheme());
            try {
                ApplicationInfo ai = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
                appName = ai.loadLabel(pm).toString();
                icon = ai.loadIcon(pm);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Use stored name and default icon as fallback
            }

            AppInfo info = new AppInfo(packageName, appName, icon, true);
            appInfoList.add(info);
        }

        // Clean up storage: remove entries for apps that are already enabled
        if (!toRemoveFromStorage.isEmpty()) {
            appsToHideStorage.removeAll(toRemoveFromStorage);
        }

        appInfoList.sort(Comparator.comparing(a -> a.getAppName().toLowerCase()));

        return appInfoList;
    }

    private boolean isAppEnabled(PackageManager pm, String packageName) {
        try {
            int setting = pm.getApplicationEnabledSetting(packageName);
            // ENABLED or DEFAULT means the app is currently enabled
            return setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        } catch (IllegalArgumentException e) {
            // Package not found (uninstalled) — treat as not restorable, remove from storage
            return true;
        }
    }

    public void disableApps() {
        if (adbOperationInProgress) return;

        // Step 1: Check PIN
        if (!pinStorage.hasPin()) {
            showPinRequiredDialog();
            return;
        }

        // Step 2: Check permissions
        if (!hasRequiredPermissions()) {
            showPermissionsRequiredDialog();
            return;
        }

        // Step 3: Check selection
        List<AppInfo> checkedApps = adapter.getCheckedApps();

        if (checkedApps.isEmpty()) {
            Toast.makeText(this, R.string.no_apps_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        // Build map of packages to disable (checked = hide)
        Map<String, String> packagesToDisable = new HashMap<>();
        for (AppInfo app : checkedApps) {
            packagesToDisable.put(app.getPackageName(), app.getAppName());
        }

        // Show confirmation dialog before proceeding
        StringBuilder appNames = new StringBuilder();
        for (AppInfo app : checkedApps) {
            if (appNames.length() > 0) appNames.append("\n");
            appNames.append("• ").append(app.getAppName());
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.hide_confirm_title)
                .setMessage(getString(R.string.hide_confirm_message, appNames.toString()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    performDisableApps(packagesToDisable);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPinInputDialog(String title, int messageResId) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.pin_hint);
        input.setTextSize(24);

        FrameLayout container = new FrameLayout(this);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(messageResId)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String pin = input.getText().toString().trim();
                    int validation = pinStorage.validate(pin);
                    if (validation == PinStorage.OK && pinStorage.save(pin)) {
                        Toast.makeText(this, R.string.pin_set_successfully, Toast.LENGTH_SHORT).show();
                        updatePinState();
                    } else if (validation == PinStorage.STARTS_WITH_ZERO) {
                        Toast.makeText(this, R.string.pin_no_leading_zero, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.pin_too_short, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPinRequiredDialog() {
        showPinInputDialog(getString(R.string.set_pin_title), R.string.pin_required_dialog_message);
    }

    private void showPermissionsRequiredDialog() {
        // Check if we can still ask for permissions, or if user selected "Don't ask again"
        boolean canAskAgain = false;
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                    canAskAgain = true;
                }
            }
        }

        if (canAskAgain) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permissions_required_title)
                    .setMessage(R.string.permissions_required_to_hide_message)
                    .setPositiveButton(R.string.grant_permissions, (dialog, which) -> {
                        requestRequiredPermissions();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            // User selected "Don't ask again" — send to app settings
            new AlertDialog.Builder(this)
                    .setTitle(R.string.permissions_required_title)
                    .setMessage(R.string.permissions_open_settings_message)
                    .setPositiveButton(R.string.open_settings, (dialog, which) -> {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(android.net.Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    private void performDisableApps(Map<String, String> packagesToDisable) {
        setAdbOperationInProgress(true);
        List<String> packageNames = new ArrayList<>(packagesToDisable.keySet());

        ShellExecutor shell = ShellExecutor.getInstance(this);
        shell.disableApps(packageNames, new ShellExecutor.BatchCallback() {
            @Override
            public void onResult(ShellExecutor.BatchResult result) {
                mainHandler.post(() -> {
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

                    if (result.isFullSuccess()) {
                        Toast.makeText(MainActivity.this,
                                R.string.apps_hidden_successfully,
                                Toast.LENGTH_SHORT).show();
                    } else if (result.hasAnySuccess()) {
                        // Partial success
                        Toast.makeText(MainActivity.this,
                                getString(R.string.apps_hide_partial,
                                        result.getSucceededPackages().size(),
                                        result.getErrors().size()),
                                Toast.LENGTH_LONG).show();
                    } else {
                        // Total failure
                        Toast.makeText(MainActivity.this,
                                getString(R.string.apps_hide_error,
                                        String.join("\n", result.getErrors())),
                                Toast.LENGTH_LONG).show();
                    }

                    updateTabAppearance();
                });
            }

            @Override
            public void onConnectionError(String error) {
                mainHandler.post(() -> {
                    setAdbOperationInProgress(false);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.apps_hide_error, error),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    public void restoreSelectedApps() {
        if (adbOperationInProgress) return;

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
                mainHandler.post(() -> {
                    setAdbOperationInProgress(false);

                    // Remove successfully restored apps from storage (even if some failed)
                    if (result.hasAnySuccess()) {
                        appsToHideStorage.removeAll(result.getSucceededPackages());
                    }

                    if (result.isFullSuccess()) {
                        Toast.makeText(MainActivity.this,
                                R.string.apps_restored_successfully,
                                Toast.LENGTH_SHORT).show();
                    } else if (result.hasAnySuccess()) {
                        Toast.makeText(MainActivity.this,
                                getString(R.string.apps_restore_partial,
                                        result.getSucceededPackages().size(),
                                        result.getErrors().size()),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(MainActivity.this,
                                getString(R.string.apps_restore_error,
                                        String.join("\n", result.getErrors())),
                                Toast.LENGTH_LONG).show();
                    }

                    if (appsToHideStorage.hasHiddenApps()) {
                        switchToRestoreMode();
                    } else {
                        switchToHideMode();
                    }
                });
            }

            @Override
            public void onConnectionError(String error) {
                mainHandler.post(() -> {
                    setAdbOperationInProgress(false);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.apps_restore_error, error),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
