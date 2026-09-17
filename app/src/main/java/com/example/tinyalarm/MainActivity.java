package com.example.tinyalarm;

import android.Manifest;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;

public class MainActivity extends android.app.Activity {
    private static final long MISSED_ALARM_WINDOW_MS = 10 * 60_000L;
    private static final long DUPLICATE_ALARM_WINDOW_MS = 20_000L;
    private LinearLayout list;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            checkDueAlarms();
            buildUi();
            refreshHandler.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        maybeAskNotificationPermission();
        refreshAlarmPermissionState();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAlarmPermissionState();
        buildUi();
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshHandler.postDelayed(refreshRunnable, 1_000L);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(0xFFF8F7F2);
        scrollView.addView(root);

        TextView title = text("Tiny Alarm", 32, 0xFF161616);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        if (shouldShowExactAlarmPrompt()) {
            TextView warning = text("Exact alarm permission is off", 14, 0xFFB42318);
            warning.setGravity(Gravity.CENTER_HORIZONTAL);
            root.addView(warning, matchWrapWithTop(8));

            Button permission = button("Allow exact alarms");
            permission.setOnClickListener(v -> openExactAlarmSettings());
            root.addView(permission, matchWrapWithTop(8));
        }

        if (!ignoresBatteryOptimizations()) {
            TextView warning = text("Battery optimization may delay alarms", 14, 0xFFB42318);
            warning.setGravity(Gravity.CENTER_HORIZONTAL);
            root.addView(warning, matchWrapWithTop(8));

            Button permission = button("Allow unrestricted battery");
            permission.setOnClickListener(v -> openBatterySettings());
            root.addView(permission, matchWrapWithTop(8));
        }

        if (!notificationsEnabled()) {
            TextView warning = text("Alarm notifications are off", 14, 0xFFB42318);
            warning.setGravity(Gravity.CENTER_HORIZONTAL);
            root.addView(warning, matchWrapWithTop(8));

            Button permission = button("Allow alarm notifications");
            permission.setOnClickListener(v -> openNotificationSettings());
            root.addView(permission, matchWrapWithTop(8));
        }

        String lastFired = AlarmStore.lastFiredText(this);
        if (lastFired != null && !lastFired.isEmpty()) {
            TextView status = text(lastFired, 14, 0xFF6A6A6A);
            status.setGravity(Gravity.CENTER_HORIZONTAL);
            root.addView(status, matchWrapWithTop(8));
        }

        addRingingControls(root);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, matchWrapWithTop(18));

        Button addAlarm = button("+ Add alarm");
        addAlarm.setTextSize(18);
        addAlarm.setOnClickListener(v -> {
            AlarmItem item = AlarmStore.add(this);
            if (item == null) {
                Toast.makeText(this, "Maximum 5 alarms", Toast.LENGTH_SHORT).show();
            } else {
                chooseTime(item);
            }
        });
        List<AlarmItem> alarms = AlarmStore.load(this);
        if (alarms.size() < AlarmStore.SLOT_COUNT) {
            root.addView(addAlarm, matchWrapWithTop(18));
        }
        if (alarms.isEmpty()) {
            TextView empty = text("No alarms yet", 16, 0xFF6A6A6A);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            list.addView(empty, matchWrapWithTop(20));
        }
        for (AlarmItem alarm : alarms) {
            addAlarmRow(alarm);
        }

        addDebugLog(root);

        setContentView(scrollView);
    }

    private void addAlarmRow(AlarmItem alarm) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundColor(0xFFFFFFFF);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(top, matchWrap());

        Button time = button(alarm.timeText());
        time.setTextSize(28);
        time.setOnClickListener(v -> chooseTime(alarm));
        top.addView(time, new LinearLayout.LayoutParams(0, dp(64), 1));

        Switch enabled = new Switch(this);
        enabled.setChecked(alarm.enabled);
        enabled.setOnCheckedChangeListener((CompoundButton buttonView, boolean isChecked) -> {
            alarm.enabled = isChecked;
            AlarmStore.save(this, alarm);
            if (isChecked) {
                AlarmStore.log(this, "Enabled alarm " + alarm.id + " at " + alarm.timeText());
                AlarmScheduler.schedule(this, alarm);
            } else {
                AlarmStore.log(this, "Disabled alarm " + alarm.id);
                AlarmScheduler.cancel(this, alarm.id);
            }
            buildUi();
        });
        top.addView(enabled);

        EditText label = new EditText(this);
        label.setSingleLine(true);
        label.setText(alarm.label);
        label.setTextSize(16);
        label.setHint("Label");
        label.setOnFocusChangeListener((View v, boolean hasFocus) -> {
            if (!hasFocus) {
                alarm.label = label.getText().toString().trim();
                AlarmStore.save(this, alarm);
            }
        });
        card.addView(label, matchWrapWithTop(8));

        TextView next = text(AlarmScheduler.nextRingText(this, alarm), 14, 0xFF6A6A6A);
        card.addView(next, matchWrapWithTop(8));

        Button delete = button("Delete");
        delete.setTextColor(0xFFB42318);
        delete.setOnClickListener(v -> {
            AlarmScheduler.cancel(this, alarm.id);
            AlarmStore.delete(this, alarm.id);
            AlarmScheduler.refreshAlarmClockWindow(this);
            Toast.makeText(this, "Alarm deleted", Toast.LENGTH_SHORT).show();
            buildUi();
        });
        card.addView(delete, matchWrapWithTop(8));

        LinearLayout.LayoutParams params = matchWrapWithTop(12);
        list.addView(card, params);
    }

    private void chooseTime(AlarmItem alarm) {
        TimePickerDialog dialog = new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> {
                    alarm.hour = hourOfDay;
                    alarm.minute = minute;
                    alarm.enabled = true;
                    AlarmStore.save(this, alarm);
                    AlarmStore.log(this, "Set alarm " + alarm.id + " to " + alarm.timeText());
                    AlarmScheduler.cancel(this, alarm.id);
                    AlarmScheduler.schedule(this, alarm);
                    Toast.makeText(this, AlarmScheduler.timeLeftText(alarm), Toast.LENGTH_LONG).show();
                    buildUi();
                },
                alarm.hour,
                alarm.minute,
                true
        );
        dialog.show();
    }

    private void maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    private void openExactAlarmSettings() {
        AlarmStore.log(this, "Opening exact alarm permission settings");
        AlarmStore.setExactAlarmSettingsOpened(this);
        try {
            startActivity(AlarmScheduler.exactAlarmSettingsIntent(this));
        } catch (ActivityNotFoundException e) {
            AlarmStore.log(this, "Exact alarm settings screen unavailable");
            Toast.makeText(this, "Open Android settings and allow Alarms & reminders", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshAlarmPermissionState() {
        boolean allowed = AlarmScheduler.canScheduleExact(this);
        boolean wasAllowed = AlarmStore.exactAlarmWasAllowed(this);
        if (allowed && !wasAllowed) {
            AlarmStore.log(this, "Exact alarm permission became allowed; rescheduling enabled alarms");
            AlarmScheduler.rescheduleEnabled(this);
        }
        AlarmStore.setExactAlarmWasAllowed(this, allowed);
    }

    private boolean shouldShowExactAlarmPrompt() {
        return !AlarmScheduler.canScheduleExact(this) || !AlarmStore.exactAlarmSettingsOpened(this);
    }

    private boolean ignoresBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openBatterySettings() {
        AlarmStore.log(this, "Opening battery optimization settings");
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Set Tiny Alarm battery to Unrestricted in Android settings", Toast.LENGTH_LONG).show();
        }
    }

    private boolean notificationsEnabled() {
        if (Build.VERSION.SDK_INT < 24) {
            return true;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        return manager == null || manager.areNotificationsEnabled();
    }

    private void openNotificationSettings() {
        AlarmStore.log(this, "Opening notification settings");
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            openAppSettings();
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void addRingingControls(LinearLayout root) {
        int ringingAlarmId = AlarmStore.ringingAlarmId(this);
        if (ringingAlarmId <= 0) {
            return;
        }
        final int alarmIdForControls = ringingAlarmId;
        TextView ringing = text("Alarm is ringing", 16, 0xFFB42318);
        ringing.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(ringing, matchWrapWithTop(12));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button snooze = button("Snooze 5 min");
        snooze.setOnClickListener(v -> {
            Intent intent = new Intent(this, AlarmService.class);
            intent.setAction(AlarmService.ACTION_SNOOZE);
            intent.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmIdForControls);
            startService(intent);
            AlarmStore.clearRingingAlarm(this);
            buildUi();
        });
        row.addView(snooze, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button stop = button("Stop");
        stop.setTextColor(0xFFB42318);
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, AlarmService.class);
            intent.setAction(AlarmService.ACTION_STOP);
            intent.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmIdForControls);
            startService(intent);
            stopService(new Intent(this, AlarmService.class));
            AlarmStore.clearRingingAlarm(this);
            buildUi();
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        stopParams.leftMargin = dp(8);
        row.addView(stop, stopParams);

        root.addView(row, matchWrapWithTop(8));
    }

    private TextView text(String value, int sp, int color) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        return textView;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void checkDueAlarms() {
        long now = System.currentTimeMillis();
        for (AlarmItem alarm : AlarmStore.load(this)) {
            long triggerAt = AlarmScheduler.nextTriggerMillis(alarm.hour, alarm.minute);
            if (triggerAt > now) {
                Calendar today = Calendar.getInstance();
                today.set(Calendar.HOUR_OF_DAY, alarm.hour);
                today.set(Calendar.MINUTE, alarm.minute);
                today.set(Calendar.SECOND, 0);
                today.set(Calendar.MILLISECOND, 0);
                triggerAt = today.getTimeInMillis();
            }
            if (alarm.enabled
                    && now >= triggerAt
                    && now < triggerAt + MISSED_ALARM_WINDOW_MS
                    && !AlarmStore.handledOccurrence(this, alarm.id, triggerAt)
                    && !AlarmStore.firedRecently(this, alarm.id, DUPLICATE_ALARM_WINDOW_MS)
                    && !AlarmStore.firedThisMinute(this, alarm.id)) {
                AlarmStore.log(this, "MISSED_DUE_CHECK_TRIGGER alarm " + alarm.id);
                AlarmStore.markFired(this, alarm.id);
                AlarmScheduler.schedule(this, alarm);
                AlarmService.start(this, alarm.id);
                return;
            }
        }
    }

    private void addDebugLog(LinearLayout root) {
        String log = AlarmStore.debugLog(this);
        if (log == null || log.isEmpty()) {
            return;
        }
        TextView title = text("Debug log", 16, 0xFF161616);
        root.addView(title, matchWrapWithTop(24));

        TextView body = text(log, 12, 0xFF6A6A6A);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setPadding(dp(10), dp(10), dp(10), dp(10));
        body.setBackgroundColor(0xFFFFFFFF);
        root.addView(body, matchWrapWithTop(8));
    }

}
