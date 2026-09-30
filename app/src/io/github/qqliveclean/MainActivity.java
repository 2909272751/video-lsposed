package io.github.qqliveclean;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashMap;
import java.util.Map;

/** Three app-specific setting pages, with one shared compatibility and recovery entry. */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(245, 247, 250);
    private static final int INK = Color.rgb(30, 43, 48);
    private static final int MUTED = Color.rgb(101, 116, 124);
    private static final int ACCENT = Color.rgb(0, 122, 255);
    private static final int SECTION = Color.rgb(26, 111, 160);

    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private final Map<String, Boolean> defaults = new LinkedHashMap<>();
    private TextView status;
    private boolean refreshing;
    private final ScrollView[] pages = new ScrollView[3];
    private final Button[] pageButtons = new Button[3];
    private int selectedPage;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(16), dp(14), dp(16), 0);
        setContentView(root);

        root.addView(text("视频精简", 25, INK, true));
        TextView subtitle = text("选择应用，按页面分别设置", 13, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(10));
        root.addView(subtitle);

        LinearLayout statusCard = card();
        status = text("", 14, INK, false);
        statusCard.addView(status);
        Button checkCompatibility = new Button(this);
        checkCompatibility.setText("查看本应用兼容结果");
        checkCompatibility.setAllCaps(false);
        checkCompatibility.setTextColor(ACCENT);
        checkCompatibility.setBackgroundColor(Color.TRANSPARENT);
        checkCompatibility.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { scanCurrentApp(); }
        });
        statusCard.addView(checkCompatibility);
        root.addView(statusCard);

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setPadding(0, dp(12), 0, dp(8));
        String[] names = {"腾讯视频", "优酷", "爱奇艺"};
        for (int index = 0; index < names.length; index++) {
            final int selected = index;
            Button button = new Button(this);
            button.setText(names[index]);
            button.setTextSize(13);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(0, 0, 0, 0);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { showPage(selected); }
            });
            pageButtons[index] = button;
            navigation.addView(button, new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        root.addView(navigation);

        FrameLayout pageHost = new FrameLayout(this);
        LinearLayout[] pageBodies = new LinearLayout[3];
        for (int index = 0; index < pages.length; index++) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            LinearLayout pageBody = new LinearLayout(this);
            pageBody.setOrientation(LinearLayout.VERTICAL);
            pageBody.setPadding(0, 0, 0, dp(30));
            scroll.addView(pageBody);
            pages[index] = scroll;
            pageBodies[index] = pageBody;
            pageHost.addView(scroll);
        }
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = pageBodies[0];

        section(body, "启动与播放");
        LinearLayout splashCard = card();
        toggle(splashCard, Config.BLOCK_SPLASH, "拦截开屏广告",
                "阻止新请求，并跳过已缓存广告的展示", true);
        toggle(splashCard, Config.BLOCK_PLAYER_ADS, "拦截播放广告",
                "尝试阻止播放前、中、后的广告请求", true);
        toggle(splashCard, Config.BLOCK_FEED_AUTOPLAY, "关闭首页片段自动播放",
                "首页预览不自动起播；主动点开视频仍可播放", true);
        body.addView(splashCard);

        section(body, "首页与个人中心");
        LinearLayout moreAdsCard = card();
        toggle(moreAdsCard, Config.BLOCK_AD_REQUESTS, "隐藏首页顶部轮播",
                "整块隐藏，里面的普通推荐也会一并移除", true);
        toggle(moreAdsCard, Config.BLOCK_MINE_AD, "隐藏「我的」广告横幅",
                "只处理个人中心的独立广告卡片", true);
        body.addView(moreAdsCard);

        section(body, "底部导航");
        LinearLayout tabsCard = card();
        toggle(tabsCard, Config.SHOW_SHORT_VIDEO, "显示「短剧」", null, true);
        toggle(tabsCard, Config.SHOW_VIP, "显示「会员专区」", null, true);
        toggle(tabsCard, Config.SHOW_GOODS, "显示「好物」", null, true);
        addNote(tabsCard, "关闭开关隐藏对应入口，首页和个人中心始终保留。修改后重启腾讯视频生效。", 12);
        body.addView(tabsCard);

        section(body, "通知");
        LinearLayout qqliveNotifyCard = card();
        toggle(qqliveNotifyCard, Config.BLOCK_PUSH_NOTIFY, "拦截推送通知广告",
                "只拦会员促销类通知；追剧提醒、播放和下载通知不受影响", true);
        body.addView(qqliveNotifyCard);

        body = pageBodies[1];
        section(body, "启动与播放");
        LinearLayout youkuCard = card();
        toggle(youkuCard, Config.YOUKU_BLOCK_SPLASH, "拦截开屏广告",
                "覆盖冷启动和回到前台时的开屏广告", true);
        toggle(youkuCard, Config.YOUKU_BLOCK_PAUSE_AD, "拦截全屏暂停广告",
                "暂停播放时不展示全屏广告", true);
        body.addView(youkuCard);

        section(body, "首页与个人中心");
        LinearLayout youkuPromoCard = card();
        toggle(youkuPromoCard, Config.YOUKU_BLOCK_AD_SLOT, "隐藏首页顶部轮播",
                "整块隐藏，普通推荐也会一并移除", true);
        toggle(youkuPromoCard, Config.YOUKU_HIDE_MINE_PROMOS, "隐藏「我的」活动推广",
                "收起历史记录下方的活动条和轮播", true);
        body.addView(youkuPromoCard);

        section(body, "底部导航");
        LinearLayout youkuTabs = card();
        addNote(youkuTabs, "关闭不常用入口；首页和「我的」始终保留。", 12);
        toggle(youkuTabs, Config.YOUKU_SHOW_SHORT_DRAMA, "显示「短剧」", null, true);
        toggle(youkuTabs, Config.YOUKU_SHOW_VIP, "显示「会员」", null, true);
        toggle(youkuTabs, Config.YOUKU_SHOW_GOOD_MOVIES, "显示「淘好片」", null, true);
        toggle(youkuTabs, Config.YOUKU_HIDE_BOTTOM_BAR, "隐藏整个底部导航",
                "所有入口都会消失，默认关闭", false);
        body.addView(youkuTabs);

        section(body, "通知");
        LinearLayout youkuNotifyCard = card();
        toggle(youkuNotifyCard, Config.BLOCK_PUSH_NOTIFY, "拦截推送通知广告",
                "只拦会员促销类通知；追剧提醒、播放和下载通知不受影响", true);
        body.addView(youkuNotifyCard);

        body = pageBodies[2];
        section(body, "启动广告");
        LinearLayout qiyiCard = card();
        toggle(qiyiCard, Config.IQIYI_BLOCK_SPLASH, "拦截开屏广告",
                "阻止启动时的广告请求", true);
        body.addView(qiyiCard);

        section(body, "首页与个人中心");
        LinearLayout qiyiPromoCard = card();
        toggle(qiyiPromoCard, Config.IQIYI_HIDE_HOME_TOP_AD, "隐藏首页顶部轮播",
                "整块隐藏，普通推荐也会一并移除", true);
        toggle(qiyiPromoCard, Config.IQIYI_HIDE_MINE_BANNER, "隐藏「我的」顶部广告",
                "只处理标有「广告」的横幅", true);
        body.addView(qiyiPromoCard);

        section(body, "底部导航");
        LinearLayout qiyiTabs = card();
        addNote(qiyiTabs, "关闭不常用入口；首页和「我的」始终保留。", 12);
        toggle(qiyiTabs, Config.IQIYI_SHOW_FREE, "显示「免费」", null, true);
        toggle(qiyiTabs, Config.IQIYI_SHOW_PLUS, "显示中间的「＋」入口", null, true);
        toggle(qiyiTabs, Config.IQIYI_SHOW_MEMBER, "显示「会员」", null, true);
        body.addView(qiyiTabs);

        section(body, "通知");
        LinearLayout qiyiNotifyCard = card();
        toggle(qiyiNotifyCard, Config.BLOCK_PUSH_NOTIFY, "拦截推送通知广告",
                "只拦会员促销类通知；追剧提醒、播放和下载通知不受影响", true);
        body.addView(qiyiNotifyCard);

        body = pageBodies[0];
        section(body, "诊断与恢复");
        LinearLayout debugCard = card();
        addNote(debugCard, "顶部的「兼容结果」可查看每项规则是否安装、是否实际触发。", 12);
        toggle(debugCard, Config.DEBUG_LOG, "记录详细日志",
                "遇到漏拦截时再开启，便于定位", false);
        body.addView(debugCard);

        LinearLayout footer = card();
        Button reset = new Button(this);
        reset.setAllCaps(false);
        reset.setText("恢复默认设置");
        reset.setTextColor(ACCENT);
        reset.setBackgroundColor(Color.TRANSPARENT);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (App.reset(MainActivity.this)) {
                    refresh();
                    Toast.makeText(MainActivity.this, "设置已恢复；请重启目标应用", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "保存失败：" + App.lastError(), Toast.LENGTH_LONG).show();
                }
            }
        });
        footer.addView(reset);
        addNote(footer, "设置改动后，强停并重新打开对应应用。应用更新后请查看兼容结果。", 12);
        body.addView(footer);
        showPage(state == null ? 0 : state.getInt("selected_page", 0));
    }

    private void showPage(int selected) {
        selectedPage = selected;
        String packageName = selected == 0 ? Config.PACKAGE
                : selected == 1 ? Config.PACKAGE_YOUKU : Config.PACKAGE_IQIYI;
        String label = Config.appLabel(packageName);
        String suffix = " · 新版本，规则逐项尝试";
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(packageName, 0);
            if (info.versionCode == Config.verifiedVersionCode(packageName))
                suffix = " · 此版本有真机适配记录";
        } catch (Throwable ignored) { suffix = " · 未安装"; }
        status.setText("版本 " + installedVersion(packageName) + suffix
                + "\n修改后强停并重新打开" + label);
        for (int index = 0; index < pages.length; index++) {
            pages[index].setVisibility(index == selected ? View.VISIBLE : View.GONE);
            pageButtons[index].setTextColor(index == selected ? Color.WHITE : SECTION);
            GradientDrawable background = new GradientDrawable();
            background.setColor(index == selected ? SECTION : Color.WHITE);
            background.setCornerRadius(dp(12));
            pageButtons[index].setBackground(background);
        }
    }

    private void scanCurrentApp() {
        final String packageName = selectedPage == 0 ? Config.PACKAGE
                : selectedPage == 1 ? Config.PACKAGE_YOUKU : Config.PACKAGE_IQIYI;
        final android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
        progress.setTitle("版本与兼容检测");
        progress.setMessage("正在读取 " + Config.appLabel(packageName) + " 的逐项结果…");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();
        new Thread(new Runnable() {
            @Override public void run() {
                final String result = CompatibilityScanner.scan(MainActivity.this, packageName);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        progress.dismiss();
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle(Config.appLabel(packageName) + " · 兼容结果")
                                .setMessage(result)
                                .setPositiveButton("关闭", null)
                                .show();
                    }
                });
            }
        }, "compat-scan").start();
    }

    @Override protected void onResume() {
        super.onResume();
        // Re-publish on every visit so the file channel is current even if a write was missed.
        App.publish(this);
        refresh();
    }

    private void refresh() {
        showPage(selectedPage);
        status.setTextColor(INK);

        refreshing = true;
        try {
            for (Map.Entry<String, Switch> entry : switches.entrySet()) {
                String key = entry.getKey();
                boolean wanted = App.read(this, key, defaults.get(key));
                if (entry.getValue().isChecked() != wanted) entry.getValue().setChecked(wanted);
            }
        } finally { refreshing = false; }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("selected_page", selectedPage);
        super.onSaveInstanceState(state);
    }

    private String versionText(String packageName, int verifiedCode) {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(packageName, 0);
            return info.versionName + (info.versionCode == verifiedCode
                    ? " · 已在本机验证部分规则" : " · 新版本，规则将逐项尝试");
        } catch (Throwable ignored) {
            return "未安装";
        }
    }

    private String installedVersion(String packageName) {
        try {
            return getPackageManager().getPackageInfo(packageName, 0).versionName;
        } catch (Throwable ignored) {
            return "未安装";
        }
    }

    private void toggle(LinearLayout parent, String key, String title, String detail, boolean initial) {
        if (parent.getChildCount() > 0) separator(parent);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(detail == null ? 48 : 62));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, INK, false));
        if (detail != null) {
            TextView hint = text(detail, 12, MUTED, false);
            hint.setPadding(0, dp(3), dp(8), 0);
            labels.addView(hint);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(this);
        control.setContentDescription(title);
        control.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, Color.rgb(222, 226, 229)}));
        control.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.rgb(168, 205, 245), Color.rgb(190, 196, 199)}));
        control.setChecked(initial);
        control.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (refreshing) return;
                if (!App.write(MainActivity.this, key, checked)) {
                    button.setOnCheckedChangeListener(null);
                    button.setChecked(!checked);
                    button.setOnCheckedChangeListener(this);
                    Toast.makeText(MainActivity.this,
                            "保存失败：" + App.lastError(), Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(MainActivity.this, checked ? "已开启，重启目标应用生效" : "已关闭，重启目标应用生效",
                        Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(control);
        parent.addView(row);
        switches.put(key, control);
        defaults.put(key, initial);
    }

    private void section(LinearLayout body, String title) {
        TextView label = text(title, 14, SECTION, true);
        label.setPadding(dp(4), dp(20), 0, dp(8));
        body.addView(label);
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(13), dp(9), dp(13), dp(9));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.WHITE);
        shape.setCornerRadius(dp(13));
        box.setBackground(shape);
        return box;
    }

    private void addNote(LinearLayout box, String message, int size) {
        TextView note = text(message, size, MUTED, false);
        note.setPadding(0, dp(4), 0, dp(5));
        box.addView(note);
    }

    private void separator(LinearLayout box) {
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(239, 242, 244));
        box.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private TextView text(String content, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    /** Partition header for one target app: bold, accented and spaced from what precedes it. */
    private void appHeader(LinearLayout parent, String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextSize(15);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(0xFF1565C0);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(22);
        params.bottomMargin = dp(6);
        header.setLayoutParams(params);
        parent.addView(header);
        View rule = new View(this);
        rule.setBackgroundColor(0x331565C0);
        LinearLayout.LayoutParams ruleParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        ruleParams.bottomMargin = dp(8);
        rule.setLayoutParams(ruleParams);
        parent.addView(rule);
    }
}
