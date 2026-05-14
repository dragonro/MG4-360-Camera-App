package com.drivehub.kamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

final class UiPrefs {

    static final String REC_PREFS_NAME = "rec_prefs";
    static final String KEY_TILE_CORNER_RADIUS = "tileCornerRadius";
    static final String KEY_ACCENT_COLOR = "accentColor";
    static final int MAX_TILE_CORNER_RADIUS = 35;
    private static final int DEFAULT_TILE_CORNER_RADIUS = 16;
    private static final String DEFAULT_ACCENT_COLOR = "#E7E7E7";

    private UiPrefs() {
    }

    static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(REC_PREFS_NAME, Context.MODE_PRIVATE);
    }

    static int getTileCornerRadiusSetting(SharedPreferences prefs) {
        return clampRadiusSetting(prefs.getInt(KEY_TILE_CORNER_RADIUS, DEFAULT_TILE_CORNER_RADIUS));
    }

    static float getCornerRadiusFraction(SharedPreferences prefs) {
        return clampRadiusSetting(getTileCornerRadiusSetting(prefs)) / (float) MAX_TILE_CORNER_RADIUS;
    }

    static float getCornerRadiusPx(View view, SharedPreferences prefs) {
        int minSize = Math.min(view.getWidth(), view.getHeight());
        return minSize * 0.5f * getCornerRadiusFraction(prefs);
    }

    static String getAccentColorSetting(SharedPreferences prefs) {
        return normalizeAccentColor(prefs.getString(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR));
    }

    static int getAccentColorInt(SharedPreferences prefs) {
        try {
            return android.graphics.Color.parseColor(getAccentColorSetting(prefs));
        } catch (IllegalArgumentException ignored) {
            return android.graphics.Color.parseColor(DEFAULT_ACCENT_COLOR);
        }
    }

    static String normalizeAccentColor(String value) {
        if (value == null) return DEFAULT_ACCENT_COLOR;
        String trimmed = value.trim().toUpperCase(java.util.Locale.US);
        if (trimmed.isEmpty()) return DEFAULT_ACCENT_COLOR;
        if (!trimmed.startsWith("#")) {
            trimmed = "#" + trimmed;
        }
        if (trimmed.matches("^#[0-9A-F]{6}$")) {
            return trimmed;
        }
        if (trimmed.matches("^#[0-9A-F]{8}$")) {
            return trimmed;
        }
        return DEFAULT_ACCENT_COLOR;
    }

    static Integer tryParseAccentColorOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim().toUpperCase(java.util.Locale.US);
        if (trimmed.isEmpty()) return null;
        if (!trimmed.startsWith("#")) {
            trimmed = "#" + trimmed;
        }
        if (!trimmed.matches("^#[0-9A-F]{6}$") && !trimmed.matches("^#[0-9A-F]{8}$")) {
            return null;
        }
        try {
            return android.graphics.Color.parseColor(trimmed);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int clampRadiusSetting(int value) {
        return Math.max(0, Math.min(MAX_TILE_CORNER_RADIUS, value));
    }
}
