package io.github.qqliveclean.apps.heytapmarketclean;

/**
 * 单一事实源：包名、特性清单、开关键名、规则结构版本。
 * 键名 <-> MainActivity 开关 <-> MainHook 的 setId 必须一一对应。
 *
 * 所有锚点均来自 OPPO 软件商店 26.5.2_CN（versionCode 260502）的 DEX 实测，
 * 证据见 docs/ANALYSIS-*.md。混淆名跨版本会整批死亡，所以每条规则都带
 * 「形状判据」（参数/返回类型/修饰符）做二次校验，对不上就 fail-open。
 */
final class Config {
    static final String MODULE = "io.github.heytapmarketclean";

    /** 目标 App 包名。 */
    static final String TARGET = "com.heytap.market";

    /**
     * 规则结构版本：只有规则语义变化时才 +1（会让锚点缓存失效重探）。
     */
    /** 规则结构版本：规则语义变了就 +1，缓存 token 随之失效、重新探测。 */
    static final String SCHEMA = "7";

    /** RemotePreferences 组名。 */
    static final String GROUP = "io.github.heytapmarketclean_settings";

    // ── 特性键 ─────────────────────────────────────────────────────────────
    static final String F_FLOAT_AD       = "float_ad";        // 悬浮广告
    static final String F_AI_BUBBLE      = "ai_bubble";       // AI 搜索引导气泡
    static final String F_CTA_DIALOG     = "cta_dialog";      // 活动弹窗 CTA
    static final String F_MSP_AD         = "msp_ad";          // msp 营销弹窗（启动/留存/提示）
    static final String F_BOOT_GUIDE     = "boot_guide";      // 开机必备引导页
    static final String F_BOTTOM_BAR     = "bottom_bar";      // 底部导航栏
    static final String F_TOP_BANNER     = "top_banner";      // 首页顶部大图推广轮播
    static final String F_NAV_BADGE      = "nav_badge";       // 底栏 tab 上的红点/数字角标
    static final String F_MINE_UPGRADE   = "mine_upgrade";    // 待更新
    static final String F_MINE_UNINSTALL = "mine_uninstall";  // 应用卸载
    static final String F_MINE_DOWNLOAD  = "mine_download";   // 下载管理
    static final String F_MINE_CLEAN     = "mine_clean";      // 存储空间清理
    static final String F_MINE_HEALTH    = "mine_health";     // 应用健康状态
    static final String F_MINE_BANNER    = "mine_banner";     // 热门好礼横幅
    static final String F_MINE_RECOMMEND = "mine_recommend";  // 继续探索推荐卡
    static final String F_MINE_VIP       = "mine_vip";        // 游戏 VIP 卡
    static final String F_NOTI_RECOMMEND = "noti_recommend";  // 热门内容推荐推送
    static final String F_NOTI_PUSH_HIGH = "noti_push_high";  // 热门内容推送（高优先级）
    static final String F_NOTI_TOOL      = "noti_tool";       // 应用运行状态提示（加速推广）
    static final String F_NOTI_UPGRADE   = "noti_upgrade";    // 可更新应用提醒
    static final String F_NOTI_SELF      = "noti_self";       // 商店自身更新进度
    static final String F_NOTI_SCAN      = "noti_scan";       // 通知侦察（记录所有推送）

    static final String[] FEATURES = {
            F_FLOAT_AD, F_AI_BUBBLE, F_CTA_DIALOG, F_MSP_AD, F_BOOT_GUIDE,
            F_BOTTOM_BAR, F_TOP_BANNER, F_NAV_BADGE,
            F_MINE_UPGRADE, F_MINE_UNINSTALL, F_MINE_DOWNLOAD,
            F_MINE_CLEAN, F_MINE_HEALTH, F_MINE_BANNER, F_MINE_RECOMMEND, F_MINE_VIP,
            F_NOTI_RECOMMEND, F_NOTI_PUSH_HIGH, F_NOTI_TOOL, F_NOTI_UPGRADE, F_NOTI_SELF, F_NOTI_SCAN,
    };

    static final String[] FEATURE_LABELS = {
            "悬浮广告", "AI 搜索引导气泡", "活动弹窗（CTA）", "营销弹窗（msp）", "开机必备引导页",
            "底栏推广入口", "顶部横幅推广位", "底栏红点角标",
            "待更新", "应用卸载", "下载管理",
            "存储空间清理", "应用健康状态", "热门好礼横幅", "“继续探索”推荐卡", "游戏 VIP 卡",
            "热门内容推荐推送", "热门内容推送（高优先级）", "应用运行状态提示",
            "可更新应用提醒", "商店自身更新进度", "通知侦察（记录全部推送）",
    };

    /** 每一项都必须写清「对应页面」——用户要求一眼看出改的是哪个界面。 */
    static final String[] FEATURE_PAGES = {
            "任意页右下角悬浮图标",
            "首页顶部搜索栏的 AI 图标气泡",
            "任意页弹出的活动/广告弹窗",
            "冷启动与切页时弹出的营销弹窗（msp SDK）",
            "冷启动后的“开机必备”全屏引导页",
            "首页底部导航栏（只留名单内的 tab，并平均铺满整条栏）",
            "首页顶部横向大图推广位（可滑动的推广轮播）",
            "底部导航栏 tab 右下角的小红点 / 数字角标",
            "我的 · 顶部三宫格左",
            "我的 · 顶部三宫格中",
            "我的 · 顶部三宫格右",
            "我的 · 存储空间清理卡片",
            "我的 · 应用健康状态卡片",
            "我的 · 热门好礼免费领横幅",
            "我的 · “在这里，继续探索”推荐卡",
            "我的 · 登录/游戏 VIP 头部卡",
            "系统通知栏（锁屏/桌面下拉）里的推荐广告",
            "系统通知栏里的热门内容推送（importance=4，会震动/响铃）",
            "系统通知栏里的加速/清理推广提示",
            "系统通知栏里的“有 N 个应用可更新”提醒",
            "系统通知栏里软件商店自己更新时的进度条",
            "不拦截任何通知，只把实际出现的通道与标题记进日志",
    };

    static final String[] FEATURE_NOTES = {
            "闸门：FloatJumpPriorManager.canShow(FloatShowType) 恒 false",
            "闸门：AISearchBubbleUtil 展示方法置空（不影响 AI 搜索入口本身）",
            "闸门：CtaManager.showCTA 置空（保留弹窗回调，不卡界面）",
            "闸门：msp DialogHelper 的展示入口置空 + 留存判断恒 false（包名未混淆，跨版本稳）",
            "闸门：安装引导 Intent 构造返回 null（App 自身已做空判断）",
            "在底栏「建视图」那一步移除名单外的 tab（不是先画后藏，所以切页不闪）；剩余 tab 测量时按栏宽均分",
            "只隐藏 stage_inner_listview 子树里那个 recycler_view（顶部推广轮播）——外层列表同时装着下面所有应用卡片，隐藏它等于整个首页都没了",
            "闸门：COUINavigationView.setTipsViewByItemId 置空，角标根本不挂上去（不是事后隐藏）",
            "按资源 id ll_upgrade 隐藏；三项全开时整张卡一起隐藏",
            "按资源 id ll_uninstall 隐藏；三项全开时整张卡一起隐藏",
            "按资源 id ll_download_manager 隐藏；三项全开时整张卡一起隐藏",
            "按资源 id cl_clean 隐藏；与健康卡同时开启时整行隐藏",
            "按资源 id cl_health 隐藏；与清理卡同时开启时整行隐藏",
            "按资源 id scroll_banner + banner_indicator 隐藏",
            "按结构识别（卡片内含 HorizontalAppItemView）隐藏",
            "按资源 id vip_layout 隐藏",
            "闸门：NotificationManager.notify 按通道 id 拦截（platform_recommend_push_notification）",
            "闸门：同上，拦推送 SDK 家族的 com.heytap.marketpush_noti_high（importance=4）",
            "闸门：同上，拦 platform_tool_notification（加速/清理推广）",
            "闸门：同上，拦 platform_check_updates_notification",
            "闸门：同上，拦 platform_self_updated_progress_notification",
            "启动时枚举 getNotificationChannels() 并逐条打日志；每次 notify 记录通道与标题。不改变任何通知行为",
    };

    /** 0=广告拦截 1=界面 2=我的页面 3=通知 */
    static final int[] FEATURE_CATEGORY = {
            0, 0, 0, 0, 0, 1, 0, 1, 2, 2, 2, 2, 2, 2, 2, 2,
            3, 3, 3, 3, 3, 3,
    };

    static final String[] CATEGORIES = {"广告拦截", "界面", "我的页面", "通知"};

    /**
     * 默认值：广告类默认开；「我的」页按用户逐项勾选的结果开
     * （会员 VIP、卸载/空间清理、健康、热门好礼横幅、继续探索推荐卡 = 开；
     *   待更新、下载管理 = 关）。
     * 通知类只默认关**营销**的三类；可更新应用提醒 / 商店自身更新 = 关（用户可自行打开）。
     * 下载任务、安装完成、更新完成这三类是**用户主动行为**产生的，一概不拦，
     * 所以根本不给开关——误伤代价比多一条通知大得多。
     */
    static final boolean[] FEATURE_DEFAULT = {
            true, true, true, true, true, true, true, true,
            false, true, false, true, true, true, true, true,
            true, true, true, false, false, true,
    };

    static String key(String feature) { return feature + "_enabled"; }

    // ── 通知拦截 ────────────────────────────────────────────────────────────
    /**
     * 推送所在（且只）需要装通知闸门的子进程。
     *
     * 26.5.2_CN 清单实测共 11 个子进程：`:restart :track :recovery :rhea :media
     * :hlog :background ×3 :tbl_privileged_process0..4 :tbl_sandboxed_process0`。
     * 只挑其中最可能发通知的两个：
     *  - `:rhea`       —— OPPO 推送 SDK（Rhea）的宿主进程
     *  - `:background` —— 后台任务聚合，App 常用它在后台发通知
     * 其余一律跳过，避免为一个通知规则让模块在 11 个进程里都加载一遍。
     */
    static final String[] NOTIFY_PROCESSES = {":rhea", ":background"};

    /**
     * 进程名是否需要装通知闸门。
     *
     * ⚠️ `processName` 给的是**全名**（`com.heytap.market:rhea`），不是 `:rhea`。
     * 早先直接拿 `":rhea"` 去 equals，日志实测为
     * `skip: secondary process com.heytap.market:rhea` —— 一次都没命中。
     * 所以比**后缀**。包名已在上游门闸比过，这里不会误匹配别的包。
     */
    static boolean isNotifyProcess(String process) {
        if (process == null) return false;
        for (int i = 0; i < NOTIFY_PROCESSES.length; i++) {
            if (process.endsWith(NOTIFY_PROCESSES[i])) return true;
        }
        return false;
    }

    /**
     * 通道 id 特征串 → FEATURES 下标。
     *
     * 实测到的真实通道 id（`dumpsys notification` + aapt2 资源表）：
     *   platform_recommend_push_notification          热门内容推荐      ← 广告
     *   com.heytap.marketpush_noti_high               热门内容推送      ← 广告，importance=4
     *   platform_tool_notification                    应用运行状态提示  ← 加速/清理推广
     *   platform_check_updates_notification           可更新应用
     *   platform_self_updated_progress_notification   商店自身更新
     *   platform_download_task_notification           下载任务          ← 用户主动，不拦
     *   platform_installed_notification               安装完成          ← 用户主动，不拦
     *   platform_updated_notification                 更新完成          ← 用户主动，不拦
     *
     * 实测到的真实通道（26.5.2_CN 真机 `getNotificationChannels()`，v0.6.0 侦察功能输出）：
     *   Foreground_Channel_Id              核心服务      importance=3   前台服务，不拦
     *   com.heytap.marketpush_noti_high    热门内容推送   importance=4   ★营销，已覆盖
     *   download_task_notify_channel_id    下载任务提醒   importance=3   用户主动，不拦
     *
     * ⚠️ **运行期通道 id 和 dex 里的资源名不是一回事**，这个坑是侦察功能实测出来的：
     *   资源 `platform_download_task_notification` → 运行期 `download_task_notify_channel_id`
     *   资源 `platform_recommend_push_notification` → 推送 SDK 走的是 `com.heytap.marketpush_noti_high`
     * 所以特征串取的是**去包名、去前缀后的辨识部分**，两种命名形态都能命中；
     * 没命中的通道会照实打在 `noti_channel: ... covered=-` 里，靠侦察看缺口，不靠猜。
     *
     * 刻意**不给**下载任务/安装完成/更新完成配特征串：那是用户自己点了才产生的通知，
     * 误伤代价远大于多一条通知。核心服务（前台服务）同理。
     */
    static final String[] CHANNEL_MATCH = {
            "recommend",      // 热门内容推荐推送
            "push_noti",      // 热门内容推送（实测 com.heytap.marketpush_noti_high；也能命中 *push_notify_channel_id）
            "tool",           // 应用运行状态 / 加速清理推广
            "check_update",   // 可更新应用提醒
            "self_updated",   // 商店自身更新进度
    };

    // ── 资源 id 名（真机 dumpsys 视图树 + aapt2 资源表实测） ────────────────
    static final String ID_BOTTOM_TAB   = "fl_navi_menu_tab";
    static final String ID_BOTTOM_NAV   = "navi_menu_tab";
    static final String ID_FLOAT_AD     = "view_id_float_ad";
    static final String ID_MINE_UPGRADE = "ll_upgrade";
    static final String ID_MINE_UNINST  = "ll_uninstall";
    static final String ID_MINE_DOWN    = "ll_download_manager";
    static final String ID_MINE_CLEAN   = "cl_clean";
    static final String ID_MINE_HEALTH  = "cl_health";
    static final String ID_MINE_BANNER  = "scroll_banner";
    static final String ID_MINE_INDIC   = "banner_indicator";
    static final String ID_MINE_VIP     = "vip_layout";
    static final String ID_MINE_LIST    = "mine_list_view";
    /** 首页内容区的外层列表：横幅轮播和下面所有应用卡片都是它的子项，只作锚点用。 */
    static final String ID_TOP_STAGE    = "stage_inner_listview";
    /** 顶部横幅推广轮播本身（只在上面那个列表的子树里找，避免误伤「我的」页的同名 id）。 */
    static final String ID_TOP_BANNER   = "recycler_view";
    /** 底栏 tab 上的角标（ViewStub，未 inflate 时不占空间）。 */
    static final String ID_NAV_TIP      = "vs_warning_tip";
    // 「我的」页卡片是复用的通用布局（cl_content + tv_title/tv_subtitle/tv_button），
    // 运行期没有 cl_clean / cl_health 这类专属 id，只能靠标题文案或推荐卡的 id 组合识别。
    /** 通用卡片的内容容器。 */
    static final String ID_MINE_CARD_CONTENT = "cl_content";
    /** 通用卡片的标题。 */
    static final String ID_MINE_CARD_TITLE   = "tv_title";
    /** 推荐卡的容器。 */
    static final String ID_MINE_REC_CARD     = "card_container";
    /** 推荐卡里的单个应用项。 */
    static final String ID_MINE_REC_ITEM     = "v_app_item";
    /** 推荐卡里的评分控件（HorizontalAppItemView 的标志性子 id）。 */
    static final String ID_MINE_REC_RATING   = "horizontal_app_item_view_app_rating";

    /** 靠标题文案识别的卡片；文案是跨混淆最稳的锚点。 */
    static final String[] MINE_CARD_LABELS_CLEAN  = {"存储空间清理", "空间清理", "清理加速"};
    static final String[] MINE_CARD_LABELS_HEALTH = {"应用健康状态", "安全防护", "健康状态"};
    /** 热门好礼横幅的标题也走文案兜底（scroll_banner 有专属 id，但有些版本没有）。 */
    static final String[] MINE_CARD_LABELS_BANNER = {"热门好礼", "好礼免费领", "福利", "活动"};

    // ── 底栏 tab 内部结构（真机 dumpsys 视图树实测，class=com.coui.appcompat.bottomnavigation.*）──
    // 列表容器 COUINavigationMenuView 没有资源 id，只能按类名在 navi_menu_tab 的直接子项里找。
    /** tab 的大号文案（选中态显示的那个）。 */
    static final String ID_TAB_LABEL_LARGE = "navigation_bar_item_large_label_view";
    /** tab 的小号文案（未选中态显示的那个）。 */
    static final String ID_TAB_LABEL_SMALL = "navigation_bar_item_small_label_view";

    /**
     * 底栏名单。
     *
     * v0.4.0 之前这里是「隐藏名单」（默认 福利,活动,签到,福利中心），靠设 GONE 藏 tab。
     * v0.4.0 起改成**保留名单**：只留用户明确要的几项，其余从源头不再建视图。
     *
     * 为什么不继续用隐藏名单：COUINavigationMenuView 的布局是按
     * 「子视图序号 i × (宽度 / itemCount)」排的，itemCount 取自适配器。
     * 只把子视图设成 GONE 而不动 itemCount，被藏的项仍然占着槽位，
     * 于是界面上留下等宽的空洞——这就是之前「太丑」的真正原因。
     * 从 buildMenuView 之后移除子视图 + 让计数跟着变，才是干净的均分。
     *
     * 取值是 tab 文案，可由设置页覆盖。
     */
    static final String DEFAULT_TAB_LABELS = "首页,我的";

    /** 底栏隐藏名单的设置键。 */
    static final String KEY_TAB_LABELS = "bottom_bar_labels";

    static String readString(android.content.SharedPreferences prefs, String key, String fallback) {
        if (prefs == null) return fallback;
        try {
            String v = prefs.getString(key, fallback);
            return (v == null || v.trim().length() == 0) ? fallback : v;
        } catch (Throwable ignored) { return fallback; }
    }

    /** 容错读取：RemotePreferences 未连接时返回默认值，绝不抛给调用方。 */
    static boolean readBoolean(android.content.SharedPreferences prefs, String key, boolean fallback) {
        if (prefs == null) return fallback;
        try { return prefs.getBoolean(key, fallback); }
        catch (Throwable ignored) { return fallback; }
    }

    static int indexOf(String feature) {
        for (int i = 0; i < FEATURES.length; i++) if (FEATURES[i].equals(feature)) return i;
        return -1;
    }

    private Config() {}
}
