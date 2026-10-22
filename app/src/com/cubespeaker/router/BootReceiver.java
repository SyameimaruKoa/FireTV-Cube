package com.cubespeaker.router;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * (Re)starts the router after boot, package replace, or an HDMI audio plug event.
 * The service itself re-asserts the hint periodically, so this is only a fast path.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences sp = context.getSharedPreferences(
                RouterService.PREFS, Context.MODE_PRIVATE);
        if (!sp.getBoolean(RouterService.KEY_ENABLED, true)) {
            return;
        }
        Intent service = new Intent(context, RouterService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
