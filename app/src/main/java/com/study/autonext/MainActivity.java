package com.study.autonext;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Entire UI uses standard Android widgets; no network, logins, libraries or advertising. */
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(29, 37, 59);
    private static final int SECONDARY = Color.rgb(104, 116, 143);
    private static final int BG = Color.rgb(246, 248, 252);
    private static final int ACCENT = Color.rgb(72, 88, 216);
    private static final int[] MINUTES = {3, 5, 10, 15, 20, 30, 45, 60, 90, 120};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updater = new Runnable() {
        @Override public void run() {
            renderState();
            handler.postDelayed(this, 1200L);
        }
    };
    private LinearLayout body;
    private TextView running, status, targetText, fallbackText, countText, footerText;
    private Switch enabledSwitch, fallbackSwitch, coordinateSwitch, mediaSwitch;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Config.get(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(updater);
        handler.post(updater);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(updater);
        super.onPause();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        body = column();
        body.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(body);
        setContentView(scroll);

        TextView eyebrow = text("学习辅助工具  /  ANDROID", 11, ACCENT, true);
        body.addView(eyebrow);
        TextView heading = text("视频自动翻篇", 30, INK, true);
        marginTop(body, heading, 7);
        TextView subtitle = text("视频结束自动进入下一篇 · 识别失败使用备用计时", 13, SECONDARY, false);
        marginTop(body, subtitle, 6);

        LinearLayout hero = card(Color.rgb(38, 49, 90), 18);
        hero.setTag("dark");
        marginTop(body, hero, 24);
        running = text("检查中…", 17, Color.WHITE, true);
        hero.addView(running);
        status = text("等待辅助服务连接", 13, Color.rgb(216, 224, 251), false);
        marginTop(hero, status, 9);
        countText = text("翻篇尝试  0 次", 12, Color.rgb(191, 204, 247), false);
        marginTop(hero, countText, 10);
        enabledSwitch = toggleRow(hero, "启用自动翻篇", "只在选定的学习 App 运行", Config.ENABLED, false,
                (on) -> {
                    prefs.edit().putBoolean(Config.ENABLED, on).apply();
                    if (on && !accessibilityEnabled()) {
                        new AlertDialog.Builder(this)
                            .setTitle("需要开启无障碍服务")
                            .setMessage("请在系统设置中找到“视频自动翻篇辅助服务”并启用。此权限用于读取当前页面上的视频结束文字和点击“下一篇”。")
                            .setPositiveButton("去设置", (d,w) -> openAccessibility())
                            .setNegativeButton("稍后", null).show();
                    }
                    renderState();
                });

        sectionTitle("运行设置");
        LinearLayout controls = card(Color.WHITE, 16);
        body.addView(controls);
        targetText = action(controls, "目标应用", "学习通（点击切换）", this::chooseTarget);
        thinRule(controls);
        fallbackSwitch = toggleRow(controls, "备用计时翻篇", "未检测到结束也可按计时尝试", Config.FALLBACK, true,
                (on) -> prefs.edit().putBoolean(Config.FALLBACK, on).apply());
        thinRule(controls);
        fallbackText = action(controls, "备用等待时长", "5 分钟", this::chooseMinutes);
        thinRule(controls);
        coordinateSwitch = toggleRow(controls, "找不到按钮时按坐标点击", "默认关闭；仅确认界面一致后开启", Config.FORCE_COORDINATE, false,
                (on) -> {
                    if (on) new AlertDialog.Builder(this)
                        .setTitle("坐标点击可能误触")
                        .setMessage("只有目标应用在前台时才会执行，但若你处在课程列表或设置页，也可能误点。建议先进入视频页、设置正确的“下一篇”坐标，再启用。")
                        .setPositiveButton("我已了解", (d,w) -> prefs.edit().putBoolean(Config.FORCE_COORDINATE, true).apply())
                        .setNegativeButton("取消", (d,w) -> {
                            coordinateSwitch.setChecked(false);
                            prefs.edit().putBoolean(Config.FORCE_COORDINATE, false).apply();
                        }).show();
                    else prefs.edit().putBoolean(Config.FORCE_COORDINATE, false).apply();
                });
        thinRule(controls);
        action(controls, "调整模拟点击位置", "根据截图默认在右下角（87%, 93%）", this::chooseCoordinates);

        sectionTitle("高级监测");
        LinearLayout advanced = card(Color.WHITE, 16);
        body.addView(advanced);
        mediaSwitch = toggleRow(advanced, "媒体状态识别（可选）", "部分播放器允许读取媒体时长与播放状态", Config.MEDIA, false,
                (on) -> {
                    prefs.edit().putBoolean(Config.MEDIA, on).apply();
                    if (on) new AlertDialog.Builder(this)
                        .setTitle("媒体状态检测权限")
                        .setMessage("可选：请在“通知使用权”设置中允许“视频结束检测（可选）”。学习通内嵌视频未必提供媒体状态，打不开也不影响计时功能。")
                        .setPositiveButton("打开设置", (d,w) -> {
                            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                        }).setNegativeButton("稍后", null).show();
                });
        thinRule(advanced);
        action(advanced, "重新开始计时", "回到视频页后，从零计算备用时间", () -> {
            prefs.edit().putLong(Config.RESET_TOKEN, System.currentTimeMillis()).apply();
            Toast.makeText(this, "已发送重置指令", Toast.LENGTH_SHORT).show();
        });
        thinRule(advanced);
        action(advanced, "测试下一篇按钮", "打开目标 App 并在 5 秒后尝试一次", () -> {
            if (!accessibilityEnabled()) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
                openAccessibility();
                return;
            }
            prefs.edit().putBoolean(Config.ENABLED, true)
                .putLong(Config.TEST_CLICK_AT, SystemClock.elapsedRealtime() + 5000L).apply();
            Intent launch = getPackageManager().getLaunchIntentForPackage(
                    prefs.getString(Config.TARGET, "com.chaoxing.mobile"));
            if (launch != null) startActivity(launch);
            else Toast.makeText(this, "请手动打开目标 App 的视频页", Toast.LENGTH_LONG).show();
        });

        sectionTitle("权限与使用说明");
        LinearLayout help = card(Color.WHITE, 16);
        body.addView(help);
        action(help, "开启无障碍权限", "自动识别控件及模拟点击所需", this::openAccessibility);
        thinRule(help);
        action(help, "关于本软件", "作者、版本、识别范围与注意事项", this::showAbout);
        TextView tip = text("使用提示：保持手机亮屏，进入课程视频页后再启动。计时仅累计目标应用在前台、且课程页可操作的时间；5 分钟兜底不会判断视频是否真的播完。", 12, SECONDARY, false);
        tip.setLineSpacing(dp(3), 1f);
        marginTop(body, tip, 18);
        footerText = text("作者：剑圣不是易无极    ·    v1.0.0", 12, SECONDARY, false);
        footerText.setGravity(Gravity.CENTER_HORIZONTAL);
        marginTop(body, footerText, 22);
        renderState();
    }

    private void renderState() {
        if (running == null) return;
        boolean access = accessibilityEnabled();
        boolean enabled = prefs.getBoolean(Config.ENABLED, false);
        running.setText(!access ? "无障碍未开启" : (enabled ? "自动翻篇正在运行" : "自动翻篇已暂停"));
        status.setText(prefs.getString(Config.STATUS,
            "启用服务后，打开目标课程视频"));
        countText.setText("翻篇尝试  " + prefs.getLong(Config.ADVANCES, 0L) + " 次（不代表成功次数）");
        targetText.setText(prefs.getString(Config.TARGET_NAME, "学习通") + "  ·  " +
            prefs.getString(Config.TARGET, "com.chaoxing.mobile"));
        fallbackText.setText(prefs.getInt(Config.MINUTES, 5) + " 分钟（可调整）");
        if (enabledSwitch != null && enabledSwitch.isChecked() != enabled)
            enabledSwitch.setChecked(enabled);
    }

    private void chooseMinutes() {
        String[] labels = new String[MINUTES.length];
        int saved = prefs.getInt(Config.MINUTES, 5);
        int chosen = 1;
        for (int i = 0; i < MINUTES.length; i++) {
            labels[i] = MINUTES[i] + " 分钟";
            if (MINUTES[i] == saved) chosen = i;
        }
        new AlertDialog.Builder(this).setTitle("识别失败后等待多久？")
                .setSingleChoiceItems(labels, chosen, (dialog, which) -> {
                    prefs.edit().putInt(Config.MINUTES, MINUTES[which]).apply();
                    fallbackText.setText(MINUTES[which] + " 分钟（可调整）");
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseTarget() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
        List<AppChoice> apps = new ArrayList<>();
        apps.add(new AppChoice("学习通（默认）", "com.chaoxing.mobile"));
        for (ResolveInfo info : infos) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.equals("com.chaoxing.mobile")) continue;
            String label = info.loadLabel(pm).toString();
            boolean duplicate = false;
            for (AppChoice a : apps) if (a.pkg.equals(pkg)) { duplicate = true; break; }
            if (!duplicate) apps.add(new AppChoice(label, pkg));
        }
        if (apps.size() > 1) {
            List<AppChoice> rest = apps.subList(1, apps.size());
            java.text.Collator collator = java.text.Collator.getInstance(Locale.CHINA);
            Collections.sort(rest, (a, b) -> collator.compare(a.label, b.label));
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).label;
        new AlertDialog.Builder(this).setTitle("选择要自动翻篇的 App")
            .setItems(labels, (dialog, position) -> {
                AppChoice app = apps.get(position);
                prefs.edit().putString(Config.TARGET, app.pkg)
                    .putString(Config.TARGET_NAME, app.label)
                    .putLong(Config.RESET_TOKEN, System.currentTimeMillis()).apply();
                renderState();
            }).setNegativeButton("取消", null).show();
    }

    private void chooseCoordinates() {
        LinearLayout layout = column();
        layout.setPadding(dp(20), dp(12), dp(20), dp(10));
        int savedX = prefs.getInt(Config.X_PERCENT, 87);
        int savedY = prefs.getInt(Config.Y_PERCENT, 93);
        TextView xLabel = text("横向位置：" + savedX + "%", 14, INK, true);
        TextView yLabel = text("纵向位置：" + savedY + "%", 14, INK, true);
        SeekBar xSeek = new SeekBar(this);
        xSeek.setMax(100); xSeek.setProgress(savedX);
        SeekBar ySeek = new SeekBar(this);
        ySeek.setMax(100); ySeek.setProgress(savedY);
        layout.addView(xLabel); layout.addView(xSeek);
        marginTop(layout, yLabel, 12); layout.addView(ySeek);
        xSeek.setOnSeekBarChangeListener(watch(n -> xLabel.setText("横向位置：" + n + "%")));
        ySeek.setOnSeekBarChangeListener(watch(n -> yLabel.setText("纵向位置：" + n + "%")));
        new AlertDialog.Builder(this).setTitle("模拟点击坐标（屏幕百分比）")
            .setMessage("参考截图，下一篇在屏幕右下角。仅在确实找不到按钮时使用坐标点击。")
            .setView(layout)
            .setPositiveButton("保存", (d,w) -> prefs.edit()
                .putInt(Config.X_PERCENT, xSeek.getProgress())
                .putInt(Config.Y_PERCENT, ySeek.getProgress()).apply())
            .setNegativeButton("取消", null).show();
    }

    private interface IntChange { void changed(int v); }
    private SeekBar.OnSeekBarChangeListener watch(IntChange listener) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                listener.changed(progress);
            }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
        };
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
            .setTitle("关于视频自动翻篇")
            .setMessage("作者：剑圣不是易无极\n版本：1.0.0\n\n检测方式：\n① 无障碍页面中的“播放结束”“重播”等提示\n② 可访问的视频进度条到达末尾\n③ 可选的媒体播放状态（需要单独授权）\n④ 以上失效时的备用计时\n\n仅在选中的目标 App 前台时运行；不需要 Root，不上传学习数据。\n\n注意：无障碍无法读取所有 WebView/视频的内部状态；自动点击不保证下一篇加载成功，也不保证学习平台记录观看完成。")
            .setPositiveButton("知道了", null).show();
    }

    private void openAccessibility() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String expected = new ComponentName(this, AutoNextService.class).flattenToString();
        for (String service : enabled.split(":")) if (expected.equalsIgnoreCase(service)) return true;
        return false;
    }

    private interface OnToggle { void changed(boolean value); }
    private Switch toggleRow(LinearLayout parent, String title, String description,
                             String key, boolean defaultValue, OnToggle onToggle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(12));
        LinearLayout labels = column();
        LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0, -2, 1f);
        row.addView(labels, labelsLp);
        boolean dark = "dark".equals(parent.getTag());
        labels.addView(text(title, 15, dark ? Color.WHITE : INK, true));
        TextView hint = text(description, 12,
                dark ? Color.rgb(216, 224, 251) : SECONDARY, false);
        marginTop(labels, hint, 4);
        Switch control = new Switch(this);
        control.setChecked(prefs.getBoolean(key, defaultValue));
        control.setOnCheckedChangeListener((v, enabled) -> onToggle.changed(enabled));
        row.addView(control);
        parent.addView(row);
        return control;
    }

    private TextView action(LinearLayout parent, String title, String detail, Runnable act) {
        LinearLayout wrapper = column();
        wrapper.setPadding(0, dp(12), 0, dp(12));
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(title, 15, INK, true);
        line.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = text("›", 26, SECONDARY, false);
        line.addView(arrow);
        wrapper.addView(line);
        TextView detailText = text(detail, 12, SECONDARY, false);
        marginTop(wrapper, detailText, 4);
        parent.addView(wrapper);
        wrapper.setBackground(makeBg(Color.TRANSPARENT, 0));
        wrapper.setOnClickListener(view -> act.run());
        return detailText;
    }

    private void sectionTitle(String title) {
        TextView label = text(title, 15, INK, true);
        marginTop(body, label, 24);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) label.getLayoutParams();
        lp.bottomMargin = dp(10);
    }
    private LinearLayout card(int color, int radius) {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(makeBg(color, radius));
        return card;
    }
    private GradientDrawable makeBg(int color, int radius) {
        GradientDrawable b = new GradientDrawable();
        b.setColor(color);
        b.setCornerRadius(dp(radius));
        return b;
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private TextView text(String str, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(str); tv.setTextSize(sp); tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setIncludeFontPadding(true);
        return tv;
    }
    private void marginTop(LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(margin);
        parent.addView(view, lp);
    }
    private void thinRule(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(237, 240, 247));
        parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + .5f);
    }
    private static class AppChoice {
        final String label, pkg;
        AppChoice(String label, String pkg) { this.label = label; this.pkg = pkg; }
    }
}
