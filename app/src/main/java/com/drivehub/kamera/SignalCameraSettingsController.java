package com.drivehub.kamera;

import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;

final class SignalCameraSettingsController {

    private final MainActivity activity;

    SignalCameraSettingsController(MainActivity activity) {
        this.activity = activity;
    }

    void bind(
            SharedPreferences prefs,
            Switch swOverlay,
            SeekBar seekOverlayHideDelay,
            EditText etOverlayHideDelayValue
    ) {
        if (swOverlay != null) {
            swOverlay.setChecked(prefs.getBoolean(UiPrefs.KEY_OVERLAY_ON_SIGNAL, false));
            swOverlay.setOnCheckedChangeListener((btn, checked) -> {
                prefs.edit().putBoolean(UiPrefs.KEY_OVERLAY_ON_SIGNAL, checked).apply();
                if (!checked) {
                    OverlayService.hideOverlay(activity);
                }
            });
        }

        if (seekOverlayHideDelay == null) return;

        // Slider and text input update each other, so guard programmatic changes to avoid loops.
        final boolean[] syncingOverlayHideDelay = {false};
        int savedOverlayHideDelayMs = UiPrefs.getOverlayHideDelayMs(prefs);
        seekOverlayHideDelay.setMax(UiPrefs.MAX_OVERLAY_HIDE_DELAY_MS / UiPrefs.OVERLAY_HIDE_DELAY_STEP_MS);
        seekOverlayHideDelay.setProgress(savedOverlayHideDelayMs / UiPrefs.OVERLAY_HIDE_DELAY_STEP_MS);

        if (etOverlayHideDelayValue != null) {
            etOverlayHideDelayValue.setText(String.valueOf(savedOverlayHideDelayMs));
            etOverlayHideDelayValue.setSelection(etOverlayHideDelayValue.getText().length());
            etOverlayHideDelayValue.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(android.text.Editable s) {
                    if (syncingOverlayHideDelay[0] || s == null) return;
                    String text = s.toString().trim();
                    if (text.isEmpty()) return;
                    try {
                        int delayMs = UiPrefs.clampOverlayHideDelayMs(Integer.parseInt(text));
                        prefs.edit().putLong(UiPrefs.KEY_OVERLAY_HIDE_DELAY_MS, delayMs).apply();
                        int progress = delayMs / UiPrefs.OVERLAY_HIDE_DELAY_STEP_MS;
                        if (seekOverlayHideDelay.getProgress() != progress) {
                            seekOverlayHideDelay.setProgress(progress);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
            etOverlayHideDelayValue.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) return;
                // Normalize free-form keyboard input on blur so the stored value always stays valid.
                String text = etOverlayHideDelayValue.getText() == null
                        ? ""
                        : etOverlayHideDelayValue.getText().toString().trim();
                int delayMs;
                try {
                    delayMs = text.isEmpty() ? UiPrefs.getOverlayHideDelayMs(prefs) : Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    delayMs = UiPrefs.getOverlayHideDelayMs(prefs);
                }
                delayMs = UiPrefs.clampOverlayHideDelayMs(delayMs);
                prefs.edit().putLong(UiPrefs.KEY_OVERLAY_HIDE_DELAY_MS, delayMs).apply();
                syncingOverlayHideDelay[0] = true;
                etOverlayHideDelayValue.setText(String.valueOf(delayMs));
                etOverlayHideDelayValue.setSelection(etOverlayHideDelayValue.getText().length());
                syncingOverlayHideDelay[0] = false;
                int progress = delayMs / UiPrefs.OVERLAY_HIDE_DELAY_STEP_MS;
                if (seekOverlayHideDelay.getProgress() != progress) {
                    seekOverlayHideDelay.setProgress(progress);
                }
            });
        }

        seekOverlayHideDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int delayMs = UiPrefs.clampOverlayHideDelayMs(progress * UiPrefs.OVERLAY_HIDE_DELAY_STEP_MS);
                prefs.edit().putLong(UiPrefs.KEY_OVERLAY_HIDE_DELAY_MS, delayMs).apply();
                if (etOverlayHideDelayValue == null) return;
                String currentValue = etOverlayHideDelayValue.getText() == null
                        ? ""
                        : etOverlayHideDelayValue.getText().toString();
                String nextValue = String.valueOf(delayMs);
                if (!nextValue.equals(currentValue)) {
                    syncingOverlayHideDelay[0] = true;
                    etOverlayHideDelayValue.setText(nextValue);
                    etOverlayHideDelayValue.setSelection(etOverlayHideDelayValue.getText().length());
                    syncingOverlayHideDelay[0] = false;
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    // Helper base so the controller only overrides the callback it actually needs.
    private abstract static class SimpleTextWatcher implements android.text.TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
