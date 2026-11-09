package com.cubespeaker.router;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Plays a short 1 kHz tone on the Cube's built-in speaker.
 *
 * Used only from the UI so the user can check, without talking to Alexa,
 * whether the speaker amplifier is actually unmuted and audible.
 */
final class TestTone {
    private static final String TAG = "CubeSpeaker";

    private static final int SAMPLE_RATE = 48000;
    private static final int FREQ_HZ = 1000;
    private static final int SECONDS = 8;
    /** Hidden AudioManager.STREAM_SYSTEM_ENFORCED; USAGE_ASSISTANCE_SONIFICATION maps here. */
    private static final int STREAM_SYSTEM_ENFORCED = 7;

    private TestTone() {
    }

    static void play(final Context context) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                playBlocking(context);
            }
        }, "cube-test-tone").start();
    }

    private static void playBlocking(Context context) {
        AudioTrack track = null;
        AudioManager am = null;
        int oldVol = -1;
        try {
            am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo speaker = null;
            if (am != null) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        speaker = d;
                        break;
                    }
                }
            }

            // The HAL initializes a freshly-opened speaker output at volume
            // index 0 (-72 dB) and never receives the stream volume unless the
            // framework sends a volume update.  Touch the volume here so the
            // output gets a non-zero value (and log whether we are allowed to).
            int maxVol = -1;
            if (am != null) {
                try {
                    oldVol = am.getStreamVolume(STREAM_SYSTEM_ENFORCED);
                    maxVol = am.getStreamMaxVolume(STREAM_SYSTEM_ENFORCED);
                    Log.i(TAG, "test tone vol before=" + oldVol + "/" + maxVol);
                    am.setStreamVolume(STREAM_SYSTEM_ENFORCED, maxVol, 0);
                    Log.i(TAG, "test tone setStreamVolume OK -> "
                            + am.getStreamVolume(STREAM_SYSTEM_ENFORCED));
                } catch (Throwable t) {
                    Log.w(TAG, "test tone setStreamVolume failed", t);
                }
            }

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();

            int minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, SAMPLE_RATE * 2); // ~1 s, never huge
            track = new AudioTrack(attrs, format, bufSize,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "test tone: AudioTrack not initialized");
                return;
            }
            if (speaker != null) {
                boolean ok = track.setPreferredDevice(speaker);
                Log.i(TAG, "test tone preferred speaker=" + speaker.getId() + " ok=" + ok);
            } else {
                Log.w(TAG, "test tone: no built-in speaker reported");
            }

            track.play();

            // The output (and its HAL volume) is created by play(); nudge the
            // stream volume again now so the HAL gets a non-zero index.
            Thread.sleep(400);
            if (am != null && maxVol > 0) {
                try {
                    am.setStreamVolume(STREAM_SYSTEM_ENFORCED, maxVol, 0);
                    Log.i(TAG, "test tone volume after play -> "
                            + am.getStreamVolume(STREAM_SYSTEM_ENFORCED));
                } catch (Throwable t) {
                    Log.w(TAG, "test tone volume after play failed", t);
                }
            }

            int total = SAMPLE_RATE * SECONDS;
            short[] chunk = new short[4800]; // 100 ms
            double phase = 0;
            double step = 2 * Math.PI * FREQ_HZ / SAMPLE_RATE;
            for (int written = 0; written < total; ) {
                int n = Math.min(chunk.length, total - written);
                for (int i = 0; i < n; i++) {
                    double t = (double) (written + i) / SAMPLE_RATE;
                    double fade = Math.min(1.0,
                            Math.min(t * 20.0, (SECONDS - t) * 20.0));
                    chunk[i] = (short) (Math.sin(phase) * 12000 * Math.max(0.0, fade));
                    phase += step;
                    if (phase > 2 * Math.PI) {
                        phase -= 2 * Math.PI;
                    }
                }
                track.write(chunk, 0, n);
                written += n;
            }
            Thread.sleep(300);
            Log.i(TAG, "test tone done");
        } catch (Throwable t) {
            Log.w(TAG, "test tone failed", t);
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignored) {
                }
                track.release();
            }
            if (am != null && oldVol >= 0) {
                try {
                    am.setStreamVolume(STREAM_SYSTEM_ENFORCED, oldVol, 0);
                    Log.i(TAG, "test tone vol restored=" + oldVol);
                } catch (Throwable t) {
                    Log.w(TAG, "test tone vol restore failed", t);
                }
            }
        }
    }
}
