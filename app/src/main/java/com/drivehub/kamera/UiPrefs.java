package com.drivehub.kamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

final class UiPrefs {

    static final String REC_PREFS_NAME = "rec_prefs";
    static final String KEY_TILE_CORNER_RADIUS = "tileCornerRadius";
    private static final int DEFAULT_TILE_CORNER_RADIUS = 16;

    private UiPrefs() {
    }

    static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(REC_PREFS_NAME, Context.MODE_PRIVATE);
    }

    static int getTileCornerRadiusSetting(SharedPreferences prefs) {
        return clampRadiusSetting(prefs.getInt(KEY_TILE_CORNER_RADIUS, DEFAULT_TILE_CORNER_RADIUS));
    }

    static float getCornerRadiusFraction(SharedPreferences prefs) {
        return clampRadiusSetting(getTileCornerRadiusSetting(prefs)) / 100f;
    }

    static float getCornerRadiusPx(View view, SharedPreferences prefs) {
        int minSize = Math.min(view.getWidth(), view.getHeight());
        return minSize * 0.5f * getCornerRadiusFraction(prefs);
    }

    private static int clampRadiusSetting(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
