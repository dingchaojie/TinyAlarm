package com.example.tinyalarm;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class AlarmStore {
    static final int SLOT_COUNT = 5;
    private static final String PREFS = "alarms";
    private static final String IDS = "ids";
    private static final String NEXT_ID = "next_id";
    private static final String LAST_FIRED = "last_fired";
    private static final String LAST_FIRED_ID = "last_fired_id";
    private static final String LAST_FIRED_AT = "last_fired_at";
    private static final String DEBUG_LOG = "debug_log";
    private static final String EXACT_ALLOWED = "exact_allowed";
    private static final String EXACT_SETTINGS_OPENED = "exact_settings_opened";
    private static final String RINGING_ALARM_ID = "ringing_alarm_id";
    private static final int MAX_DEBUG_LINES = 80;

    private AlarmStore() {
    }

    static List<AlarmItem> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureInitialized(prefs);
        List<AlarmItem> alarms = new ArrayList<>();
        for (int id : alarmIds(prefs)) {
            alarms.add(read(prefs, id));
        }
        Collections.sort(alarms, new Comparator<AlarmItem>() {
            @Override
            public int compare(AlarmItem left, AlarmItem right) {
                int leftMinutes = left.hour * 60 + left.minute;
                int rightMinutes = right.hour * 60 + right.minute;
                if (leftMinutes != rightMinutes) {
                    return leftMinutes - rightMinutes;
                }
                return left.id - right.id;
            }
        });
        return alarms;
    }

    static AlarmItem get(Context context, int id) {
        for (AlarmItem item : load(context)) {
            if (item.id == id) {
                return item;
            }
        }
        return null;
    }

    static void save(Context context, AlarmItem item) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(key(item.id, "hour"), item.hour)
                .putInt(key(item.id, "minute"), item.minute)
                .putBoolean(key(item.id, "enabled"), item.enabled)
                .putString(key(item.id, "label"), item.label)
                .apply();
    }

    static void log(Context context, String message) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
        String oldLog = prefs.getString(DEBUG_LOG, "");
        String combined = timestamp + "  " + message + (oldLog == null || oldLog.isEmpty() ? "" : "\n" + oldLog);
        String[] lines = combined.split("\n");
        StringBuilder trimmed = new StringBuilder();
        for (int i = 0; i < lines.length && i < MAX_DEBUG_LINES; i++) {
            if (trimmed.length() > 0) {
                trimmed.append('\n');
            }
            trimmed.append(lines[i]);
        }
        prefs.edit().putString(DEBUG_LOG, trimmed.toString()).apply();
    }

    static String debugLog(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DEBUG_LOG, "");
    }

    static AlarmItem add(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureInitialized(prefs);
        if (alarmIds(prefs).size() >= SLOT_COUNT) {
            return null;
        }
        int id = prefs.getInt(NEXT_ID, 1);
        AlarmItem item = new AlarmItem(id, 7, 0, false, "New alarm");
        String ids = prefs.getString(IDS, "");
        String nextIds = ids == null || ids.isEmpty() ? String.valueOf(id) : ids + "," + id;
        prefs.edit()
                .putString(IDS, nextIds)
                .putInt(NEXT_ID, id + 1)
                .putInt(key(id, "hour"), item.hour)
                .putInt(key(id, "minute"), item.minute)
                .putBoolean(key(id, "enabled"), item.enabled)
                .putString(key(id, "label"), item.label)
                .apply();
        log(context, "Added alarm " + id + " at " + item.timeText());
        return item;
    }

    static void delete(Context context, int id) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        StringBuilder ids = new StringBuilder();
        for (int existingId : alarmIds(prefs)) {
            if (existingId != id) {
                if (ids.length() > 0) {
                    ids.append(',');
                }
                ids.append(existingId);
            }
        }
        SharedPreferences.Editor editor = prefs.edit()
                .putString(IDS, ids.toString())
                .remove(key(id, "hour"))
                .remove(key(id, "minute"))
                .remove(key(id, "enabled"))
                .remove(key(id, "label"))
                .remove(key(id, "fired_year"))
                .remove(key(id, "fired_day"))
                .remove(key(id, "fired_hour"))
                .remove(key(id, "fired_minute"));
        if (prefs.getInt(RINGING_ALARM_ID, -1) == id) {
            editor.remove(RINGING_ALARM_ID);
        }
        if (ids.length() == 0) {
            editor.remove(LAST_FIRED);
            editor.remove(LAST_FIRED_ID);
            editor.remove(LAST_FIRED_AT);
            editor.remove(RINGING_ALARM_ID);
            editor.putInt(NEXT_ID, 1);
        }
        editor.apply();
        log(context, "Deleted alarm " + id);
    }

    static void markFired(Context context, int id) {
        Calendar now = Calendar.getInstance();
        String firedText = "Alarm " + id + " fired at " + new java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(new java.util.Date());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(LAST_FIRED, firedText)
                .putInt(LAST_FIRED_ID, id)
                .putLong(LAST_FIRED_AT, System.currentTimeMillis())
                .putInt(key(id, "fired_year"), now.get(Calendar.YEAR))
                .putInt(key(id, "fired_day"), now.get(Calendar.DAY_OF_YEAR))
                .putInt(key(id, "fired_hour"), now.get(Calendar.HOUR_OF_DAY))
                .putInt(key(id, "fired_minute"), now.get(Calendar.MINUTE))
                .apply();
        log(context, firedText);
    }

    static String lastFiredText(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_FIRED, "");
    }

    static boolean firedThisMinute(Context context, int id) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Calendar now = Calendar.getInstance();
        return prefs.getInt(key(id, "fired_year"), -1) == now.get(Calendar.YEAR)
                && prefs.getInt(key(id, "fired_day"), -1) == now.get(Calendar.DAY_OF_YEAR)
                && prefs.getInt(key(id, "fired_hour"), -1) == now.get(Calendar.HOUR_OF_DAY)
                && prefs.getInt(key(id, "fired_minute"), -1) == now.get(Calendar.MINUTE);
    }

    static boolean exactAlarmWasAllowed(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(EXACT_ALLOWED, false);
    }

    static void setExactAlarmWasAllowed(Context context, boolean allowed) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(EXACT_ALLOWED, allowed).apply();
    }

    static boolean exactAlarmSettingsOpened(Context context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return true;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(EXACT_SETTINGS_OPENED, false);
    }

    static void setExactAlarmSettingsOpened(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(EXACT_SETTINGS_OPENED, true).apply();
    }

    static void setRingingAlarm(Context context, int id) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(RINGING_ALARM_ID, id).apply();
    }

    static void clearRingingAlarm(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(RINGING_ALARM_ID).apply();
    }

    static int ringingAlarmId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(RINGING_ALARM_ID, -1);
    }

    static int recentFiredAlarmId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long firedAt = prefs.getLong(LAST_FIRED_AT, 0L);
        if (firedAt <= 0L || System.currentTimeMillis() - firedAt > 10 * 60_000L) {
            return -1;
        }
        return prefs.getInt(LAST_FIRED_ID, -1);
    }

    static boolean handledOccurrence(Context context, int id, long triggerAt) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long firedAt = prefs.getLong(LAST_FIRED_AT, 0L);
        return firedAt >= triggerAt
                && prefs.getInt(LAST_FIRED_ID, -1) == id;
    }

    static boolean firedRecently(Context context, int id, long windowMs) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long firedAt = prefs.getLong(LAST_FIRED_AT, 0L);
        return firedAt > 0L
                && System.currentTimeMillis() - firedAt <= windowMs
                && prefs.getInt(LAST_FIRED_ID, -1) == id;
    }

    private static void ensureInitialized(SharedPreferences prefs) {
        if (prefs.contains(IDS)) {
            return;
        }
        prefs.edit()
                .putString(IDS, "")
                .putInt(NEXT_ID, 1)
                .apply();
    }

    private static List<Integer> alarmIds(SharedPreferences prefs) {
        ensureInitialized(prefs);
        List<Integer> ids = new ArrayList<>();
        String rawIds = prefs.getString(IDS, "");
        if (rawIds == null || rawIds.trim().isEmpty()) {
            return ids;
        }
        String[] parts = rawIds.split(",");
        for (String part : parts) {
            try {
                ids.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    private static AlarmItem read(SharedPreferences prefs, int id) {
        return new AlarmItem(
                id,
                prefs.getInt(key(id, "hour"), 7),
                prefs.getInt(key(id, "minute"), 0),
                prefs.getBoolean(key(id, "enabled"), false),
                prefs.getString(key(id, "label"), "Alarm")
        );
    }

    private static String key(int id, String field) {
        return "alarm_" + id + "_" + field;
    }
}
