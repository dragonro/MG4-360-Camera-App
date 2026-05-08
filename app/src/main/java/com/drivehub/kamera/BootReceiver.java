package com.drivehub.kamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        // Bootta hem kayıt (kayıt açıksa) hem sinyal dinleyicisini başlat.
        // Overlay davranışını `overlayOnSignal` ayarı kontrol ediyor; sinyal servisi olmadan overlay tetiklenmez.
        RecordingService.startIfNeeded(context);
        try {
            SignalService.start(context);
        } catch (Throwable ignored) {
        }
    }
}

