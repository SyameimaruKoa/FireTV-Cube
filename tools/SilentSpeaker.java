import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.lang.reflect.Method;

/**
 * Keeps a silent AudioTrack on the Cube's built-in speaker.
 *
 * Usage: SilentSpeaker <seconds> [usage] [contentType]
 *
 * The track is silent (zero samples) but keeps the speaker output active.
 * Fire OS then routes ordinary media streams to the speaker too.
 */
public final class SilentSpeaker {
    private static final int SAMPLE_RATE = 48000;

    static AudioDeviceInfo[] devices(int flags) throws Exception {
        Method m = AudioManager.class.getMethod("getDevicesStatic", int.class);
        return (AudioDeviceInfo[]) m.invoke(null, flags);
    }

    public static void main(String[] args) throws Exception {
        double secs = args.length > 0 ? Double.parseDouble(args[0]) : 20;
        int usage = args.length > 1 ? Integer.parseInt(args[1]) : AudioAttributes.USAGE_MEDIA;
        int contentType = args.length > 2 ? Integer.parseInt(args[2])
                : AudioAttributes.CONTENT_TYPE_MUSIC;

        AudioDeviceInfo speaker = null;
        AudioDeviceInfo hdmi = null;
        for (AudioDeviceInfo d : devices(AudioManager.GET_DEVICES_OUTPUTS)) {
            System.out.println("dev id=" + d.getId() + " type=" + d.getType()
                    + " name=" + d.getProductName() + " addr=" + d.getAddress());
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                speaker = d;
            } else if (d.getType() == AudioDeviceInfo.TYPE_HDMI) {
                hdmi = d;
            }
        }

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(contentType)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(minBuf, SAMPLE_RATE * 2);
        AudioTrack track = new AudioTrack(attrs, fmt, buf,
                AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            System.out.println("track not initialized");
            return;
        }
        boolean preferred = speaker != null && track.setPreferredDevice(speaker);
        System.out.println("silent speaker start usage=" + usage
                + " contentType=" + contentType
                + " preferred=" + preferred
                + " speakerId=" + (speaker == null ? -1 : speaker.getId())
                + " session=" + track.getAudioSessionId());
        track.play();
        try {
            System.out.println("routed=" + track.getRoutedDevice());
        } catch (Throwable ignored) {
        }

        short[] zeros = new short[4800]; // 100 ms
        long end = System.currentTimeMillis() + (long) (secs * 1000);
        int chunks = 0;
        while (System.currentTimeMillis() < end) {
            track.write(zeros, 0, zeros.length);
            if (++chunks % 20 == 0) {
                try {
                    System.out.println("t=" + (chunks / 10) + "s routed=" + track.getRoutedDevice());
                } catch (Throwable ignored) {
                }
            }
        }
        track.stop();
        track.release();
        System.out.println("silent speaker done");
    }
}
