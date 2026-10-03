package io.github.qqliveclean.apps.heytapmarketclean;

import android.app.Activity;
import android.app.Application;
import android.app.BroadcastOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模块入口：三重门闸 + 逐特性探针 + 状态上报 + 全部 hook。
 *
 * 三条硬性纪律（见 SKILL.md 第 1 节）：
 *  1. 只 hook 一次性决策闸门；UI 只在 onResume / 切页时按 id 精确隐藏，绝不遍历全树、绝不轮询。
 *  2. intercept 内零分配、零反射、零日志（命中打点用 CAS 只打一次）。
 *  3. fail-open：任何一步失败都只 log(WARN)，目标 App 行为与未装模块完全一致。
 */
public final class MainHook extends io.github.qqliveclean.RuleHost {
    private static final String TAG = "HmClean";
    private static final String REPORT_ACTION = Config.MODULE + ".REPORT";
    private static final String REPORT_RECEIVER = Config.MODULE + ".StatusReceiver";

    // ── 锚点：类名与方法名都来自 26.5.2_CN 的 DEX 实测（混淆名以 unicode 转义写） ──
    /** 悬浮广告优先级闸门（Kotlin 单例 FloatJumpPriorManager）。 */
    private static final String CLS_FLOAT_PRIOR = "a.a.a.qx5";
    private static final String M_FLOAT_CANSHOW = "\u0528";
    private static final String T_FLOAT_SHOWTYPE = "com.nearme.uikit.widget.floatJump.FloatShowType";

    /** AI 搜索气泡（AISearchBubbleUtil）：\u052f 内会 log "Show bubble failed"。 */
    private static final String CLS_AI_BUBBLE = "a.a.a.p";
    private static final String M_AI_SHOW = "\u052f";
    private static final String T_EFFECTIVE_VIEW = "com.oplus.anim.EffectiveAnimationView";

    /** CTA 活动弹窗管理器（非混淆名）。 */
    private static final String CLS_CTA = "a.a.a.hg3";
    private static final String M_CTA_SHOW = "showCTA";

    // ── msp 营销弹窗（用户反馈「打开软件会弹窗」的拦截面）────────────────
    // msp = 营销服务平台，是 App 自带的广告/营销 SDK，「打开时弹出的那个窗」就出自这里。
    // 它的包名**没有混淆**（发布过的 SDK），所以这是整个 App 里最稳的锚点之一，
    // 比 a.a.a.* 那些混淆名可靠得多——正好落在「多版本自适配」要的地方。
    //
    // dex 实测：com.heytap.msp.sdk.common.dialog.DialogHelper 是弹窗调度中枢，
    // create/show/dismiss 一整套；广告弹窗体是 CommonDialog，三个构造器全是
    // (Activity, 图片地址, 文案, 跳转链接, ..., 回调) —— 典型广告素材。
    private static final String CLS_MSP_HELPER   = "com.heytap.msp.sdk.common.dialog.DialogHelper";
    private static final String M_MSP_SHOW_AD    = "showDownloadDialog";
    private static final String M_MSP_NEED_KEEP = "needShowRetentionDialog";
    private static final String M_MSP_SHOW_TIPS = "showTipsDialog";
    private static final String M_MSP_SHOW_KEEP = "showRetentionTipDialog";

    // ── 底栏：从「建视图」那一步减员，而不是事后把视图设成 GONE ──────────
    // COUI 的底栏控件是非混淆包名（ColorOS UI Kit），锚点跨版本稳定。
    // buildMenuView() 正是「先按列表建视图」的那一步——在这一步之后、
    // 任何 measure/layout/draw 之前移除子视图，被删的项就永远不会被画出来。
    private static final String CLS_NAV_MENU_VIEW = "com.coui.appcompat.material.navigation.NavigationBarMenuView";
    private static final String M_NAV_BUILD_MENU = "buildMenuView";
    private static final String CLS_NAV_VIEW = "com.coui.appcompat.bottomnavigation.COUINavigationView";
    /** 挂角标（红点/数字）的那一步。 */
    private static final String M_NAV_SET_TIPS = "setTipsViewByItemId";

    /** “开机必备”安装引导页 Intent 构造器。 */
    private static final String CLS_BOOT_GUIDE = "a.a.a.ue8";
    private static final String M_GUIDE_INTENT = "\u0528";

    /** 悬浮广告视图（RelativeLayout 子类）等类的名字，仅用于文档与排障。 */
    private static final String CLS_VIEWPAGER = "androidx.viewpager.widget.ViewPager";

    // ── 特性开关快照（configure 时写一次，UI 线程只读） ────────────────────
    private static final boolean[] ON = new boolean[Config.FEATURES.length];
    private static final int I_FLOAT = Config.indexOf(Config.F_FLOAT_AD);
    private static final int I_AIBUBBLE = Config.indexOf(Config.F_AI_BUBBLE);
    private static final int I_CTA = Config.indexOf(Config.F_CTA_DIALOG);
    private static final int I_MSP = Config.indexOf(Config.F_MSP_AD);
    private static final int I_BOOT = Config.indexOf(Config.F_BOOT_GUIDE);
    private static final int I_BOTTOM = Config.indexOf(Config.F_BOTTOM_BAR);
    private static final int I_TOPBANNER = Config.indexOf(Config.F_TOP_BANNER);
    private static final int I_BADGE = Config.indexOf(Config.F_NAV_BADGE);
    private static final int I_UPGRADE = Config.indexOf(Config.F_MINE_UPGRADE);
    private static final int I_UNINSTALL = Config.indexOf(Config.F_MINE_UNINSTALL);
    private static final int I_DOWNLOAD = Config.indexOf(Config.F_MINE_DOWNLOAD);
    private static final int I_CLEAN = Config.indexOf(Config.F_MINE_CLEAN);
    private static final int I_HEALTH = Config.indexOf(Config.F_MINE_HEALTH);
    private static final int I_BANNER = Config.indexOf(Config.F_MINE_BANNER);
    private static final int I_RECOMMEND = Config.indexOf(Config.F_MINE_RECOMMEND);
    private static final int I_VIP = Config.indexOf(Config.F_MINE_VIP);

    // 通知类。Config.CHANNEL_MATCH 的第 i 项对应 I_NOTI_BASE + i，两处必须同步改。
    private static final int I_NOTI_RECOMMEND = Config.indexOf(Config.F_NOTI_RECOMMEND);
    private static final int I_NOTI_BASE = I_NOTI_RECOMMEND;
    private static final int I_NOTI_PUSH_HIGH = Config.indexOf(Config.F_NOTI_PUSH_HIGH);
    private static final int I_NOTI_TOOL      = Config.indexOf(Config.F_NOTI_TOOL);
    private static final int I_NOTI_UPGRADE   = Config.indexOf(Config.F_NOTI_UPGRADE);
    private static final int I_NOTI_SELF      = Config.indexOf(Config.F_NOTI_SELF);
    private static final int I_NOTI_SCAN      = Config.indexOf(Config.F_NOTI_SCAN);

    /** 每次触达界面后的补隐藏时刻（有限次、随事件触发，不是轮询）。 */
    /**
     * 补隐藏的时间点。
     *
     * 用户反馈「点击底栏切换时隐藏的单元又出来」的根因就是这个窗口太短：
     * 切 tab 会触发 ViewPager.setCurrentItem（已确认挂上，日志有
     * `ui triggers installed: onResume + 2 pager method(s)`），但「我的」页的
     * 推广位是**异步**下发的，重渲染常发生在 1.5 秒之后，原来的
     * {0, 600, 2000, 5000, 12000} 最后一次在 12s，中间的空档就露出来了。
     *
     * 这里改成一条覆盖整个重渲染周期、且**有界**的补跑序列：
     * 起点密集（抢在内容到达前），尾部拉长到 5s 收口，之后不再跑——
     * 不是常驻轮询，只是「用户刚切了一下页」之后的有限几次。
     *
     * 上一版因为怕闪烁把长尾全删了，那是过度反应：闪烁的成因是
     * **先显示后隐藏**，而这里所有隐藏函数只设 GONE、从不把视图设回 VISIBLE，
     * 所以多跑几轮只可能「藏得更多」，结构上不可能造成「闪一下又出现」。
     */
    private static final long[] APPLY_DELAYS = {0L, 200L, 600L, 1500L, 3000L, 5000L};

    /**
     * 绘制前兜底的帧数上限（≈330ms @60fps）。
     *
     * **不要**拿它去覆盖「我的」页那 1s 的异步下发窗口——帧数管不住时间，
     * 真正处理那条路径的是 installInflateTriggers（在内容创建时拦）。
     */
    private static final int PRE_DRAW_MAX_PASSES = 20;

    private String processName;

    /**
     * 类加载即留证：框架把对象 new 出来时一定跑这里。
     * 用来区分「回调没被调用」和「回调里日志根本没出来」——两者的现象一模一样。
     */
    static { trace("[schema=" + Config.SCHEMA + "] MainHook class loaded"); }

    /**
     * 框架构造器。两个都留着，因为真机上的两套框架要的构造器不一样：
     *  - Vector v2.2（现役框架）：只有 ()，见 framework/vector.dex 里
     *    Lio/github/libxposed/api/XposedModule; 只有一个 <init>()；
     *    真机日志：NoSuchMethodException: MainHook.<init> []
     *  - 老 LSPosed（LSPosed IT / 1.9.2 及更早）：只有 (XposedInterface, ModuleLoadedParam)
     *    真机日志：NoSuchMethodException: MainHook.<init> [XposedInterface, ...]
     * 没被框架调用的那个构造器不会在类加载时解析，所以两套框架都能加载。
     *
     * 构造器里同时写两处：框架 log() 落到 /data/adb/lspd/log/modules_*.log（不会被刷掉），
     * trace() 落到 logcat。logcat 缓冲区会被刷掉（实测一轮就刷掉 9 万行），
     * 验收一律以 modules_*.log 为准。
     */
    /**
     * 宿主模块已经由 LSPosed 实例化，这里只取它的引用来转发 hook/log。
     * 自己 new 一个 XposedModule 永远不会被框架接管（会抛 Framework not attached），
     * 所以独立模块时代那两条构造函数都不再保留。
     */
    public MainHook(io.github.libxposed.api.XposedModule host) {
        super(host);
        INSTANCE = this;
        note("MainHook entered as part of 广告净化");
    }

    /** 框架 log() + logcat 双写；log() 不可用时只留 logcat，绝不外泄异常。 */
    private void note(String message) {
        String line = "[schema=" + Config.SCHEMA + "] " + message;
        try { log(Log.INFO, TAG, line); } catch (Throwable ignored) { }
        trace(line);
    }

    /** 静态规则代码用的同类出口（UI 规则是 static 的，但日志仍走框架通道）。 */
    private static volatile MainHook INSTANCE;

    private static void noteStatic(String message) {
        MainHook module = INSTANCE;
        if (module != null) { module.note(message); return; }
        trace("[schema=" + Config.SCHEMA + "] " + message);
    }

    /**
     * 第二条独立日志通道：只走 android.util.Log，不依赖框架注入的任何方法。
     *
     * 为什么要两条：框架的 modules_*.log 偶尔会丢掉安装期那一批行（实测 12 秒后
     * 只剩 hit 行），只留一条通道时「模块没跑」和「日志被刷掉」分不开。
     * 只在冷路径（构造/回调/探针/hit 首次）调用，intercept 内不调用。
     *
     * 注意：目标 App 进程（uid 10410）写不了 /data/local/tmp（0771 shell:shell），
     * 所以这里不写文件；读法：logcat -d | Select-String HmClean，
     * 或设备端先 logcat -d > 文件再 grep，别等几十秒后直接读（缓冲区会被刷掉）。
     */
    static void trace(String message) {
        try { android.util.Log.i("HmClean", message); } catch (Throwable ignored) { }
    }

    /**
     * 命中计数：每个规则命中时通过 CAS 只记一次。
     *
     * 关键：真机实测冷启动后 12 秒，logcat 里安装期那批 feature= 行会被刷掉
     * （12 秒能出 9 万行），而稍后写的 hit 行还在——于是「装上了但被日志刷没」
     * 和「没装上」分不开。参考项目对此的解法是：把状态和命中一起在首次命中时重发。
     * 我们照做：首次 hit 时把整张状态表重打一遍，安装结果就不会丢。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>
            HITS = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>();

    private static final AtomicBoolean STATES_DUMPED = new AtomicBoolean(false);

    /** 通知规则：每条只允许打一次日志（避免一天几十条通知刷爆 logcat，也避免热路径拼串）。 */
    private static final AtomicBoolean[] notiLogged = newLogGates();
    /** 通知侦察的「有未拦截的推送经过」也只打一次。 */
    private static final AtomicBoolean SCAN_ONCE = new AtomicBoolean(false);

    private static AtomicBoolean[] newLogGates() {
        int n = Config.FEATURES.length;
        AtomicBoolean[] a = new AtomicBoolean[n];
        for (int i = 0; i < n; i++) a[i] = new AtomicBoolean(false);
        return a;
    }

    /**
     * 特性 -> 实际 hook 到的方法签名。
     *
     * 用途：目标 App 一升级，某个锚点可能改签名或改逻辑，这时必须能从日志一眼看出
     * 「hook 到了哪个方法」，否则只能靠猜。安装期写的行会被 logcat 刷掉
     * （实测启动 40 秒就能出十几万行），所以随延迟汇总一起重发。
     */
    private static final java.util.LinkedHashMap<String, String> ANCHORS =
            new java.util.LinkedHashMap<String, String>();

    /**
     * 安装期自检命中的特性。
     *
     * 为什么要自检：广告有投放条件和频控，实际使用中可能一整天都不弹。
     * 那时候「hook 装上了」和「拦截真的生效」无法区分——状态只能显示 matched，
     * 却没有任何证据说明拦截器跑通了。装完立刻反射调一次就能把这个区别消掉。
     *
     * 安全性：四个拦截器都**不调用 chain.proceed()**，自检调过去只会被直接拦下，
     * 不可能触发真实的广告逻辑。唯一副作用是走了一遍方法入口，可忽略。
     */
    private static final java.util.Set<String> VERIFIED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean selfTest;

    private static void recordAnchor(String feature, Method method) {
        try {
            synchronized (ANCHORS) {
                ANCHORS.put(feature, describe(method));
            }
            noteStatic("anchor=" + feature + " method=" + describe(method));
        } catch (Throwable ignored) { }
    }

    /**
     * 命中总次数（每命中一次就 +1，跟「首次命中」那个标志位分开）。
     * 绘制前守卫靠它判断「这一帧还有没有新东西被藏掉」，连续两帧没变化就摘掉自己。
     */
    private static final java.util.concurrent.atomic.AtomicInteger HIT_TOTAL =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 记一次命中（重复调用只有第一次写日志），并触发一次延迟的状态+命中汇总。 */
    static void hit(String rule) {
        // 自检期间的调用不算真实命中：那是模块自己调的，不是用户遇到了广告
        if (selfTest) { VERIFIED.add(rule); return; }
        HIT_TOTAL.incrementAndGet();
        try {
            java.util.concurrent.atomic.AtomicBoolean flag = HITS.get(rule);
            if (flag == null) {
                flag = new java.util.concurrent.atomic.AtomicBoolean(false);
                java.util.concurrent.atomic.AtomicBoolean prev = HITS.putIfAbsent(rule, flag);
                if (prev != null) flag = prev;
            }
            if (flag.compareAndSet(false, true)) {
                noteStatic("hit=" + rule);
                MainHook module = INSTANCE;
                if (module != null) module.scheduleSummary();
            }
        } catch (Throwable ignored) { }
    }

    /** 每个特性在报告里附上「是否真的命中过」。 */
    private static String hitSummary() {
        try {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, java.util.concurrent.atomic.AtomicBoolean> e : HITS.entrySet()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(e.getKey()).append('=').append(e.getValue().get() ? "hit" : "no-hit");
            }
            return sb.toString();
        } catch (Throwable ignored) { return ""; }
    }

    private final java.util.concurrent.atomic.AtomicBoolean summaryScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 延迟汇总：把「锚点 + 状态 + 命中」一起重发。
     *
     * 为什么必须重发：安装期写出的行会被 logcat 缓冲区挤掉（实测冷启动十几秒就能出
     * 十几万行），只剩后面写的命中行。参考项目也是这么处理的。
     *
     * 注意不能只在「有命中」时才汇总——那正好是「一条都没拦到」最需要被看见的情况。
     * 所以安装完成时也排一次，保证无论如何都有一份可读的结果。
     */
    private void scheduleSummary() {
        if (!summaryScheduled.compareAndSet(false, true)) return;
        postSummary(2000L);
    }

    private void postSummary(long delayMs) {
        try {
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    dumpStatesOnce();
                    note("summary hits[" + hitSummary() + "] done=" + reportDone
                            + "/" + Config.FEATURES.length);
                }
            }, delayMs);
        } catch (Throwable ignored) { }
    }

    /** 整张状态表只重发一次（安装期行已写过，这里是保险）。 */
    private void dumpStatesOnce() {
        if (!STATES_DUMPED.compareAndSet(false, true)) return;
        try {
            synchronized (ANCHORS) {
                for (java.util.Map.Entry<String, String> e : ANCHORS.entrySet()) {
                    trace("[schema=" + Config.SCHEMA + "] anchor=" + e.getKey() + " method=" + e.getValue());
                }
            }
            for (String feature : Config.FEATURES) {
                String state = states.get(feature);
                if (state == null) continue;
                trace("[schema=" + Config.SCHEMA + "] feature=" + feature + " result=" + state);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * 绝不要 override 框架的 log(...)。
     * 真机实测两版框架的行为都不同：
     *  - 旧 LSPosed(API 100)：XposedModule 里没有 super 实现，super.log(...) 抛 NoSuchMethodError
     *  - Vector v2.2：log(...) 在 XposedInterface 里是 final，override 直接 LinkageError
     * 统一做法：用继承下来的 log(...)，日志进 /data/adb/lspd/log/modules_*.log。
     * 需要额外留痕时用 trace()（它不碰框架 API）。
     */

    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final AtomicBoolean configured = new AtomicBoolean(false);
    private Context reportContext;
    private String reportToken;
    private long reportRun;
    private int reportDone;
    private final ConcurrentHashMap<String, String> states = new ConcurrentHashMap<String, String>();
    private final ConcurrentHashMap<String, String> details = new ConcurrentHashMap<String, String>();
    private String probeFailure;

    /** onPackageLoaded 记下的包名；Vector 的 PackageReadyParam 没有 getPackageName()，只能这样带过去。 */
    private String loadedPackage;

    /**
     * 只记进程名；这里禁止 loadClass / 任何初始化（onModuleLoaded 早于 App 一切）。
     * 每个回调的第一句都是 trace()：它只走 android.util.Log，不依赖框架注入的任何方法。
     * 目标 App 进程写不了 /data/local/tmp（0771 shell:shell），所以证据主要看 logcat。
     */
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            note("onModuleLoaded entered");
            processName = param.getProcessName();
            note("onModuleLoaded process=" + processName + " api=" + apiVersion());
        } catch (Throwable error) {
            note("onModuleLoaded failed " + error);
        }
    }

    /**
     * 独立模块时代这里还有一个 onPackageLoaded 回调，用来在 ClassLoader 建好前先过一遍门闸。
     * libxposed API 102 只回调 onPackageReady，那条路从来不会执行，所以删掉，
     * 免得下面拿一个永远为 null 的 loadedPackage 当依赖。
     */

    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            note("onPackageReady entered");
            // Vector v2.2 的 PackageReadyParam 只有 getClassLoader() / getAppComponentFactory()，
            // 没有 getPackageName()（framework/vector.dex 实测），所以包名退回配置里的目标包。
            String pkg = Config.TARGET;
            note("onPackageReady pkg=" + pkg + " process=" + processName);
            enter(pkg, processName, param.getClassLoader());
        } catch (Throwable error) {
            note("onPackageReady failed " + error);
        }
    }

    private void enter(String packageName, String process, ClassLoader loader) {
        String line = "[schema=" + Config.SCHEMA + "] enter pkg=" + packageName
                + " process=" + process + " api=" + apiVersion();
        log(Log.INFO, TAG, line);
        trace(line);
        if (!Config.TARGET.equals(packageName)) return;
        if (process != null && !Config.TARGET.equals(process)) {
            // 子进程一律不装 UI/广告钩子（界面规则在子进程里毫无意义，
            // 还要额外付出类加载与 Context 初始化）。
            // 例外：推送通知很可能就是从 :rhea（OPPO 推送 SDK）/ :background 发出来的，
            // 只在主进程拦 notify 会一条都拦不到，所以这两个进程只装**通知**钩子。
            if (Config.isNotifyProcess(process)) {
                log(Log.INFO, TAG, "secondary process accepted for notification gate: " + process);
                if (!installed.compareAndSet(false, true)) return;
                if (loader == null) { log(Log.INFO, TAG, "classloader not ready, deferring"); return; }
                installNotificationOnly(loader, process);
            } else {
                log(Log.INFO, TAG, "skip: secondary process " + process);
            }
            return;
        }
        if (!installed.compareAndSet(false, true)) return;
        if (loader == null) {
            // onPackageLoaded 阶段 App ClassLoader 还没建好，等 onPackageReady 再装
            log(Log.INFO, TAG, "classloader not ready, deferring to onPackageReady");
            return;
        }
        armContextHook(loader);
    }

    /**
     * 只装通知闸门（不碰界面、不碰广告），用于推送所在的子进程。
     *
     * 这里连 Context 钩子都不挂：通知规则完全靠方法形状匹配，
     * 不需要 App 的任何上下文，开销就是几个方法的 hook。
     */
    private void installNotificationOnly(ClassLoader loader, String process) {
        try { installNotificationGate(loader, process, null); }
        catch (Throwable error) {
            log(Log.WARN, TAG, "notification gate unavailable in " + process, error);
        }
    }

    /** 只读一次，用于把「框架到底支持到第几代 API」写进日志，省得下次再猜。 */
    private String apiVersion() {
        try { return String.valueOf(getApiVersion()); }
        catch (Throwable error) { return "?"; }
    }

    /**
     * 取 Context 用「一次性 Application 钩子」，绝不调 param.getApplication()
     * （部分 LSPosed 版本的 PackageReadyParam 没有这个方法，抛 NoSuchMethodError 且发生在第一条日志之前）。
     * 三级回退，任意一级成功即可。
     */
    private void armContextHook(final ClassLoader loader) {
        // 1) Application.attach(Context)：包内可见，编译期看不到但运行期存在
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).setId(Config.MODULE + "_app_attach").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof Context) configureOnce((Context) self, loader);
                    return result;
                }
            });
            log(Log.INFO, TAG, "[schema=" + Config.SCHEMA + "] armed via Application.attach");
            return;
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Application.attach unavailable, trying Instrumentation", error);
        }
        // 2) Instrumentation.callApplicationOnCreate(Application)：公开 API，必然存在
        try {
            Class<?> instrumentation = loader.loadClass("android.app.Instrumentation");
            Method call = instrumentation.getDeclaredMethod("callApplicationOnCreate", Application.class);
            hook(call).setId(Config.MODULE + "_app_oncreate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object arg = chain.getArg(0);
                    if (arg instanceof Context) configureOnce((Context) arg, loader);
                    return result;
                }
            });
            log(Log.INFO, TAG, "[schema=" + Config.SCHEMA + "] armed via Instrumentation.callApplicationOnCreate");
            return;
        } catch (Throwable error) {
            log(Log.WARN, TAG, "Instrumentation hook unavailable, trying ContextWrapper", error);
        }
        // 3) ContextWrapper.attachBaseContext(Context)：兜底（会多回调几次，用 CAS 保证只 configure 一次）
        try {
            Class<?> wrapper = loader.loadClass("android.content.ContextWrapper");
            Method base = wrapper.getDeclaredMethod("attachBaseContext", Context.class);
            hook(base).setId(Config.MODULE + "_app_basecontext").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object arg = chain.getArg(0);
                    if (arg instanceof Context) configureOnce((Context) arg, loader);
                    return result;
                }
            });
            log(Log.INFO, TAG, "[schema=" + Config.SCHEMA + "] armed via ContextWrapper.attachBaseContext");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "no context hook available; module inert", error);
        }
    }

    private void configureOnce(Context context, ClassLoader loader) {
        if (!configured.compareAndSet(false, true)) return;
        try { configure(context, loader); }
        catch (Throwable error) { log(Log.ERROR, TAG, "configure failed", error); }
    }

    private void configure(Context context, ClassLoader loader) {
        // 目标 App 的 classloader：模块自身的 loader 里没有 androidx 等第三方类，
        // 反射它们必须用这个（实测 ClassNotFoundException 就是这么来的）。
        TARGET_LOADER = loader;
        log(Log.INFO, TAG, "[schema=" + Config.SCHEMA + "] context ready, configuring");
        PackageInfo info;
        try { info = context.getPackageManager().getPackageInfo(Config.TARGET, 0); }
        catch (Throwable error) { log(Log.WARN, TAG, "skip: cannot read target version", error); return; }

        reportContext = context;
        // 缓存令牌：版本号 / 更新时间 / 规则结构版本 —— 任一变化都代表锚点可能失效
        reportToken = info.versionCode + ":" + info.lastUpdateTime + ":" + Config.SCHEMA;
        reportRun = System.currentTimeMillis();
        reportDone = 0;
        states.clear();
        details.clear();
        log(Log.INFO, TAG, "checking hooks for " + Config.TARGET + " " + info.versionName
                + " schema=" + Config.SCHEMA);

        // 框架的 getRemotePreferences 是注入方法，不同框架不一定都补齐；拿不到就按默认值走
        SharedPreferences prefs = null;
        try { prefs = io.github.qqliveclean.FamilySettings.prefs(context, io.github.qqliveclean.FamilySettings.HEYTAP); }
        catch (Throwable error) { log(Log.WARN, TAG, "family settings unavailable", error); }
        for (int i = 0; i < Config.FEATURES.length; i++) {
            ON[i] = Config.readBoolean(prefs, Config.key(Config.FEATURES[i]), Config.FEATURE_DEFAULT[i]);
        }
        TAB_LABELS = Config.readString(prefs, Config.KEY_TAB_LABELS, Config.DEFAULT_TAB_LABELS);

        resolveIds(context);

        probe(Config.F_FLOAT_AD, ON[I_FLOAT], new ThrowingRunnable() {
            @Override public void run() throws Exception { installFloatAdGate(loader); }
        });
        probe(Config.F_AI_BUBBLE, ON[I_AIBUBBLE], new ThrowingRunnable() {
            @Override public void run() throws Exception { installAiBubbleGate(loader); }
        });
        probe(Config.F_CTA_DIALOG, ON[I_CTA], new ThrowingRunnable() {
            @Override public void run() throws Exception { installCtaGate(loader); }
        });
        probe(Config.F_MSP_AD, ON[I_MSP], new ThrowingRunnable() {
            @Override public void run() throws Exception { installMspAdGate(loader); }
        });
        try { installNotificationGate(loader, Config.TARGET, context); }
        catch (Throwable error) {
            log(Log.WARN, TAG, "notification gate unavailable", error);
        }
        if (ON[I_BOTTOM]) {
            try { installBottomBarPrune(loader); }
            catch (Throwable error) {
                log(Log.WARN, TAG, "bottom bar prune unavailable", error);
                report("complete", Config.F_BOTTOM_BAR, "miss", "buildMenuView 不可用：" + describe(error));
            }
        }
        if (ON[I_BADGE]) {
            try { installNavBadgeGate(loader); }
            catch (Throwable error) {
                log(Log.WARN, TAG, "nav badge gate unavailable", error);
                report("complete", Config.F_NAV_BADGE, "miss", "setTipsViewByItemId 不可用：" + describe(error));
            }
        }
        probe(Config.F_BOOT_GUIDE, ON[I_BOOT], new ThrowingRunnable() {
            @Override public void run() throws Exception { installBootGuideGate(loader); }
        });

        // UI 类规则：按资源 id / 结构隐藏，统一由 onResume + 切页事件驱动。
        // 它们的匹配结果由资源 id 解析情况决定（miss / partial / matched 都如实上报）。
        // 底栏的状态由上面的 installBottomBarPrune 决定（资源 id 在不在已经不重要，
        // 只要能拿到 tab 文案就能筛）；这里只保留资源 id 解析作为补充信息。
        // UI 类规则：按资源 id / 结构隐藏，统一由 onResume + 切页事件驱动。
        // 它们的匹配结果由资源 id 解析情况决定（miss / partial / matched 都如实上报）。
        // 底栏的状态由上面的 installBottomBarPrune 决定（资源 id 在不在已经不重要，
        // 只要能拿到 tab 文案就能筛）；这里只保留资源 id 解析作为补充信息。
        // installInflateTriggers 是「异步下发内容」这条路径的正解：定时扫描挡不住 1s 后的落地。
        if (anyUiRule()) {
            try { installInflateTriggers(loader); }
            catch (Throwable error) {
                log(Log.WARN, TAG, "inflate triggers unavailable (UI rules fall back to timed scans)", error);
            }
        }
        probeUi(Config.F_BOTTOM_BAR, K_BOTTOM_NAV, K_TAB_LABEL_LARGE, K_TAB_LABEL_SMALL);
        probeUi(Config.F_TOP_BANNER, K_TOP_STAGE, K_TOP_BANNER);

        probeUi(Config.F_MINE_UPGRADE, K_MINE_UPGRADE);
        probeUi(Config.F_MINE_UNINSTALL, K_MINE_UNINSTALL);
        probeUi(Config.F_MINE_DOWNLOAD, K_MINE_DOWNLOAD);
        probeUi(Config.F_MINE_CLEAN, K_MINE_CLEAN);
        probeUi(Config.F_MINE_HEALTH, K_MINE_HEALTH);
        probeUi(Config.F_MINE_BANNER, K_MINE_BANNER, K_MINE_INDIC);
        probeUi(Config.F_MINE_RECOMMEND, K_MINE_LIST);
        probeUi(Config.F_MINE_VIP, K_MINE_VIP);

        if (anyUiRule()) {
            try { installUiTriggers(loader); }
            catch (Throwable error) { log(Log.WARN, TAG, "UI trigger unavailable", error); }
        }

        // 广告闸门装完后立刻自检，把「装上了」升级成「确认拦得住」
        try { selfTestAdGates(loader, context); }
        catch (Throwable error) { log(Log.WARN, TAG, "self test unavailable", error); }

        report("complete", "", "", "");
        log(Log.INFO, TAG, "install_summary features=" + Config.FEATURES.length
                + " uiRule=" + anyUiRule());
        // 无论有没有命中都排一次汇总：一条都没拦到时，恰恰最需要这份结果
        postSummary(6000L);
    }

    // ══════════════════════ 广告闸门 ══════════════════════

    /** 悬浮广告：canShow(FloatShowType) 恒 false，两条上屏路径都会先问它。 */
    private void installFloatAdGate(ClassLoader loader) throws Exception {
        Class<?> owner = load(loader, CLS_FLOAT_PRIOR);
        Method gate = requireMethod(owner, M_FLOAT_CANSHOW, "boolean", new String[]{T_FLOAT_SHOWTYPE});
        hook(gate).setId(Config.MODULE + "_float_gate").intercept(new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) {
                hit(Config.F_FLOAT_AD);          // 命中只记一次，返回值用缓存装箱
                return Boolean.FALSE;
            }
        });
        log(Log.INFO, TAG, "hooked: float ad gate " + gate);
        recordAnchor(Config.F_FLOAT_AD, gate);
    }

    /** AI 搜索气泡：只把“展示气泡”置空，AI 搜索入口本身保持可用。 */
    private void installAiBubbleGate(ClassLoader loader) throws Exception {
        Class<?> owner = load(loader, CLS_AI_BUBBLE);
        Method show = requireMethod(owner, M_AI_SHOW, "void", new String[]{T_EFFECTIVE_VIEW});
        hook(show).setId(Config.MODULE + "_ai_bubble").intercept(new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) {
                hit(Config.F_AI_BUBBLE);
                return null;
            }
        });
        log(Log.INFO, TAG, "hooked: ai bubble " + show);
        recordAnchor(Config.F_AI_BUBBLE, show);
    }

    /** CTA 活动弹窗：showCTA 置空；回调方法一律不碰（碰了会卡住弹窗流程）。 */
    private void installCtaGate(ClassLoader loader) throws Exception {
        Class<?> owner = load(loader, CLS_CTA);
        Method show = findByNamePrefix(owner, M_CTA_SHOW, "void", 2);
        hook(show).setId(Config.MODULE + "_cta_show").intercept(new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) {
                hit(Config.F_CTA_DIALOG);
                return null;
            }
        });
        log(Log.INFO, TAG, "hooked: cta " + show);
        recordAnchor(Config.F_CTA_DIALOG, show);
    }

    /**
     * msp 营销弹窗闸门。
     *
     * 用户反馈「有时候打开软件会出现一个弹窗」。这类广告是**服务端触发、带频控**的，
     * 在开发机上往往一条都不弹，等它偶发来定位不现实。改成从 DEX 把弹窗面一次摸清：
     * 穷举所有 Dialog/PopupWindow 子类（共 20 个 App 自有类），再按归属筛——
     * 落在 com.heytap.msp 下的就是营销 SDK 的弹窗面。
     *
     * 这里拦的是**决策点**而不是 Dialog.show()：show() 是所有 App 共用的框架方法，
     * 在它上面挂钩会波及正常弹窗（协议、确认框、支付），代价太大。
     * 而 DialogHelper 是 msp 私有的，拦它只影响营销弹窗。
     *
     * 四个方法分两类：
     *  - 展示入口（void）：置空，广告不弹
     *  - 留存判断（boolean）：恒 false，让 SDK 自己就不打算弹
     * 两条都拦是有意的冗余：不同版本走的可能是不同那条。
     *
     * 全部 private 方法，Xposed 可以挂；但每个都单独 try，一个挂不上不影响其余。
     */
    private void installMspAdGate(ClassLoader loader) throws Exception {
        Class<?> owner = load(loader, CLS_MSP_HELPER);
        int hooked = 0;

        // 展示入口：置空。Request 形参一律不碰，碰了可能触发空指针。
        String[] voids = {M_MSP_SHOW_AD, M_MSP_SHOW_TIPS, M_MSP_SHOW_KEEP};
        for (final String label : voids) {
            try {
                Method m = findByNameReturn(owner, label, "void");
                if (m == null) throw new NoSuchMethodException(owner.getName() + "." + label + "() -> void");
                hook(m).setId(Config.MODULE + "_msp_" + label).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        hit(Config.F_MSP_AD);
                        return null;
                    }
                });
                log(Log.INFO, TAG, "hooked: msp " + label + " " + m);
                recordAnchor(Config.F_MSP_AD + "/" + label, m);
                hooked++;
            } catch (Throwable error) {
                // 单个方法挂不上不算失败：不同版本可能只走其中一条
                log(Log.WARN, TAG, "msp gate skipped " + label, error);
            }
        }

        // 留存弹窗的判断：恒 false
        try {
            Method need = findByNameReturn(owner, M_MSP_NEED_KEEP, "boolean");
            if (need == null) throw new NoSuchMethodException(owner.getName() + "." + M_MSP_NEED_KEEP + "() -> boolean");
            hook(need).setId(Config.MODULE + "_msp_need_retention").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    hit(Config.F_MSP_AD);
                    return Boolean.FALSE;
                }
            });
            log(Log.INFO, TAG, "hooked: msp " + M_MSP_NEED_KEEP + " " + need);
            recordAnchor(Config.F_MSP_AD + "/" + M_MSP_NEED_KEEP, need);
            hooked++;
        } catch (Throwable error) {
            log(Log.WARN, TAG, "msp gate skipped " + M_MSP_NEED_KEEP, error);
        }

        if (hooked == 0) throw new NoSuchMethodException("no msp dialog gate found in " + CLS_MSP_HELPER);
    }

    /**
     * 按「名字 + 返回类型」找方法，不校验形参。
     *
     * msp 这几个方法在版本之间形参会变（showDownloadDialog 的 Request 版本迭代过），
     * 所以这里只锚死名字和返回类型，形参个数交给调用方按需填 null。
     * 找不到返回 null（而不是抛异常），让调用方自己决定要不要跳过——闸门是冗余的，
     * 少一个不致命。
     */
    private static Method findByNameReturn(Class<?> owner, String name, String ret) throws Exception {
        for (Method m : owner.getDeclaredMethods()) {
            if (!name.equals(m.getName())) continue;
            if (ret != null && !"void".equals(ret) && !m.getReturnType().getName().equals(ret)) continue;
            if ("void".equals(ret) && m.getReturnType() != void.class) continue;
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /** 基本类型形参的零值，避免反射调用时自动装箱抛 IllegalArgumentException。 */
    private static Object defaultPrimitive(Class<?> type) {
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return Integer.valueOf(0);
        if (type == long.class) return Long.valueOf(0L);
        if (type == float.class) return Float.valueOf(0f);
        if (type == double.class) return Double.valueOf(0d);
        if (type == short.class) return Short.valueOf((short) 0);
        if (type == byte.class) return Byte.valueOf((byte) 0);
        if (type == char.class) return Character.valueOf('\0');
        return null;
    }

    /** 开机必备引导页：Intent 构造返回 null —— 唯一调用方 c4b.Ԩ 本来就判空。 */
    private void installBootGuideGate(ClassLoader loader) throws Exception {
        Class<?> owner = load(loader, CLS_BOOT_GUIDE);
        Method build = requireMethod(owner, M_GUIDE_INTENT, "android.content.Intent",
                new String[]{"android.content.Context"});
        hook(build).setId(Config.MODULE + "_boot_guide").intercept(new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) {
                hit(Config.F_BOOT_GUIDE);
                return null;
            }
        });
        log(Log.INFO, TAG, "hooked: boot guide " + build);
        recordAnchor(Config.F_BOOT_GUIDE, build);
    }

    // ══════════════════════ 安装期自检 ══════════════════════

    /**
     * 装完广告闸门后，立刻反射调一次，确认拦截器真的把调用吃掉了。
     *
     * 解决的真实问题：广告有投放条件与频控，测试时往往一条都不弹，
     * 于是「hook 装上了」和「拦截真的生效」没法区分，状态永远停在 matched。
     * 这里每个闸门试一次，试到了就升级成 verified，试不到就保持 matched 并如实说明——
     * 宁可说「没验证」，也不假装成功。
     *
     * 安全性由拦截器本身保证：四个都不调用 chain.proceed()，所以自检调过去会被直接拦下，
     * 不会触发任何真实广告逻辑。
     */
    private void selfTestAdGates(ClassLoader loader, Context context) {
        if (!ON[I_FLOAT] && !ON[I_AIBUBBLE] && !ON[I_CTA] && !ON[I_MSP] && !ON[I_BOOT]) return;
        selfTest = true;
        try {
            // 1) 浮窗广告：构造 qx5 再问它能不能展示
            if (ON[I_FLOAT]) {
                try {
                    Class<?> owner = load(loader, CLS_FLOAT_PRIOR);
                    Method gate = requireMethod(owner, M_FLOAT_CANSHOW, "boolean",
                            new String[]{T_FLOAT_SHOWTYPE});
                    Object instance = newInstance(owner);
                    Class<?> showType = load(loader, T_FLOAT_SHOWTYPE.replace('.', '/'));
                    Object[] constants = showType.getEnumConstants();
                    if (constants != null && constants.length > 0) {
                        Object result = gate.invoke(instance, constants[0]);
                        // 拦截器返回 false；走到这里说明确实被吃掉了
                        if (result == null || Boolean.TRUE.equals(result)) {
                            note("selftest float_ad UNEXPECTED returned " + result);
                        }
                    }
                } catch (Throwable error) { note("selftest float_ad skipped: " + describe(error)); }
            }
            // 2) AI 搜索气泡：展示方法形参是 View，自检传 null——反正会被直接拦掉
            if (ON[I_AIBUBBLE]) {
                try {
                    Class<?> owner = load(loader, CLS_AI_BUBBLE);
                    Method show = requireMethod(owner, M_AI_SHOW, "void", new String[]{T_EFFECTIVE_VIEW});
                    Object instance = newInstance(owner);
                    show.invoke(instance, new Object[]{null});
                } catch (Throwable error) { note("selftest ai_bubble skipped: " + describe(error)); }
            }
            // 2) CTA 弹窗：hg3.getInstance() 是单例，直接调 showCTA
            if (ON[I_CTA]) {
                try {
                    Class<?> owner = load(loader, CLS_CTA);
                    Method getInstance = owner.getDeclaredMethod("getInstance");
                    getInstance.setAccessible(true);
                    Object instance = getInstance.invoke(null);
                    Method show = findByNamePrefix(owner, M_CTA_SHOW, "void", 2);
                    if (instance != null) show.invoke(instance, context, null);
                } catch (Throwable error) { note("selftest cta_dialog skipped: " + describe(error)); }
            }
            // 3) 开机必备引导页：ue8.Ԩ 是 static，直接调，期望返回 null
            if (ON[I_BOOT]) {
                try {
                    Class<?> owner = load(loader, CLS_BOOT_GUIDE);
                    Method build = requireMethod(owner, M_GUIDE_INTENT, "android.content.Intent",
                            new String[]{"android.content.Context"});
                    Object result = build.invoke(null, context);
                    if (result != null) note("selftest boot_guide UNEXPECTED non-null Intent");
                } catch (Throwable error) { note("selftest boot_guide skipped: " + describe(error)); }
            }
            // 4) msp 营销弹窗：留存判断是 instance 方法，需要先造一个 DialogHelper。
            //    showDownloadDialog(Request) 只用 null 试——拦截器在碰形参之前就返回了，
            //    不会真的去解析这个 Request，所以传 null 是安全的。
            if (ON[I_MSP]) {
                try {
                    Class<?> owner = load(loader, CLS_MSP_HELPER);
                    Object instance = allocateInstance(owner);
                    if (instance != null) {
                        Method need = findByNameReturn(owner, M_MSP_NEED_KEEP, "boolean");
                        if (need != null) {
                            Object result = need.invoke(instance, emptyArgs(need));
                            if (!Boolean.FALSE.equals(result)) {
                                note("selftest msp_ad UNEXPECTED " + M_MSP_NEED_KEEP + "=" + result);
                            }
                        }
                        Method showAd = findByNameReturn(owner, M_MSP_SHOW_AD, "void");
                        if (showAd != null) {
                            // 形参一律传 null：拦截器在碰形参之前就返回了，
                            // 不会真的去解析这个 Request，所以自检不会触发任何真实广告逻辑
                            showAd.invoke(instance, emptyArgs(showAd));
                        }
                    }
                } catch (Throwable error) { note("selftest msp_ad skipped: " + describe(error)); }
            }
        } finally {
            selfTest = false;
        }
        try {
            if (!VERIFIED.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String f : VERIFIED) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(f);
                    // 注意：这里不再 reportDone++，这几项在 probe() 里已经计过，
                    // 否则进度会变成 17/13 这种对不上的数
                    states.put(f, "verified");
                    details.put(f, "安装期自检：拦截器已确认生效");
                    report("running", f, "verified", "安装期自检：拦截器已确认生效");
                }
                note("selftest verified=" + sb);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * 建一个目标类的实例用于自检。
     *
     * 注意：这些类的无参构造**不是 public**（实测 qx5 的 acc=0x10002 是「包可见」，
     * 直接 newInstance() 会抛 IllegalAccessException），必须 setAccessible(true)。
     * 找不到无参构造就抛出去，由调用方记为「跳过」，绝不假装验证通过。
     */
    private static Object newInstance(Class<?> owner) throws Exception {
        java.lang.reflect.Constructor<?> ctor = owner.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    /**
     * 用「形参最少」的那个构造造一个实例，形参一律填零值/null。
     *
     * 自检专用：msp 的 DialogHelper 只有 (Context, Activity, BizAgentImpl, String)
     * 这一个构造，没有无参版本，newInstance() 会直接 NoSuchMethodException。
     * 传 null 是安全的——自检只调被拦下的方法，拦截器在碰形参之前就返回了，
     * 构造体里读到的 null 不会被真正使用。
     */
    private static Object newInstanceAny(Class<?> owner) throws Exception {
        java.lang.reflect.Constructor<?>[] all = owner.getDeclaredConstructors();
        if (all.length == 0) throw new NoSuchMethodException(owner.getName() + " has no constructor");
        java.lang.reflect.Constructor<?> best = all[0];
        for (java.lang.reflect.Constructor<?> c : all) {
            if (c.getParameterTypes().length < best.getParameterTypes().length) best = c;
        }
        best.setAccessible(true);
        return best.newInstance(emptyArgs(best));
    }

    /**
     * 自检专用的造实例方式：Unsafe.allocateInstance，**完全跳过构造器**。
     *
     * 为什么不能用 newInstanceAny：msp 的 DialogHelper 构造器是
     * (Context, Activity, BizAgentImpl, String)，传 null 进构造体第一步就 NPE，
     * 自检直接 InvocationTargetException，什么都验不到。
     *
     * allocateInstance 出来的实例字段全是 null/0，正好符合自检需要：
     * 只调被拦下的方法，拦截器在碰 this 之前就 return 了，字段为 null 根本走不到；
     * 而不跑构造器就不会被构造体里的 NPE 绊倒。Unsafe 不可用时退回 newInstanceAny。
     */
    private static Object allocateInstance(Class<?> owner) throws Exception {
        if (UNSAFE != null) {
            try {
                Method alloc = UNSAFE.getClass().getMethod("allocateInstance", Class.class);
                Object instance = alloc.invoke(UNSAFE, owner);
                if (instance != null) return instance;
            } catch (Throwable error) {
                noteStatic("selftest: Unsafe.allocateInstance unavailable (" + describe(error) + ")");
            }
        }
        return newInstanceAny(owner);
    }

    /** sun.misc.Unsafe.theUnsafe，只在安装期取一次，intercept 内不使用。 */
    private static final Object UNSAFE = resolveUnsafe();

    private static Object resolveUnsafe() {
        try {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field field = unsafe.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable ignored) { return null; }
    }

    /** 形参占位：一律 null/零值。仅用于自检造实例与调用被拦下的方法。 */
    private static Object[] emptyArgs(java.lang.reflect.Executable m) {
        Class<?>[] params = m.getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = params[i].isPrimitive() ? defaultPrimitive(params[i]) : null;
        }
        return args;
    }

    /** 异常文本：自检跳过的原因要能直接读懂。 */
    private static String describe(Throwable error) {
        if (error == null) return "null";
        try {
            return error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
        } catch (Throwable ignored) { return "<unavailable>"; }
    }

    /** 方法签名文本：自适配排障时，版本一变第一眼就要看出 hook 到了哪个方法。 */
    private static String describe(Method method) {
        if (method == null) return "null";
        try {
            StringBuilder sb = new StringBuilder(method.getDeclaringClass().getName())
                    .append('.').append(method.getName()).append('(');
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(types[i].getSimpleName());
            }
            return sb.append(")->").append(method.getReturnType().getSimpleName()).toString();
        } catch (Throwable ignored) { return "<unavailable>"; }
    }

    // ══════════════════════ 界面规则 ══════════════════════

    private static boolean anyUiRule() {
        return ON[I_BOTTOM] || ON[I_TOPBANNER] || ON[I_FLOAT] || ON[I_UPGRADE] || ON[I_UNINSTALL]
                || ON[I_DOWNLOAD] || ON[I_CLEAN] || ON[I_HEALTH] || ON[I_BANNER]
                || ON[I_RECOMMEND] || ON[I_VIP];
    }

    /**
     * 触发点只有两个，都是「用户做了一次动作」级别的频率：
     *  - Activity.onResume：冷启动 / 从别的页面回来
     *  - ViewPager.setCurrentItem：底部栏切页（“我的”页是 ViewPager 的第 5 页）
     * 命中后按 APPLY_DELAYS 补隐藏若干次，因为卡片数据是异步来的。
     */
    private void installUiTriggers(ClassLoader loader) throws Exception {
        final Handler handler = new Handler(Looper.getMainLooper());

        Method resume = Activity.class.getDeclaredMethod("onResume");
        hook(resume).setId(Config.MODULE + "_ui_resume").intercept(new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity) schedule(handler, (Activity) self);
                return result;
            }
        });

        int hooked = 0;
        try {
            Class<?> pager = loader.loadClass(CLS_VIEWPAGER);
            for (Method candidate : pager.getDeclaredMethods()) {
                if (!"setCurrentItem".equals(candidate.getName())) continue;
                Class<?>[] params = candidate.getParameterTypes();
                boolean ok = (params.length == 1 && params[0] == int.class)
                        || (params.length == 2 && params[0] == int.class && params[1] == boolean.class);
                if (!ok) continue;
                hook(candidate).setId(Config.MODULE + "_ui_pager_" + params.length)
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object result = chain.proceed();
                                Activity activity = currentActivity(chain.getThisObject());
                                if (activity != null) schedule(handler, activity);
                                return result;
                            }
                        });
                hooked++;
            }
        } catch (Throwable error) {
            log(Log.WARN, TAG, "ViewPager trigger unavailable (tab switch will still be covered by onResume)", error);
        }
        log(Log.INFO, TAG, "ui triggers installed: onResume + " + hooked + " pager method(s)");
    }

    /** ViewPager 自己不知道 Activity，用它的 Context 反查（不解包装、失败就放弃）。 */
    private static Activity currentActivity(Object pager) {
        if (!(pager instanceof View)) return null;
        try {
            Context context = ((View) pager).getContext();
            while (context instanceof android.content.ContextWrapper) {
                if (context instanceof Activity) return (Activity) context;
                context = ((android.content.ContextWrapper) context).getBaseContext();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void schedule(Handler handler, final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        // 绘制前守卫：真正消除闪烁的就是它。定时扫描退居二线，只当兜底
        // （理论上每帧都会跑到，但要是不 attach（无窗口）还有定时这一道保险）。
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor != null) installPreDrawGuard(decor);
        } catch (Throwable ignored) { }
        Runnable task = new Runnable() {
            @Override public void run() {
                try { applyRules(activity); } catch (Throwable ignored) { }
            }
        };
        for (int i = 0; i < APPLY_DELAYS.length; i++) {
            if (APPLY_DELAYS[i] == 0L) task.run();
            else handler.postDelayed(task, APPLY_DELAYS[i]);
        }
    }

    /**
     * 隐藏首页顶部的横幅推广轮播。
     *
     * ── 踩过的坑，务必保留这段约束 ──
     * 最初的写法是直接 `findViewById(stage_inner_listview)` 隐藏整个外层列表，
     * 结果**整个首页空了**：真机实测首页可见文案从 30 条掉到 4 条。
     *
     * 原因看真机视图树就清楚了：
     *   stage_inner_listview  RecyclerView  [0,233][1080,2106]  ← 整屏内容区
     *     ├─ recycler_view    RecyclerView  [0,269][1080,818]    ← 顶部推广轮播
     *     ├─ card_container   [48,818][1032,1064]                 ← 应用卡 1
     *     ├─ card_container   [48,1064][1032,1286]                ← 应用卡 2
     *     └─ ……一直到 2106
     * 横幅和下面**所有应用卡片**是同一个 RecyclerView 的子项，
     * 隐藏外层 = 横幅和应用列表一起没了。
     *
     * 所以拿 stage_inner_listview 当**锚点**（证明我们在首页这个 stage 上），
     * 只在它的子树里找 recycler_view 去隐藏——下面的应用卡片自然上移补位。
     *
     * 为什么不能直接按 recycler_view 全树找：「我的」页的卡片列表也叫
     * recycler_view（com.coui.card.api.view.NestedScrollingRecyclerView，真机实测），
     * 直接找会连「我的」页一起打穿。限定在 stage 的子树内就没事。
     */
    private static void hideTopBanner(View root) {
        int stageId = ID[K_TOP_STAGE];
        int bannerId = ID[K_TOP_BANNER];
        if (stageId == 0) return;
        try {
            View stage = root.findViewById(stageId);
            if (!(stage instanceof ViewGroup)) return;
            View banner = bannerId == 0 ? null : ((ViewGroup) stage).findViewById(bannerId);
            if (banner == null) return;
            if (banner.getVisibility() != View.GONE) banner.setVisibility(View.GONE);
            hit(Config.F_TOP_BANNER);
        } catch (Throwable ignored) { }
    }

    /** 每次触达只做十几次 findViewById（按 id 精确命中），不做任何树遍历。 */
    /**
     * 在「内容被创建的那一刻」套用规则 —— 真正消除闪烁的那一步。
     *
     * ── 问题 ──
     * 「我的」页的推广元素是异步下发的，切页后约 1s 才进视图树。定时扫描
     * `{0, 200, 600, 1500, 3000, 5000}` 在 t=0 时它们还不存在，要等到下一档
     * 才藏得住，中间那段时间用户看得见。真机逐帧抓到的闪现节点：
     *   ll_uninstall / iv_not_uninstall / download_icon / upgrade_icon / line
     *   scroll_banner
     *   vip_layout
     * （`iv_not_uninstall` 就是三宫格里那个圆形图标。）
     * 换句话说：**凡是靠「事后隐藏」实现的规则都有这个闪的问题**，
     * 每加一个开关就得重新踩一次。底栏那次的结论在这里同样成立。
     *
     * ── 做法 ──
     * 拦两个「新建视图」的事件，都在第一帧绘制之前：
     *  1. `ViewStub.inflate()` —— 角标、空态图标这些占位替换都走这里；
     *  2. `RecyclerView.onViewAttachedToWindow(holder)` —— 列表项进窗口的瞬间。
     * 两者都是**事件驱动**：不来就不执行，稳态零开销，不是轮询。
     * 命中的只有「刚加进来的那一小块子树」，扫描面很小。
     *
     * 触发时机上它们都在 measure/layout/draw 之前，所以置 GONE 之后
     * 这块内容**一帧都没被画出来过**——这就是不闪的保证。
     */
    private void installInflateTriggers(ClassLoader loader) throws Exception {
        int hooked = 0;

        // 1) ViewStub.inflate：角标 / 空态插图 / 延迟卡片
        try {
            Class<?> stub = loader.loadClass("android.view.ViewStub");
            for (Method m : stub.getMethods()) {
                if (!"inflate".equals(m.getName())) continue;
                if (m.getReturnType() != View.class) continue;
                m.setAccessible(true);
                hook(m).setId(Config.MODULE + "_stub_inflate").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        try { applyRulesToFresh(result); } catch (Throwable ignored) { }
                        return result;
                    }
                });
                hooked++;
            }
        } catch (Throwable error) {
            log(Log.WARN, TAG, "ViewStub.inflate trigger unavailable", error);
        }

        // 2) RecyclerView.onViewAttachedToWindow：列表项挂到窗口的瞬间
        try {
            Class<?> rv = loader.loadClass("androidx.recyclerview.widget.RecyclerView");
            for (Method m : rv.getMethods()) {
                if (!"onViewAttachedToWindow".equals(m.getName())) continue;
                m.setAccessible(true);
                hook(m).setId(Config.MODULE + "_rv_attach").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        try { applyRulesToFresh(holderItemView(chain.getArg(0))); } catch (Throwable ignored) { }
                        return result;
                    }
                });
                hooked++;
            }
        } catch (Throwable error) {
            log(Log.WARN, TAG, "RecyclerView attach trigger unavailable", error);
        }

        // 3) ViewGroup.addView：普通布局 inflate 出来的视图（静态头部那些）
        //    —— 静态布局既不走 ViewStub 也不走 RecyclerView，只有这一条路能拦到。
        //    实测「我的」页的 ll_uninstall（应用卸载格）就是这一类，
        //    只装前两个触发点时它仍然会闪一下。
        try {
            Class<?> vg = loader.loadClass("android.view.ViewGroup");
            for (Method m : vg.getDeclaredMethods()) {
                if (!"addView".equals(m.getName())) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 3 || params[0] != View.class) continue;
                m.setAccessible(true);
                hook(m).setId(Config.MODULE + "_vg_addview").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        try {
                            Object child = chain.getArg(0);
                            if (child instanceof View) hideOnAttach((View) child);
                        } catch (Throwable ignored) { }
                        return result;
                    }
                });
                hooked++;
            }
        } catch (Throwable error) {
            log(Log.WARN, TAG, "ViewGroup.addView trigger unavailable", error);
        }

        if (hooked == 0) throw new NoSuchMethodException("no inflate/attach trigger found");
        log(Log.INFO, TAG, "inflate triggers installed: " + hooked + " method(s)");
    }

    /**
     * 视图刚被挂上父容器时，按 id 直接置 GONE。
     *
     * 只处理**按资源 id 命中**的那些规则——这正是会闪现的那一批
     * （ll_uninstall / vip_layout / scroll_banner / 悬浮广告 / 首页横幅 …）。
     * 结构类规则（推荐卡靠 HorizontalAppItemView 识别）和「三项全开就整卡隐藏」
     * 这类需要看父容器关系的，仍由定时扫描兜底，不在这里做。
     *
     * 命中率上：addView 是布局填充时的高频调用，所以拦截器里只有一次
     * `getId()` 加一次十几个 int 的定长比对；id 对不上直接返回。
     *
     * ⚠️ **必须先查开关**：这一条漏查过一次，后果是「待更新」「下载管理」
     * （两者默认都是关的）也被藏掉，三宫格三个格子全空，整个「我的」页变空白——
     * 页面只剩底栏两个字。applyRulesToRoot 里每条规则前面都有 `if (ON[...])`，
     * 这里也得有，否则就绕过了用户的设置。
     */
    private static void hideOnAttach(View v) {
        try {
            int id = v.getId();
            if (id == 0 || id == View.NO_ID) return;
            int idx = idIndex(id);
            if (idx < 0) return;
            if (!ON[idx]) return;                   // 开关关着就绝不能动
            if (v.getVisibility() == View.GONE) return;
            v.setVisibility(View.GONE);
            hit(Config.FEATURES[idx]);
        } catch (Throwable ignored) { }
    }

    /** id → Config.FEATURES 下标；不是规则目标返回 -1。定长扫描，无需 Map。 */
    private static int idIndex(int id) {
        for (int i = 0; i < ID_COUNT; i++) {
            if (ID[i] == id) return featureIndexOf(i);
        }
        return -1;
    }

    /** K_* 常量 → Config.FEATURES 下标（两个数组是一一对应的，顺序按声明排列）。 */
    private static int featureIndexOf(int k) {
        switch (k) {
            case K_FLOAT_AD: return I_FLOAT;
            case K_TOP_BANNER: return I_TOPBANNER;
            case K_MINE_UPGRADE: return I_UPGRADE;
            case K_MINE_UNINSTALL: return I_UNINSTALL;
            case K_MINE_DOWNLOAD: return I_DOWNLOAD;
            case K_MINE_CLEAN: return I_CLEAN;
            case K_MINE_HEALTH: return I_HEALTH;
            case K_MINE_BANNER: return I_BANNER;
            case K_MINE_VIP: return I_VIP;
            default: return -1;     // 其余 id（底栏、角标、列表等）不走这条路径
        }
    }

    /** ViewHolder.itemView 是 public final 字段，直接读；读不到就放弃。 */
    private static View holderItemView(Object holder) {
        if (holder == null) return null;
        try {
            Object v = holder.getClass().getField("itemView").get(holder);
            return v instanceof View ? (View) v : null;
        } catch (Throwable ignored) { return null; }
    }

    /** 对刚创建/刚挂上的这块内容套规则；同时也看它现在的父容器（布局参数可能挂在外层）。 */
    private static void applyRulesToFresh(Object fresh) {
        if (!(fresh instanceof View)) return;
        View view = (View) fresh;
        applyRulesToRoot(view);
        try {
            android.view.ViewParent parent = view.getParent();
            if (parent instanceof View) applyRulesToRoot((View) parent);
        } catch (Throwable ignored) { }
    }

    /**
     * 通知闸门：按通道 id 拦下营销类推送，并在开关打开时记录 App 实际推送的通道。
     *
     * ── 为什么拦 notify 而不靠关权限 ──
     * 关掉 `POST_NOTIFICATIONS` 或把通道设为「无」是一刀切，会把
     * **下载任务 / 安装完成 / 更新完成**一起弄没——那三条是用户自己点了才产生的，
     * 误伤代价比多一条通知大得多。所以在 `notify()` 这一步按通道分流：
     * 命中的直接**不 proceed**，通知根本不会走到 system_server。
     *
     * ── 成本（这是本模块里最便宜的一处钩子）──
     *  - `notify` 一天被调的次数是**个位数到几十次**，不是 per-frame；
     *  - 拦截器里只有：读一个安装期就解析好的 Field → 一次 5 元素线性子串比对
     *    （`String.contains` 不分配）→ 不中就 proceed 返回。零分配、零反射、零正则；
     *  - 日志**每条规则只打一次**（CAS 门），之后完全静默；
     *  - 没有定时器、没有轮询、没有后台常驻。
     *  作为对照：模块里最贵的是 `ViewGroup.addView`（每次布局填充调上千次），
     *  通知钩子比它便宜几个数量级。而拦掉一条 importance=4 的推送
     *  省下的是震动/响铃/亮屏——那才是真正的耗电大头。
     *
     * @param ctx 非 null 时（仅主进程）做一次通道枚举侦察；子进程传 null
     */
    private void installNotificationGate(ClassLoader loader, String process, Context ctx) throws Exception {
        // Notification.mChannelId 是隐藏字段，API 26+ 才有。安装期解析一次，运行期零反射。
        Field channelId = null;
        try { channelId = Notification.class.getDeclaredField("mChannelId"); channelId.setAccessible(true); }
        catch (Throwable ignored) { }
        final Field F_CHANNEL = channelId;
        if (F_CHANNEL == null) throw new NoSuchFieldException("Notification.mChannelId");

        int hooked = 0;
        for (Method m : NotificationManager.class.getMethods()) {
            if (!"notify".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            // 两个重载都要抓：notify(int, Notification) 和 notify(String, int, Notification)。
            // 早先只按「长度==2」筛，把带 tag 的那个（三参）漏掉了，
            // 结果推送 SDK 走的那条路径一次都没被拦。
            boolean plain = (p.length == 2 && p[0] == int.class && p[1] == Notification.class);
            boolean tagged = (p.length == 3 && p[0] == String.class && p[1] == int.class
                    && p[2] == Notification.class);
            if (!plain && !tagged) continue;
            // Notification 参数的下标：notify(int, Notification) 是 1，
            // notify(String, int, Notification) 是 2。写死 1 会把 int 的 id 当成通知对象。
            final int argIndex = plain ? 1 : 2;
            m.setAccessible(true);
            hook(m).setId(Config.MODULE + "_noti_block").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object n = chain.getArg(argIndex);
                    String cid = channelIdOf(n, F_CHANNEL);
                    int idx = matchChannel(cid);
                    if (idx >= 0 && ON[idx]) {
                        if (hitOnce(idx)) {
                            noteStatic("noti blocked: " + Config.FEATURES[idx] + " channel=" + cid
                                    + " proc=" + process + " title=" + titleOf(n));
                        }
                        return null;                    // 不 proceed = 真拦截
                    }
                    if (idx >= 0 && ON[I_NOTI_SCAN] && SCAN_ONCE.compareAndSet(false, true)) {
                        noteStatic("noti passthru: " + Config.FEATURES[idx] + " channel=" + cid + " proc=" + process);
                    }
                    return chain.proceed();
                }
            });
            hooked++;
        }
        if (hooked == 0) throw new NoSuchMethodException("NotificationManager.notify not found");

        if (ctx != null && ON[I_NOTI_SCAN]) scanExistingChannels(ctx);
        log(Log.INFO, TAG, "notification gate: " + hooked + " notify() overload(s), process=" + process);
    }

    /** 读通道 id；拿不到返回 null（pre-O 的通知没有通道），调用方按 null 处理。 */
    private static String channelIdOf(Object notification, Field f) {
        if (notification == null || f == null) return null;
        try { return (String) f.get(notification); }
        catch (Throwable ignored) { return null; }
    }

    /**
     * 通道 id → 特性下标。线性扫 5 个短串，不中返回 -1。
     * 特征串见 Config.CHANNEL_MATCH（用的是资源名的辨识部分，不是包名全串，
     * 因为推送 SDK 那条是「包名 + 资源名」拼出来的）。
     */
    private static int matchChannel(String cid) {
        if (cid == null || cid.length() == 0) return -1;
        for (int i = 0; i < Config.CHANNEL_MATCH.length; i++) {
            if (cid.contains(Config.CHANNEL_MATCH[i])) return I_NOTI_BASE + i;
        }
        return -1;
    }

    /** 每条规则只允许打一次日志（CAS 门），避免高频重复输出。 */
    private static boolean hitOnce(int featureIndex) {
        try {
            if (notiLogged[featureIndex].compareAndSet(false, true)) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** 通知标题：只在真的要打日志时才取（.toString() 会分配，所以放在 CAS 之后）。 */
    private static String titleOf(Object notification) {
        try {
            if (!(notification instanceof Notification)) return "";
            Bundle extras = ((Notification) notification).extras;
            if (extras == null) return "";
            CharSequence t = extras.getCharSequence(Notification.EXTRA_TITLE);
            return t == null ? "" : String.valueOf(t);
        } catch (Throwable ignored) { return ""; }
    }

    /**
     * 通知侦察：启动时把 App **已经建好**的通道全列一遍。
     * 这是「查询推送总类」最直接的办法——不用等新通知到达。
     * 只在主进程、只在开关打开时跑一次。
     */
    private void scanExistingChannels(Context ctx) {
        if (ctx == null) return;
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            java.util.List<NotificationChannel> list = nm.getNotificationChannels();
            if (list == null) return;
            int covered = 0;
            for (int i = 0; i < list.size(); i++) {
                NotificationChannel c = list.get(i);
                String cid = c.getId();
                int idx = matchChannel(cid);
                if (idx >= 0) covered++;
                noteStatic("noti_channel: id=" + cid + " name=" + c.getName()
                        + " importance=" + c.getImportance()
                        + " covered=" + (idx >= 0 ? Config.FEATURES[idx] : "-"));
            }
            noteStatic("noti_scan: channels=" + list.size() + " covered=" + covered);
        } catch (Throwable error) {
            log(Log.WARN, TAG, "notification scan failed", error);
        }
    }

    private static void applyRules(Activity activity) {
        View decor;
        try { decor = activity.getWindow().getDecorView(); }
        catch (Throwable ignored) { return; }
        if (decor == null) return;
        applyRulesToRoot(decor);
    }

    /**
     * 绘制前守卫：把规则挂到 decor 的 OnPreDrawListener 上，**短时兜底**。
     *
     * ── 这一版的定位（上一版想错了一轮，留个记录）──
     * 最初以为只要「绘制前」就万事大吉，忽略了两个事实：
     *  1. 按**帧**设上限管不住**时间**：60fps 下 12 帧只有 200ms，
     *     而「我的」页的卡片是切页后约 1s 才由网络下发落地的
     *     （真机帧计数：帧1 643 节点 → 帧3 840 → 帧5 1009 → 帧7 1157 稳定）；
     *  2. 「连续两帧没命中就摘掉」这个提前退出的判据更糟——
     *     异步内容落地前本来就没有东西可命中，它会在第 2 帧就摘自己。
     *
     * 所以**真正消除闪烁的是 installInflateTriggers**（在内容被创建的那一刻拦），
     * 这里只是同一帧内的最后一道保险，20 帧（≈330ms）就摘。
     *
     * 开销：只在切页/回前台时挂，最多 20 帧即摘；稳态不挂监听器、不轮询。
     */
    private static void installPreDrawGuard(final View decor) {
        if (!anyUiRule()) return;
        try {
            ViewTreeObserver vto = decor.getViewTreeObserver();
            if (vto == null || !vto.isAlive()) return;
            vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                private int passes = 0;

                @Override public boolean onPreDraw() {
                    passes++;
                    try { applyRulesToRoot(decor); } catch (Throwable ignored) { }
                    if (passes >= PRE_DRAW_MAX_PASSES) {
                        try {
                            ViewTreeObserver live = decor.getViewTreeObserver();
                            if (live != null) live.removeOnPreDrawListener(this);
                        } catch (Throwable ignored) { }
                    }
                    return true;
                }
            });
        } catch (Throwable ignored) { }
    }

    private static void applyRulesToRoot(View decor) {
        // 底栏不再在这里按文案筛：改由 buildMenuView 钩子在建视图时移除，
        // 见 installBottomBarPrune —— 延迟隐藏正是「闪一下」的成因。
        // 均分不需要任何额外干预：菜单项一旦设为不可见，COUI 的 onMeasure 自然按新项数均分。
        if (ON[I_TOPBANNER]) hideTopBanner(decor);
        if (ON[I_FLOAT]) hide(decor, ID[K_FLOAT_AD], Config.F_FLOAT_AD);

        boolean allThree = ON[I_UPGRADE] && ON[I_UNINSTALL] && ON[I_DOWNLOAD];
        if (allThree) hideCardOf(decor, ID[K_MINE_UPGRADE], ID[K_MINE_LIST], Config.F_MINE_UPGRADE);
        else {
            if (ON[I_UPGRADE]) hide(decor, ID[K_MINE_UPGRADE], Config.F_MINE_UPGRADE);
            if (ON[I_UNINSTALL]) hide(decor, ID[K_MINE_UNINSTALL], Config.F_MINE_UNINSTALL);
            if (ON[I_DOWNLOAD]) hide(decor, ID[K_MINE_DOWNLOAD], Config.F_MINE_DOWNLOAD);
        }

        boolean bothHealth = ON[I_CLEAN] && ON[I_HEALTH];
        if (bothHealth) hideParentOf(decor, ID[K_MINE_CLEAN], Config.F_MINE_CLEAN, Config.F_MINE_HEALTH);
        else {
            if (ON[I_CLEAN]) hide(decor, ID[K_MINE_CLEAN], Config.F_MINE_CLEAN);
            if (ON[I_HEALTH]) hide(decor, ID[K_MINE_HEALTH], Config.F_MINE_HEALTH);
        }
        // 兜底：这些卡运行期没有专属 id，按标题文案识别（见 hideMineCardsByTitle 注释）
        if (ON[I_CLEAN]) {
            hideMineCardsByTitle(decor, ID[K_MINE_LIST], Config.MINE_CARD_LABELS_CLEAN, Config.F_MINE_CLEAN);
        }
        if (ON[I_HEALTH]) {
            hideMineCardsByTitle(decor, ID[K_MINE_LIST], Config.MINE_CARD_LABELS_HEALTH, Config.F_MINE_HEALTH);
        }

        if (ON[I_BANNER]) {
            hide(decor, ID[K_MINE_BANNER], Config.F_MINE_BANNER);
            hide(decor, ID[K_MINE_INDIC], Config.F_MINE_BANNER);
        }
        if (ON[I_VIP]) hide(decor, ID[K_MINE_VIP], Config.F_MINE_VIP);
        if (ON[I_RECOMMEND]) hideRecommendCards(decor, ID[K_MINE_LIST], Config.F_MINE_RECOMMEND);

        // 三宫格有格子被藏掉时重新平分整行，否则会留下空洞 + 孤零零的分隔线
        if (ON[I_UPGRADE] || ON[I_UNINSTALL] || ON[I_DOWNLOAD]) rebalanceTopGrid(decor);

        // 列表守卫：一次性隐藏挡不住 RecyclerView 重新绑定，挂上 attach 监听兜底
        if (ON[I_RECOMMEND] || ON[I_CLEAN] || ON[I_HEALTH] || ON[I_BANNER]) {
            try {
                View list = decor.findViewById(ID[K_MINE_LIST]);
                if (list != null) {
                    installMineListGuard(list);
                    if (list instanceof ViewGroup) {
                        ViewGroup group = (ViewGroup) list;
                        for (int i = 0; i < group.getChildCount(); i++) filterMineChild(group.getChildAt(i));
                    }
                }
            } catch (Throwable ignored) { }
        }
    }

    /** 底栏保留名单；设置页可改，configure 时从 RemotePreferences 读入。 */
    private static volatile String TAB_LABELS = Config.DEFAULT_TAB_LABELS;

    /**
     * 底栏：只保留名单里的 tab，其余从源头不建视图。
     *
     * ── 上一版为什么「丑」而且会闪 ──
     * 做法是拿到底栏后按文案把不要的子视图 setVisibility(GONE)。两个问题：
     *  1) 布局不匀：COUINavigationMenuView 的排布是「子视图序号 i × (宽度 / itemCount)」，
     *     itemCount 来自适配器（= 菜单项数）。只设 GONE 不动 itemCount，
     *     被藏的项照样占着槽位，于是界面上留下等宽的空洞。
     *  2) 会闪：补隐藏是延迟触发的，切页后要先渲染一帧完整底栏，
     *     下一轮定时器才把 GONE 设上——用户看到的就是「先出现、再消失」。
     *     更糟的是恢复逻辑里有一句 setVisibility(VISIBLE)，
     *     每次补跑都会把该显示的项先点亮再藏一次。
     *
     * ── 这一版怎么做 ──
     * 拦的是 NavigationBarMenuView.buildMenuView()：**「先按列表建视图」那一步**。
     * proceed() 让它照常建完，随后在同一个同步调用里把不要的子视图 removeViewAt 掉。
     * 这一切发生在任何 measure / layout / draw 之前，所以：
     *  - 不会被画出来 → 不会闪
     *  - 子视图数真的变成 2 → itemCount 跟着变小 → 两项各占一半，没有空洞
     * 全程不碰 setVisibility，也就从根上没有了「先点亮再熄灭」。
     *
     * 触发时机只有底栏自身重建（冷启动 / 恢复 / 切主题），频率极低。
     * 绝不做定时轮询，也不遍历整棵视图树：只操作 menu 的直接子项。
     */
    private void installBottomBarPrune(ClassLoader loader) throws Exception {
        Class<?> owner = load(loader, CLS_NAV_MENU_VIEW);
        final Method build = requireMethod(owner, M_NAV_BUILD_MENU, "void", new String[0]);
        hook(build).setId(Config.MODULE + "_nav_prune").intercept(new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof ViewGroup) pruneNavTabs((ViewGroup) self);
                } catch (Throwable ignored) { }
                return result;
            }
        });
        log(Log.INFO, TAG, "hooked: bottom bar prune at " + build);
        recordAnchor(Config.F_BOTTOM_BAR, build);
    }

    /**
     * 从底栏里移除不在保留名单里的子视图。
     *
     * 文案**从菜单项读，不从视图读**——buildMenuView 那一刻 TextView 的 text 还没绑定
     * （实测只能认出「首页」，后面的项文案全空），按视图读会把「我的」这类
     * 正常入口误当成「取不到就不动」的漏网之鱼。菜单项顺序与建出来的子视图顺序
     * 严格一一对应，所以按同一下标取文案、再按同一下标删视图是对得上的。
     *
     * 倒序删除：否则前面的删除会让后面的下标前移。
     */
    /**
     * 从底栏里移除不在保留名单里的 tab：**菜单项设不可见 + 子视图删掉**。
     *
     * ── 为什么两样都要做 ──
     * COUINavigationMenuView.onMeasure 的除数是
     * `getMenu().getVisibleItems().size()`（菜单可见项数），不是子视图数：
     *
     *     int width = MeasureSpec.getSize(spec) - mDefaultPadding * 2;   // 1080-72 = 1008
     *     int n     = getMenu().getVisibleItems().size();                  // 除数
     *     int each  = width / (n == 0 ? 1 : n);                            // 1008/5 = 202
     *     ...
     *     setMeasuredDimension(子项宽度之和, mItemHeight);                  // 2×202 = 404
     *
     * 只删视图 → 除数还是 5 → 每项仍 202 → 容器缩成 404 挤在屏幕中间；
     * 只设不可见 → 子视图还在 → 仍然画出来。
     * 两件都做，COUI 自己就会算出 1008/2 = 504，两项正好铺满整条栏，
     * **不需要任何布局干预**（也就不会踩到「改尺寸触发 requestLayout → 布局死循环」）。
     *
     * 用 setVisible(false) 而不是 removeItemAt：菜单项留在原位，
     * app 侧按 item id 做的切页映射不会被改坏。
     *
     * 文案从 MenuBuilder 读而不是从视图读 —— buildMenuView 那一刻 TextView
     * 还没绑上文案（实测按视图读只能认出「首页」一项）。
     * 倒序删除视图：否则前面的删除会让后面的下标前移。
     */
    private static void pruneNavTabs(ViewGroup menu) {
        String keep = TAB_LABELS;
        int count;
        try { count = menu.getChildCount(); } catch (Throwable ignored) { return; }
        if (count <= 0) return;

        Object builder = navMenuBuilder(menu);
        int dropped = 0;
        int kept = 0;
        StringBuilder seen = new StringBuilder();
        int[] drop = new int[Math.min(count, 32)];

        for (int i = 0; i < count; i++) {
            View child;
            try { child = menu.getChildAt(i); } catch (Throwable ignored) { continue; }
            if (child == null) continue;
            String title = navItemTitle(builder, i);
            if (title == null) title = tabTitle(child);     // 回退：菜单读不到才看视图
            if (title == null) continue;                    // 两处都读不到就不动，宁可漏删不误伤
            if (seen.indexOf(title) < 0) seen.append(title).append('/');
            if (keep.contains(title)) { kept++; continue; }
            if (dropped < drop.length) drop[dropped++] = i;
        }

        // 缓存容器直接引用：底栏不在 Activity 的 decor 树里，
        // 之后「恢复/切页」时只能靠它才能再找到底栏。

        for (int k = dropped - 1; k >= 0; k--) {
            try {
                menu.removeViewAt(drop[k]);
                hit(Config.F_BOTTOM_BAR);
            } catch (Throwable ignored) { }
        }

        // 只删视图还不够：COUINavigationMenuView.onMeasure 的除数是
        // `getMenu().getVisibleItems().size()`——**菜单里的可见项数**，不是子视图数。
        // 视图删了、菜单项还在，除数还是 5，于是每项仍按 (1080-72)/5 = 202 算，
        // 容器缩成 2×202 = 404 挤在屏幕正中间。
        //
        // 所以把对应菜单项 setVisible(false)：
        //  - MenuBuilder.getVisibleItems() 只收 isVisible() 的项，除数随之变成 2，
        //    COUI 自己就会算出 (1080-72)/2 = 504，两项正好铺满整条栏；
        //  - 用「设不可见」而不是 removeItemAt，是为了让菜单项本身**留在原位**：
        //    app 侧按下标/按 MenuItem id 做的切页映射不会被我们改动坏
        //    （真机验证过：点「我的」确实到我的页）。
        for (int k = 0; k < dropped; k++) {
            hideMenuItem(builder, drop[k]);
        }

        if (dropped > 0 || seen.length() > 0) reportTabsOnce(seen.toString(), dropped, kept);
    }

    /** 把第 index 个菜单项设为不可见；拿不到就跳过（视图已经删了，只是排版不均分）。 */
    private static void hideMenuItem(Object builder, int index) {
        try {
            if (builder == null) return;
            Object item = M_NAV_MENUITEM.invoke(builder, index);
            if (item == null) return;
            if (M_ITEM_VISIBLE == null) {
                M_ITEM_VISIBLE = item.getClass().getMethod("setVisible", boolean.class);
                M_ITEM_VISIBLE.setAccessible(true);
            }
            M_ITEM_VISIBLE.invoke(item, Boolean.FALSE);
        } catch (Throwable ignored) { }
    }


    /** 缓存下来的菜单反射句柄，只在安装后第一次用到时解析一次，之后零反射。 */
    private static volatile Method M_NAV_MENUSIZE;
    private static volatile Method M_NAV_MENUITEM;
    private static volatile Method M_ITEM_TITLE;
    private static volatile Method M_ITEM_VISIBLE;
    private static volatile Object NAV_BUILDER;

    /**
     * 拿到底栏背后的 MenuBuilder；读不到就返回 null，后续退回按视图文案判断。
     *
     * getMenu() 声明在 NavigationBarMenuView（基类）上，运行期实例是它的子类
     * COUINavigationMenuView，所以不能直接 getDeclaredMethod——必须沿类链往上找，
     * 否则永远是 null（第一版就栽在这里，日志里 seen 只剩「首页」）。
     */
    private static Object navMenuBuilder(ViewGroup menu) {
        Object cached = NAV_BUILDER;
        if (cached != null) return cached;
        try {
            Method getter = findDeclaredMethod(menu.getClass(), "getMenu");
            if (getter == null) return null;
            getter.setAccessible(true);
            Object builder = getter.invoke(menu);
            if (builder == null) return null;
            M_NAV_MENUSIZE = builder.getClass().getMethod("size");
            M_NAV_MENUITEM = builder.getClass().getMethod("getItem", int.class);
            NAV_BUILDER = builder;
            return builder;
        } catch (Throwable ignored) { return null; }
    }


    /**
     * 底栏红点/数字角标：闸门式拦掉「挂角标」那一步。
     *
     * 角标由 COUINavigationView.setTipsViewByItemId(itemId, …) 挂到 tab 上
     * （真机视图树里是 tab 内的 app:id/vs_warning_tip，一个 ViewStub，
     *  截图上就是「我的」右上角那个 130）。
     *
     * 这里直接不往下走，角标**根本不会被挂上去**——比事后 findViewById 再隐藏干净，
     * 也不会出现「先亮一下再消失」。
     *
     * 拦的是全部 `setTipsView*`，不只 setTipsViewByItemId：真机上后者并不是唯一入口，
     * 还有一个直接收 item 视图的 setTipsView(itemView, text, ...)。
     * 角标通常要等登录态/消息数异步回来才设置，所以「这一轮没调用」不等于用不上——
     * 两个入口一起封，才不会换个路径又冒出来。一个都没拦到就当没装上，不留半吊子。
     */
    private void installNavBadgeGate(ClassLoader loader) throws Exception {
        Class<?> owner = load(loader, CLS_NAV_VIEW);
        int hooked = 0;
        for (Method m : owner.getMethods()) {
            if (!m.getName().startsWith(M_NAV_SET_TIPS)) continue;
            if (m.getReturnType() != void.class) continue;
            m.setAccessible(true);
            final String label = m.getName() + "/" + m.getParameterTypes().length;
            hook(m).setId(Config.MODULE + "_nav_badge").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    hit(Config.F_NAV_BADGE);
                    return null;      // 不 proceed：角标不挂上去
                }
            });
            recordAnchor(Config.F_NAV_BADGE + "/" + label, m);
            hooked++;
        }
        if (hooked == 0) {
            throw new NoSuchMethodException(owner.getName() + "." + M_NAV_SET_TIPS + "*(...)");
        }
        log(Log.INFO, TAG, "nav badge gate: blocked " + hooked + " setTipsView* method(s)");
    }


    /** 沿类链往上找方法（getDeclaredMethod 只看本类，继承来的会漏）。 */
    private static Method findDeclaredMethod(Class<?> type, String name, Class<?>... params) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredMethod(name, params); }
            catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    /** 第 index 个菜单项的文案；读不到返回 null。 */
    private static String navItemTitle(Object builder, int index) {
        if (builder == null || M_NAV_MENUSIZE == null) return null;
        try {
            if (((Integer) M_NAV_MENUSIZE.invoke(builder)).intValue() <= index) return null;
            Object item = M_NAV_MENUITEM.invoke(builder, index);
            if (item == null) return null;
            if (M_ITEM_TITLE == null) {
                M_ITEM_TITLE = item.getClass().getMethod("getTitle");
                M_ITEM_TITLE.setAccessible(true);
            }
            Object title = M_ITEM_TITLE.invoke(item);
            if (title == null) return null;
            String value = title.toString().trim();
            return value.length() == 0 ? null : value;
        } catch (Throwable ignored) { return null; }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean TABS_REPORTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void reportTabsOnce(String seen, int hidden, int kept) {
        if (!TABS_REPORTED.compareAndSet(false, true)) return;
        noteStatic("bottom_bar tabs seen=[" + seen + "] dropped=" + hidden + " kept=" + kept
                + " keepList=[" + TAB_LABELS + "]");
    }

    /** 取一个 tab 项的文案：先大标签，再小标签。取不到就返回 null（宁可不删，也不误伤）。 */
    private static String tabTitle(View item) {
        String title = textOf(item, ID[K_TAB_LABEL_LARGE]);
        if (title == null) title = textOf(item, ID[K_TAB_LABEL_SMALL]);
        return title;
    }

    private static String textOf(View root, int id) {
        if (id == 0) return null;
        try {
            android.widget.TextView view = (android.widget.TextView) root.findViewById(id);
            if (view == null) return null;
            CharSequence text = view.getText();
            if (text == null) return null;
            String value = text.toString().trim();
            return value.length() == 0 ? null : value;
        } catch (Throwable ignored) { return null; }
    }

    /** varargs 形式：第二个起是「隐藏了要记一次命中的特性 key」。 */
    private static void hide(View root, int id, String... features) {
        if (id == 0) return;
        try {
            View view = root.findViewById(id);
            if (view != null && view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
                markHits(features);
            }
        } catch (Throwable ignored) { }
    }

    private static void markHits(String... features) {
        if (features == null) return;
        for (int i = 0; i < features.length; i++) if (features[i] != null) hit(features[i]);
    }

    /** 把目标 id 的「卡片级祖先」隐藏掉（RecyclerView 的直接子项）。 */
    private static void hideCardOf(View root, int id, int listId, String... features) {
        if (id == 0) return;
        try {
            View view = root.findViewById(id);
            if (view == null) return;
            View card = view;
            for (int i = 0; i < 3 && card.getParent() instanceof View; i++) {
                View parent = (View) card.getParent();
                if (parent.getId() == listId) break;
                card = parent;
            }
            if (card.getVisibility() != View.GONE) {
                card.setVisibility(View.GONE);
                markHits(features);
            }
        } catch (Throwable ignored) { }
    }

    private static void hideParentOf(View root, int id, String... features) {
        if (id == 0) return;
        try {
            View view = root.findViewById(id);
            if (view == null) return;
            if (view.getParent() instanceof View) {
                View parent = (View) view.getParent();
                if (parent.getVisibility() != View.GONE) {
                    parent.setVisibility(View.GONE);
                    markHits(features);
                }
            } else if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
                markHits(features);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * 推荐卡：「继续探索」里的一排游戏/应用。
     *
     * 真机实测踩过的坑：早期版本按**类名**找 HorizontalAppItemView，结果一张都没隐藏——
     * 运行期这些视图的类名是 RelativeLayout/LinearLayout，标记全在**资源 id 名**上
     * （card_container / v_app_item / horizontal_app_item_view_app_rating）。
     * 所以现在按 id 组合判定，这是跨版本最稳的形状特征。
     * 只在 mine_list_view 的直接子项上判断（十几个子项），不是全树遍历。
     */
    private static void hideRecommendCards(View root, int listId, String... features) {
        if (listId == 0) return;
        try {
            View list = root.findViewById(listId);
            if (!(list instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) list;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child == null || child.getVisibility() == View.GONE) continue;
                if (!isRecommendCard(child)) continue;
                child.setVisibility(View.GONE);
                markHits(features);
            }
        } catch (Throwable ignored) { }
    }

    /** 推荐卡形状：card_container + v_app_item（+ horizontal_app_item_view_*）同时出现。 */
    private static boolean isRecommendCard(View child) {
        try {
            boolean card = ID[K_MINE_REC_CARD] != 0 && child.findViewById(ID[K_MINE_REC_CARD]) != null;
            boolean item = ID[K_MINE_REC_ITEM] != 0 && child.findViewById(ID[K_MINE_REC_ITEM]) != null;
            boolean rating = ID[K_MINE_REC_RATING] != 0 && child.findViewById(ID[K_MINE_REC_RATING]) != null;
            return (card && item) || (item && rating);
        } catch (Throwable ignored) { return false; }
    }

    /**
     * 靠标题文案隐藏「我的」页的通用卡片。
     *
     * 实测结论：这些卡片共用同一套布局（cl_content + tv_title/tv_subtitle/tv_button），
     * 资源表里虽有 cl_clean / cl_health，但运行期并不存在，findViewById 永远返回 null
     * ——只按 id 隐藏会出现「状态 matched、实际没隐藏」。文案是跨混淆最稳的锚点，
     * 这里用它兜底；找不到就静默跳过（fail-open，不误伤其它卡片）。
     */
    private static void hideMineCardsByTitle(View root, int listId, String[] labels, String... features) {
        if (listId == 0 || ID[K_MINE_CARD_TITLE] == 0) return;
        try {
            View list = root.findViewById(listId);
            if (!(list instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) list;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child == null || child.getVisibility() == View.GONE) continue;
                String title = textOf(child, ID[K_MINE_CARD_TITLE]);
                if (title == null || !matchesAny(title, labels)) continue;
                child.setVisibility(View.GONE);
                markHits(features);
            }
        } catch (Throwable ignored) { }
    }

    private static boolean matchesAny(String value, String[] options) {
        if (options == null) return false;
        for (int i = 0; i < options.length; i++) {
            if (options[i] != null && value.contains(options[i])) return true;
        }
        return false;
    }

    /**
     * 「我的」页列表守卫。
     *
     * 实测踩到的坑（很典型）：只在 onResume 隐藏一次，滚动后 RecyclerView 重新绑定
     * 会把子项恢复成 VISIBLE，卡片又冒出来了——日志显示 hit，界面上却还在。
     * 视图层的一次性修改挡不住重新绑定；参考项目对这类列表也是拦数据源，
     * 但本页文案（「继续探索」等）实测不在 dex 里，是服务端下发的，没有可锚定的数据源。
     *
     * 所以退一步用**事件驱动**的守卫：只监听「子项被挂到窗口」这一个事件，
     * 每次滑动只触发寥寥数次，不做定时轮询、不 hook 任何全局高频方法，
     * 也不做整树遍历（只看 RecyclerView 的直接子项）。
     */
    private static final java.util.WeakHashMap<View, Boolean> GUARDED =
            new java.util.WeakHashMap<View, Boolean>();

    private static void installMineListGuard(final View list) {
        if (!(list instanceof ViewGroup)) return;
        try {
            synchronized (GUARDED) {
                if (GUARDED.containsKey(list)) return;
                GUARDED.put(list, Boolean.TRUE);
            }
            // OnChildAttachStateChangeListener 在编译用的 android.jar 里不存在
            // （只有 OnHierarchyChangeListener），所以用后者：子项被加进来时同样会回调。
            // 注意 setOnHierarchyChangeListener 会**覆盖**宿主自己挂的监听器，
            // 所以先把原来的取出来，在我们的回调里转发过去——不能因为去广告就把 App 弄坏。
            // 编译用的 android.jar 里没有 getOnHierarchyChangeListener()，只能反射读字段；
            // 读不到就降级为直接挂（RecyclerView 本身并不用这个监听器，风险很低）。
            final ViewGroup.OnHierarchyChangeListener previous = readHierarchyListener((ViewGroup) list);
            ViewGroup.OnHierarchyChangeListener listener = new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) {
                    filterMineChild(child);
                    if (previous != null) previous.onChildViewAdded(parent, child);
                }
                @Override public void onChildViewRemoved(View parent, View child) {
                    if (previous != null) previous.onChildViewRemoved(parent, child);
                }
            };
            ((ViewGroup) list).setOnHierarchyChangeListener(listener);
        } catch (Throwable ignored) { }
    }

    /** 对单个列表子项套用全部「我的」页卡片规则。 */
    private static void filterMineChild(View child) {
        if (child == null || child.getVisibility() == View.GONE) return;
        try {
            if (ON[I_RECOMMEND] && isRecommendCard(child)) {
                child.setVisibility(View.GONE);
                hit(Config.F_MINE_RECOMMEND);
                return;
            }
            String title = textOf(child, ID[K_MINE_CARD_TITLE]);
            if (title == null) return;
            if (ON[I_CLEAN] && matchesAny(title, Config.MINE_CARD_LABELS_CLEAN)) {
                child.setVisibility(View.GONE);
                hit(Config.F_MINE_CLEAN);
                return;
            }
            if (ON[I_HEALTH] && matchesAny(title, Config.MINE_CARD_LABELS_HEALTH)) {
                child.setVisibility(View.GONE);
                hit(Config.F_MINE_HEALTH);
                return;
            }
            if (ON[I_BANNER] && matchesAny(title, Config.MINE_CARD_LABELS_BANNER)) {
                child.setVisibility(View.GONE);
                hit(Config.F_MINE_BANNER);
            }
        } catch (Throwable ignored) { }
    }

    /** 反射读 ViewGroup 已有的层级监听器，读不到就返回 null（降级直接挂）。 */
    private static ViewGroup.OnHierarchyChangeListener readHierarchyListener(ViewGroup group) {
        try {
            java.lang.reflect.Field field = ViewGroup.class.getDeclaredField("mOnHierarchyChangeListener");
            field.setAccessible(true);
            Object value = field.get(group);
            return (value instanceof ViewGroup.OnHierarchyChangeListener)
                    ? (ViewGroup.OnHierarchyChangeListener) value : null;
        } catch (Throwable ignored) { return null; }
    }

    /**
     * 顶部三宫格重排。
     *
     * 用户反馈的真问题：三个格子是等宽并排的（真机 bounds 48-376 / 377-704 / 704-1032），
     * 隐藏中间的「应用卸载」后，左右两张卡各占 1/3 贴住两边，中间空一大块，
     * 看着「不均匀、不居中」，两个 1px 分隔线还杵在那里。
     *
     * 做法：把该行里所有分隔线一并收掉，剩下的格子改成 width=0 + weight=1，
     * 让它们平分整行。全部隐藏时不动，避免整行塌成 0 宽。
     */
    private static void rebalanceTopGrid(View decor) {
        try {
            View anchor = decor.findViewById(ID[K_MINE_UPGRADE]);
            if (anchor == null) anchor = decor.findViewById(ID[K_MINE_UNINSTALL]);
            if (anchor == null) anchor = decor.findViewById(ID[K_MINE_DOWNLOAD]);
            if (anchor == null) return;
            if (!(anchor.getParent() instanceof ViewGroup)) return;
            ViewGroup row = (ViewGroup) anchor.getParent();

            int visible = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child != null && isGridSlot(child) && child.getVisibility() != View.GONE) visible++;
            }
            if (visible == 0) return;      // 整行都藏了就别动布局

            // ConstraintLayout 的子项是按「约束 + 百分比宽度」定位的，
            // 改 width 或加 weight 都不会让它重排（真机实测：改成定宽后两张卡直接重叠）。
            // 正确做法是把可见的格子接成一条 chain，由 ConstraintLayout 自己平分。
            java.util.List<View> slots = new java.util.ArrayList<View>();
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child != null && isGridSlot(child) && child.getVisibility() != View.GONE) {
                    slots.add(child);
                }
            }
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child != null && isRowDivider(child) && child.getVisibility() != View.GONE) {
                    child.setVisibility(View.GONE);       // 分隔线跟着一起收掉
                }
            }
            chainEvenly(row, slots);
        } catch (Throwable ignored) { }
    }

    /**
     * 把可见格子接成一条 ConstraintLayout chain，让它们平分整行。
     *
     * 真机实测的关键事实：这一行的父容器是 androidx.constraintlayout.widget.ConstraintLayout，
     * 子项宽度 1640（远大于行宽 984），靠「start/end 约束 + 百分比宽度」定位。
     * 所以改 width、加 weight 都不管用（改完直接重叠成 48-1032 与 212-1032）。
     * 唯一正确解法是重建约束，让 ConstraintLayout 自己按 chain 均分。
     *
     * androidx 的类不在编译用的 android.jar 里，只能反射读写 LayoutParams 的 public 字段；
     * 任何一步读不到就整体放弃（保持原样），绝不留下错乱的布局。
     */
    private static final java.util.concurrent.atomic.AtomicInteger GRID_DIAG_COUNT =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 目标 App 的 classloader；只用于反射第三方容器类，模块自身 loader 里没有它们。 */
    private static volatile ClassLoader TARGET_LOADER;

    private static void gridDiag(String message) {
        if (GRID_DIAG_COUNT.getAndIncrement() >= 4) return;   // 别刷屏
        noteStatic("grid diag: " + message);
    }

    private static void chainEvenly(ViewGroup row, java.util.List<View> slots) {
        if (slots == null || slots.size() < 2) { gridDiag("slots=" + (slots == null ? 0 : slots.size())); return; }
        try {
            // 必须用目标 App 的 classloader：模块自身 loader 里没有 androidx.constraintlayout
            ClassLoader owner = TARGET_LOADER;
            if (owner == null) { gridDiag("no target classloader"); return; }
            Class<?> clp = Class.forName(
                    "androidx.constraintlayout.widget.ConstraintLayout$LayoutParams", false, owner);
            android.view.ViewGroup.LayoutParams first = slots.get(0).getLayoutParams();
            gridDiag("rowClass=" + row.getClass().getName()
                    + " lpClass=" + (first == null ? "null" : first.getClass().getName())
                    + " isCLP=" + (first != null && clp.isInstance(first))
                    + " fields=startToStart:" + hasField(clp, "startToStart")
                    + ",endToEnd:" + hasField(clp, "endToEnd")
                    + ",startToEnd:" + hasField(clp, "startToEnd")
                    + ",endToStart:" + hasField(clp, "endToStart")
                    + ",widthPercent:" + hasField(clp, "widthPercent")
                    + ",width:" + hasField(clp, "width"));
            java.lang.reflect.Field fStartToStart = clp.getField("startToStart");
            java.lang.reflect.Field fEndToEnd = clp.getField("endToEnd");
            java.lang.reflect.Field fStartToEnd = clp.getField("startToEnd");
            java.lang.reflect.Field fEndToStart = clp.getField("endToStart");
            java.lang.reflect.Method mWidthPercent = null;
            try { mWidthPercent = clp.getMethod("setWidthPercent", float.class); } catch (Throwable ignored) { }
            java.lang.reflect.Field fWidth = clp.getField("width");
            java.lang.reflect.Field fBias = clp.getField("horizontalBias");

            final int PARENT = 0;          // ConstraintLayout.LayoutParams.PARENT_ID
            for (int i = 0; i < slots.size(); i++) {
                View slot = slots.get(i);
                android.view.ViewGroup.LayoutParams raw = slot.getLayoutParams();
                if (raw == null || !clp.isInstance(raw)) { gridDiag("bail at slot " + i); return; }
                int prevId = (i > 0) ? slots.get(i - 1).getId() : PARENT;
                int nextId = (i < slots.size() - 1) ? slots.get(i + 1).getId() : PARENT;

                fStartToStart.setInt(raw, i == 0 ? PARENT : -1);
                fStartToEnd.setInt(raw, i == 0 ? -1 : prevId);
                fEndToStart.setInt(raw, i == slots.size() - 1 ? -1 : nextId);
                fEndToEnd.setInt(raw, i == slots.size() - 1 ? PARENT : -1);
                if (mWidthPercent != null) mWidthPercent.invoke(raw, 0f);   // 0 = 不按百分比
                fWidth.setInt(raw, 0);                // 0 = MATCH_CONSTRAINT
                fBias.setFloat(raw, 0.5f);
                slot.setLayoutParams(raw);
            }
            row.requestLayout();
            gridDiag("chained " + slots.size() + " slots");
        } catch (Throwable error) { gridDiag("failed: " + describe(error)); }
    }

    private static String hasField(Class<?> type, String name) {
        try { type.getField(name); return "y"; }
        catch (Throwable ignored) { return "n"; }
    }

    private static boolean isGridSlot(View view) {
        if (view == null) return false;
        int id = view.getId();
        return id != 0 && (id == ID[K_MINE_UPGRADE] || id == ID[K_MINE_UNINSTALL]
                || id == ID[K_MINE_DOWNLOAD]);
    }

    /** 该行里 id 名以 line 开头的 1px 分隔线；只看本行的子项，不会误伤别的卡片。 */
    private static boolean isRowDivider(View view) {
        try {
            if (view == null || view.getId() == 0) return false;
            if (isGridSlot(view)) return false;
            String name = view.getResources().getResourceEntryName(view.getId());
            return name != null && name.startsWith("line");
        } catch (Throwable ignored) { return false; }
    }

    // ══════════════════════ 资源 id 解析 ══════════════════════

    // K_* 是 ID[] 的下标
    private static final int K_BOTTOM_TAB = 0;
    private static final int K_BOTTOM_NAV = 1;
    private static final int K_FLOAT_AD = 2;
    private static final int K_MINE_UPGRADE = 3;
    private static final int K_MINE_UNINSTALL = 4;
    private static final int K_MINE_DOWNLOAD = 5;
    private static final int K_MINE_CLEAN = 6;
    private static final int K_MINE_HEALTH = 7;
    private static final int K_MINE_BANNER = 8;
    private static final int K_MINE_INDIC = 9;
    private static final int K_MINE_VIP = 10;
    private static final int K_MINE_LIST = 11;
    private static final int K_TAB_LABEL_LARGE = 12;
    private static final int K_TAB_LABEL_SMALL = 13;
    private static final int K_MINE_CARD_TITLE = 14;
    private static final int K_MINE_REC_CARD = 15;
    private static final int K_MINE_REC_ITEM = 16;
    private static final int K_MINE_REC_RATING = 17;
    private static final int K_TOP_STAGE = 18;
    private static final int K_NAV_TIP = 19;
    private static final int K_TOP_BANNER = 20;
    private static final int ID_COUNT = 21;

    private static final String[] ID_NAMES = {
            Config.ID_BOTTOM_TAB, Config.ID_BOTTOM_NAV, Config.ID_FLOAT_AD,
            Config.ID_MINE_UPGRADE, Config.ID_MINE_UNINST, Config.ID_MINE_DOWN,
            Config.ID_MINE_CLEAN, Config.ID_MINE_HEALTH, Config.ID_MINE_BANNER,
            Config.ID_MINE_INDIC, Config.ID_MINE_VIP, Config.ID_MINE_LIST,
            Config.ID_TAB_LABEL_LARGE, Config.ID_TAB_LABEL_SMALL,
            Config.ID_MINE_CARD_TITLE, Config.ID_MINE_REC_CARD,
            Config.ID_MINE_REC_ITEM, Config.ID_MINE_REC_RATING,
            Config.ID_TOP_STAGE, Config.ID_NAV_TIP, Config.ID_TOP_BANNER,
    };
    private static final int[] ID = new int[ID_COUNT];
    private static volatile boolean idsResolved;

    /** 底栏的两个文案 id 单独解析（供 Config 里的名字 -> 运行期 id 用）。 */
    static int tabLabelLargeId() { return ID[K_TAB_LABEL_LARGE]; }
    static int tabLabelSmallId() { return ID[K_TAB_LABEL_SMALL]; }

    private void resolveIds(Context context) {
        Resources resources;
        try { resources = context.getResources(); }
        catch (Throwable error) { log(Log.WARN, TAG, "resources unavailable", error); return; }
        int found = 0;
        for (int i = 0; i < ID_COUNT; i++) {
            try { ID[i] = resources.getIdentifier(ID_NAMES[i], "id", Config.TARGET); }
            catch (Throwable ignored) { ID[i] = 0; }
            if (ID[i] != 0) found++;
        }
        idsResolved = true;
        log(Log.INFO, TAG, "resource ids resolved: " + found + "/" + ID_COUNT);
        note("resource ids resolved: " + found + "/" + ID_COUNT + " "
                + (found == ID_COUNT ? "complete" : "PARTIAL: some features will fall back"));
    }

    /** 某个特性依赖的资源 id 是否齐备 —— UI 类规则的 matched / partial / miss 依据。 */
    private String uiState(String feature, int[] required) {
        if (!idsResolved) return "miss";
        int have = 0;
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < required.length; i++) {
            if (ID[required[i]] != 0) have++;
            else missing.append(ID_NAMES[required[i]]).append(' ');
        }
        if (have == required.length) return "matched";
        if (have == 0) { probeFailure = "未找到 id: " + missing.toString().trim(); return "miss"; }
        probeFailure = "部分 id 缺失: " + missing.toString().trim();
        probePartial = true;
        return "partial";
    }

    // ══════════════════════ 反射工具（安装期一次，运行期零反射） ══════════════

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return loader.loadClass(name);
    }

    /** 严格签名校验：名字对了但签名不对 => 当作没找到，绝不硬 hook。 */
    private Method requireMethod(Class<?> owner, String name, String returnName, String[] paramNames)
            throws Exception {
        Class<?>[] params = resolveShape(owner.getClassLoader(), paramNames);
        Method method = owner.getDeclaredMethod(name, params);
        if (!matches(method, returnName, params)) {
            throw new NoSuchMethodException("unexpected signature: " + method);
        }
        return method;
    }

    /**
     * 名字可能被 R8 改名时用：按「形状」在类里唯一命中才算数（歧义即失败）。
     * 这是多版本自适配的第二层（第一层是已知名直取）。
     */
    private Method findByNamePrefix(Class<?> owner, String namePrefix, String returnName, int paramCount)
            throws Exception {
        Method found = null;
        for (Method candidate : owner.getDeclaredMethods()) {
            if (!candidate.getName().startsWith(namePrefix)) continue;
            if (candidate.getReturnType() != resolveOne(owner.getClassLoader(), returnName)) continue;
            if (candidate.getParameterTypes().length != paramCount) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous candidates for " + namePrefix);
            found = candidate;
        }
        if (found == null) throw new NoSuchMethodException("no " + namePrefix + " in " + owner.getName());
        return found;
    }

    private static boolean matches(Method method, String returnName, Class<?>[] params) {
        try {
            if (method.getReturnType() != resolveOne(method.getDeclaringClass().getClassLoader(), returnName)) {
                return false;
            }
        } catch (Throwable ignored) { return false; }
        Class<?>[] actual = method.getParameterTypes();
        if (actual.length != params.length) return false;
        for (int i = 0; i < actual.length; i++) if (actual[i] != params[i]) return false;
        return true;
    }

    private static Class<?>[] resolveShape(ClassLoader loader, String[] names) throws ClassNotFoundException {
        Class<?>[] out = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) out[i] = resolveOne(loader, names[i]);
        return out;
    }

    private static Class<?> resolveOne(ClassLoader loader, String name) throws ClassNotFoundException {
        if ("boolean".equals(name)) return boolean.class;
        if ("byte".equals(name)) return byte.class;
        if ("char".equals(name)) return char.class;
        if ("short".equals(name)) return short.class;
        if ("int".equals(name)) return int.class;
        if ("long".equals(name)) return long.class;
        if ("float".equals(name)) return float.class;
        if ("double".equals(name)) return double.class;
        if ("void".equals(name)) return void.class;
        return loader.loadClass(name);
    }

    // ══════════════════════ 探针与上报 ══════════════════════

    private boolean probePartial;

    /** 允许抛受检异常的安装动作（反射查找必然要处理受检异常）。 */
    private interface ThrowingRunnable { void run() throws Exception; }

    /** action 为 null 表示这个特性不装 hook；异常一律折算成 miss，绝不外泄。 */
    private void probe(String feature, boolean enabled, ThrowingRunnable action) {
        probeFailure = null;
        probePartial = false;
        if (enabled) {
            try {
                if (action != null) action.run();
                else probeFailure = null;
            } catch (Throwable error) {
                probeFailure = error.toString();
                log(Log.WARN, TAG, feature + " probe failed", error);
            }
        }
        String state;
        if (!enabled) state = "off";
        else if (probeFailure != null) state = "miss";
        else if (probePartial) state = "partial";
        else state = "matched";
        reportDone++;
        report("running", feature, state, probeFailure == null ? "" : probeFailure);
        String line = "feature=" + feature + " result=" + state
                + (probeFailure == null ? "" : " why=" + probeFailure);
        log(Log.INFO, TAG, line);
        trace(line);
    }

    /** UI 类特性在 id 解析之后单独判定（这样 partial 的原因能报出来）。 */
    private void probeUi(String feature, int... required) {
        if (!ON[Config.indexOf(feature)]) { probe(feature, false, null); return; }
        String state = uiState(feature, required);
        reportDone++;
        report("running", feature, state, probeFailure == null ? "" : probeFailure);
        String line = "feature=" + feature + " result=" + state
                + (probeFailure == null ? "" : " why=" + probeFailure);
        log(Log.INFO, TAG, line);
        trace(line);
    }

    /** 上报到设置页；失败不影响目标 App。 */
    private void report(String phase, String feature, String state, String detail) {
        if (!feature.isEmpty() && !state.isEmpty()) {
            states.put(feature, state);
            details.put(feature, detail);
        }
        if (reportContext == null) return;
        try {
            Bundle extras = new Bundle();
            extras.putString("token", reportToken);
            extras.putLong("run", reportRun);
            extras.putString("phase", phase);
            extras.putInt("done", reportDone);
            extras.putInt("total", Config.FEATURES.length);
            extras.putString("feature", feature);
            extras.putString("state", state);
            extras.putString("detail", detail.length() > 180 ? detail.substring(0, 180) : detail);
            Intent intent = new Intent(REPORT_ACTION);
            // 收件人必须是模块自己的组件：目标进程里 getPackageName() 返回的是目标包名
            intent.setComponent(new ComponentName(Config.MODULE, REPORT_RECEIVER));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtras(extras);
            if (Build.VERSION.SDK_INT >= 34) {
                Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
                reportContext.sendBroadcast(intent, null, options);
            } else {
                reportContext.sendBroadcast(intent);
            }
        } catch (Throwable error) { log(Log.WARN, TAG, "status report unavailable", error); }
    }
}
