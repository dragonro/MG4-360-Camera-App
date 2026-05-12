package com.drivehub.kamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // On boot, start both recording (if enabled) and the signal listener.
        // The `overlayOnSignal` setting controls overlay behavior; without the signal service, the overlay cannot trigger.
        RecordingService.startIfNeeded(context);
        try {
            SignalService.start(context);
        } catch (Throwable ignored) {
        }
    }
}
