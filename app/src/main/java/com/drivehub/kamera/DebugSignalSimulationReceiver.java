// Author: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Exported only from the debug manifest so adb can drive signal-state tests in the emulator.
 */
public class DebugSignalSimulationReceiver extends BroadcastReceiver {

    private static final String TAG = "DebugSignalReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "Ignoring signal simulation in non-debug build.");
            return;
        }
        if (!SignalService.ACTION_DEBUG_SET_SIGNAL_STATE.equals(intent.getAction())) {
            return;
        }

        Intent service = new Intent(context, SignalService.class);
        service.setAction(SignalService.ACTION_DEBUG_SET_SIGNAL_STATE);
        service.putExtra(
                SignalService.EXTRA_DEBUG_LAMP,
                intent.getIntExtra(SignalService.EXTRA_DEBUG_LAMP, 0)
        );
        service.putExtra(
                SignalService.EXTRA_DEBUG_GEAR,
                intent.getIntExtra(SignalService.EXTRA_DEBUG_GEAR, 0)
        );
        try {
            context.startForegroundService(service);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Signal simulation service start blocked by Android background limits. "
                    + "Use control-script option 8, which starts the service through adb.", e);
        }
    }
}
