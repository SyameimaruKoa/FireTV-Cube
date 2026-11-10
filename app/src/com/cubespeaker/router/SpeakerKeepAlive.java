package com.cubespeaker.router;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Keeps a silent track on the Cube's built-in speaker while "all audio" mode is
 * enabled.
 *
 * Fire OS watches for an active track whose preferred device is the built-in
 * speaker ("Audio Speaker track active" in AudioPolicyManager).  While such a
 * track exists it sets {@code hdmi_mute=1} and routes the ordinary media
 * strategy (STREAM_MUSIC, flags 0) to {@code AudioOut_D} as well.  Writing
 * digital silence keeps the track active without making any sound, so every
 * media stream - including HDMI video audio - is played by the Cube speaker
 * instead of the TV.
 *
 * The track deliberately uses USAGE_MEDIA and never requests audio focus, so it
 * does not pause or duck the app that is actually playing.
 */
final class SpeakerKeepAlive {
    private static final String TAG = "CubeSpeaker";

    private static final int SAMPLE_RATE = 48000;

    private volatile boolean running;
    private Thread thread;
    private AudioTrack track;

    synchronized boolean isRunning() {
        return running;
    }

    synchronized void start(Context context) {
        if (running) {
            return;
        }
        final Context app = context.getApplicationContext();
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(app);
            }
        }, "cube-speaker-keepalive");
        thread.start();
        Log.i(TAG, "keepalive starting");
    }

    synchronized void stop() {
        if (!running && thread == null) {
            return;
        }
        running = false;
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000L);
            } catch (InterruptedException ignored) {
            }
        }
        AudioTrack active = track;
        if (active != null) {
            // The loop normally releases the track itself.  This is only a
            // safety net for a thread stuck in write().
            try {
                active.release();
            } catch (Throwable ignored) {
            }
        }
        thread = null;
        track = null;
        Log.i(TAG, "keepalive stopped");
    }

    private void loop(Context context) {
        AudioTrack local = null;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo speaker = null;
            if (am != null) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        speaker = d;
                        break;
                    }
                }
            }

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buffer = Math.max(min, SAMPLE_RATE / 10 * 2);
            local = new AudioTrack(attrs, format, buffer,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (local.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "keepalive track not initialized");
                return;
            }
            boolean preferred = speaker != null && local.setPreferredDevice(speaker);
            track = local;
            local.play();
            Log.i(TAG, "keepalive active preferred=" + preferred
                    + " speaker=" + (speaker == null ? -1 : speaker.getId())
                    + " session=" + local.getAudioSessionId());

            short[] zeros = new short[4800]; // 100 ms of digital silence
            while (running) {
                local.write(zeros, 0, zeros.length);
            }
        } catch (Throwable t) {
            if (running) {
                Log.w(TAG, "keepalive failed", t);
            }
        } finally {
            running = false;
            track = null;
            if (local != null) {
                try {
                    local.stop();
                } catch (Throwable ignored) {
                }
                try {
                    local.release();
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "keepalive loop finished");
        }
    }
}
