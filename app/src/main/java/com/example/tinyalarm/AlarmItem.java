package com.example.tinyalarm;

final class AlarmItem {
    final int id;
    int hour;
    int minute;
    boolean enabled;
    String label;

    AlarmItem(int id, int hour, int minute, boolean enabled, String label) {
        this.id = id;
        this.hour = hour;
        this.minute = minute;
        this.enabled = enabled;
        this.label = label;
    }

    String timeText() {
        return String.format("%02d:%02d", hour, minute);
    }
}
