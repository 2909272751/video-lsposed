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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One page per target app, reached from a list.
 *
 * <p>The previous layout used four tabs and put five apps' worth of switches into a single
 * "其他" tab, so one screen held around forty toggles from six different apps - no way to tell
 * which switch belonged to which app. Now the first page is a list of apps, each showing how
 * many of its own switches are on, and tapping one opens that app alone.
 */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(245, 247, 250);
    private static final int INK = Color.rgb(30, 43, 48);
    private static final int MUTED = Color.rgb(101, 116, 124);
    private static final int ACCENT = Color.rgb(0, 122, 255);
    private static final int SECTION = Color.rgb(26, 111, 160);

    private static final int PAGE_LIST = 0;
    private static final int PAGE_COUNT = 9;
    /** Entry titles, indexed by page; index 0 is the list page and has no title. */
    private static final String[] PAGE_TITLES = {
        "", "腾讯视频", "优酷", "爱奇艺", "QQ 音乐", "滴滴出行", "淘宝 / 闲鱼", "微博", "OPPO 软件商店"};

    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private final Map<String, Boolean> defaults = new LinkedHashMap<>();
    /** Keys owned by each page, in order, so the list can count them without re-walking the tree. */
    private final List<List<String>> pageKeys = new ArrayList<List<String>>();
    private final TextView[] statusForPage = new TextView[PAGE_COUNT];
    private final Map<Integer, Button> compatButtons = new LinkedHashMap<Integer, Button>();
    private TextView status;
    private Button backButton;
    private TextView headerTitle;
    private TextView headerSubtitle;
    private LinearLayout listBody;
    private boolean refreshing;
    private final ScrollView[] pages = new ScrollView[PAGE_COUNT];
    private int selectedPage;
    private List<String> buildingKeys;
    private int pageBeingBuilt;

    /** The app a page belongs to, or null for the list page. */
    private String pagePackage(int page) {
        switch (page) {
            case 1: return Config.PACKAGE;
            case 2: return Config.PACKAGE_YOUKU;
            case 3: return Config.PACKAGE_IQIYI;
            case 4: return Config.PACKAGE_QQMUSIC;
            case 5: return Config.PACKAGE_DIDI;
            // 淘宝 and 闲鱼 ship one rule set, so they stay one entry instead of two identical ones.
            case 6: return Config.PACKAGE_TAOBAO;
            case 7: return Config.PACKAGE_WEIBO;
            case 8: return Config.PACKAGE_HEYTAP;
            default: return null;
        }
    }

    private String pageFamily(int page) {
        switch (page) {
            case 4: return FamilySettings.QQMUSIC;
            case 5: return FamilySettings.DIDI;
            case 6: return FamilySettings.TAOBAO;
            case 7: return FamilySettings.WEIBO;
            case 8: return FamilySettings.HEYTAP;
            default: return null;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(16), dp(14), dp(16), 0);
        setContentView(root);

        // Header doubles as the detail-page title bar: the list page shows the app name, and a
        // detail page shows that app's own name with a back arrow.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        backButton = new Button(this);
        backButton.setText("‹");
        backButton.setTextSize(24);
        backButton.setAllCaps(false);
        backButton.setMinWidth(0);
        backButton.setMinimumWidth(0);
        backButton.setPadding(dp(6), 0, dp(12), 0);
        backButton.setTextColor(SECTION);
        backButton.setBackgroundColor(Color.TRANSPARENT);
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showPage(PAGE_LIST); }
        });
        header.addView(backButton);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        headerTitle = text("广告净化", 25, INK, true);
        titles.addView(headerTitle);
        headerSubtitle = text("六个去广告模块已合并为一个，统一在这里设置", 13, MUTED, false);
        headerSubtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(headerSubtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        FrameLayout pageHost = new FrameLayout(this);
        LinearLayout[] pageBodies = new LinearLayout[PAGE_COUNT];
        for (int index = 0; index < PAGE_COUNT; index++) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            LinearLayout pageBody = new LinearLayout(this);
            pageBody.setOrientation(LinearLayout.VERTICAL);
            pageBody.setPadding(0, 0, 0, dp(30));
            scroll.addView(pageBody);
            pages[index] = scroll;
            pageBodies[index] = pageBody;
            pageKeys.add(new ArrayList<String>());
            pageHost.addView(scroll);
        }
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        listBody = pageBodies[PAGE_LIST];
        LinearLayout body = pageBodies[1];
        buildingKeys = pageKeys.get(1);
        pageBeingBuilt = 1;
        addPageStatus(body, text("", 13, MUTED, false));

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

        body = pageBodies[2];
        buildingKeys = pageKeys.get(2);
        pageBeingBuilt = 2;
        addPageStatus(body, text("", 13, MUTED, false));
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

        section(body, "频道入口");
        LinearLayout youkuChannels = card();
        addNote(youkuChannels, "关闭后顶部频道栏不再显示该入口；只隐藏界面，不改动优酷的数据与选片逻辑。", 12);
        for (String[] entry : Config.CHANNEL_CATALOG) {
            toggle(youkuChannels, entry[0], "显示「" + entry[1] + "」", null, true);
        }
        body.addView(youkuChannels);

        section(body, "通知");
        LinearLayout youkuNotifyCard = card();
        toggle(youkuNotifyCard, Config.BLOCK_PUSH_NOTIFY, "拦截推送通知广告",
                "只拦会员促销类通知；追剧提醒、播放和下载通知不受影响", true);
        body.addView(youkuNotifyCard);

        body = pageBodies[3];
        buildingKeys = pageKeys.get(3);
        pageBeingBuilt = 3;
        addPageStatus(body, text("", 13, MUTED, false));
        section(body, "启动广告");
        LinearLayout qiyiCard = card();
        toggle(qiyiCard, Config.IQIYI_BLOCK_SPLASH, "拦截开屏广告",
                "阻止启动时的广告请求", true);
        toggle(qiyiCard, Config.IQIYI_BLOCK_PLAYER_ADS, "拦截播放广告",
                "去掉前贴/中插广告和播放器内的横幅、角标推广", true);
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

        // The rule sets that used to ship as five separate APKs get one page each, in the same order as
        // the list. Their keys carry a per-family prefix, so a toggle here can never be confused
        // with a video-app one - QQ音乐 and the host both want "block_splash".
        for (int familyIndex = 0; familyIndex < FamilySettings.FAMILIES.length; familyIndex++) {
            String family = FamilySettings.FAMILIES[familyIndex];
            int page = 4 + familyIndex;
            body = pageBodies[page];
            buildingKeys = pageKeys.get(page);
            pageBeingBuilt = page;
            addPageStatus(body, text("", 13, MUTED, false));
            LinearLayout familyCard = card();
            String[] familyKeys = FamilySettings.keysOf(family);
            String[] familyLabels = FamilySettings.labelsOf(family);
            for (int keyIndex = 0; keyIndex < familyKeys.length; keyIndex++) {
                toggleFamily(familyCard, family, familyKeys[keyIndex], familyLabels[keyIndex],
                        FamilySettings.defaultFor(family, familyKeys[keyIndex]));
            }
            body.addView(familyCard);
            body.addView(compatButtonFor(page));
        }

        // The list page carries the app rows plus the one global section; per-app settings never
        // appear here.
        section(listBody, "应用");
        LinearLayout appListCard = card();
        for (int page = 1; page < PAGE_COUNT; page++) appListCard.addView(appRow(page));
        listBody.addView(appListCard);

        section(listBody, "全局");
        LinearLayout debugCard = card();
        buildingKeys = null;
        addNote(debugCard, "每个应用页面底部的「查看兼容结果」会列出该应用每一项规则是否安装、是否实际触发。", 12);
        toggle(debugCard, Config.DEBUG_LOG, "记录详细日志",
                "遇到漏拦截时再开启，便于定位", false);
        listBody.addView(debugCard);

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
        listBody.addView(footer);
        showPage(state == null ? PAGE_LIST : state.getInt("selected_page", PAGE_LIST));
    }

    /** Version line for one app page, inserted above its sections. */
    private void addPageStatus(LinearLayout body, TextView view) {
        statusForPage[pageBeingBuilt] = view;
        view.setPadding(0, 0, 0, dp(10));
        body.addView(view);
    }

    /** The compatibility-scan button for one app page; each page owns its own. */
    private View compatButtonFor(final int page) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText("查看本应用兼容结果");
        button.setTextColor(ACCENT);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { scanApp(page); }
        });
        compatButtons.put(page, button);
        return button;
    }

    /** One tappable list row: app name, its installed version, and how many switches are on. */
    private View appRow(final int page) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(2), dp(6), dp(2), dp(6));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showPage(page); }
        });

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(PAGE_TITLES[page], 16, INK, true));
        List<String> keys = pageKeys.get(page);
        labels.addView(text(summaryOf(page, keys), 12, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        String version = installedVersion(pagePackage(page));
        if (!"未安装".equals(version)) {
            TextView mark = text(version, 12, MUTED, false);
            mark.setPadding(dp(8), 0, dp(6), 0);
            row.addView(mark);
        }
        TextView arrow = text("›", 20, SECTION, false);
        row.addView(arrow);
        return row;
    }

    /** "已开启 6 / 8 项" plus a hint when the app is not installed. */
    private String summaryOf(int page, List<String> keys) {
        String packageName = pagePackage(page);
        if ("未安装".equals(installedVersion(packageName))) return "未安装";
        int on = 0;
        for (String key : keys) {
            Boolean stored = App.read(this, key, defaults.get(key));
            if (stored != null && stored) on++;
        }
        return "已开启 " + on + " / " + keys.size() + " 项";
    }

    private void showPage(int selected) {
        if (selected < 0 || selected >= PAGE_COUNT) selected = PAGE_LIST;
        selectedPage = selected;
        boolean isList = selected == PAGE_LIST;

        backButton.setVisibility(isList ? View.INVISIBLE : View.VISIBLE);
        headerTitle.setText(isList ? "广告净化" : PAGE_TITLES[selected]);
        headerSubtitle.setText(isList
                ? "六个去广告模块已合并为一个，统一在这里设置"
                : "设置改动后，强停并重新打开" + Config.appLabel(pagePackage(selected)));

        if (!isList) {
            TextView line = statusForPage[selected];
            String packageName = pagePackage(selected);
            if (line != null) {
                String suffix = " · 新版本，规则逐项尝试";
                try {
                    android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(packageName, 0);
                    if (info.versionCode == Config.verifiedVersionCode(packageName))
                        suffix = " · 此版本有真机适配记录";
                } catch (Throwable ignored) { suffix = " · 未安装"; }
                line.setText("版本 " + installedVersion(packageName) + suffix);
            }
        }

        for (int index = 0; index < PAGE_COUNT; index++) {
            pages[index].setVisibility(index == selected ? View.VISIBLE : View.GONE);
        }
        if (isList) refreshListSummaries();
    }

    /** The list shows counts, so it has to be recomputed every time it becomes visible. */
    private void refreshListSummaries() {
        // child 0 is the "应用" section label, child 1 is the card holding one row per app.
        if (listBody.getChildCount() < 2) return;
        View second = listBody.getChildAt(1);
        if (!(second instanceof LinearLayout)) return;
        LinearLayout card = (LinearLayout) second;
        for (int index = 0; index < card.getChildCount(); index++) {
            View row = card.getChildAt(index);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout rowBox = (LinearLayout) row;
            if (rowBox.getChildCount() < 1) continue;
            View labels = rowBox.getChildAt(0);
            if (!(labels instanceof LinearLayout)) continue;
            LinearLayout labelBox = (LinearLayout) labels;
            if (labelBox.getChildCount() < 2) continue;
            View summary = labelBox.getChildAt(1);
            if (summary instanceof TextView) {
                ((TextView) summary).setText(summaryOf(index + 1, pageKeys.get(index + 1)));
            }
        }
    }

    private void scanApp(int page) {
        final String packageName = pagePackage(page);
        if (packageName == null) return;
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
                if (!App.write(MainActivity.this, key, checked, pagePackage(selectedPage))) {
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

    /**
     * Same switch, but stored under the family prefix. refresh() reads whatever key is in the
     * switches map, so passing the prefixed key through is all that is needed to make the stored
     * value round-trip.
     */
    private void toggleFamily(LinearLayout parent, String family, String key, String title,
                              boolean initial) {
        toggle(parent, FamilySettings.prefixed(family, key), title, null, initial);
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
