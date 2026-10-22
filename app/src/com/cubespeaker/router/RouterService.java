package com.cubespeaker.router;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Keeps the Fire OS "HDMI out state hint" at 0 (HDMI not active).
 *
 * The hint only changes routing for AudioAttributes carrying
 * AmazonAudioManager.FLAG_AUTO_OUT (0x10000000). Alexa TTS uses exactly that flag,
 * so it is played on the Cube's built-in speaker while ordinary media tracks
 * (flags == 0) keep playing over HDMI unchanged.
 */
public class RouterService extends Service {
    static final String TAG = "CubeSpeaker";

    static final String PREFS = "router";
    static final String KEY_ENABLED = "enabled";

    static final String ACTION_STOP = "com.cubespeaker.router.STOP";

    private static final int NOTIFICATION_ID = 0x43554245; // "CUBE"
    private static final String CHANNEL_ID = "router";
    private static final long POLL_MS = 500L;
    private static final long RETRY_MS = 5000L;

    private Handler handler;
    private volatile boolean running;
    private int failures;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                int hint = HintUtil.get(RouterService.this);
                failures = 0;
                if (hint != 0) {
                    HintUtil.set(RouterService.this, 0);
                    Log.i(TAG, "hint " + hint + " -> 0");
                }
                handler.postDelayed(this, POLL_MS);
            } catch (Throwable t) {
                failures++;
                if (failures <= 3 || failures % 20 == 0) {
                    Log.w(TAG, "hint update failed (" + failures + ")", t);
                }
                handler.postDelayed(this, failures > 3 ? RETRY_MS : POLL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!sp.getBoolean(KEY_ENABLED, true)) {
            stopSelf();
            return;
        }

        running = true;
        handler.post(tick);
        Log.i(TAG, "service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_ENABLED, false).apply();
            try {
                HintUtil.set(this, 1);
            } catch (Throwable ignored) {
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && !running) {
            running = true;
            handler.post(tick);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        Log.i(TAG, "service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Cube Speaker Router", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Alexa TTS routing");
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("Alexa音声 → Cubeスピーカー")
                .setContentText("自動ルーティング有効")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }
}
