package com.study.autonext;

/** Pure Java rules, intentionally separate for lightweight unit tests. */
public final class AdvancePolicy {
    private AdvancePolicy() { }
    public static boolean fallbackDue(boolean enabled, boolean targetForeground,
                                      long activeMs, int minutes) {
        return enabled && targetForeground && minutes > 0
            && activeMs >= minutes * 60_000L;
    }
    public static boolean cooldownPassed(long nowMs, long lastAttemptMs, long cooldownMs) {
        return lastAttemptMs <= 0 || nowMs - lastAttemptMs >= cooldownMs;
    }
    public static boolean validProgress(float current, float min, float max) {
        return max > min && current >= min && current <= max;
    }
    public static float progress(float current, float min, float max) {
        return validProgress(current, min, max) ? (current - min) / (max - min) : -1f;
    }
}
