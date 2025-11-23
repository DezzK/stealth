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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.LinkMovementMethod;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import dezz.stealth.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    private ExcludeAppsStorage excludeAppsStorage;
    private AppsToHideStorage appsToHideStorage;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    ActivityMainBinding binding;
    private AppsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        excludeAppsStorage = new ExcludeAppsStorage(this);
        appsToHideStorage = new AppsToHideStorage(this);

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        binding = ActivityMainBinding.inflate(this.getLayoutInflater());
        setContentView(binding.getRoot());

        initializeViews();
    }

    @Override
    protected void onStart() {
        super.onStart();

        List<AppInfo> appsList = getAppsList();
        adapter = new AppsAdapter(appsList, excludeAppsStorage);
        binding.recyclerView.setAdapter(adapter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void initializeViews() {
        final String appVersion = VersionGetter.getAppVersionName(this);
        if (appVersion != null) {
            binding.headerText.setText(String.format("%s %s", getString(R.string.app_name), appVersion));
        }
        binding.copyrightNoticeText.setMovementMethod(LinkMovementMethod.getInstance());

        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));

        binding.hideAppsButton.setOnClickListener(v -> disableApps());
        binding.restoreAppsButton.setOnClickListener(v -> restoreApps());
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

            AppInfo info = new AppInfo(
                    appInfo.packageName,
                    appInfo.loadLabel(pm).toString(),
                    appInfo.loadIcon(pm),
                    selectedApps.contains(appInfo.packageName)
            );
            appInfoList.add(info);
        }

        // Sort by app name
        appInfoList.sort(Comparator.comparing(a -> a.getAppName().toLowerCase()));

        return appInfoList;
    }

    public void disableApps() {
        List<String> packagesToDisable = getPackagesToDisable();
        appsToHideStorage.save(packagesToDisable);

        // Execute ADB commands via AdbHelper
        AdbHelper adbHelper = new AdbHelper();
        adbHelper.disableApps(packagesToDisable, new AdbHelper.AdbCallback() {
            @Override
            public void onSuccess(String message) {
                mainHandler.post(() -> {
                    // Show success message
                    Toast.makeText(MainActivity.this,
                            R.string.apps_hidden_successfully,
                            Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    // Show error message
                    Toast.makeText(MainActivity.this,
                            String.format(getString(R.string.apps_hide_error), error),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    public void restoreApps() {
        Set<String> packagesToEnable = appsToHideStorage.load();

        // Execute ADB commands via AdbHelper
        AdbHelper adbHelper = new AdbHelper();
        adbHelper.enableApps(packagesToEnable, new AdbHelper.AdbCallback() {
            @Override
            public void onSuccess(String message) {
                mainHandler.post(() -> {
                    // Show success message
                    Toast.makeText(MainActivity.this,
                            R.string.apps_restored_successfully,
                            Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    // Show error message
                    Toast.makeText(MainActivity.this,
                            String.format(getString(R.string.apps_restore_error), error),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private List<String> getPackagesToDisable() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        String currentPackageName = getPackageName();
        Set<String> appsToKeep = excludeAppsStorage.getAppsToKeep();
        List<String> packageList = new ArrayList<>();
        for (ApplicationInfo appInfo : packages) {
            if (AlwaysIgnoreAppResolver.alwaysIgnoreApp(appInfo, currentPackageName) ||
                    appsToKeep.contains(appInfo.packageName)) {
                continue;
            }

            packageList.add(appInfo.packageName);
        }

        return packageList;
    }
}
