package com.example.tinyalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, 1);
        String source = intent.getStringExtra(AlarmScheduler.EXTRA_TRIGGER_SOURCE);
        if (source == null || source.isEmpty()) {
            source = "unknown";
        }
        if (AlarmStore.firedThisMinute(context, id)) {
            AlarmStore.log(context, "TRIGGER_DUPLICATE ignored receiver alarm " + id + " source " + source);
            return;
        }
        AlarmStore.log(context, "TRIGGER receiver alarm " + id + " source " + source);
        AlarmStore.markFired(context, id);
        AlarmItem item = AlarmStore.get(context, id);
        if (item != null && item.enabled) {
            AlarmScheduler.schedule(context, item);
        }

        AlarmService.start(context, id);
    }
}
