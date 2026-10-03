package io.github.qqliveclean.apps.didi;

import android.content.SharedPreferences;

/** 单一事实源：目标包、特性清单（key ↔ 开关 ↔ 实现 ↔ 对应页面 ↔ 分类）。 */
public final class Config {
    private Config() {}

    public static final String PACKAGE = "com.sdu.didi.psnger";
    public static final String TAG = "DiDiAdClean";
    /** 规则结构版本；改动语义时 +1（日志里带 schema，区分「没生效」和「没加载」）。 */
    public static final int REPORT_SCHEMA = 5;
    /** RemotePreferences 组名：设置页写、被 hook 进程读，两端必须一致。 */
    public static final String GROUP = "didiclean";
    public static final String STATUS_FILE = "compatibility_status";
    public static final String ACTION_REPORT = "io.github.qqliveclean.apps.didi.REPORT";
    /** 兼容报告通道（rows 式，参考 video-lsposed 的 COMPAT_REPORT）。 */
    public static final String ACTION_COMPAT = "io.github.qqliveclean.apps.didi.COMPAT_REPORT";
    public static final String COMPAT_FILE = "compat_reports";

    // ── 分类（设置页按此分组显示）──────────────────────────────────────────
    public static final String[] CATEGORIES = {"global", "ad", "home", "diagnostic"};
    public static final String[] CATEGORY_TITLES = {"全局", "广告拦截", "首页界面", "诊断与恢复"};
    public static final String[] CATEGORY_NOTES = {
            "影响所有页面",
            "广告位：开屏 / 弹窗 / 通知",
            "对应滴滴首页（出行 tab）上的界面元素",
            "只写日志，不改行为",
    };

    // ── 特性（key 即开关键名，一一对应 MainHook 的 setId）──────────────────
    public static final String[] FEATURES = {
            "no_ads",            // 全局
            "popup",             // 广告
            "splash",            // 广告
            "block_dialogs",     // 广告（通用弹窗，默认关）
            "hide_promo_card",   // 首页
            "hide_home_banner",  // 首页
            "hide_bottom_nav",   // 首页
            "keep_home_tabs",    // 首页（底部栏只留 首页/我的）
            "hide_top_tabs",     // 首页
            "hide_top_tools",    // 首页
            "hide_scene_row",    // 首页
            "probes",            // 诊断
    };
    public static final String[] FEATURE_LABELS = {
            "广告总闸（弹窗 / 通知 / 开屏展示）",
            "广告弹窗 · 通知（只拦广告，正常弹窗保留）",
            "开屏广告",
            "⚠ 通用弹窗全拦（默认关：会连正常提示一起拦）",
            "首页营销卡片 / 营销横幅",
            "首页底部营销专区",
            "首页底部导航栏",
            "底部导航栏只留「首页 / 我的」",
            "首页顶部频道栏",
            "首页右上角工具按钮（乘车码 / 扫一扫）",
            "首页场景行（AI 叫车 / 预约 / 帮人叫车 / 接送机）",
            "调用观测（只写日志）",
    };
    /** 对应页面：让用户一眼知道这个开关改的是哪个界面。 */
    public static final String[] FEATURE_PAGES = {
            "全局 · 所有广告位",
            "任意页 · 弹窗浮层 / 通知（只拦广告 SDK 的弹窗）",
            "启动页 · 冷启动开屏",
            "任意页 · DiDi 自有弹窗（活动/营销/提示都走这里，谨慎开启）",
            "首页 · 顶部智能卡片（如“宠物出行 打车最高减10元”）",
            "首页 · 最下方“一个滴滴，畅行全城”推广区",
            "首页 · 底部导航栏（首页/消息/行程/我的）",
            "首页 · 底部导航栏逐项（去掉消息/行程，保留首页/我的）",
            "首页 · 顶部频道栏（出行/送货/旅行/车主）",
            "首页 · 右上角（乘车码 / 扫一扫）",
            "首页 · 搜索卡片下方一行（AI叫车/预约/帮人叫车/接送机）",
            "诊断 · 不改变界面",
    };
    public static final String[] FEATURE_NOTES = {
            "把广告 SDK 置为“未就绪”，广告展示路径自然退出。实测命中 AdSdk.d(AdRequest)",
            "拦截 PopRequest / NotifyRequest（按方法名挂，跨版本稳）",
            "不运行开屏展示流程、不接受开屏资源（布防中，待开屏库存命中）",
            "拦截 com.didi.sdk.view.dialog.b.show()：滴滴自有弹窗组件的统一出口。**默认关闭**——它也会承载确认/安全类弹窗，开了会连正常弹窗一起拦",
            "隐藏 v8_smart_card_container / home_main_card_activity_image",
            "隐藏 home_banner_proxy_view / ch_banner_casper_container",
            "隐藏 v6x_home_bottom_nav（整条底部栏，含消息/行程入口）",
            "在 BottomNavigationView 构建 tab 前过滤数据：只保留 home_page/user_center（宽度按过滤后的数量算，安全）",
            "隐藏 tabLayout（出行/送货/旅行/车主 频道）",
            "隐藏 home_v8x_action_bar_riding_container / riding_code / scan",
            "隐藏 ch_scene_layout（搜索卡片下面那行快捷入口）",
            "只读探针：只打 hit 日志、原样放行，用于确认真实调用链",
    };
    public static final String[] FEATURE_CATEGORY = {
            "global", "ad", "ad", "ad", "home", "home", "home", "home", "home", "home", "home", "diagnostic",
    };
    public static final boolean[] FEATURE_DEFAULTS = {
            true, true, true, false, true, true, false, false, true, true, false, true,
    };

    /** 探针目标类（非混淆类名，方法名被 R8 混淆 → 按名字/形状挂）。 */
    public static final String[] PROBE_CLASSES = {
            "com.didi.ad.splash.QuickSplashShow",
            "com.didi.ad.splash.QuickSplashLoad",
            "com.didi.ad.AdSdk",
            "com.didi.ad.base.net.HttpSender",
            "com.didi.ad.api.AdRequest",
    };

    /** 参数类型里出现这些前缀 = 该方法是广告链路的关键节点。 */
    public static final String[] INTERESTING_TYPE_PREFIXES = {
            "com.didi.ad.",
            "java.util.Map",
            "java.util.HashMap",
            "kotlin.coroutines.Continuation",
    };

    public static final int MAX_HOOKS = 40;

    /**
     * 版本兼容自检表：运行时逐个探测锚点是否存在（滴滴换版本时 R8 会改方法名/类名）。
     * 每项 = {类名, 方法名或空, 形状提示}；形状提示：""=只查类、"1"=1 个参数、"view"=创建视图、
     * "list"=第二参为 List。缺失的锚点会被自动跳过（fail-open），并在设置页显示自检结果。
     */
    public static final String[][] COMPAT_ANCHORS = {
            {"com.didi.ad.AdSdk", "d", "1"},
            {"com.didi.ad.AdSdk", "g", ""},
            {"com.didi.ad.splash.QuickSplashShow", "f", "1"},
            {"com.didi.ad.splash.QuickSplashShow", "c", "1"},
            {"com.didi.ad.splash.QuickSplashLoad", "", ""},
            {"com.didi.ad.base.net.HttpSender", "", ""},
            {"com.didi.ad.api.AdRequest", "", ""},
            {"com.didi.carhailing.framework.v8.home.V8HomeFragment", "", "view"},
            {"com.didi.carhailing.framework.v8.home.V8xHomeFragment", "", "view"},
            {"com.didi.carhailing.framework.v8.home.V8xHomeContainerFragment", "", "view"},
            {"com.didi.carhailing.framework.common.app.HomeContainer", "", "view"},
            {"com.didi.carhailing.framework.common.bottombar.bottom.widget.BottomNavigationView", "", "list"},
            {"com.didi.sdk.view.dialog.b", "show", ""},
    };

    public static boolean read(SharedPreferences prefs, String key, boolean defaultValue) {
        if (prefs == null) return defaultValue;
        try {
            return prefs.getBoolean(key, defaultValue);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    public static int indexOf(String key) {
        for (int i = 0; i < FEATURES.length; i++) if (FEATURES[i].equals(key)) return i;
        return -1;
    }

    public static boolean defaultOf(String key) {
        int i = indexOf(key);
        return i >= 0 && FEATURE_DEFAULTS[i];
    }

    public static String labelOf(String key) {
        int i = indexOf(key);
        return i < 0 ? key : FEATURE_LABELS[i];
    }

    public static String pageOf(String key) {
        int i = indexOf(key);
        return i < 0 ? "" : FEATURE_PAGES[i];
    }

    public static String noteOf(String key) {
        int i = indexOf(key);
        return i < 0 ? "" : FEATURE_NOTES[i];
    }

    public static String categoryOf(String key) {
        int i = indexOf(key);
        return i < 0 ? "" : FEATURE_CATEGORY[i];
    }

    public static String categoryTitle(String category) {
        for (int i = 0; i < CATEGORIES.length; i++) if (CATEGORIES[i].equals(category)) return CATEGORY_TITLES[i];
        return category;
    }

    public static String categoryNote(String category) {
        for (int i = 0; i < CATEGORIES.length; i++) if (CATEGORIES[i].equals(category)) return CATEGORY_NOTES[i];
        return "";
    }
}
