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
    private static Method setParams;
    private static Method getDevicesForStream;
    private static Method isSpeakerMute;
    private static Method getForceUse;

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

    /**
     * The Fire OS audio service mutes the Cube's speaker amplifier through the
     * hidden AudioSystem parameter "speaker_mute=true" whenever STREAM_MUSIC is
     * routed to HDMI (the normal Fire TV mode).  The mute is applied at boot and
     * is not lifted when an AUTO_OUT (Alexa) track is routed to the speaker, so
     * the speaker stays silent even though the routing is correct.
     *
     * AudioSystem.setParameters() is reachable from an untrusted_app process on
     * this device (verified: AudioALSAHardware processes "speaker_mute=false"),
     * and does not need the signature permission that setSpeakerMute() requires.
     */
    private static synchronized void initAudioSystem() throws Exception {
        if (setParams == null) {
            Class<?> cls = Class.forName("android.media.AudioSystem");
            setParams = cls.getMethod("setParameters", String.class);
            getDevicesForStream = cls.getMethod("getDevicesForStream", int.class);
            try {
                getForceUse = cls.getMethod("getForceUse", int.class);
            } catch (Throwable t) {
                Log.w(TAG, "AudioSystem.getForceUse unavailable", t);
            }
        }
    }

    /**
     * Fire OS-specific diagnostics on amazon.media.AmazonAudioManager.
     * isSpeakerMute() reflects the service's speaker preference.
     */
    private static synchronized void initAmazon() throws Exception {
        if (isSpeakerMute != null) {
            return;
        }
        Class<?> cls = Class.forName("amazon.media.AmazonAudioManager");
        isSpeakerMute = cls.getMethod("isSpeakerMute");
    }

    static synchronized int getForceUse(int usage) {
        try {
            initAudioSystem();
            if (getForceUse == null) {
                return -999;
            }
            return (Integer) getForceUse.invoke(null, usage);
        } catch (Throwable t) {
            Log.w(TAG, "getForceUse(" + usage + ") failed", t);
            return -999;
        }
    }

    static synchronized boolean isSpeakerMute(Context context) {
        try {
            init(context);
            initAmazon();
            return (Boolean) isSpeakerMute.invoke(manager);
        } catch (Throwable t) {
            Log.w(TAG, "isSpeakerMute failed", t);
            return false;
        }
    }

    static synchronized void setSpeakerMute(boolean mute) {
        try {
            initAudioSystem();
            setParams.invoke(null, "speaker_mute=" + mute + ";");
        } catch (Throwable t) {
            Log.w(TAG, "speaker_mute(" + mute + ") failed", t);
        }
    }

    /** Send an arbitrary audio HAL parameter (diagnostics only). */
    static synchronized void setRawParam(String value) {
        try {
            initAudioSystem();
            setParams.invoke(null, value);
            Log.i(TAG, "setRawParam(" + value + ") ok");
        } catch (Throwable t) {
            Log.w(TAG, "setRawParam(" + value + ") failed", t);
        }
    }

    /**
     * Restore the stock speaker mute when the router is disabled, but only if
     * STREAM_MUSIC is not on the built-in speaker (i.e. we are in TV/HDMI mode).
     * In ECHO mode the system wants the speaker unmuted, so leave it alone.
     */
    static synchronized void restoreSpeakerMuteIfHdmi() {
        try {
            initAudioSystem();
            int devices = (Integer) getDevicesForStream.invoke(null, 3); // STREAM_MUSIC
            if ((devices & 0x2) == 0) { // AUDIO_DEVICE_OUT_SPEAKER
                setParams.invoke(null, "speaker_mute=true;");
                Log.i(TAG, "speaker_mute restored (music devices=0x"
                        + Integer.toHexString(devices) + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "restore speaker_mute failed", t);
        }
    }
}
