package dezz.stealth;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.AppViewHolder> {

    private final List<AppInfo> apps;
    @Nullable
    private final ExcludeAppsStorage excludeAppsStorage;

    public AppsAdapter(List<AppInfo> apps, @Nullable ExcludeAppsStorage excludeAppsStorage) {
        this.apps = apps;
        this.excludeAppsStorage = excludeAppsStorage;
    }

    public List<AppInfo> getCheckedApps() {
        List<AppInfo> checked = new ArrayList<>();
        for (AppInfo app : apps) {
            if (app.isChecked()) {
                checked.add(app);
            }
        }
        return checked;
    }

    public static class AppViewHolder extends RecyclerView.ViewHolder {
        ImageView appIcon;
        TextView appName;
        TextView appPackage;
        CheckBox checkBox;

        public AppViewHolder(View view) {
            super(view);
            appIcon = view.findViewById(R.id.appIcon);
            appName = view.findViewById(R.id.appName);
            appPackage = view.findViewById(R.id.appPackage);
            checkBox = view.findViewById(R.id.checkBox);
        }
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo app = apps.get(position);

        holder.appIcon.setImageDrawable(app.getIcon());
        holder.appName.setText(app.getAppName());
        holder.appPackage.setText(app.getPackageName());

        // Clear listener BEFORE setChecked to prevent the old listener from firing
        // for the previously-bound item when the view is recycled.
        holder.checkBox.setOnCheckedChangeListener(null);
        holder.checkBox.setChecked(app.isChecked());

        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            app.setChecked(isChecked);
            // In hide mode (excludeAppsStorage != null), checked = "hide this app".
            // ExcludeAppsStorage tracks apps to KEEP, so:
            //   checked (hide)   → remove from keep-list
            //   unchecked (keep) → add to keep-list
            if (excludeAppsStorage != null) {
                if (isChecked) {
                    excludeAppsStorage.remove(app.getPackageName());
                } else {
                    excludeAppsStorage.add(app.getPackageName());
                }
            }
        });

        holder.itemView.setOnClickListener(v -> holder.checkBox.setChecked(!holder.checkBox.isChecked()));
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }
}
