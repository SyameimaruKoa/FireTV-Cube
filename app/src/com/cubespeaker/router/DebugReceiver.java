package com.cubespeaker.router;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * ADB-only diagnostics for the speaker route.  Explicit broadcasts only, e.g.
 *
 * adb shell am broadcast -n com.cubespeaker.router/.DebugReceiver \
 *     -a com.cubespeaker.router.GET_STATE
 *
 * No production path calls this receiver.
 */
public class DebugReceiver extends BroadcastReceiver {
    private static final String TAG = "CubeSpeaker";

    private static final String ACTION_SET_HINT = "com.cubespeaker.router.SET_HINT";
    private static final String ACTION_SET_ENABLED = "com.cubespeaker.router.SET_ENABLED";
    private static final String ACTION_GET_STATE = "com.cubespeaker.router.GET_STATE";
    private static final String ACTION_PLAY_TONE = "com.cubespeaker.router.PLAY_TONE";
    private static final String ACTION_SET_PARAMS = "com.cubespeaker.router.SET_PARAMS";
    private static final String ACTION_UNMUTE = "com.cubespeaker.router.UNMUTE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        Log.i(TAG, "debug " + action + " " + intent.getExtras());
        try {
            if (ACTION_SET_HINT.equals(action)) {
                int value = intent.getIntExtra("value", 0);
                HintUtil.set(context, value);
                Log.i(TAG, "debug hint -> " + value + " now " + HintUtil.get(context));
            } else if (ACTION_SET_ENABLED.equals(action)) {
                boolean enabled = intent.getIntExtra("enabled", 1) != 0;
                context.getSharedPreferences(RouterService.PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(RouterService.KEY_ENABLED, enabled).commit();
                Intent service = new Intent(context, RouterService.class);
                if (enabled) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        context.startForegroundService(service);
                    } else {
                        context.startService(service);
                    }
                } else {
                    context.stopService(service);
                }
                Log.i(TAG, "debug enabled=" + enabled);
            } else if (ACTION_GET_STATE.equals(action)) {
                boolean enabled = context.getSharedPreferences(
                        RouterService.PREFS, Context.MODE_PRIVATE)
                        .getBoolean(RouterService.KEY_ENABLED, true);
                Log.i(TAG, "debug state enabled=" + enabled
                        + " hint=" + HintUtil.get(context)
                        + " forceUseMedia=" + HintUtil.getForceUse(1)
                        + " speakerMute=" + HintUtil.isSpeakerMute(context));
            } else if (ACTION_PLAY_TONE.equals(action)) {
                HintUtil.setSpeakerMute(false);
                TestTone.play(context);
            } else if (ACTION_SET_PARAMS.equals(action)) {
                HintUtil.setRawParam(intent.getStringExtra("value"));
            } else if (ACTION_UNMUTE.equals(action)) {
                HintUtil.setSpeakerMute(false);
                Log.i(TAG, "debug speaker_mute=false");
            }
        } catch (Throwable t) {
            Log.w(TAG, "debug " + action + " failed", t);
        }
    }
}
