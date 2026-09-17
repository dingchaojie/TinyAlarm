package com.example.tinyalarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

final class AlarmScheduler {
    static final String EXTRA_ALARM_ID = "alarm_id";
    static final String EXTRA_TRIGGER_SOURCE = "trigger_source";
    private static final String SOURCE_DAILY = "daily";
    private static final String SOURCE_DAILY_CLOCK = "daily_clock";
    private static final String SOURCE_BACKUP = "backup";
    private static final String SOURCE_SNOOZE = "snooze";
    private static final String SOURCE_SNOOZE_CLOCK = "snooze_clock";
    private static final String ACTION_DAILY = "com.example.tinyalarm.DAILY_ALARM";
    private static final String ACTION_DAILY_CLOCK = "com.example.tinyalarm.DAILY_ALARM_CLOCK";
    private static final String ACTION_BACKUP = "com.example.tinyalarm.BACKUP_ALARM";
    private static final String ACTION_SNOOZE = "com.example.tinyalarm.SNOOZE_ALARM";
    private static final String ACTION_SNOOZE_CLOCK = "com.example.tinyalarm.SNOOZE_ALARM_CLOCK";
    private static final String ACTION_SNOOZE_BACKUP = "com.example.tinyalarm.SNOOZE_BACKUP_ALARM";
    private static final int REQUEST_BASE = 31000;

    private AlarmScheduler() {
    }

    static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    static Intent exactAlarmSettingsIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    static void schedule(Context context, AlarmItem item) {
        scheduleBackup(context, item);
        refreshAlarmClockWindow(context);
    }

    private static void scheduleBackup(Context context, AlarmItem item) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null || !item.enabled) {
            return;
        }
        PendingIntent backupIntent = broadcastPendingIntent(context, item.id, PendingIntent.FLAG_UPDATE_CURRENT);
        long triggerAt = nextTriggerMillis(item.hour, item.minute);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, backupIntent);
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, backupIntent);
            }
            AlarmStore.log(context, "Scheduled exact receiver backup alarm " + item.id);
        } catch (SecurityException e) {
            AlarmStore.log(context, "Schedule failed: exact alarm permission/security");
            Toast.makeText(context, "Allow exact alarms for Tiny Alarm", Toast.LENGTH_LONG).show();
        }
    }

    static void cancel(Context context, int id) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        PendingIntent ringIntent = dailyRingPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (ringIntent != null) {
            manager.cancel(ringIntent);
            AlarmStore.log(context, "Cancelled daily alarm " + id);
        }
        PendingIntent dailyBroadcastIntent = dailyBroadcastPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (dailyBroadcastIntent != null) {
            manager.cancel(dailyBroadcastIntent);
            AlarmStore.log(context, "Cancelled daily receiver alarm " + id);
        }
        PendingIntent snoozeIntent = snoozeRingPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (snoozeIntent != null) {
            manager.cancel(snoozeIntent);
            AlarmStore.log(context, "Cancelled snooze alarm " + id);
        }
        PendingIntent dailyAlarmClockIntent = dailyAlarmClockPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (dailyAlarmClockIntent != null) {
            manager.cancel(dailyAlarmClockIntent);
            AlarmStore.log(context, "Cancelled alarm-clock receiver alarm " + id);
        }
        PendingIntent snoozeAlarmClockIntent = snoozeAlarmClockPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (snoozeAlarmClockIntent != null) {
            manager.cancel(snoozeAlarmClockIntent);
            AlarmStore.log(context, "Cancelled snooze alarm-clock receiver alarm " + id);
        }
        PendingIntent snoozeBackupIntent = snoozeBackupBroadcastPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (snoozeBackupIntent != null) {
            manager.cancel(snoozeBackupIntent);
            AlarmStore.log(context, "Cancelled snooze backup alarm " + id);
        }
        PendingIntent broadcastIntent = broadcastPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (broadcastIntent != null) {
            manager.cancel(broadcastIntent);
            AlarmStore.log(context, "Cancelled backup alarm " + id);
        }
        PendingIntent legacyBroadcastIntent = legacyBroadcastPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (legacyBroadcastIntent != null) {
            manager.cancel(legacyBroadcastIntent);
            AlarmStore.log(context, "Cancelled legacy backup alarm " + id);
        }
        refreshAlarmClockWindow(context);
    }

    static void rescheduleEnabled(Context context) {
        List<AlarmItem> alarms = AlarmStore.load(context);
        for (AlarmItem item : alarms) {
            scheduleBackup(context, item);
        }
        refreshAlarmClockWindow(context);
    }

    static void refreshAlarmClockWindow(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }

        List<AlarmItem> alarms = AlarmStore.load(context);
        for (AlarmItem item : alarms) {
            PendingIntent existing = dailyAlarmClockPendingIntent(context, item.id, PendingIntent.FLAG_NO_CREATE);
            if (existing != null) {
                manager.cancel(existing);
            }
        }

        AlarmItem nextItem = null;
        long nextTriggerAt = Long.MAX_VALUE;
        for (AlarmItem item : alarms) {
            if (!item.enabled) {
                continue;
            }
            long triggerAt = nextTriggerMillis(item.hour, item.minute);
            if (triggerAt < nextTriggerAt) {
                nextTriggerAt = triggerAt;
                nextItem = item;
            }
        }
        if (nextItem == null) {
            return;
        }

        PendingIntent ringIntent = dailyAlarmClockPendingIntent(context, nextItem.id, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent showIntent = showPendingIntent(context, nextItem.id);
        try {
            manager.setAlarmClock(new AlarmManager.AlarmClockInfo(nextTriggerAt, showIntent), ringIntent);
            AlarmStore.log(context, "Scheduled alarm-clock window alarm " + nextItem.id + " for " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(nextTriggerAt)));
        } catch (SecurityException e) {
            AlarmStore.log(context, "Alarm-clock window schedule failed: exact alarm permission/security");
        }
    }

    static String nextRingText(Context context, AlarmItem item) {
        if (!item.enabled) {
            return "Off";
        }
        if (isCurrentMinute(item) && !AlarmStore.firedThisMinute(context, item.id)) {
            return "Due now";
        }
        long triggerAt = nextTriggerMillis(item.hour, item.minute);
        Date date = new Date(triggerAt);
        return timeLeftText(triggerAt) + " - " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date);
    }

    static String timeLeftText(AlarmItem item) {
        if (!item.enabled) {
            return "Alarm off";
        }
        return timeLeftText(nextTriggerMillis(item.hour, item.minute));
    }

    static void snooze(Context context, int id, int minutes) {
        if (id <= 0) {
            AlarmStore.log(context, "Snooze ignored: missing alarm id");
            return;
        }
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        long triggerAt = System.currentTimeMillis() + minutes * 60_000L;
        PendingIntent ringIntent = snoozeAlarmClockPendingIntent(context, id, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent backupIntent = snoozeBackupBroadcastPendingIntent(context, id, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent showIntent = showPendingIntent(context, id);
        try {
            manager.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, showIntent), ringIntent);
            AlarmStore.log(context, "SNOOZE_WINDOW_SCHEDULED alarm " + id + " until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(triggerAt)));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, backupIntent);
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, backupIntent);
            }
            AlarmStore.log(context, "SNOOZE_RECEIVER_BACKUP_SCHEDULED alarm " + id);
        } catch (SecurityException e) {
            AlarmStore.log(context, "SNOOZE_FAILED exact alarm permission/security");
            Toast.makeText(context, "Allow exact alarms for Tiny Alarm", Toast.LENGTH_LONG).show();
        }
    }

    private static void cancelDaily(Context context, int id) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        PendingIntent ringIntent = dailyRingPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (ringIntent != null) {
            manager.cancel(ringIntent);
            AlarmStore.log(context, "Cancelled daily alarm " + id);
        }
        PendingIntent dailyBroadcastIntent = dailyBroadcastPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (dailyBroadcastIntent != null) {
            manager.cancel(dailyBroadcastIntent);
            AlarmStore.log(context, "Cancelled daily receiver alarm " + id);
        }
        PendingIntent dailyAlarmClockIntent = dailyAlarmClockPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (dailyAlarmClockIntent != null) {
            manager.cancel(dailyAlarmClockIntent);
            AlarmStore.log(context, "Cancelled alarm-clock receiver alarm " + id);
        }
        PendingIntent broadcastIntent = broadcastPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (broadcastIntent != null) {
            manager.cancel(broadcastIntent);
            AlarmStore.log(context, "Cancelled backup alarm " + id);
        }
        PendingIntent legacyBroadcastIntent = legacyBroadcastPendingIntent(context, id, PendingIntent.FLAG_NO_CREATE);
        if (legacyBroadcastIntent != null) {
            manager.cancel(legacyBroadcastIntent);
            AlarmStore.log(context, "Cancelled legacy backup alarm " + id);
        }
    }

    static long nextTriggerMillis(int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar.getTimeInMillis();
    }

    private static String timeLeftText(long triggerAt) {
        long remainingMinutes = Math.max(1L, (triggerAt - System.currentTimeMillis() + 59_999L) / 60_000L);
        long days = remainingMinutes / (24L * 60L);
        long hours = (remainingMinutes % (24L * 60L)) / 60L;
        long minutes = remainingMinutes % 60L;
        if (days > 0) {
            return "Rings in " + days + " day " + hours + " hr " + minutes + " min";
        }
        if (hours > 0) {
            return "Rings in " + hours + " hr " + minutes + " min";
        }
        return "Rings in " + minutes + " min";
    }

    private static boolean isCurrentMinute(AlarmItem item) {
        Calendar now = Calendar.getInstance();
        return item.hour == now.get(Calendar.HOUR_OF_DAY) && item.minute == now.get(Calendar.MINUTE);
    }

    private static PendingIntent broadcastPendingIntent(Context context, int id, int flags) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(ACTION_BACKUP);
        intent.putExtra(EXTRA_ALARM_ID, id);
        intent.putExtra(EXTRA_TRIGGER_SOURCE, SOURCE_BACKUP);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_BASE + 4000 + id,
                intent,
                flags | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent dailyBroadcastPendingIntent(Context context, int id, int flags) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(ACTION_DAILY);
        intent.putExtra(EXTRA_ALARM_ID, id);
        intent.putExtra(EXTRA_TRIGGER_SOURCE, SOURCE_DAILY);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_BASE + id,
                intent,
                flags | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent dailyAlarmClockPendingIntent(Context context, int id, int flags) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(ACTION_DAILY_CLOCK);
        intent.putExtra(EXTRA_ALARM_ID, id);
        intent.putExtra(EXTRA_TRIGGER_SOURCE, SOURCE_DAILY_CLOCK);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_BASE + 6000 + id,
                intent,
                flags | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent legacyBroadcastPendingIntent(Context context, int id, int flags) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra(EXTRA_ALARM_ID, id);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_BASE + id,
                intent,
                flags | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent snoozeBroadcastPendingIntent(Context context, int id, int flags) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(ACTION_SNOOZE);
        intent.putExtra(EXTRA_ALARM_ID, id);
        intent.putExtra(EXTRA_TRIGGER_SOURCE, SOURCE_SNOOZE);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_BASE + 2000 + id,
                intent,
                flags | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent snoozeAlarmClockPendingIntent(Context context, int id, int flags) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(ACTION_SNOOZE_CLOCK);
        intent.putExtra(EXTRA_ALARM_ID, id);
        intent.putExtra(EXTRA_TRIGGER_SOURCE, SOURCE_SNOOZE_CLOCK);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_BASE + 7000 + id,
                intent,
                flags | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent snoozeBackupBroadcastPendingIntent(Context context, int id, int flags) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(ACTION_SNOOZE_BACKUP);
        intent.putExtra(EXTRA_ALARM_ID, id);
        intent.putExtra(EXTRA_TRIGGER_SOURCE, SOURCE_SNOOZE);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_BASE + 3000 + id,
                intent,
                flags | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent dailyRingPendingIntent(Context context, int id, int flags) {
        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), context.getPackageName() + ".RingActivity");
        intent.putExtra(EXTRA_ALARM_ID, id);
        intent.putExtra("from_alarm", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context,
                REQUEST_BASE + id,
                intent,
                flags | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent snoozeRingPendingIntent(Context context, int id, int flags) {
        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), context.getPackageName() + ".RingActivity");
        intent.putExtra(EXTRA_ALARM_ID, id);
        intent.putExtra("from_alarm", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context,
                REQUEST_BASE + 2000 + id,
                intent,
                flags | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static PendingIntent showPendingIntent(Context context, int id) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                context,
                REQUEST_BASE + 1000 + id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
