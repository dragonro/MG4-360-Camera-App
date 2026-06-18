// Updated: AdrianBega/DualBytes
package com.drivehub.kamera;

import android.content.SharedPreferences;
import android.widget.Switch;

final class ProcessingSettingsController {

    interface Callback {
        void onProcessingModeChanged();
    }

    private final Callback callback;
    private boolean syncing;

    ProcessingSettingsController(Callback callback) {
        this.callback = callback;
    }

    void bind(SharedPreferences prefs, Switch swFisheye, Switch swUndistorted) {
        if (prefs == null || swFisheye == null || swUndistorted == null) return;

        syncSwitches(prefs, swFisheye, swUndistorted);

        swFisheye.setOnCheckedChangeListener((buttonView, checked) -> {
            if (syncing || !checked) {
                if (!syncing && !swUndistorted.isChecked()) {
                    syncSwitches(prefs, swFisheye, swUndistorted);
                }
                return;
            }
            setMode(prefs, swFisheye, swUndistorted, UiPrefs.PROCESSING_MODE_FISHEYE);
        });

        swUndistorted.setOnCheckedChangeListener((buttonView, checked) -> {
            if (syncing || !checked) {
                if (!syncing && !swFisheye.isChecked()) {
                    syncSwitches(prefs, swFisheye, swUndistorted);
                }
                return;
            }
            setMode(prefs, swFisheye, swUndistorted, UiPrefs.PROCESSING_MODE_UNDISTORTED);
        });
    }

    private void setMode(SharedPreferences prefs, Switch swFisheye, Switch swUndistorted, int mode) {
        int current = UiPrefs.getProcessingMode(prefs);
        UiPrefs.setProcessingMode(prefs, mode);
        syncSwitches(prefs, swFisheye, swUndistorted);
        if (current != mode && callback != null) {
            callback.onProcessingModeChanged();
        }
    }

    private void syncSwitches(SharedPreferences prefs, Switch swFisheye, Switch swUndistorted) {
        int mode = UiPrefs.getProcessingMode(prefs);
        syncing = true;
        swFisheye.setChecked(mode == UiPrefs.PROCESSING_MODE_FISHEYE);
        swUndistorted.setChecked(mode == UiPrefs.PROCESSING_MODE_UNDISTORTED);
        syncing = false;
    }
}
