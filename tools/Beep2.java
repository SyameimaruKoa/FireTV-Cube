import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Plays a PCM tone with explicit AudioAttributes (usage/content/flags).
 * Usage: Beep2 <contentType> <usage> <flags> <seconds> <freqHz> [volume]
 */
public final class Beep2 {
    public static void main(String[] args) throws Exception {
        int contentType = Integer.parseInt(args[0]);
        int usage = Integer.parseInt(args[1]);
        int flags = Integer.parseInt(args[2]);
        double secs = Double.parseDouble(args[3]);
        double freq = Double.parseDouble(args[4]);
        float vol = args.length > 5 ? Float.parseFloat(args[5]) : 0.85f;

        Class<?> aa = Class.forName("android.media.AudioAttributes");
        Class<?> aaBuilder = Class.forName("android.media.AudioAttributes$Builder");
        Object b = aaBuilder.getConstructor().newInstance();
        aaBuilder.getMethod("setContentType", int.class).invoke(b, contentType);
        aaBuilder.getMethod("setUsage", int.class).invoke(b, usage);
        aaBuilder.getMethod("setFlags", int.class).invoke(b, flags);
        Object attrs = aaBuilder.getMethod("build").invoke(b);

        Class<?> af = Class.forName("android.media.AudioFormat");
        Class<?> afBuilder = Class.forName("android.media.AudioFormat$Builder");
        Object fb = afBuilder.getConstructor().newInstance();
        afBuilder.getMethod("setSampleRate", int.class).invoke(fb, 48000);
        afBuilder.getMethod("setEncoding", int.class).invoke(fb, 2);       // PCM_16BIT
        afBuilder.getMethod("setChannelMask", int.class).invoke(fb, 4);    // CHANNEL_OUT_MONO
        Object fmt = afBuilder.getMethod("build").invoke(fb);

        int frames = (int) (48000 * secs);
        short[] buf = new short[frames];
        for (int i = 0; i < frames; i++) {
            double t = (double) i / 48000;
            double env = Math.min(1.0, Math.min(t * 20.0, (secs - t) * 20.0));
            buf[i] = (short) (Math.sin(2 * Math.PI * freq * t) * 12000 * env);
        }

        Class<?> at = Class.forName("android.media.AudioTrack");
        Constructor<?> ctor = at.getConstructor(aa, af, int.class, int.class, int.class);
        Object track = ctor.newInstance(attrs, fmt, frames * 2, 1 /* MODE_STREAM */, 0 /* session */);
        at.getMethod("setVolume", float.class).invoke(track, vol);
        at.getMethod("play").invoke(track);
        System.out.println("playing content=" + contentType + " usage=" + usage + " flags=" + flags
                + " secs=" + secs + " freq=" + freq);
        at.getMethod("write", short[].class, int.class, int.class).invoke(track, buf, 0, frames);
        Thread.sleep((long) (secs * 1000) + 300);
        at.getMethod("stop").invoke(track);
        at.getMethod("release").invoke(track);
        System.out.println("done");
    }
}
