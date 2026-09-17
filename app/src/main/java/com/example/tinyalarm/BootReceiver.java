package com.example.tinyalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "unknown" : intent.getAction();
        AlarmStore.log(context, "SYSTEM_EVENT " + action + "; rescheduling enabled alarms");
        AlarmScheduler.rescheduleEnabled(context);
    }
}
