import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Probe amazon.media.AmazonAudioManager state through reflection (runs via app_process as shell). */
public final class AudioProbe {
    static Object ctx;
    static Object aam;

    public static void main(String[] args) {
        try {
            ctx = systemContext();
            System.out.println("context = " + ctx);
        } catch (Throwable t) {
            System.out.println("context FAILED: " + t);
            t.printStackTrace(System.out);
            return;
        }
        try {
            Class<?> c = Class.forName("amazon.media.AmazonAudioManager");
            Method getInstance = c.getMethod("getInstance", Class.forName("android.content.Context"));
            aam = getInstance.invoke(null, ctx);
            System.out.println("AmazonAudioManager = " + aam);
        } catch (Throwable t) {
            System.out.println("getInstance FAILED: " + t);
            return;
        }
        probe("getDualModeAudioCapability");
        probe("isDualOutputEnabled");
        probe("getDualOutputDevices");
        probe("isSpeakerMute");
        probe("getHdmiOutStateHint");
        probe("getDolbyDapEnabled");
        for (String a : args) {
            try {
                if (a.startsWith("setHdmiOutStateHint=")) {
                    int v = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    call("setHdmiOutStateHint", new Class[]{int.class}, new Object[]{v});
                } else if (a.startsWith("enableDualOutput=")) {
                    boolean v = Boolean.parseBoolean(a.substring(a.indexOf('=') + 1));
                    call("enableDualOutput", new Class[]{boolean.class}, new Object[]{v});
                } else if (a.startsWith("setSpeakerMute=")) {
                    boolean v = Boolean.parseBoolean(a.substring(a.indexOf('=') + 1));
                    call("setSpeakerMute", new Class[]{boolean.class}, new Object[]{v});
                } else if (a.startsWith("setAudioRoutingMode=")) {
                    String[] p = a.substring(a.indexOf('=') + 1).split(",");
                    call("setAudioRoutingMode", new Class[]{int.class, int.class},
                            new Object[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])});
                } else if (a.startsWith("resetAudioRoutingMode=")) {
                    int v = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    call("resetAudioRoutingMode", new Class[]{int.class}, new Object[]{v});
                } else if (a.startsWith("setForceDeviceRouting=")) {
                    int v = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    call("setForceDeviceRouting", new Class[]{int.class}, new Object[]{v});
                } else if (a.startsWith("setForceUse=")) {
                    String[] p = a.substring(a.indexOf('=') + 1).split(",");
                    call("setForceUse", new Class[]{int.class, int.class},
                            new Object[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])});
                } else if (a.startsWith("notifySpeechState=")) {
                    int v = Integer.parseInt(a.substring(a.indexOf('=') + 1));
                    call("notifySpeechState", new Class[]{int.class}, new Object[]{v});
                }
            } catch (Throwable t) {
                System.out.println("call " + a + " FAILED: " + t);
            }
        }
        probe("isDualOutputEnabled");
        probe("isSpeakerMute");
        probe("getHdmiOutStateHint");
    }

    static void probe(String name) {
        try {
            Method m = aam.getClass().getMethod(name);
            System.out.println(name + "() = " + m.invoke(aam));
        } catch (Throwable t) {
            System.out.println(name + "() FAILED: " + t);
        }
    }

    static void call(String name, Class<?>[] sig, Object[] val) throws Exception {
        Method m = aam.getClass().getMethod(name, sig);
        System.out.println(name + " -> " + m.invoke(aam, val));
    }

    static Object systemContext() throws Exception {
        Class<?> looper = Class.forName("android.os.Looper");
        looper.getMethod("prepare").invoke(null);
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method systemMain = at.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = at.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return getSystemContext.invoke(thread);
    }
}
