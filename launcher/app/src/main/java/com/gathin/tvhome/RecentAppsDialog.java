package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.Dialog;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recently used apps as a row of cards, from usage stats (PACKAGE_USAGE_STATS, granted once with appops).
 * OK opens a card's app where it was left; Up closes it, stopping its background process so the next open starts
 * fresh. The last card, All apps, opens the app library.
 */
final class RecentAppsDialog extends Dialog {
    private static final long WINDOW_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_APPS = 10;
    /** Package to the time it was closed with Up; it is listed again only after it is used past that time. */
    private static final String CLOSED_PREFS = "recent_apps_closed";

    private final Context context;
    private final Runnable allApps;
    private final List<String> packages = new ArrayList<>();
    private Adapter adapter;

    RecentAppsDialog(Context context, Runnable allApps) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.context = context;
        this.allApps = allApps;
        setContentView(R.layout.dialog_recent_apps);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);   // the launcher stays visible, dimmed
        if (Build.VERSION.SDK_INT >= 31 && context.getSystemService(WindowManager.class).isCrossWindowBlurEnabled()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.setBlurBehindRadius(48);
            getWindow().setAttributes(lp);
        }
        packages.addAll(recentPackages(context));
        findViewById(R.id.recent_empty).setVisibility(packages.isEmpty() ? View.VISIBLE : View.GONE);
        RecyclerView list = findViewById(R.id.recent_list);
        list.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.post(() -> {
            RecyclerView.ViewHolder first = list.findViewHolderForAdapterPosition(0);
            if (first != null) first.itemView.requestFocus();
        });
    }

    /** Packages used in the last day, newest first, that the TV can open; never this launcher. */
    static List<String> recentPackages(Context ctx) {
        UsageStatsManager usage = ctx.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        Map<String, Long> lastUsed = new HashMap<>();
        UsageEvents events = usage.queryEvents(now - WINDOW_MS, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastUsed.put(event.getPackageName(), event.getTimeStamp());
            }
        }
        PackageManager pm = ctx.getPackageManager();
        SharedPreferences closed = ctx.getSharedPreferences(CLOSED_PREFS, Context.MODE_PRIVATE);
        List<Map.Entry<String, Long>> newest = new ArrayList<>(lastUsed.entrySet());
        newest.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> entry : newest) {
            String pkg = entry.getKey();
            if (pkg.equals(ctx.getPackageName())) continue;
            if (entry.getValue() <= closed.getLong(pkg, 0L)) continue;   // closed since its last use
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) continue;
            out.add(pkg);
            if (out.size() == MAX_APPS) break;
        }
        return out;
    }

    private void open(String pkg) {
        PackageManager pm = context.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        dismiss();
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void close(int position) {
        String pkg = packages.remove(position);
        context.getSharedPreferences(CLOSED_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(pkg, System.currentTimeMillis()).apply();
        context.getSystemService(ActivityManager.class).killBackgroundProcesses(pkg);
        adapter.notifyItemRemoved(position);
        findViewById(R.id.recent_empty).setVisibility(packages.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recent_app, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            PackageManager pm = context.getPackageManager();
            if (position == packages.size()) {
                int pad = Math.round(26 * context.getResources().getDisplayMetrics().density);
                h.icon.setPadding(pad, pad, pad, pad);
                h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.icon.setImageResource(R.drawable.ic_apps);
                h.label.setText(context.getString(R.string.app_library));
            } else {
                String pkg = packages.get(position);
                try {
                    ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                    h.icon.setPadding(0, 0, 0, 0);
                    h.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    h.icon.setImageDrawable(pm.getApplicationIcon(info));
                    h.label.setText(pm.getApplicationLabel(info));
                } catch (PackageManager.NameNotFoundException e) {
                    h.icon.setImageDrawable(null);
                    h.label.setText(pkg);
                }
            }
        }

        @Override
        public int getItemCount() {
            return packages.size() + 1;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;

            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                label = v.findViewById(R.id.label);
                icon.setClipToOutline(true);   // square icons take the tile's rounded corners
                v.setOnFocusChangeListener((view, focused) -> {
                    float scale = focused ? 1.06f : 1f;
                    view.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
                });
                v.setOnKeyListener((view, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                    int position = getAdapterPosition();
                    boolean last = position == packages.size();
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        if (last) {
                            dismiss();
                            allApps.run();
                        } else {
                            open(packages.get(position));
                        }
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP && !last) {
                        close(position);
                        return true;
                    }
                    return false;
                });
            }
        }
    }
}
