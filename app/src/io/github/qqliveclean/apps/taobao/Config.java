package io.github.qqliveclean.apps.taobao;

/**
 * 单一事实源：目标包名、特性清单、开关键名、规则结构版本。
 * 键名 ↔ MainActivity 开关 ↔ MainHook 的 probe(...) 必须一一对应。
 */
final class Config {
    /**
     * 模块自己的包名。
     * 被 hook 的进程里 `context.getPackageName()` 返回的是**目标 App** 的包名，
     * 所以给设置页发广播时绝不能拼它，必须用这个常量（本机实测：拼错后广播被静默丢弃）。
     */
    static final String MODULE = "io.github.qqliveclean.apps.taobao";

    /** 目标 App 包名（可多个；META-INF/xposed/scope.list 必须与之一致）。 */
    static final String TAOBAO = "com.taobao.taobao";
    static final String IDLEFISH = "com.taobao.idlefish";
    static final String[] TARGETS = {TAOBAO, IDLEFISH};

    /**
     * 规则结构版本：只有**规则语义**变化时才 +1。
     * 它进入缓存 token，改大等于让所有已扫到的锚点缓存失效重扫。
     */
    static final String SCHEMA = "1";

    /** RemotePreferences 组名，被 hook 进程与设置页必须一致。 */
    static final String GROUP = "io.github.qqliveclean.apps.taobao_settings";

    /**
     * 特性清单：每个特性 = 一条独立探测的规则。
     * 新增特性时：这里加 key + 标签，MainHook 里加 probe(...)，MainActivity 里加开关。
     */
    static final String[] FEATURES = {"splash", "popup", "widget", "sdk"};
    static final String[] FEATURE_LABELS = {
            "开屏广告（启动图 / 闪屏）",
            "弹窗广告（首页浮层 / 活动弹窗）",
            "悬浮广告与广告球",
            "广告 SDK 初始化与预加载",
    };

    /** 每项对应的页面，设置页要一眼看出改的是哪个界面。 */
    static final String[] FEATURE_PAGES = {
            "冷启动 / 切回前台 / 路由唤起",
            "首页（登录成功 / 新人引导 / 主容器就绪）",
            "首页与频道页的浮层广告位",
            "全应用（后台线程，省电省流量）",
    };

    static final String SPLASH = "splash_enabled";
    static final String POPUP = "popup_enabled";
    static final String WIDGET = "widget_enabled";
    static final String SDK = "sdk_enabled";

    /** 默认值：四项都默认开启（装完即生效，不需要先摸设置页）。 */
    static final boolean DEFAULT_ON = true;

    /** 容错读取：RemotePreferences 未连接时返回默认值，绝不抛给调用方。 */
    static boolean readBoolean(android.content.SharedPreferences prefs, String key, boolean fallback) {
        if (prefs == null) return fallback;
        try { return prefs.getBoolean(key, fallback); }
        catch (Throwable ignored) { return fallback; }
    }

    static boolean isTarget(String packageName) {
        if (packageName == null) return false;
        for (String target : TARGETS) if (target.equals(packageName)) return true;
        return false;
    }

    private Config() {}
}
