import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Runs via app_process inside a debuggable APK (run-as -> untrusted_app domain).
 *
 * modes:
 *   Probe info                     - dump service state and constants
 *   Probe route <uid> <mode>       - setAudioRoutingMode(uid, mode) and report
 *   Probe reset <uid>              - resetAudioRoutingMode(uid)
 *   Probe beep <secs> <freq> [vol] - play a USAGE_MEDIA/SPEECH tone (to test uid routing)
 */
public final class Probe {
    private static void log(String s) {
        System.out.println("[probe] " + s);
    }

    private static String rootCause(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append(" | ");
            t = t.getCause();
        }
        return sb.toString();
    }

    private static String readFile(String path) {
        try {
            FileInputStream in = new FileInputStream(path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[256];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8").trim();
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    private static Object binder;

    private static Object manager() throws Exception {
        if (manager != null) return manager;
        Class<?> sm = Class.forName("android.os.ServiceManager");
        binder = sm.getMethod("getService", String.class).invoke(null, "amazonaudioservice");
        log("getService(amazonaudioservice)=" + binder);
        if (binder == null) return null;
        Class<?> stub = Class.forName("com.amazon.android.server.media.IAmazonAudioManager$Stub");
        Class<?> ibinder = Class.forName("android.os.IBinder");
        manager = stub.getMethod("asInterface", ibinder).invoke(null, binder);
        return manager;
    }

    private static Object manager;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "info";
        log("uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());
        log("selinux=" + readFile("/proc/self/attr/current"));

        if (mode.equals("beep")) {
            double secs = Double.parseDouble(args[1]);
            double freq = Double.parseDouble(args[2]);
            float vol = args.length > 3 ? Float.parseFloat(args[3]) : 0.85f;
            beep(secs, freq, vol);
            return;
        }

        Object mgr = manager();
        if (mgr == null) {
            log("service not available");
            return;
        }
        Class<?> iface = Class.forName("com.amazon.android.server.media.IAmazonAudioManager");

        // constants from the real framework AmazonAudioManager
        try {
            Class<?> aam = Class.forName("amazon.media.AmazonAudioManager");
            String[] names = {
                "FOR_MEDIA", "FOR_SYSTEM", "FOR_COMMUNICATION",
                "FORCE_SPEAKER", "FORCE_HDMI_SYSTEM_AUDIO_ENFORCED", "FORCE_NONE",
                "ROUTING_MODE_HDMI", "ROUTING_MODE_SPEAKER",
                "HDMI_OUT_ACTIVE", "HDMI_OUT_NOT_ACTIVE", "HDMI_OUT_STATE_UNKNOWN"
            };
            for (String n : names) {
                try {
                    Field f = aam.getField(n);
                    log("AAM." + n + "=" + f.get(null));
                } catch (Throwable t) {
                    log("AAM." + n + " missing");
                }
            }
        } catch (Throwable t) {
            log("AmazonAudioManager class FAILED: " + rootCause(t));
        }

        int uid = android.os.Process.myUid();
        if (mode.equals("route")) {
            uid = Integer.parseInt(args[1]);
            int routing = Integer.parseInt(args[2]);
            try {
                Method m = iface.getMethod("setAudioRoutingMode", int.class, int.class);
                Object r = m.invoke(mgr, uid, routing);
                log("setAudioRoutingMode(" + uid + "," + routing + ") -> " + r);
            } catch (Throwable t) {
                log("setAudioRoutingMode FAILED: " + rootCause(t));
            }
            return;
        }
        if (mode.equals("reset")) {
            uid = Integer.parseInt(args[1]);
            try {
                Method m = iface.getMethod("resetAudioRoutingMode", int.class);
                Object r = m.invoke(mgr, uid);
                log("resetAudioRoutingMode(" + uid + ") -> " + r);
            } catch (Throwable t) {
                log("resetAudioRoutingMode FAILED: " + rootCause(t));
            }
            return;
        }
        if (mode.equals("hint")) {
            int v = Integer.parseInt(args[1]);
            try {
                Method m = iface.getMethod("setHdmiOutStateHint", int.class);
                Object r = m.invoke(mgr, v);
                log("setHdmiOutStateHint(" + v + ") -> " + r);
            } catch (Throwable t) {
                log("setHdmiOutStateHint FAILED: " + rootCause(t));
            }
            return;
        }
        if (mode.equals("mute")) {
            boolean v = Boolean.parseBoolean(args[1]);
            try {
                Method m = iface.getMethod("setSpeakerMute", boolean.class);
                m.invoke(mgr, v);
                log("setSpeakerMute(" + v + ") ok");
            } catch (Throwable t) {
                log("setSpeakerMute FAILED: " + rootCause(t));
            }
            return;
        }
        if (mode.equals("dual")) {
            boolean v = Boolean.parseBoolean(args[1]);
            try {
                Method m = iface.getMethod("enableDualOutput", boolean.class);
                Object r = m.invoke(mgr, v);
                log("enableDualOutput(" + v + ") -> " + r);
            } catch (Throwable t) {
                log("enableDualOutput FAILED: " + rootCause(t));
            }
            return;
        }
        if (mode.equals("forcedev")) {
            int v = Integer.parseInt(args[1]);
            try {
                Method m = iface.getMethod("setForceDeviceRouting", int.class);
                m.invoke(mgr, v);
                log("setForceDeviceRouting(" + v + ") ok");
            } catch (Throwable t) {
                log("setForceDeviceRouting FAILED: " + rootCause(t));
            }
            return;
        }
        if (mode.equals("asp")) {
            int val = Integer.parseInt(args[1]);
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Object asp = sm.getMethod("getService", String.class).invoke(null, "audiosignalprocessor");
                log("getService(audiosignalprocessor)=" + asp);
                if (asp != null) {
                    Class<?> iface2 = Class.forName("com.amazon.asp.IAudioSignalProcessor");
                    Class<?> stub2 = Class.forName("com.amazon.asp.IAudioSignalProcessor$Stub");
                    Class<?> ibinder = Class.forName("android.os.IBinder");
                    Object m2 = stub2.getMethod("asInterface", ibinder).invoke(null, asp);
                    Method set = iface2.getMethod("setActiveInputSource", int.class, byte[].class);
                    Object r = set.invoke(m2, 0, new byte[]{(byte) val});
                    log("setActiveInputSource(0,[" + val + "]) -> " + r);
                }
            } catch (Throwable t) {
                log("asp FAILED: " + rootCause(t));
            }
            return;
        }
        if (mode.equals("speech")) {
            int a = Integer.parseInt(args[1]);
            int b = args.length > 2 ? Integer.parseInt(args[2]) : 0;
            try {
                Method m = iface.getMethod("notifySpeechState", int.class, int.class);
                m.invoke(mgr, a, b);
                log("notifySpeechState(" + a + "," + b + ") ok");
            } catch (Throwable t) {
                log("notifySpeechState FAILED: " + rootCause(t));
            }
            return;
        }

        String[] getters = new String[] {
            "isDualOutputEnabled", "getDualOutputDevices", "isSpeakerMute", "getHdmiOutStateHint",
            "getPackageInFocus", "getDeviceForStream"
        };
        for (String name : getters) {
            try {
                Method m;
                Object r;
                if (name.equals("getDeviceForStream")) {
                    m = iface.getMethod(name, int.class);
                    r = m.invoke(mgr, 3);
                } else {
                    m = iface.getMethod(name);
                    r = m.invoke(mgr);
                }
                log("CALL " + name + "() -> " + r);
            } catch (Throwable t) {
                log("CALL " + name + "() FAILED: " + rootCause(t));
            }
        }
    }

    private static void beep(double secs, double freq, float vol) throws Exception {
        Class<?> aa = Class.forName("android.media.AudioAttributes");
        Class<?> aaBuilder = Class.forName("android.media.AudioAttributes$Builder");
        Object b = aaBuilder.getConstructor().newInstance();
        aaBuilder.getMethod("setContentType", int.class).invoke(b, 2); // SPEECH
        aaBuilder.getMethod("setUsage", int.class).invoke(b, 1);       // MEDIA
        aaBuilder.getMethod("setFlags", int.class).invoke(b, 0);
        Object attrs = aaBuilder.getMethod("build").invoke(b);

        Class<?> af = Class.forName("android.media.AudioFormat");
        Class<?> afBuilder = Class.forName("android.media.AudioFormat$Builder");
        Object fb = afBuilder.getConstructor().newInstance();
        afBuilder.getMethod("setSampleRate", int.class).invoke(fb, 48000);
        afBuilder.getMethod("setEncoding", int.class).invoke(fb, 2);
        afBuilder.getMethod("setChannelMask", int.class).invoke(fb, 4);
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
        Object track = ctor.newInstance(attrs, fmt, frames * 2, 1, 0);
        at.getMethod("setVolume", float.class).invoke(track, vol);
        at.getMethod("play").invoke(track);
        log("beep playing secs=" + secs + " freq=" + freq + " usage=MEDIA content=SPEECH");
        at.getMethod("write", short[].class, int.class, int.class).invoke(track, buf, 0, frames);
        Thread.sleep((long) (secs * 1000) + 300);
        at.getMethod("stop").invoke(track);
        at.getMethod("release").invoke(track);
        log("beep done");
    }
}
