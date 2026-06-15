package com.drivehub.kamera;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.Editable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

final class DevSettingsController {

    void bind(
            SharedPreferences prefs,
            EditText etDefaultPollMs,
            EditText etSignalOffPollMs,
            Switch swTestVideoSources,
            TextView tvTestVideoPath,
            Button btnOpenTileTest,
            Button btnResetDefaults
    ) {
        bindTestVideoControls(prefs, swTestVideoSources, tvTestVideoPath, btnOpenTileTest);
        bindPollingField(
                prefs,
                etDefaultPollMs,
                UiPrefs.KEY_DEV_DEFAULT_POLL_MS,
                UiPrefs.getDevDefaultPollMs(prefs)
        );
        bindPollingField(
                prefs,
                etSignalOffPollMs,
                UiPrefs.KEY_DEV_SIGNAL_OFF_POLL_MS,
                UiPrefs.getDevSignalOffPollMs(prefs)
        );
        bindResetDefaultsButton(prefs, etDefaultPollMs, etSignalOffPollMs, btnResetDefaults);
    }

    private void bindTestVideoControls(
            SharedPreferences prefs,
            Switch swTestVideoSources,
            TextView tvTestVideoPath,
            Button btnOpenTileTest
    ) {
        if (swTestVideoSources != null) {
            swTestVideoSources.setChecked(UiPrefs.isDevTestVideoSourcesEnabled(prefs));
            swTestVideoSources.setOnCheckedChangeListener((buttonView, checked) ->
                    prefs.edit().putBoolean(UiPrefs.KEY_DEV_TEST_VIDEO_SOURCES, checked).apply());
        }
        if (tvTestVideoPath != null) {
            Context context = tvTestVideoPath.getContext();
            tvTestVideoPath.setText(context.getString(
                    R.string.settings_dev_test_video_path,
                    TestVideoSources.expectedPath(context)
            ));
        }
        if (btnOpenTileTest != null) {
            btnOpenTileTest.setOnClickListener(v ->
                    v.getContext().startActivity(new Intent(v.getContext(), TileViewActivity.class)));
        }
    }

    private void bindResetDefaultsButton(
            SharedPreferences prefs,
            EditText etDefaultPollMs,
            EditText etSignalOffPollMs,
            Button btnResetDefaults
    ) {
        if (btnResetDefaults == null) return;
        btnResetDefaults.setOnClickListener(v -> {
            prefs.edit()
                    .putInt(UiPrefs.KEY_DEV_DEFAULT_POLL_MS, UiPrefs.DEFAULT_DEV_DEFAULT_POLLING_MS)
                    .putInt(UiPrefs.KEY_DEV_SIGNAL_OFF_POLL_MS, UiPrefs.DEFAULT_DEV_SIGNAL_OFF_POLLING_MS)
                    .apply();
            setPollingFieldValue(etDefaultPollMs, UiPrefs.DEFAULT_DEV_DEFAULT_POLLING_MS);
            setPollingFieldValue(etSignalOffPollMs, UiPrefs.DEFAULT_DEV_SIGNAL_OFF_POLLING_MS);
            SignalService.requestRecheck();
            Toast.makeText(v.getContext(), R.string.settings_dev_defaults_reset, Toast.LENGTH_SHORT).show();
        });
    }

    private void setPollingFieldValue(EditText editText, int valueMs) {
        if (editText == null) return;
        editText.setText(String.valueOf(valueMs));
        editText.setSelection(editText.getText().length());
    }

    private void bindPollingField(
            SharedPreferences prefs,
            EditText editText,
            String key,
            int initialValueMs
    ) {
        if (editText == null) return;
        setPollingFieldValue(editText, initialValueMs);

        editText.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Integer valueMs = parsePollingMsOrNull(s == null ? null : s.toString());
                if (valueMs == null) return;
                prefs.edit().putInt(key, valueMs).apply();
                SignalService.requestRecheck();
            }
        });
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) return;
            Integer parsed = parsePollingMsOrNull(
                    editText.getText() == null ? null : editText.getText().toString()
            );
            int valueMs = parsed != null ? parsed : initialValueMs;
            valueMs = UiPrefs.clampDevPollingMs(valueMs);
            prefs.edit().putInt(key, valueMs).apply();
            String normalized = String.valueOf(valueMs);
            if (!normalized.contentEquals(editText.getText())) {
                editText.setText(normalized);
                editText.setSelection(editText.getText().length());
            }
            SignalService.requestRecheck();
        });
    }

    private Integer parsePollingMsOrNull(String value) {
        try {
            if (value == null) return null;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) return null;
            return UiPrefs.clampDevPollingMs(Integer.parseInt(trimmed));
        } catch (Throwable ignored) {
            return null;
        }
    }

}
