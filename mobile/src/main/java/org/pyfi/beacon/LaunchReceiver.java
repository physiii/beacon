package org.pyfi.beacon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Created by physiii on 11/20/16.
 */

public class LaunchReceiver extends BroadcastReceiver {
    public static final String ACTION_PULSE_SERVER_ALARM =
            "org.pyfi.beacon.ACTION_PULSE_SERVER_ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("LaunchReceiver","!! HIT !!");
        Intent serviceIntent = new Intent(context,
                wsService.class);
        context.startService(serviceIntent);
    }
}
