package io.github.qqliveclean;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;

/**
 * Single source of truth for package identity, preference keys and hook anchors.
 *
 * Version policy (deliberate): we do NOT lock to one exact versionCode. Obfuscated names
 * drift between releases, so every rule resolves on its own, logs {@code status=hooked} or
 * {@code status=miss reason=...}, and the rest keep working. Nothing is version-gated except
 * a coarse sanity floor.
 */
public final class Config {
    public static final String TAG = "QQLiveClean";

    /** Target app #1 (Tencent Video). Scope is fixed in META-INF/xposed/scope.list. */
    public static final String PACKAGE = "com.tencent.qqlive";

    /**
     * Target app #2 (优酷). Both apps are served by the same build; {@link MainHook} picks the
     * rule set from the package it was loaded for, and neither app can affect the other.
     */
    public static final String PACKAGE_YOUKU = "com.youku.phone";

    /**
     * Target app #3 (爱奇艺 iQiyi 17.8.5). Anchors: scratch-recon/iqiyi-gates.md;
     * the primary gate is QYVideoView.canInitAdsController()Z, the app's own
     * sanctioned "adBlockList" no-ad path, which kills all player ad types at once.
     */
    public static final String PACKAGE_IQIYI = "com.qiyi.video";

    /** Every package this module installs rules for. */
    public static final String[] PACKAGES = {PACKAGE, PACKAGE_YOUKU, PACKAGE_IQIYI};

    /** True for a package this module has a rule set for. */
    static boolean handles(String packageName) {
        if (packageName == null) return false;
        for (String candidate : PACKAGES) {
            if (candidate.equals(packageName)) return true;
        }
        return false;
    }

    /** Display name used in the settings page, the logs and the compatibility report header. */
    static String appLabel(String packageName) {
        if (PACKAGE_IQIYI.equals(packageName)) return "爱奇艺";
        return PACKAGE_YOUKU.equals(packageName) ? "优酷" : "腾讯视频";
    }

    /** Settings page's own private store. Served to the target process by {@link ConfigProvider}. */
    public static final String PREFS_NAME = "qqlive_clean";
    /** Channel method served by {@link ConfigProvider}. */
    public static final String CALL_GET = "get";

    /**
     * LSPosed RemotePreferences group. Still read as a fallback because it works without a
     * Context, but it is NOT the primary channel - lspd never delivers the settings-side
     * binder for this module, so the settings page cannot write it.
     */
    public static final String GROUP = "qqlive_clean_settings";

    /** Build the hook anchors below were reverse-engineered from (log only, not a gate). */
    public static final int VERIFIED_VERSION_CODE = 32321;
    public static final String VERIFIED_VERSION_NAME = "9.04.55.32321";
    /** Coarse floor: older builds predate the QAd splash SDK / KMM tab bar entirely. */
    public static final int MIN_VERSION_CODE = 31000;

    /** Same pair for Youku (com.youku.phone 11.2.1; evidence: scratch-recon/youku-gates.md). */
    public static final int VERIFIED_YOUKU_VERSION_CODE = 920;
    public static final String VERIFIED_YOUKU_VERSION_NAME = "11.2.15";
    /** 0 = no floor for Youku: every rule resolves on its own and reports its own miss. */
    public static final int MIN_YOUKU_VERSION_CODE = 0;
    public static final int VERIFIED_IQIYI_VERSION_CODE = 800170952;
    public static final String VERIFIED_IQIYI_VERSION_NAME = "17.9.2";

    static int verifiedVersionCode(String packageName) {
        if (PACKAGE_IQIYI.equals(packageName)) return VERIFIED_IQIYI_VERSION_CODE;
        return PACKAGE_YOUKU.equals(packageName) ? VERIFIED_YOUKU_VERSION_CODE : VERIFIED_VERSION_CODE;
    }

    static String verifiedVersionName(String packageName) {
        if (PACKAGE_IQIYI.equals(packageName)) return VERIFIED_IQIYI_VERSION_NAME;
        return PACKAGE_YOUKU.equals(packageName) ? VERIFIED_YOUKU_VERSION_NAME : VERIFIED_VERSION_NAME;
    }

    static int minVersionCode(String packageName) {
        if (PACKAGE_IQIYI.equals(packageName)) return 0;
        return PACKAGE_YOUKU.equals(packageName) ? MIN_YOUKU_VERSION_CODE : MIN_VERSION_CODE;
    }

    // ---- 开屏广告 ----
    public static final String BLOCK_SPLASH = "block_splash";
    public static final String BLOCK_SPLASH_PRELOAD = "block_splash_preload";

    // ---- 开屏以外的广告 ----
    /** 播放前/中/后贴片广告（mediaad.impl.u.v1 统一入口）。 */
    public static final String BLOCK_PLAYER_ADS = "block_player_ads";
    /** 全局广告请求闸门（QAdRequestManager）—— 首页焦点图/信息流/卡片广告的主要来源。 */
    public static final String BLOCK_AD_REQUESTS = "block_ad_requests";
    public static final String BLOCK_MINE_AD = "block_mine_ad";
    /** Prevent feed previews from starting on their own; explicit playback stays available. */
    public static final String BLOCK_FEED_AUTOPLAY = "block_feed_autoplay";

    // ---- 底部标签栏（true = 显示该标签）----
    public static final String SHOW_SHORT_VIDEO = "show_short_video";
    public static final String SHOW_VIP = "show_vip";
    public static final String SHOW_LIVE = "show_live";
    public static final String SHOW_DOKI = "show_doki";
    public static final String SHOW_MESSAGE = "show_message";
    public static final String SHOW_GOODS = "show_goods";
    public static final String SHOW_GALLERY = "show_gallery";
    public static final String SHOW_MINE = "show_mine";
    /** 首页 is never hidden: it is the tab bar's anchor and the fallback route. */
    public static final int PAGE_TYPE_HOME = 0;

    // ---- 顶部频道栏（true = 显示该频道）----
    public static final String SHOW_CHANNEL_MASTER = "show_channel_master";
    public static final String SHOW_CHANNEL_TV = "show_channel_tv";
    public static final String SHOW_CHANNEL_ANIME = "show_channel_anime";
    public static final String SHOW_CHANNEL_MOVIE = "show_channel_movie";
    public static final String SHOW_CHANNEL_VARIETY = "show_channel_variety";

    /** 显示名 -> 开关，按顺序出现在设置页。首页不在此列（永不隐藏）。 */
    static final String[][] CHANNEL_CATALOG = {
            {SHOW_CHANNEL_MASTER, "大师"},
            {SHOW_CHANNEL_TV, "电视剧"},
            {SHOW_CHANNEL_ANIME, "动漫"},
            {SHOW_CHANNEL_MOVIE, "电影"},
            {SHOW_CHANNEL_VARIETY, "综艺"},
    };

    // ---- 性能 ----
    /** 减少预加载：让 App 走自己的低预载分支。默认关闭（需真机量化省电收益）。 */
    public static final String REDUCE_PRELOAD = "reduce_preload";

    // ---- 优酷 (com.youku.phone) ----
    /** S-2 开屏总开关：SplashConfigInfo.getCold/HotAdEnabled -> false（App 自己的 disabled 分支）。 */
    public static final String YOUKU_BLOCK_SPLASH = "youku_block_splash";
    /** A-1 广告位总闸：j.b1.w3.b.c.d.u(I) -> false（App 自己的 onFailed(3,"当前广告位屏蔽") 分支）。 */
    public static final String YOUKU_BLOCK_AD_SLOT = "youku_block_ad_slot";
    public static final String YOUKU_HIDE_MINE_PROMOS = "youku_hide_mine_promos";
    /** P-1 全屏暂停广告：Orange 配置 ad_fullscreen_pause.isEnable 读成 "false"。 */
    public static final String YOUKU_BLOCK_PAUSE_AD = "youku_block_pause_ad";
    /**
     * B-2 底栏整条隐藏：j.b1.s.o0.g.c(Z) 强制走 App 自己的 setVisibility(8) 分支。
     * 这是"整条"隐藏而不是逐项过滤，所以默认关闭，必须由用户显式打开。
     */
    public static final String YOUKU_HIDE_BOTTOM_BAR = "youku_hide_bottom_bar";
    public static final String YOUKU_SHOW_SHORT_DRAMA = "youku_show_short_drama";
    public static final String YOUKU_SHOW_VIP = "youku_show_vip";
    public static final String YOUKU_SHOW_GOOD_MOVIES = "youku_show_good_movies";

    // ---- 爱奇艺 ----
    public static final String IQIYI_BLOCK_SPLASH = "iqiyi_block_splash";
    public static final String IQIYI_HIDE_HOME_TOP_AD = "iqiyi_hide_home_top_ad";
    public static final String IQIYI_HIDE_MINE_BANNER = "iqiyi_hide_mine_banner";
    public static final String IQIYI_SHOW_FREE = "iqiyi_show_free";
    public static final String IQIYI_SHOW_PLUS = "iqiyi_show_plus";
    public static final String IQIYI_SHOW_MEMBER = "iqiyi_show_member";

    // ---- 诊断 ----
    public static final String DEBUG_LOG = "debug_log";

    /** Every key the settings page may publish to the target process. */
    public static final String[] EXPOSED_KEYS = {
            BLOCK_SPLASH, BLOCK_SPLASH_PRELOAD, BLOCK_PLAYER_ADS, BLOCK_AD_REQUESTS, BLOCK_MINE_AD,
            BLOCK_FEED_AUTOPLAY, DEBUG_LOG,
            SHOW_SHORT_VIDEO, SHOW_VIP, SHOW_LIVE, SHOW_DOKI,
            SHOW_MESSAGE, SHOW_GALLERY, SHOW_GOODS, SHOW_MINE,
            SHOW_CHANNEL_MASTER, SHOW_CHANNEL_TV, SHOW_CHANNEL_ANIME,
            SHOW_CHANNEL_MOVIE, SHOW_CHANNEL_VARIETY, REDUCE_PRELOAD,
            YOUKU_BLOCK_SPLASH, YOUKU_BLOCK_AD_SLOT, YOUKU_HIDE_MINE_PROMOS, YOUKU_BLOCK_PAUSE_AD, YOUKU_HIDE_BOTTOM_BAR,
            YOUKU_SHOW_SHORT_DRAMA, YOUKU_SHOW_VIP, YOUKU_SHOW_GOOD_MOVIES,
            IQIYI_BLOCK_SPLASH, IQIYI_HIDE_HOME_TOP_AD, IQIYI_HIDE_MINE_BANNER,
            IQIYI_SHOW_FREE, IQIYI_SHOW_PLUS, IQIYI_SHOW_MEMBER,
    };

    static boolean defaultFor(String key) {
        switch (key) {
            case BLOCK_SPLASH:
            case BLOCK_SPLASH_PRELOAD:
            case BLOCK_PLAYER_ADS:
            case BLOCK_AD_REQUESTS:
            case BLOCK_MINE_AD:
            case BLOCK_FEED_AUTOPLAY:
            case YOUKU_BLOCK_SPLASH:
            case YOUKU_BLOCK_AD_SLOT:
            case YOUKU_HIDE_MINE_PROMOS:
            case YOUKU_BLOCK_PAUSE_AD:
            case IQIYI_BLOCK_SPLASH:
            case IQIYI_HIDE_HOME_TOP_AD:
            case IQIYI_HIDE_MINE_BANNER:
                return true;     // ad blocking is on by default
            case DEBUG_LOG:
            case REDUCE_PRELOAD:
                return false;
            case YOUKU_HIDE_BOTTOM_BAR:
                // Destructive-shaped lever: hides the WHOLE bottom bar, so opt-in only.
                return false;
            default:
                return true;     // everything else = "show the item"
        }
    }

    // ---- 开屏广告 hook 锚点（腾讯自研 QAd 广告 SDK）----
    static final String SPLASH_MANAGER = "com.tencent.qqlive.qadsplash.splash.u";

    // ---- 标签栏 hook 锚点 ----
    /** Non-obfuscated KMM model: stable across releases, carries pageType per tab kind. */
    static final String TAB_ITEM_TYPE = "com.tencent.kmm.tabbar.data.model.TabBarItemType";
    /** Its companion, whose f() returns the default bottom-bar composition. */
    static final String TAB_ITEM_TYPE_COMPANION = TAB_ITEM_TYPE + "$a";
    /** Obfuscated tab-bar data holder (jadx: re0.TabBarData). Fragile; secondary rule. */
    static final String TAB_BAR_DATA = "re0.i";
    /** Obfuscated per-tab data class (jadx: qe0.TabBarItemData). */
    static final String TAB_ITEM_DATA = "qe0.a";

    /**
     * pageType accessor candidates on {@link #TAB_ITEM_DATA}, in preference order.
     * "U" is the verified name in 9.03.95.31890 (jadx: getPageType); a miss is logged and
     * probed structurally instead of guessed wrong.
     */
    static final String[] PAGE_TYPE_ACCESSORS = {"U", "getPageType"};
    /** tabName accessor candidates, diagnostics only. */
    static final String[] TAB_NAME_ACCESSORS = {"a", "getTabName"};

    /** Every pageType we know how to name, for validating a structurally resolved accessor. */
    static final int[] KNOWN_PAGE_TYPES = {0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 16, 17, 18, 19, 20};

    // ---- 优酷 hook 锚点（com.youku.phone 11.2.1，全部在 class_defs 中核实过）----

    /** S-2 开屏总开关的模型类；getColdAdEnabled()Z / getHotAdEnabled()Z（classes5）。 */
    static final String YK_SPLASH_CONFIG_INFO = "com.youku.xadsdk.config.model.SplashConfigInfo";
    /** S-1 冷启动闸门所在类；日志 tag 是 "SplashAdPresenter"，类名本身是混淆名（classes5）。 */
    static final String YK_SPLASH_PRESENTER = "j.b1.b8.g.c";
    /** S-1 的精确方法名。同形状 (Activity)Z 还有 m/q，所以名字缺失时不允许猜。 */
    static final String YK_SPLASH_COLD_GATE = "h";
    /** A-1 广告位总闸所在类；u(I)Z（classes2）。 */
    static final String YK_AD_CONFIG_MANAGER = "j.b1.w3.b.c.d";
    /** B-2 底栏管理器；c(Z)V（classes11，唯一调用 HomeBottomNav.setVisibility 的地方）。 */
    static final String YK_NAV_BAR_MANAGER = "j.b1.s.o0.g";
    /**
     * P-1 的落点：App 在 AdPauseFullScreenPlugin.onStart() 里用
     * OrangeConfigImpl.a("ad_fullscreen_pause","isEnable","true") 读 r0。
     * 字段 hook 在 libxposed API 102 不可用（只暴露 hook(Executable)），
     * 直接 hook onStart() 又会停掉整个插件，所以改 hook 这次配置读取。
     */
    static final String YK_ORANGE_CONFIG = "com.taobao.orange.OrangeConfigImpl";
    static final String YK_PAUSE_AD_GROUP = "ad_fullscreen_pause";
    static final String YK_PAUSE_AD_KEY = "isEnable";
    /** T-1 的候选（顶层频道过滤器）；本版不 hook，只用于把 miss 原因写准。 */
    static final String YK_CHANNEL_FILTER = "j.b1.j7.b";
    static final String YK_CHANNEL = "com.youku.basic.pom.property.Channel";

    private Config() {}

    static boolean read(SharedPreferences preferences, String key, boolean fallback) {
        try { return preferences.getBoolean(key, fallback); }
        catch (Throwable ignored) { return fallback; }
    }

    /** Resolved settings for one launch, plus where they came from (logged). */
    static final class Settings {
        final String source;
        final boolean blockSplash;
        final boolean blockSplashPreload;
        final boolean blockPlayerAds;
        final boolean blockAdRequests;
        final boolean blockMineAd;
        final boolean blockFeedAutoplay;
        final boolean debugLog;
        final boolean[] hiddenPageTypes;
        final String[] hiddenChannelNames;
        final boolean reducePreload;
        // ---- 优酷 ----
        final boolean youkuBlockSplash;
        final boolean youkuBlockAdSlot;
        final boolean youkuHideMinePromos;
        final boolean youkuBlockPauseAd;
        final boolean youkuHideBottomBar;
        final boolean youkuShowShortDrama;
        final boolean youkuShowVip;
        final boolean youkuShowGoodMovies;
        final boolean iqiyiBlockSplash;
        final boolean iqiyiHideHomeTopAd;
        final boolean iqiyiHideMineBanner;
        final boolean iqiyiShowFree;
        final boolean iqiyiShowPlus;
        final boolean iqiyiShowMember;

        Settings(String source, boolean blockSplash, boolean blockSplashPreload,
                 boolean blockPlayerAds, boolean blockAdRequests, boolean blockMineAd,
                 boolean blockFeedAutoplay, boolean debugLog,
                 boolean[] hiddenPageTypes, String[] hiddenChannelNames, boolean reducePreload,
                 boolean youkuBlockSplash, boolean youkuBlockAdSlot, boolean youkuHideMinePromos,
                 boolean youkuBlockPauseAd, boolean youkuHideBottomBar,
                 boolean youkuShowShortDrama, boolean youkuShowVip, boolean youkuShowGoodMovies,
                 boolean iqiyiBlockSplash, boolean iqiyiHideHomeTopAd, boolean iqiyiHideMineBanner,
                 boolean iqiyiShowFree, boolean iqiyiShowPlus,
                 boolean iqiyiShowMember) {
            this.source = source;
            this.blockSplash = blockSplash;
            this.blockSplashPreload = blockSplashPreload;
            this.blockPlayerAds = blockPlayerAds;
            this.blockAdRequests = blockAdRequests;
            this.blockMineAd = blockMineAd;
            this.blockFeedAutoplay = blockFeedAutoplay;
            this.debugLog = debugLog;
            this.hiddenPageTypes = hiddenPageTypes;
            this.hiddenChannelNames = hiddenChannelNames;
            this.reducePreload = reducePreload;
            this.youkuBlockSplash = youkuBlockSplash;
            this.youkuBlockAdSlot = youkuBlockAdSlot;
            this.youkuHideMinePromos = youkuHideMinePromos;
            this.youkuBlockPauseAd = youkuBlockPauseAd;
            this.youkuHideBottomBar = youkuHideBottomBar;
            this.youkuShowShortDrama = youkuShowShortDrama;
            this.youkuShowVip = youkuShowVip;
            this.youkuShowGoodMovies = youkuShowGoodMovies;
            this.iqiyiBlockSplash = iqiyiBlockSplash;
            this.iqiyiHideHomeTopAd = iqiyiHideHomeTopAd;
            this.iqiyiHideMineBanner = iqiyiHideMineBanner;
            this.iqiyiShowFree = iqiyiShowFree;
            this.iqiyiShowPlus = iqiyiShowPlus;
            this.iqiyiShowMember = iqiyiShowMember;
        }
    }

    /**
     * Config from the module's provider (settings page), falling back to LSPosed
     * RemotePreferences and finally to defaults. Never throws: a broken channel must degrade
     * to defaults rather than leave the target app unprotected.
     */
    /**
     * File channel: settings page publishes, hooked process reads with plain file I/O.
     *
     * The ContentProvider route is blocked by Android 11+ package visibility -
     * com.tencent.qqlive reports {@code Unknown authority io.github.qqliveclean.config}
     * because only the CALLER's manifest can declare {@code <queries>}, and the target app's
     * manifest cannot be modified. {@code Android/media/<pkg>} is the shared-media location
     * that exists precisely so another app can read it, and reading a file needs neither a
     * Context nor a resolver, so visibility rules cannot apply.
     */
    static final String CHANNEL_DIR = "/storage/emulated/0/Android/media/io.github.qqliveclean";
    static final String CHANNEL_FILE = CHANNEL_DIR + "/qqlive_clean.conf";

    /**
     * Every location the settings page will publish to, in the order the hooked process tries
     * them. More than one because Android 16 scoped storage decides per directory whether a
     * non-media file in another app's tree is readable, and which one works is not documented
     * reliably - so the module probes and logs the answer instead of assuming.
     */
    static final String[] CHANNEL_CANDIDATES = {
            CHANNEL_FILE,
            "/storage/emulated/0/Download/qqlive_clean.conf",
            "/storage/emulated/0/Android/data/io.github.qqliveclean/files/qqlive_clean.conf",
    };

    /**
     * Delivery channel that survives Android 16: the settings page launches the target with
     * this extra while it holds the foreground, and the hook stores the payload into the
     * TARGET app's own SharedPreferences. Later launches read that cache locally, so no
     * cross-process call is ever needed again.
     */
    static final String EXTRA_CONFIG = "qqliveclean.config";
    static final String CACHE_NAME = "qqlive_clean_cache";
    static final String CACHE_MARKER = "_synced";

    /** Outcome of the last cache read/store; surfaced through the first hit line. */
    static volatile String cacheReport = "not_attempted";

    static SharedPreferences cache(Context context) {
        return context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE);
    }

    static boolean isExposed(String key) {
        for (String candidate : EXPOSED_KEYS) {
            if (candidate.equals(key)) return true;
        }
        return false;
    }

    /** Reads the local cache written by a previous "apply settings" launch. */
    static Bundle readCache(Context context) {
        if (context == null) {
            cacheReport = "no_context";
            return null;
        }
        try {
            SharedPreferences preferences = cache(context);
            if (!preferences.getBoolean(CACHE_MARKER, false)) {
                cacheReport = "empty";
                return null;
            }
            Bundle bundle = new Bundle();
            bundle.putBoolean(ConfigProvider.KEY_AVAILABLE, true);
            for (String key : EXPOSED_KEYS) {
                bundle.putBoolean(key, preferences.getBoolean(key, defaultFor(key)));
            }
            cacheReport = "ok";
            return bundle;
        } catch (Throwable error) {
            cacheReport = "read:" + error.getClass().getSimpleName();
            return null;
        }
    }

    /** Persists a payload delivered by the settings page. */
    static void storeFromPayload(Context context, String payload) {
        if (payload == null || payload.isEmpty()) {
            cacheReport = "no_payload";
            return;
        }
        try {
            SharedPreferences.Editor editor = cache(context).edit();
            int stored = 0;
            for (String pair : payload.split(";")) {
                int equals = pair.indexOf('=');
                if (equals <= 0) continue;
                String key = pair.substring(0, equals).trim();
                if (!isExposed(key)) continue;
                editor.putBoolean(key, Boolean.parseBoolean(pair.substring(equals + 1).trim()));
                stored++;
            }
            editor.putBoolean(CACHE_MARKER, true).commit();
            cacheReport = "stored=" + stored;
        } catch (Throwable error) {
            cacheReport = "store:" + error.getClass().getSimpleName();
        }
    }

    /** Pulls the payload out of the launch Intent, if this launch came from the settings page. */
    static void storeFromIntent(Context context, android.content.Intent intent) {
        if (intent == null) {
            cacheReport = "no_intent";
            return;
        }
        String payload = null;
        try {
            payload = intent.getStringExtra(EXTRA_CONFIG);
        } catch (Throwable error) {
            cacheReport = "extra:" + error.getClass().getSimpleName();
            return;
        }
        storeFromPayload(context, payload);
    }

    /** Serialises the current settings for {@link #EXTRA_CONFIG}. */
    static String exportPayload(Context context) {
        StringBuilder builder = new StringBuilder();
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        for (String key : EXPOSED_KEYS) {
            if (builder.length() > 0) builder.append(';');
            builder.append(key).append('=')
                    .append(preferences.getBoolean(key, defaultFor(key)));
        }
        return builder.toString();
    }

    /** Per-path outcome of the last channel probe; surfaced through the first hit line. */
    static volatile String channelReport = "not_attempted";
    /** Why the ContentProvider route failed. */
    static volatile String providerError = "not_attempted";

    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(0, slash);
        int parent = name.lastIndexOf('/');
        return parent < 0 ? name : name.substring(parent + 1);
    }

    /**
     * Reads the published settings file. Context-free (plain file IO), so neither a Context nor
     * Android's package-visibility rules can block it.
     */
    static Bundle readChannelFile() {
        StringBuilder report = new StringBuilder();
        for (String path : CHANNEL_CANDIDATES) {
            String label = shortName(path);
            try {
                java.io.File file = new java.io.File(path);
                if (!file.exists()) {
                    report.append(label).append("=absent ");
                    continue;
                }
                if (!file.canRead()) {
                    report.append(label).append("=unreadable ");
                    continue;
                }
                java.util.Properties properties = new java.util.Properties();
                java.io.FileInputStream input = new java.io.FileInputStream(file);
                try {
                    properties.load(input);
                } finally {
                    try { input.close(); } catch (Throwable ignored) {}
                }
                Bundle bundle = new Bundle();
                bundle.putBoolean(ConfigProvider.KEY_AVAILABLE, true);
                for (String key : EXPOSED_KEYS) {
                    String value = properties.getProperty(key);
                    if (value != null) bundle.putBoolean(key, Boolean.parseBoolean(value.trim()));
                }
                channelReport = label + "=ok";
                return bundle;
            } catch (Throwable error) {
                report.append(label).append('=')
                        .append(error.getClass().getSimpleName()).append(' ');
            }
        }
        channelReport = report.length() == 0 ? "no_candidates" : report.toString().trim();
        return null;
    }

    static Settings resolve(Context context, SharedPreferences remote) {
        // The provider is the live source of truth. An older build preferred a target-side
        // cache, so a single old "apply" launch could freeze every later setting change.
        Bundle provider = readFromProvider(context);
        if (provider != null) {
            // URI grants may expire at reboot. Preserve the latest successful read
            // for launches before the user next opens the module settings page.
            try {
                SharedPreferences.Editor editor = cache(context).edit();
                for (String key : EXPOSED_KEYS)
                    editor.putBoolean(key, provider.getBoolean(key, defaultFor(key)));
                editor.putBoolean(CACHE_MARKER, true).apply();
                cacheReport = "updated_from_provider";
            } catch (Throwable error) {
                cacheReport = "provider_cache:" + error.getClass().getSimpleName();
            }
            return fromValues("module_provider", provider);
        }
        // Retain the existing fallbacks for ROMs that cannot resolve the provider. They are
        // only used when the current settings cannot be read.
        Bundle cache = readCache(context);
        if (cache != null) return fromValues("target_cache[" + cacheReport + "]", cache);
        Bundle file = readChannelFile();
        if (file != null) return fromValues("file_channel[" + channelReport + "]", file);
        // Framework-managed preferences (read-only here), then built-in defaults.
        String suffix = " cache=" + cacheReport + " file=" + channelReport + " provider=" + providerError;
        if (remote != null) return fromRemote("remote_preferences" + suffix, remote);
        return fromValues("defaults" + suffix, null);
    }

    private static Settings fromValues(String source, Bundle provider) {
        boolean[] hidden = new boolean[64];
        hidden[1] = !value(provider, null, SHOW_SHORT_VIDEO);
        hidden[2] = !value(provider, null, SHOW_VIP);
        hidden[3] = !value(provider, null, SHOW_LIVE);
        hidden[4] = !value(provider, null, SHOW_MINE);
        hidden[5] = !value(provider, null, SHOW_DOKI);
        hidden[6] = !value(provider, null, SHOW_MESSAGE);
        hidden[16] = !value(provider, null, SHOW_GALLERY);
        hidden[21] = !value(provider, null, SHOW_GOODS);
        hidden[PAGE_TYPE_HOME] = false;   // never hide 首页
        return new Settings(source,
                value(provider, null, BLOCK_SPLASH),
                value(provider, null, BLOCK_SPLASH_PRELOAD),
                value(provider, null, BLOCK_PLAYER_ADS),
                value(provider, null, BLOCK_AD_REQUESTS),
                value(provider, null, BLOCK_MINE_AD),
                value(provider, null, BLOCK_FEED_AUTOPLAY),
                value(provider, null, DEBUG_LOG),
                hidden, hiddenChannels(provider, null), value(provider, null, REDUCE_PRELOAD),
                value(provider, null, YOUKU_BLOCK_SPLASH),
                value(provider, null, YOUKU_BLOCK_AD_SLOT),
                value(provider, null, YOUKU_HIDE_MINE_PROMOS),
                value(provider, null, YOUKU_BLOCK_PAUSE_AD),
                value(provider, null, YOUKU_HIDE_BOTTOM_BAR),
                value(provider, null, YOUKU_SHOW_SHORT_DRAMA),
                value(provider, null, YOUKU_SHOW_VIP),
                value(provider, null, YOUKU_SHOW_GOOD_MOVIES),
                value(provider, null, IQIYI_BLOCK_SPLASH),
                value(provider, null, IQIYI_HIDE_HOME_TOP_AD),
                value(provider, null, IQIYI_HIDE_MINE_BANNER),
                value(provider, null, IQIYI_SHOW_FREE),
                value(provider, null, IQIYI_SHOW_PLUS),
                value(provider, null, IQIYI_SHOW_MEMBER));
    }

    private static Settings fromRemote(String source, SharedPreferences remote) {
        boolean[] hidden = new boolean[64];
        hidden[1] = !value(null, remote, SHOW_SHORT_VIDEO);
        hidden[2] = !value(null, remote, SHOW_VIP);
        hidden[3] = !value(null, remote, SHOW_LIVE);
        hidden[4] = !value(null, remote, SHOW_MINE);
        hidden[5] = !value(null, remote, SHOW_DOKI);
        hidden[6] = !value(null, remote, SHOW_MESSAGE);
        hidden[16] = !value(null, remote, SHOW_GALLERY);
        hidden[21] = !value(null, remote, SHOW_GOODS);
        hidden[PAGE_TYPE_HOME] = false;   // never hide 首页
        return new Settings(source,
                value(null, remote, BLOCK_SPLASH),
                value(null, remote, BLOCK_SPLASH_PRELOAD),
                value(null, remote, BLOCK_PLAYER_ADS),
                value(null, remote, BLOCK_AD_REQUESTS),
                value(null, remote, BLOCK_MINE_AD),
                value(null, remote, BLOCK_FEED_AUTOPLAY),
                value(null, remote, DEBUG_LOG),
                hidden, hiddenChannels(null, remote), value(null, remote, REDUCE_PRELOAD),
                value(null, remote, YOUKU_BLOCK_SPLASH),
                value(null, remote, YOUKU_BLOCK_AD_SLOT),
                value(null, remote, YOUKU_HIDE_MINE_PROMOS),
                value(null, remote, YOUKU_BLOCK_PAUSE_AD),
                value(null, remote, YOUKU_HIDE_BOTTOM_BAR),
                value(null, remote, YOUKU_SHOW_SHORT_DRAMA),
                value(null, remote, YOUKU_SHOW_VIP),
                value(null, remote, YOUKU_SHOW_GOOD_MOVIES),
                value(null, remote, IQIYI_BLOCK_SPLASH),
                value(null, remote, IQIYI_HIDE_HOME_TOP_AD),
                value(null, remote, IQIYI_HIDE_MINE_BANNER),
                value(null, remote, IQIYI_SHOW_FREE),
                value(null, remote, IQIYI_SHOW_PLUS),
                value(null, remote, IQIYI_SHOW_MEMBER));
    }


    /** Display names of every channel the user turned off; 首页 is never included. */
    private static String[] hiddenChannels(Bundle provider, SharedPreferences remote) {
        ArrayList<String> names = new ArrayList<String>();
        for (String[] entry : CHANNEL_CATALOG) {
            if (!value(provider, remote, entry[0])) names.add(entry[1]);
        }
        return names.toArray(new String[names.size()]);
    }

    /** Either source may be null; a null source means "use the documented default". */
    private static boolean value(Bundle provider, SharedPreferences remote, String key) {
        boolean fallback = defaultFor(key);
        if (provider != null) return provider.getBoolean(key, fallback);
        if (remote != null) return read(remote, key, fallback);
        return fallback;
    }

    /** One IPC per process launch; returns null (never throws) when the channel is unavailable. */
    static Bundle readFromProvider(Context context) {
        if (context == null) {
            providerError = "no_context";
            return null;
        }
        try {
            Bundle bundle = context.getContentResolver().call(
                    Uri.parse("content://" + ConfigProvider.AUTHORITY), CALL_GET, null, null);
            if (bundle != null && bundle.getBoolean(ConfigProvider.KEY_AVAILABLE, false)) return bundle;
            providerError = "empty_or_unavailable_bundle";
        } catch (Throwable error) {
            providerError = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        return null;
    }

    static boolean anyHidden(boolean[] hidden) {
        for (boolean value : hidden) if (value) return true;
        return false;
    }

    /** Human label for logs and the settings page. */
    static String pageTypeName(int pageType) {
        switch (pageType) {
            case 0: return "首页";
            case 1: return "短视频";
            case 2: return "VIP会员";
            case 3: return "直播";
            case 4: return "我的";
            case 5: return "热议";
            case 6: return "消息";
            case 8: return "观看历史";
            case 9: return "下载";
            case 10: return "搜索";
            case 11: return "首页(SKP)";
            case 12: return "上新";
            case 13: return "片库";
            case 14: return "我的(SKP)";
            case 16: return "好片";
            case 17: return "探片";
            case 18: return "筛选页";
            case 19: return "少儿模式";
            case 20: return "搜索(浮层)";
            default: return "pageType=" + pageType;
        }
    }

    static boolean knownPageType(int pageType) {
        for (int known : KNOWN_PAGE_TYPES) if (known == pageType) return true;
        return false;
    }
}
