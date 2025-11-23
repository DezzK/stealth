package dezz.stealth;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.AppViewHolder> {

    private final List<AppInfo> apps;
    private final ExcludeAppsStorage excludeAppsStorage;

    public AppsAdapter(List<AppInfo> apps, ExcludeAppsStorage excludeAppsStorage) {
        this.apps = apps;
        this.excludeAppsStorage = excludeAppsStorage;
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
        holder.checkBox.setChecked(app.isChecked());

        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            app.setChecked(isChecked);
            if (isChecked) {
                excludeAppsStorage.add(app.getPackageName());
            } else {
                excludeAppsStorage.remove(app.getPackageName());
            }
        });

        holder.itemView.setOnClickListener(v -> holder.checkBox.setChecked(!holder.checkBox.isChecked()));
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }
}