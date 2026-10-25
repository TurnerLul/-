package com.study.autonext;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * Only operates when the configured target app is foreground.
 * Accessibility exposes UI nodes, not the pixels or private state of a video player.
 * Therefore completion cues are best-effort; the fallback timer is deliberately explicit.
 */
public final class AutoNextService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long previousTick = 0L;
    private long activeMs = 0L;
    private long lastAttempt = 0L;
    private long endHintSince = 0L;
    private long progressHintSince = 0L;
    private long lastMediaCheck = 0L;
    private long previousResetToken = 0L;
    private String previousTarget = "";
    private String lastStatus = "";
    private AccessibilityNodeInfo currentNext;
    private long lastPoll = 0L;
    private boolean clicking = false;
    // Require the old video's end signal to disappear before acting on another signal.
    private boolean awaitCueClear = false;
    private long cueClearSince = 0L;
    private boolean mediaWasAwayFromEnd = true;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            poll();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        handler.removeCallbacks(ticker);
        previousTick = SystemClock.elapsedRealtime();
        previousTarget = Config.get(this).getString(Config.TARGET, "com.chaoxing.mobile");
        previousResetToken = Config.get(this).getLong(Config.RESET_TOKEN, 0L);
        writeStatus("辅助服务已连接，等待目标学习页面");
        handler.post(ticker);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        // A debounced event poll gives quicker completion detection than the 1 s ticker alone.
        long now = SystemClock.elapsedRealtime();
        if (now - lastPoll >= 650L && !clicking) handler.post(this::poll);
    }

    @Override public void onInterrupt() {
        writeStatus("无障碍服务被系统中断");
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(ticker);
        writeStatus("辅助服务已停止");
        super.onDestroy();
    }

    private void poll() {
        long now = SystemClock.elapsedRealtime();
        long delta = previousTick > 0 ? Math.min(2500L, Math.max(0L, now - previousTick)) : 0L;
        previousTick = now;
        lastPoll = now;

        SharedPreferences prefs = Config.get(this);
        String target = prefs.getString(Config.TARGET, "com.chaoxing.mobile");
        if (!target.equals(previousTarget)) {
            previousTarget = target;
            resetProgress();
        }
        long resetToken = prefs.getLong(Config.RESET_TOKEN, 0L);
        if (resetToken != previousResetToken) {
            previousResetToken = resetToken;
            resetProgress();
            writeStatus("已重新开始计时");
        }
        if (!prefs.getBoolean(Config.ENABLED, false)) {
            resetProgress();
            writeStatus("自动翻篇已暂停");
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            writeStatus("屏幕已关闭，暂停计时");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null ||
                !target.contentEquals(root.getPackageName())) {
            writeStatus("等待切换到：" + prefs.getString(Config.TARGET_NAME, "学习通"));
            return;
        }
        NodeScan scan = scanNodes(root);
        boolean coordinateMode = prefs.getBoolean(Config.FORCE_COORDINATE, false);
        boolean pageUsable = scan.next != null || coordinateMode;
        currentNext = scan.next;
        if (!pageUsable) {
            writeStatus("没有找到“下一篇”，等待课程视频页面");
            return;
        }

        if (clicking) return;
        activeMs += delta;
        prefs.edit().putLong(Config.ACTIVE_MS, activeMs).apply();
        String reason = null;
        boolean sawEnd = scan.explicitEnd;
        if (sawEnd) {
            if (endHintSince == 0L) endHintSince = now;
            if (now - endHintSince > 900L) reason = "识别到视频结束提示";
        } else endHintSince = 0L;

        boolean sawProgress = scan.progress >= 0.995f;
        if (sawProgress) {
            if (progressHintSince == 0L) progressHintSince = now;
            if (now - progressHintSince > 1200L) reason = "识别到视频进度达到末尾";
        } else progressHintSince = 0L;

        if (reason == null && prefs.getBoolean(Config.MEDIA, false)
                && now - lastMediaCheck > 1900L) {
            lastMediaCheck = now;
            if (mediaFinished(target, now)) reason = "检测到媒体播放已结束";
        }
        int minutes = prefs.getInt(Config.MINUTES, 5);
        if (reason == null && AdvancePolicy.fallbackDue(
                prefs.getBoolean(Config.FALLBACK, true), true, activeMs, minutes)) {
            reason = "备用计时达到 " + minutes + " 分钟";
        }

        long testAt = prefs.getLong(Config.TEST_CLICK_AT, 0L);
        if (testAt > 0 && now >= testAt) {
            prefs.edit().remove(Config.TEST_CLICK_AT).apply();
            if (now - testAt < 20_000L) reason = "你手动发起的点击测试";
        }
        if (awaitCueClear) {
            if (!sawEnd && !sawProgress) {
                if (cueClearSince == 0L) cueClearSince = now;
                if (now - cueClearSince >= 1700L) awaitCueClear = false;
            } else {
                cueClearSince = 0L;
            }
            // A stale completed video UI can persist during navigation. Only time-based
            // fallback or a user-requested test is allowed until the cues clear.
            if (awaitCueClear && reason != null && !reason.startsWith("备用计时")
                    && !reason.startsWith("你手动")) reason = null;
        }
        if (reason != null && AdvancePolicy.cooldownPassed(now, lastAttempt, 12_000L)) {
            clickNext(scan.next, coordinateMode, reason, now, prefs);
        } else {
            long remaining = Math.max(0, minutes * 60_000L - activeMs);
            if (!prefs.getBoolean(Config.FALLBACK, true)) {
                writeStatus("监测中：仅在识别到播放结束后翻篇");
            } else {
                long secs = (remaining + 999L) / 1000L;
                writeStatus(String.format(Locale.CHINA,
                    "监测中 · 备用点击倒计时 %d:%02d", secs / 60, secs % 60));
            }
        }
    }

    private void clickNext(AccessibilityNodeInfo node, boolean coordinateMode,
                           String reason, long now, SharedPreferences prefs) {
        clicking = true;
        awaitCueClear = true;
        cueClearSince = 0L;
        lastAttempt = now;
        activeMs = 0L;
        endHintSince = 0L;
        progressHintSince = 0L;
        prefs.edit().putLong(Config.ACTIVE_MS, 0L).putString(Config.LAST_REASON, reason).apply();
        if (node != null) {
            AccessibilityNodeInfo clickable = node;
            for (int i = 0; i < 5 && clickable != null; i++) {
                if (clickable.isClickable() && clickable.isEnabled()
                    && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    attempted(reason + " · 已执行控件点击", prefs);
                    return;
                }
                clickable = clickable.getParent();
            }
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() > 0 && bounds.height() > 0) {
                tap(bounds.centerX(), bounds.centerY(), reason + " · 已模拟按钮点击", prefs);
                return;
            }
        }
        if (coordinateMode) {
            Point size = new Point();
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) wm.getDefaultDisplay().getRealSize(size);
            if (size.x > 0 && size.y > 0) {
                int xPercent = prefs.getInt(Config.X_PERCENT, 87);
                int yPercent = prefs.getInt(Config.Y_PERCENT, 93);
                tap(size.x * xPercent / 100f, size.y * yPercent / 100f,
                    reason + " · 已执行坐标点击", prefs);
                return;
            }
        }
        clicking = false;
        writeStatus("未找到可点击的下一篇按钮，未进行点击");
    }

    private void tap(float x, float y, String reason, SharedPreferences prefs) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 100);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                attempted(reason, prefs);
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                clicking = false;
                writeStatus("点击手势被系统取消，本轮未完成");
            }
        }, handler);
        if (!dispatched) {
            clicking = false;
            writeStatus("系统未接受点击手势");
        }
    }

    private void attempted(String reason, SharedPreferences prefs) {
        clicking = false;
        long count = prefs.getLong(Config.ADVANCES, 0L) + 1L;
        prefs.edit().putLong(Config.ADVANCES, count).putString(Config.LAST_REASON, reason).apply();
        writeStatus("已尝试翻篇（不代表确认成功）：" + reason);
    }

    private void resetProgress() {
        activeMs = 0L;
        endHintSince = 0L;
        progressHintSince = 0L;
        awaitCueClear = false;
        cueClearSince = 0L;
        mediaWasAwayFromEnd = true;
        Config.get(this).edit().putLong(Config.ACTIVE_MS, 0L).apply();
    }

    private void writeStatus(String status) {
        if (!TextUtils.equals(status, lastStatus)) {
            lastStatus = status;
            Config.status(this, status);
        }
    }

    private static final class NodeScan {
        AccessibilityNodeInfo next;
        boolean explicitEnd;
        float progress = -1f;
    }

    private NodeScan scanNodes(AccessibilityNodeInfo root) {
        NodeScan scan = new NodeScan();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        Point size = new Point();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null) wm.getDefaultDisplay().getRealSize(size);
        int checked = 0;
        while (!queue.isEmpty() && checked++ < 350) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (!node.isVisibleToUser()) continue;
            String text = node.getText() == null ? "" : node.getText().toString().trim();
            String desc = node.getContentDescription() == null ? "" :
                node.getContentDescription().toString().trim();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (isNext(text) || isNext(desc)) {
                if (scan.next == null || bounds.centerY() > screenNodeCenterY(scan.next)) {
                    scan.next = node;
                }
            }
            if (isCompletion(text) || isCompletion(desc)) scan.explicitEnd = true;
            AccessibilityNodeInfo.RangeInfo info = node.getRangeInfo();
            String clazz = node.getClassName() == null ? "" : node.getClassName().toString();
            if (info != null && (clazz.contains("SeekBar") || clazz.contains("Slider")
                    || clazz.contains("ProgressBar"))) {
                float fraction = AdvancePolicy.progress(
                        info.getCurrent(), info.getMin(), info.getMax());
                // Exclude bottom screen controls; a player seek bar is typically in the video area.
                if (fraction > scan.progress && size.y > 0
                        && bounds.centerY() > size.y * .15f
                        && bounds.centerY() < size.y * .89f) scan.progress = fraction;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return scan;
    }

    private int screenNodeCenterY(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return rect.centerY();
    }

    private boolean isNext(String text) {
        return text.equals("下一篇") || text.equals("下一节")
                || text.equals("下一课") || text.equals("下一个视频")
                || text.startsWith("下一篇 ") || text.startsWith("下一节 ");
    }

    private boolean isCompletion(String text) {
        return text.equals("重播") || text.equals("重新播放")
                || text.equals("播放完成") || text.equals("播放结束")
                || text.equals("视频结束") || text.equals("视频已结束")
                || text.equals("视频播放完毕") || text.equals("播放完毕")
                || text.equals("观看完成") || text.equals("已观看完成");
    }

    private boolean mediaFinished(String target, long now) {
        try {
            MediaSessionManager manager =
                (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return false;
            List<MediaController> controllers = manager.getActiveSessions(
                new ComponentName(this, MediaObserverService.class));
            if (controllers == null) return false;
            for (MediaController controller : controllers) {
                if (!target.equals(controller.getPackageName())) continue;
                MediaMetadata metadata = controller.getMetadata();
                PlaybackState state = controller.getPlaybackState();
                if (metadata == null || state == null) continue;
                long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                long position = state.getPosition();
                if (duration < 1000 || position < 0) continue;
                if (state.getState() == PlaybackState.STATE_PLAYING) {
                    long elapsed = Math.max(0L, now - state.getLastPositionUpdateTime());
                    position += (long)(elapsed * state.getPlaybackSpeed());
                }
                if (position < duration - 5000L) mediaWasAwayFromEnd = true;
                if (mediaWasAwayFromEnd && position >= duration - 1100L
                    && (state.getState() == PlaybackState.STATE_PAUSED
                    || state.getState() == PlaybackState.STATE_STOPPED
                    || state.getState() == PlaybackState.STATE_NONE)) {
                    mediaWasAwayFromEnd = false;
                    return true;
                }
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Media-session visibility is optional and often unavailable for embedded WebView video.
        }
        return false;
    }
}
