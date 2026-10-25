package com.study.autonext;

import android.content.Context;
import android.content.SharedPreferences;

/** Shared configuration; no networking or analytics. */
public final class Config {
    private Config() { }
    public static final String ENABLED = "enabled";
    public static final String TARGET = "target_package";
    public static final String TARGET_NAME = "target_name";
    public static final String MINUTES = "fallback_minutes";
    public static final String FALLBACK = "fallback_enabled";
    public static final String X_PERCENT = "x_percent";
    public static final String Y_PERCENT = "y_percent";
    public static final String STATUS = "status";
    public static final String ADVANCES = "advances";
    public static final String MEDIA = "media_enabled";
    public static final String TEST_CLICK = "test_click";
    public static final String STATUS_AT = "status_at";
    public static final String LAST_REASON = "last_reason";
    public static final String RESET_TOKEN = "reset_token";
    public static final String FORCE_COORDINATE = "force_coordinate";
    public static final String ACTIVE_MS = "active_ms";
    public static final String TEST_CLICK_AT = "test_click_at";

    public static SharedPreferences get(Context context) {
        return context.getApplicationContext().getSharedPreferences("autonext", Context.MODE_PRIVATE);
    }
    public static void status(Context context, String status) {
        get(context).edit().putString(STATUS, status)
            .putLong(STATUS_AT, System.currentTimeMillis()).apply();
    }
}
