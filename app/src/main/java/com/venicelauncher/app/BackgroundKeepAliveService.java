package com.venicelauncher.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class BackgroundKeepAliveService extends Service {
    private static final String TAG = "VeniceBgService";

    public static final String ACTION_START_KEEPALIVE = "com.venicelauncher.app.START_KEEPALIVE";
    public static final String ACTION_STOP_KEEPALIVE = "com.venicelauncher.app.STOP_KEEPALIVE";
    public static final String ACTION_GENERATION_STARTED = "com.venicelauncher.app.GENERATION_STARTED";
    public static final String ACTION_GENERATION_FINISHED = "com.venicelauncher.app.GENERATION_FINISHED";

    private static final String CHANNEL_ID = "venice_bg_channel";
    private static final String CHANNEL_COMPLETION_ID = "venice_completion_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int COMPLETION_NOTIFICATION_ID = 1002;

    private static final long MAX_GENERATION_WAKELOCK_TIMEOUT = 15 * 60 * 1000L; // 15 minutes safety limit

    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;
    private boolean isGenerating = false;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable wakeLockTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            Log.w(TAG, "Safety timeout reached for generation WakeLock");
            isGenerating = false;
            releaseWakeLock();
            updateNotification(false);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP_KEEPALIVE.equals(action)) {
            stopForegroundService();
            return START_NOT_STICKY;
        }

        if (ACTION_GENERATION_STARTED.equals(action)) {
            isGenerating = true;
            acquireWakeLock();
            timeoutHandler.removeCallbacks(wakeLockTimeoutRunnable);
            timeoutHandler.postDelayed(wakeLockTimeoutRunnable, MAX_GENERATION_WAKELOCK_TIMEOUT);
            startOrUpdateForeground(true);
        } else if (ACTION_GENERATION_FINISHED.equals(action)) {
            boolean wasGenerating = isGenerating;
            isGenerating = false;
            timeoutHandler.removeCallbacks(wakeLockTimeoutRunnable);
            releaseWakeLock();

            if (wasGenerating) {
                notifyGenerationComplete();
            }

            SharedPreferences prefs = getSharedPreferences("venice_settings", MODE_PRIVATE);
            boolean alwaysKeepAlive = prefs.getBoolean("always_keep_alive", false);
            if (alwaysKeepAlive) {
                // Update to idle status, definitely NOT generating
                startOrUpdateForeground(false);
            } else {
                stopForegroundService();
                return START_NOT_STICKY;
            }
        } else {
            // Default START_KEEPALIVE (idle start)
            isGenerating = false;
            SharedPreferences prefs = getSharedPreferences("venice_settings", MODE_PRIVATE);
            boolean alwaysKeepAlive = prefs.getBoolean("always_keep_alive", false);
            if (alwaysKeepAlive) {
                startOrUpdateForeground(false);
            } else {
                stopForegroundService();
                return START_NOT_STICKY;
            }
        }

        return START_STICKY;
    }

    private void startOrUpdateForeground(boolean generating) {
        Notification notification = buildNotification(generating);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                }
                startForeground(NOTIFICATION_ID, notification, serviceType);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed starting foreground service", e);
            try {
                startForeground(NOTIFICATION_ID, notification);
            } catch (Exception ex) {
                Log.e(TAG, "Fallback startForeground failed", ex);
            }
        }
    }

    private void updateNotification(boolean generating) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(generating));
        }
    }

    private Notification buildNotification(boolean generating) {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        String title = generating
                ? getString(R.string.bg_notification_generating)
                : getString(R.string.bg_notification_title);

        String desc = generating
                ? getString(R.string.bg_channel_desc)
                : getString(R.string.bg_notification_idle);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_venice_logo)
                .setContentTitle(title)
                .setContentText(desc)
                .setContentIntent(pendingIntent)
                .setOngoing(generating)
                .setPriority(generating ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_LOW)
                .setColor(getColor(R.color.venice_accent));

        if (generating) {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }

    private void notifyGenerationComplete() {
        SharedPreferences prefs = getSharedPreferences("venice_settings", MODE_PRIVATE);
        boolean notify = prefs.getBoolean("notify_completion", true);
        boolean vibrate = prefs.getBoolean("vibrate_completion", true);

        if (!notify) return;

        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 1, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_COMPLETION_ID)
                .setSmallIcon(R.drawable.ic_check)
                .setContentTitle(getString(R.string.bg_completion_title))
                .setContentText(getString(R.string.bg_completion_desc))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(getColor(R.color.status_active));

        if (notificationManager != null) {
            notificationManager.notify(COMPLETION_NOTIFICATION_ID, builder.build());
        }

        if (vibrate) {
            triggerVibration();
        }
    }

    private void triggerVibration() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 200, 100, 200}, -1));
                } else {
                    vibrator.vibrate(new long[]{0, 200, 100, 200}, -1);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Vibration error", e);
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VeniceLauncher:GenerationKeepAlive");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(MAX_GENERATION_WAKELOCK_TIMEOUT);
                Log.d(TAG, "WakeLock acquired for background generation");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed acquiring WakeLock", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "WakeLock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed releasing WakeLock", e);
        }
    }

    private void stopForegroundService() {
        releaseWakeLock();
        timeoutHandler.removeCallbacks(wakeLockTimeoutRunnable);
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bg_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription(getString(R.string.bg_channel_desc));
            serviceChannel.setShowBadge(false);

            NotificationChannel completionChannel = new NotificationChannel(
                    CHANNEL_COMPLETION_ID,
                    getString(R.string.bg_completion_title),
                    NotificationManager.IMPORTANCE_HIGH
            );
            completionChannel.setDescription(getString(R.string.bg_completion_desc));
            completionChannel.enableVibration(true);

            if (notificationManager != null) {
                notificationManager.createNotificationChannel(serviceChannel);
                notificationManager.createNotificationChannel(completionChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        timeoutHandler.removeCallbacks(wakeLockTimeoutRunnable);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
