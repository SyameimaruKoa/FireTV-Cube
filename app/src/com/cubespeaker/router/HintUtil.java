package com.cubespeaker.router;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Reflection wrapper around Amazon's hidden {@code amazon.media.AmazonAudioManager}.
 *
 * getHdmiOutStateHint()/setHdmiOutStateHint() only require MODIFY_AUDIO_SETTINGS and
 * are visible to ordinary (untrusted_app) processes on Fire OS 7 / raven.
 */
final class HintUtil {
    private static final String TAG = "CubeSpeaker";

    private static Object manager;
    private static Method getHint;
    private static Method setHint;

    private HintUtil() {
    }

    static synchronized void init(Context context) throws Exception {
        if (manager != null) {
            return;
        }
        Class<?> cls = Class.forName("amazon.media.AmazonAudioManager");
        Method getInstance = cls.getMethod("getInstance", Context.class);
        manager = getInstance.invoke(null, context.getApplicationContext());
        getHint = cls.getMethod("getHdmiOutStateHint");
        setHint = cls.getMethod("setHdmiOutStateHint", int.class);
        Log.i(TAG, "AmazonAudioManager bound");
    }

    static synchronized int get(Context context) throws Exception {
        init(context);
        return (Integer) getHint.invoke(manager);
    }

    static synchronized void set(Context context, int value) throws Exception {
        init(context);
        setHint.invoke(manager, value);
    }
}
