package com.example.tinyalarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import java.io.IOException;

public class AlarmService extends Service {
    static final String ACTION_STOP = "com.example.tinyalarm.STOP";
    static final String ACTION_SNOOZE = "com.example.tinyalarm.SNOOZE";
    private static final String CHANNEL_ID = "ringing_alarm_service_v2";
    private static final int NOTIFICATION_ID = 88;
    private static final float START_VOLUME = 0.18f;
    private static final float VOLUME_STEP = 0.10f;
    private static final long RAMP_INTERVAL_MS = 8_000L;
    private final Handler volumeHandler = new Handler(Looper.getMainLooper());
    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private float currentVolume = START_VOLUME;
    private final Runnable volumeRamp = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer == null) {
                return;
            }
            currentVolume = Math.min(1.0f, currentVolume + VOLUME_STEP);
            mediaPlayer.setVolume(currentVolume, currentVolume);
            if (currentVolume < 1.0f) {
                volumeHandler.postDelayed(this, RAMP_INTERVAL_MS);
            }
        }
    };

    static void start(Context context, int alarmId) {
        Intent serviceIntent = new Intent(context, AlarmService.class);
        serviceIntent.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            AlarmStore.log(context, "SERVICE_START_REQUEST alarm " + alarmId);
        } catch (RuntimeException ignored) {
            AlarmStore.log(context, "SERVICE_START_FAILED alarm " + alarmId);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int alarmId = resolveAlarmId(intent);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AlarmStore.log(this, "STOP_ACTION received alarm " + alarmId);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SNOOZE.equals(intent.getAction())) {
            AlarmStore.log(this, "SNOOZE_ACTION received alarm " + alarmId);
            if (alarmId > 0) {
                AlarmScheduler.snooze(this, alarmId, 5);
            } else {
                AlarmStore.log(this, "Snooze skipped: missing alarm id");
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        AlarmStore.log(this, "SERVICE_STARTED alarm " + alarmId);
        AlarmStore.setRingingAlarm(this, alarmId);
        createChannel();
        startForeground(NOTIFICATION_ID, notification(alarmId));
        AlarmStore.log(this, "NOTIFICATION_POSTED alarm " + alarmId);
        startSound();
        startVibration();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        volumeHandler.removeCallbacks(volumeRamp);
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
        AlarmStore.clearRingingAlarm(this);
        AlarmStore.log(this, "SERVICE_DESTROYED");
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        AlarmStore.log(this, "APP_TASK_REMOVED recents task swiped/removed");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int resolveAlarmId(Intent intent) {
        int savedAlarmId = AlarmStore.ringingAlarmId(this);
        if (intent == null) {
            return savedAlarmId > 0 ? savedAlarmId : 1;
        }
        return intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, savedAlarmId > 0 ? savedAlarmId : 1);
    }

    private Notification notification(int alarmId) {
        Intent ringIntent = new Intent(this, MainActivity.class);
        ringIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                8_100 + alarmId,
                ringIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Tiny Alarm")
                .setContentText("Alarm is ringing")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_ALARM)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setOnlyAlertOnce(false)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Ringing alarms",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Tiny Alarm foreground ringing service");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.enableVibration(false);
        channel.setSound(null, attributes);
        if (manager != null) {
            manager.createNotificationChannel(channel);
            NotificationChannel savedChannel = manager.getNotificationChannel(CHANNEL_ID);
            if (savedChannel != null) {
                AlarmStore.log(this, "Alarm notification channel importance " + savedChannel.getImportance());
            }
        }
    }

    private void startSound() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            return;
        }
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }
        if (uri == null) {
            return;
        }
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mediaPlayer.setLooping(true);
            currentVolume = START_VOLUME;
            mediaPlayer.setVolume(currentVolume, currentVolume);
            mediaPlayer.prepare();
            mediaPlayer.start();
            AlarmStore.log(this, "SOUND_STARTED alarm tone playing");
            volumeHandler.removeCallbacks(volumeRamp);
            volumeHandler.postDelayed(volumeRamp, RAMP_INTERVAL_MS);
        } catch (IOException | IllegalStateException | SecurityException e) {
            mediaPlayer.release();
            mediaPlayer = null;
            AlarmStore.log(this, "SOUND_FAILED " + e.getClass().getSimpleName());
        }
    }

    private void startVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = manager == null ? null : manager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator == null) {
            AlarmStore.log(this, "VIBRATION_UNAVAILABLE");
            return;
        }
        long[] pattern = {0, 700, 400, 700, 900};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            vibrator.vibrate(pattern, 0);
        }
        AlarmStore.log(this, "VIBRATION_STARTED");
    }
}
