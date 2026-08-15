package com.sameerali.appawake;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Displays launchable applications with a large, touch-friendly selection row. */
public final class AppListAdapter extends BaseAdapter {

    public interface SelectionListener {
        void onSelectionChanged(String packageName, boolean selected);
    }

    private final Context context;
    private final SelectionListener listener;
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> visibleApps = new ArrayList<>();
    private final Set<String> selectedPackages = new HashSet<>();

    public AppListAdapter(Context context, SelectionListener listener) {
        this.context = context;
        this.listener = listener;
        selectedPackages.addAll(AppPreferences.selectedPackages(context));
    }

    public void setApps(List<AppEntry> apps) {
        allApps.clear();
        allApps.addAll(apps);
        visibleApps.clear();
        visibleApps.addAll(apps);
        selectedPackages.clear();
        selectedPackages.addAll(AppPreferences.selectedPackages(context));
        notifyDataSetChanged();
    }

    public void filter(String query) {
        visibleApps.clear();
        for (AppEntry app : allApps) {
            if (app.matches(query)) {
                visibleApps.add(app);
            }
        }
        notifyDataSetChanged();
    }

    public void refreshSelections() {
        selectedPackages.clear();
        selectedPackages.addAll(AppPreferences.selectedPackages(context));
        notifyDataSetChanged();
    }

    public void toggle(int position) {
        if (position < 0 || position >= visibleApps.size()) {
            return;
        }
        AppEntry app = visibleApps.get(position);
        boolean selected = !selectedPackages.contains(app.packageName);
        if (selected) {
            selectedPackages.add(app.packageName);
        } else {
            selectedPackages.remove(app.packageName);
        }
        AppPreferences.setPackageSelected(context, app.packageName, selected);
        notifyDataSetChanged();
        listener.onSelectionChanged(app.packageName, selected);
    }

    @Override
    public int getCount() {
        return visibleApps.size();
    }

    @Override
    public AppEntry getItem(int position) {
        return visibleApps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).packageName.hashCode();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        RowHolder holder;
        if (convertView == null) {
            holder = createRow(parent);
            convertView = holder.root;
            convertView.setTag(holder);
        } else {
            holder = (RowHolder) convertView.getTag();
        }

        AppEntry app = getItem(position);
        holder.icon.setImageDrawable(app.icon);
        holder.label.setText(app.label);
        holder.packageName.setText(app.packageName);
        holder.checkBox.setChecked(selectedPackages.contains(app.packageName));
        return convertView;
    }

    private RowHolder createRow(ViewGroup parent) {
        int horizontalPadding = dp(16);
        int verticalPadding = dp(10);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        root.setMinimumHeight(dp(70));
        root.setBackgroundColor(context.getColor(R.color.surface));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(14), 0, dp(8), 0);
        root.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView label = new TextView(context);
        label.setTextColor(context.getColor(R.color.text_primary));
        label.setTextSize(16);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setMaxLines(1);
        textColumn.addView(label);

        TextView packageName = new TextView(context);
        packageName.setTextColor(context.getColor(R.color.text_secondary));
        packageName.setTextSize(11);
        packageName.setMaxLines(1);
        textColumn.addView(packageName);

        CheckBox checkBox = new CheckBox(context);
        checkBox.setClickable(false);
        checkBox.setFocusable(false);
        root.addView(checkBox, new LinearLayout.LayoutParams(dp(48), dp(48)));

        return new RowHolder(root, icon, label, packageName, checkBox);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class RowHolder {
        final LinearLayout root;
        final ImageView icon;
        final TextView label;
        final TextView packageName;
        final CheckBox checkBox;

        RowHolder(
                LinearLayout root,
                ImageView icon,
                TextView label,
                TextView packageName,
                CheckBox checkBox
        ) {
            this.root = root;
            this.icon = icon;
            this.label = label;
            this.packageName = packageName;
            this.checkBox = checkBox;
        }
    }
}
