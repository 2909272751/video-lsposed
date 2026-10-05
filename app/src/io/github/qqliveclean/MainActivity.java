package io.github.qqliveclean;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
 * Two-level settings screen: the root page lists the apps, each app page lists its own groups, and
 * each group page holds that group's switches.
 *
 * <p>The first cut used four tabs and crammed five apps' switches into one "其他" tab, so a single
 * screen held about forty toggles from six different apps with no way to tell them apart. The
 * current shape follows how LotusX presents the same problem.
 */
public final class MainActivity extends Activity {

    // ── palette (recomputed per configuration, so dark mode is just a rebuild) ───────────
    private int background, surface, ink, muted, accent, sectionColor, divider, trackOff, thumbOff;

    private void applyPalette() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        background  = dark ? 0xFF0E1013 : 0xFFF5F7FA;
        surface    = dark ? 0xFF191C21 : 0xFFFFFFFF;
        ink        = dark ? 0xFFE8EBEE : 0xFF1E2B30;
        muted      = dark ? 0xFF98A0A8 : 0xFF65747C;
        accent     = dark ? 0xFF6FA8FF : 0xFF007AFF;
        sectionColor = dark ? 0xFF7FB8E8 : 0xFF1A6FA0;
        divider    = dark ? 0xFF262A30 : 0xFFEFF2F4;
        trackOff   = dark ? 0xFF3A4048 : 0xFFBEC4C7;
        thumbOff   = dark ? 0xFF6A727C : 0xFFDEE2E5;
    }

    // ── settings model ────────────────────────────────────────────────────────────────
    private static final class Item {
        final String key, title, detail; final boolean def;
        Item(String key, String title, String detail, boolean def) {
            this.key = key; this.title = title; this.detail = detail; this.def = def;
        }
    }

    private static final class Group {
        String title; String note; final List<Item> items = new ArrayList<Item>();
        Group(String title, String note) { this.title = title; this.note = note; }
        Group add(String key, String title, String detail, boolean def) {
            items.add(new Item(key, title, detail, def));
            return this;
        }
    }

    private static final class AppEntry {
        final String title, pkg, family; final List<Group> groups = new ArrayList<Group>();
        AppEntry(String title, String pkg, String family) {
            this.title = title; this.pkg = pkg; this.family = family;
        }
        Group group(String title, String note) { Group g = new Group(title, note); groups.add(g); return g; }
    }

    /** One navigable screen. appIndex -1 is the root; groupIndex -1 is that app's own menu. */
    private static final class Page {
        final int appIndex, groupIndex, parent;
        Page(int app, int group, int parent) { appIndex = app; groupIndex = group; this.parent = parent; }
    }

    private final List<AppEntry> apps = new ArrayList<AppEntry>();
    private final List<Page> pages = new ArrayList<Page>();
    private final Map<String, Switch> switches = new LinkedHashMap<String, Switch>();
    private final Map<String, Boolean> defaults = new LinkedHashMap<String, Boolean>();
    private final Map<Integer, TextView> summaries = new LinkedHashMap<Integer, TextView>();

    private ScrollView[] views = new ScrollView[0];
    private TextView headerTitle, headerSubtitle;
    private Button backButton;
    private int selected;
    private boolean refreshing;

    // ── launcher icon visibility ──────────────────────────────────────────────────────
    // The launcher entry is a separate activity-alias, so hiding the icon disables one component
    // and leaves MainActivity reachable - which is what keeps the module usable from LSPosed once
    // the icon is gone.
    private static final String ALIAS = ".MainActivityAlias";
    private ComponentName alias() { return new ComponentName(getPackageName(), getPackageName() + ALIAS); }

    private boolean iconHidden() {
        return getPackageManager().getComponentEnabledSetting(alias())
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setIconHidden(boolean hide) {
        getPackageManager().setComponentEnabledSetting(alias(),
                hide ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                     : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP);
    }

    /** Rules withdrawn because their target stopped existing; never rendered as a switch. */
    private static boolean isWithdrawn(String family, String key) {
        return FamilySettings.HEYTAP.equals(family) && "boot_guide_enabled".equals(key);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        applyPalette();
        buildModel();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setPadding(dp(16), dp(12), dp(16), 0);
        setContentView(root);
        root.addView(buildHeader());

        FrameLayout host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));
        views = new ScrollView[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(0, 0, 0, dp(28));
            scroll.addView(body);
            views[i] = scroll;
            host.addView(scroll);
            render(pages.get(i), body);
        }
        showPage(state == null ? 0 : state.getInt("page", 0));
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        backButton = new Button(this);
        backButton.setText("‹");
        backButton.setTextSize(26);
        backButton.setAllCaps(false);
        backButton.setMinWidth(0);
        backButton.setMinimumWidth(0);
        backButton.setPadding(dp(6), 0, dp(10), 0);
        backButton.setTextColor(sectionColor);
        backButton.setBackgroundColor(Color.TRANSPARENT);
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showPage(pages.get(selected).parent); }
        });
        bar.addView(backButton);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        headerTitle = text("广告净化", 24, ink, true);
        titles.addView(headerTitle);
        headerSubtitle = text("", 13, muted, false);
        headerSubtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(headerSubtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        Button overflow = new Button(this);
        overflow.setText("⋮");
        overflow.setTextSize(22);
        overflow.setAllCaps(false);
        overflow.setMinWidth(0);
        overflow.setMinimumWidth(0);
        overflow.setPadding(dp(14), 0, dp(4), 0);
        overflow.setTextColor(ink);
        overflow.setBackgroundColor(Color.TRANSPARENT);
        overflow.setContentDescription("更多");
        overflow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showOverflow(); }
        });
        bar.addView(overflow);
        return bar;
    }

    // ── the whole settings model ──────────────────────────────────────────────────────
    private void buildModel() {
        AppEntry qqlive = new AppEntry("腾讯视频", Config.PACKAGE, null);
        qqlive.group("启动与播放", null)
                .add(Config.BLOCK_SPLASH, "拦截开屏广告", "阻止新请求，并跳过已缓存广告的展示", true)
                .add(Config.BLOCK_PLAYER_ADS, "拦截播放广告", "尝试阻止播放前、中、后的广告请求", true)
                .add(Config.BLOCK_FEED_AUTOPLAY, "关闭首页片段自动播放", "首页预览不自动起播；主动点开视频仍可播放", true);
        qqlive.group("首页与个人中心", null)
                .add(Config.BLOCK_AD_REQUESTS, "隐藏首页顶部轮播", "整块隐藏，里面的普通推荐也会一并移除", true)
                .add(Config.BLOCK_MINE_AD, "隐藏「我的」广告横幅", "只处理个人中心的独立广告卡片", true);
        qqlive.group("底部导航", "关闭开关隐藏对应入口，首页和个人中心始终保留。修改后重启腾讯视频生效。")
                .add(Config.SHOW_SHORT_VIDEO, "显示「短剧」", null, true)
                .add(Config.SHOW_VIP, "显示「会员专区」", null, true)
                .add(Config.SHOW_GOODS, "显示「好物」", null, true);
        qqlive.group("通知", null)
                .add(Config.BLOCK_PUSH_NOTIFY, "拦截推送通知广告", "只拦会员促销类通知；追剧提醒、播放和下载通知不受影响", true);

        AppEntry youku = new AppEntry("优酷", Config.PACKAGE_YOUKU, null);
        youku.group("启动与播放", null)
                .add(Config.YOUKU_BLOCK_SPLASH, "拦截开屏广告", "覆盖冷启动和回到前台时的开屏广告", true)
                .add(Config.YOUKU_BLOCK_PAUSE_AD, "拦截全屏暂停广告", "暂停播放时不展示全屏广告", true);
        youku.group("首页与个人中心", null)
                .add(Config.YOUKU_BLOCK_AD_SLOT, "隐藏首页顶部轮播", "整块隐藏，普通推荐也会一并移除", true)
                .add(Config.YOUKU_HIDE_MINE_PROMOS, "隐藏「我的」活动推广", "收起历史记录下方的活动条和轮播", true);
        youku.group("底部导航", "关闭不常用入口；首页和「我的」始终保留。")
                .add(Config.YOUKU_SHOW_SHORT_DRAMA, "显示「短剧」", null, true)
                .add(Config.YOUKU_SHOW_VIP, "显示「会员」", null, true)
                .add(Config.YOUKU_SHOW_GOOD_MOVIES, "显示「淘好片」", null, true)
                .add(Config.YOUKU_HIDE_BOTTOM_BAR, "隐藏整个底部导航", "所有入口都会消失，默认关闭", false);
        Group channels = youku.group("频道入口", "关闭后顶部频道栏不再显示该入口；只隐藏界面，不改动优酷的数据与选片逻辑。");
        for (String[] entry : Config.CHANNEL_CATALOG) {
            channels.items.add(new Item(entry[0], "显示「" + entry[1] + "」", null, true));
        }
        youku.group("通知", null)
                .add(Config.BLOCK_PUSH_NOTIFY, "拦截推送通知广告", "只拦会员促销类通知；追剧提醒、播放和下载通知不受影响", true);

        AppEntry iqiyi = new AppEntry("爱奇艺", Config.PACKAGE_IQIYI, null);
        iqiyi.group("启动广告", null)
                .add(Config.IQIYI_BLOCK_SPLASH, "拦截开屏广告", "阻止启动时的广告请求", true)
                .add(Config.IQIYI_BLOCK_PLAYER_ADS, "拦截播放广告", "去掉前贴/中插广告和播放器内的横幅、角标推广", true);
        iqiyi.group("首页与个人中心", null)
                .add(Config.IQIYI_HIDE_HOME_TOP_AD, "隐藏首页顶部轮播", "整块隐藏，普通推荐也会一并移除", true)
                .add(Config.IQIYI_HIDE_MINE_BANNER, "隐藏「我的」顶部广告", "只处理标有「广告」的横幅", true);
        iqiyi.group("底部导航", "关闭不常用入口；首页和「我的」始终保留。")
                .add(Config.IQIYI_SHOW_FREE, "显示「免费」", null, true)
                .add(Config.IQIYI_SHOW_PLUS, "显示中间的「＋」入口", null, true)
                .add(Config.IQIYI_SHOW_MEMBER, "显示「会员」", null, true);
        iqiyi.group("通知", null)
                .add(Config.BLOCK_PUSH_NOTIFY, "拦截推送通知广告", "只拦会员促销类通知；追剧提醒、播放和下载通知不受影响", true);

        apps.add(qqlive);
        apps.add(youku);
        apps.add(iqiyi);

        // The five rule sets that used to ship as standalone modules get one app entry each, in
        // FamilySettings order. Their keys keep the per-family prefix, so a QQ音乐 toggle can never
        // be confused with a video-app toggle that happens to want the same short name.
        for (int i = 0; i < FamilySettings.FAMILIES.length; i++) {
            String family = FamilySettings.FAMILIES[i];
            // The family still needs its real package: that is what makes the row show an icon and
            // a version, and more importantly what App.write() delivers the setting to - a family
            // key written with a null target package never reaches the app it governs.
            AppEntry entry = new AppEntry(FamilySettings.FAMILY_LABELS[i], Config.pkgOfFamily(family), family);
            Group group = entry.group(labelOfFamily(family) + "过滤",
                    FamilySettings.HEYTAP.equals(family)
                            ? "「开机必备引导页」在本版本已改为首页卡片，没有可拦截的入口，该开关已移除。"
                            : null);
            String[] keys = FamilySettings.keysOf(family);
            String[] labels = FamilySettings.labelsOf(family);
            for (int k = 0; k < keys.length; k++) {
                if (isWithdrawn(family, keys[k])) continue;
                group.items.add(new Item(FamilySettings.prefixed(family, keys[k]), labels[k], null,
                        FamilySettings.defaultFor(family, keys[k])));
            }
            apps.add(entry);
        }

        pages.add(new Page(-1, -1, -1));
        for (int i = 0; i < apps.size(); i++) {
            int menu = pages.size();
            pages.add(new Page(i, -1, 0));
            for (int g = 0; g < apps.get(i).groups.size(); g++) pages.add(new Page(i, g, menu));
        }
    }

    private static String labelOfFamily(String family) {
        String label = FamilySettings.labelOf(family, "");
        if (label == null) label = FamilySettings.FAMILY_LABELS[FamilySettings.indexOf(family)];
        int slash = label.indexOf(" / ");
        return slash > 0 ? label.substring(0, slash) : label;
    }

    // ── rendering ─────────────────────────────────────────────────────────────────────
    private void render(Page page, LinearLayout body) {
        if (page.appIndex < 0) { renderRoot(body); return; }
        AppEntry app = apps.get(page.appIndex);
        if (page.groupIndex < 0) renderAppMenu(app, body);
        else renderGroup(app, app.groups.get(page.groupIndex), body);
    }

    private void renderRoot(LinearLayout body) {
        section(body, "应用");
        LinearLayout listCard = card();
        for (int i = 0; i < apps.size(); i++) listCard.addView(appRow(i));
        body.addView(listCard);

        section(body, "全局");
        LinearLayout debugCard = card();
        toggle(debugCard, Config.DEBUG_LOG, "记录详细日志", "遇到漏拦截时再开启，便于定位", false);
        body.addView(debugCard);

        LinearLayout footer = card();
        footer.addView(actionButton("恢复默认设置", new Runnable() {
            @Override public void run() { resetAll(); }
        }));
        addNote(footer, "设置改动后，强停并重新打开对应应用。应用更新后请查看兼容结果。", 12);
        body.addView(footer);
    }

    private View appRow(final int index) {
        final AppEntry app = apps.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));

        ImageView icon = new ImageView(this);
        Drawable drawable = app.pkg == null ? null : iconOf(app.pkg);
        if (drawable != null) { icon.setImageDrawable(drawable); icon.setScaleType(ImageView.ScaleType.FIT_CENTER); }
        row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, 0, 0);
        labels.addView(text(app.title, 16, ink, true));
        TextView summary = text("", 12, muted, false);
        labels.addView(summary);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        if (app.pkg != null) {
            TextView mark = text(installedVersion(app.pkg), 12, muted, false);
            mark.setPadding(dp(8), 0, dp(4), 0);
            row.addView(mark);
        }
        row.addView(text("›", 20, sectionColor, false));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showPage(menuIndexOf(index)); }
        });
        summaries.put(index, summary);
        return row;
    }

    private void renderAppMenu(final AppEntry app, LinearLayout body) {
        body.addView(versionLine(app));
        section(body, "分组");
        LinearLayout card = card();
        final int appIndex = apps.indexOf(app);
        for (int g = 0; g < app.groups.size(); g++) {
            if (g > 0) separator(card);
            Group group = app.groups.get(g);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(52));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text(group.title, 15, ink, true));
            labels.addView(text(countText(app, group), 12, muted, false));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(text("›", 20, sectionColor, false));
            final int groupIndex = g;
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { showPage(groupIndexOf(appIndex, groupIndex)); }
            });
            card.addView(row);
        }
        body.addView(card);
        body.addView(compatButton(app));
    }

    private String countText(AppEntry app, Group group) {
        int on = 0;
        for (Item item : group.items) if (App.read(this, item.key, item.def)) on++;
        return "已开启 " + on + " / " + group.items.size() + " 项";
    }

    private View versionLine(AppEntry app) {
        String value;
        if (app.pkg == null) {
            int total = 0;
            for (Group group : app.groups) total += group.items.size();
            value = "共 " + total + " 项 · 规则由广告净化统一安装";
        } else {
            String suffix;
            String version = installedVersion(app.pkg);
            if ("未安装".equals(version)) {
                suffix = " · 未安装";
            } else {
                suffix = " · 新版本，规则逐项尝试";
                try {
                    PackageInfo info = getPackageManager().getPackageInfo(app.pkg, 0);
                    if (info.versionCode == Config.verifiedVersionCode(app.pkg))
                        suffix = " · 此版本有真机适配记录";
                } catch (Throwable ignored) { suffix = " · 未安装"; }
            }
            value = "版本 " + version + suffix;
        }
        TextView line = text(value, 13, muted, false);
        line.setPadding(0, 0, 0, dp(10));
        return line;
    }

    private void renderGroup(AppEntry app, Group group, LinearLayout body) {
        section(body, group.title);
        LinearLayout card = card();
        if (group.note != null) addNote(card, group.note, 12);
        for (Item item : group.items) toggle(card, item.key, item.title, item.detail, item.def);
        body.addView(card);
    }

    private View compatButton(final AppEntry app) {
        if (app.family != null) {
            TextView note = text("该规则集由广告净化统一安装，没有独立模块；作用域已包含 "
                    + app.pkg + "。", 12, muted, false);
            note.setPadding(dp(4), dp(14), 0, 0);
            return note;
        }
        return actionButton("查看本应用兼容结果", new Runnable() {
            @Override public void run() { scan(app.pkg); }
        });
    }

    private int menuIndexOf(int appIndex) {
        for (int i = 0; i < pages.size(); i++)
            if (pages.get(i).appIndex == appIndex && pages.get(i).groupIndex < 0) return i;
        return 0;
    }

    private int groupIndexOf(int appIndex, int groupIndex) {
        for (int i = 0; i < pages.size(); i++)
            if (pages.get(i).appIndex == appIndex && pages.get(i).groupIndex == groupIndex) return i;
        return 0;
    }

    private void showPage(int index) {
        if (index < 0 || index >= pages.size()) index = 0;
        selected = index;
        Page page = pages.get(index);
        backButton.setVisibility(page.parent < 0 ? View.INVISIBLE : View.VISIBLE);

        if (page.appIndex < 0) {
            headerTitle.setText("广告净化");
            headerSubtitle.setText("六个去广告模块，已合并为一个");
        } else {
            AppEntry app = apps.get(page.appIndex);
            headerTitle.setText(app.title);
            headerSubtitle.setText(page.groupIndex < 0
                    ? "设置改动后，强停并重新打开" + labelOfApp(app)
                    : "共 " + app.groups.get(page.groupIndex).items.size() + " 项");
        }
        for (int i = 0; i < views.length; i++) views[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        refreshSummaries();
    }

    private static String labelOfApp(AppEntry app) {
        return app.pkg == null ? app.title : Config.appLabel(app.pkg);
    }

    private void refreshSummaries() {
        for (Map.Entry<Integer, TextView> entry : summaries.entrySet()) {
            AppEntry app = apps.get(entry.getKey());
            int total = 0, on = 0;
            for (Group group : app.groups) {
                for (Item item : group.items) {
                    total++;
                    if (App.read(this, item.key, item.def)) on++;
                }
            }
            boolean missing = app.pkg != null && "未安装".equals(installedVersion(app.pkg));
            entry.getValue().setText(missing ? "未安装" : "已开启 " + on + " / " + total + " 项");
        }
    }

    // ── overflow menu ─────────────────────────────────────────────────────────────────
    private void showOverflow() {
        final boolean hidden = iconHidden();
        new AlertDialog.Builder(this)
                .setItems(new String[]{
                        hidden ? "恢复桌面图标" : "隐藏桌面图标",
                        "恢复默认设置",
                        "关于"},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) {
                                    setIconHidden(!hidden);
                                    Toast.makeText(MainActivity.this, !hidden
                                            ? "桌面图标已隐藏；仍可从 LSPosed 的模块列表打开本页面"
                                            : "桌面图标已恢复", Toast.LENGTH_LONG).show();
                                } else if (which == 1) {
                                    resetAll();
                                } else {
                                    showAbout();
                                }
                            }
                        })
                .show();
    }

    private void resetAll() {
        if (App.reset(this)) {
            refresh();
            Toast.makeText(this, "设置已恢复；请重启目标应用", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "保存失败：" + App.lastError(), Toast.LENGTH_LONG).show();
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("广告净化 " + versionName())
                .setMessage("把原先分散的 6 个去广告模块合并成一个。\n\n"
                        + "覆盖长视频、音乐、电商、出行、社交和系统商店。\n"
                        + "每条规则只在 LSPosed 作用域里对应的应用内生效。")
                .setPositiveButton("关闭", null)
                .show();
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Throwable ignored) { return ""; }
    }

    private void scan(final String packageName) {
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
                        new AlertDialog.Builder(MainActivity.this)
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
        App.publish(this);
        refresh();
    }

    private void refresh() {
        showPage(selected);
        boolean guard = refreshing;
        refreshing = true;
        try {
            for (Map.Entry<String, Switch> entry : switches.entrySet()) {
                boolean wanted = App.read(this, entry.getKey(), defaults.get(entry.getKey()));
                if (entry.getValue().isChecked() != wanted) entry.getValue().setChecked(wanted);
            }
        } finally {
            refreshing = guard;
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", selected);
        super.onSaveInstanceState(state);
    }

    // ── widgets ───────────────────────────────────────────────────────────────────────
    private void toggle(final LinearLayout parent, final String key, String title, String detail, boolean initial) {
        if (parent.getChildCount() > 0) separator(parent);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(detail == null ? 50 : 64));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, ink, false));
        if (detail != null) {
            TextView hint = text(detail, 12, muted, false);
            hint.setPadding(0, dp(3), dp(10), 0);
            labels.addView(hint);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        Switch control = new Switch(this);
        control.setContentDescription(title);
        control.setThumbTintList(new ColorStateList(
                new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
                new int[] { accent, thumbOff }));
        control.setTrackTintList(new ColorStateList(
                new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
                new int[] { withAlpha(accent, 150), trackOff }));
        control.setChecked(initial);
        control.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (refreshing) return;
                if (!App.write(MainActivity.this, key, checked, targetPackageFor(key))) {
                    button.setOnCheckedChangeListener(null);
                    button.setChecked(!checked);
                    button.setOnCheckedChangeListener(this);
                    Toast.makeText(MainActivity.this, "保存失败：" + App.lastError(), Toast.LENGTH_LONG).show();
                    return;
                }
                refreshSummaries();
                Toast.makeText(MainActivity.this, "已保存，强停目标应用后生效", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(control);
        parent.addView(row);
        switches.put(key, control);
        defaults.put(key, initial);
    }

    /** Family keys must reach that family's own app; plain keys go to their video app. */
    private String targetPackageFor(String key) {
        String family = FamilySettings.familyOfPrefix(key);
        if (family == null) return null;
        for (AppEntry app : apps) if (family.equals(app.family)) return app.pkg;
        return null;
    }

    private Button actionButton(String caption, final Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(caption);
        button.setTextColor(accent);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { action.run(); }
        });
        return button;
    }

    private void section(LinearLayout body, String title) {
        TextView label = text(title, 14, sectionColor, true);
        label.setPadding(dp(4), dp(20), 0, dp(8));
        body.addView(label);
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(13), dp(9), dp(13), dp(9));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surface);
        shape.setCornerRadius(dp(13));
        box.setBackground(shape);
        return box;
    }

    private void addNote(LinearLayout box, String message, int size) {
        TextView note = text(message, size, muted, false);
        note.setPadding(0, dp(4), 0, dp(5));
        box.addView(note);
    }

    private void separator(LinearLayout box) {
        View line = new View(this);
        line.setBackgroundColor(divider);
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

    private Drawable iconOf(String packageName) {
        try { return getPackageManager().getApplicationIcon(packageName); }
        catch (Throwable ignored) { return null; }
    }

    private String installedVersion(String packageName) {
        try { return getPackageManager().getPackageInfo(packageName, 0).versionName; }
        catch (Throwable ignored) { return "未安装"; }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}