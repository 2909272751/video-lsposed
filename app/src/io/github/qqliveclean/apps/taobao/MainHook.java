package io.github.qqliveclean.apps.taobao;

import android.app.Activity;
import android.app.Application;
import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 淘系去广告：入口 + 三重门闸 + 逐特性探针 + 状态上报。
 *
 * 设计纪律（lsposed-module-dev skill 第 1 节）：
 *  - 只 hook **一次性决策闸门**（开屏 showMaybe / 弹窗 request / SDK init），
 *    不碰 onDraw / onBindViewHolder / onScroll / 动画帧；无轮询、无全树遍历兜底。
 *  - intercept 内零分配、零反射、零日志（命中只打一次 CAS 打点）。
 *  - 逐特性独立探测：一条规则失败不影响其它规则；全失败时目标 App 行为与未装模块完全一致。
 *  - 每条锚点都有本 App 的 file:line 逆向证据，见 docs/ANALYSIS.md。
 *
 * 注意：目标 App 的类（fastjson 等）**不能**在本文件里写 .class 字面量
 * ——它们不在本模块的编译类路径上。所有参数形状都用「类名字符串」在运行时解析。
 */
public final class MainHook extends io.github.qqliveclean.RuleHost {

    public MainHook(io.github.libxposed.api.XposedModule host) {
        super(host);
    }
    private static final String TAG = "淘系去广告";
    /** 构建标识：日志每行都带，用来区分「没生效」和「没加载」。 */
    private static final String BUILD = "schema=" + Config.SCHEMA;
    private static final String HOOK_PREFIX = "io.github.qqliveclean.apps.taobao.";

    private static final String REPORT_ACTION = "io.github.qqliveclean.apps.taobao.REPORT";

    private String processName;
    private final AtomicBoolean attached = new AtomicBoolean(false);
    private final AtomicBoolean sweptLate = new AtomicBoolean(false);
    private volatile boolean configured;
    private Context reportContext;
    private String reportToken;
    private long reportRun;
    private int reportDone;
    private final ConcurrentHashMap<String, String> states = new ConcurrentHashMap<>();

    /** 当前正在探测的特性统计。 */
    private int ruleOk;
    private int ruleFail;
    private String lastFailure;

    /** 只记进程名；这里禁止 loadClass / 任何初始化。 */
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "[" + BUILD + "] module loaded in process " + processName);
    }

    /**
     * 框架差异兜底：本机 LSPosed 的 PackageReadyParam 不保证有 getApplication()，
     * 直接调用会抛 NoSuchMethodError，而且**发生在第一条日志之前** ——
     * 现象与「模块根本没加载」一模一样。所以第一条语句就直写日志；
     * Context 走 Application.attach(Context) 一次性钩子拿。
     */
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        final String pkg = param.getPackageName();
        log(Log.INFO, TAG, "[" + BUILD + "] onPackageReady pkg=" + pkg + " proc=" + processName);
        if (!Config.isTarget(pkg)) return;
        // 只 hook 主进程：子进程（推送 / 沙箱 / webview）没有首页也没有开屏，hook 它们纯属浪费电
        if (processName != null && !pkg.equals(processName)) {
            log(Log.INFO, TAG, "[" + BUILD + "] skip sub process " + processName);
            return;
        }
        final ClassLoader loader = param.getClassLoader();
        if (loader == null) { log(Log.WARN, TAG, "[" + BUILD + "] skip: no classloader"); return; }
        if (!attached.compareAndSet(false, true)) return;
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).setId(HOOK_PREFIX + "app_attach").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object argument = chain.getArg(0);
                    if (!configured && argument instanceof Context) {
                        configured = true;
                        try { configure((Context) argument, loader); }
                        catch (Throwable error) { log(Log.ERROR, TAG, "[" + BUILD + "] configure failed", error); }
                    }
                    return result;
                }
            });
            log(Log.INFO, TAG, "[" + BUILD + "] application.attach hook installed for " + pkg);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "[" + BUILD + "] cannot hook Application.attach", error);
        }
    }

    private void configure(Context context, ClassLoader loader) {
        PackageInfo info;
        try { info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0); }
        catch (Throwable error) { log(Log.WARN, TAG, "[" + BUILD + "] cannot read target version", error); return; }

        reportContext = context;
        // 缓存令牌：安装包版本 / 更新时间 / 规则结构版本 —— 任一变化即让已扫锚点失效
        reportToken = info.versionCode + ":" + info.lastUpdateTime + ":" + Config.SCHEMA;
        reportRun = System.currentTimeMillis();
        reportDone = 0;
        states.clear();
        log(Log.INFO, TAG, "[" + BUILD + "] checking hooks for " + context.getPackageName()
                + " " + info.versionName + " (" + info.versionCode + ")");

        SharedPreferences prefs = getRemotePreferences(Config.GROUP);
        final boolean isTaobao = Config.TAOBAO.equals(context.getPackageName());

        probe("splash", read(prefs, Config.SPLASH), new Runnable() {
            @Override public void run() { installSplash(loader, isTaobao); }
        });
        probe("popup", read(prefs, Config.POPUP), new Runnable() {
            @Override public void run() { installPopup(loader, isTaobao); }
        });
        probe("widget", read(prefs, Config.WIDGET), new Runnable() {
            @Override public void run() { installWidget(loader, isTaobao); }
        });
        probe("sdk", read(prefs, Config.SDK), new Runnable() {
            @Override public void run() { installAdSdk(loader, isTaobao); }
        });

        report("complete", "", "", "");
        log(Log.INFO, TAG, "[" + BUILD + "] install_summary " + summary());
    }

    // ══════════════════════ 特性实现 ══════════════════════
    //
    // 证据表见 docs/ANALYSIS.md：每条规则都注明 反编译文件:行 + 为什么它是闸门。

    /** 开屏广告：冷启动 + 热启动 + 路由唤起的全部入口，外加渲染出口兜底。 */
    private void installSplash(ClassLoader loader, boolean isTaobao) {
        if (isTaobao) {
            // 淘宝开屏总闸门 = BootImageWorkFlow（混淆成 tb.fi2，内部类 BootImageWorkFlow$2 反证身份）。
            // 证据：docs/RECON-TAOBAO.md A1；fi2.java:237-241 提前 return 之后才是拉起与分发。
            block(loader, "tb.fi2", "a",
                    new String[]{"int", "android.app.Activity"}, "tb_bootimage_gate");
            // 淘宝自带的开屏抑制开关（热启投票器之一）：恒 false 即走"拦截"分支
            force(loader, "com.taobao.bootimage.arch.flow.bootimage.BroadcastFlowInterceptor", "a",
                    new String[]{"int"}, false, "tb_bootimage_forbidden");
            // 启动 Intent 带 bootImage=0 时官方就跳过开屏：恒 true = 对所有启动跳过
            force(loader, "tb.kol", "a", new String[]{"android.content.Intent"}, true, "tb_page_skip_splash");
            // 广告渲染方（mmad TopShow）：数据到了也不渲染
            block(loader, "com.taobao.mmad.TopShowBootImage", "onStart",
                    new String[]{"java.util.List", "int"}, "tb_topshow_onstart");
            // 开屏素材/配置的接收点（渲染层之外的兜底）
            block(loader, "com.taobao.bootimage.BootImageDataMgr", "i",
                    new String[]{"com.alibaba.fastjson.JSONObject"}, "tb_bootimage_receive");
            return;
        }
        // 闲鱼：冷启动开屏唯一入口（连遮罩和广告请求都不发生）
        block(loader, "com.taobao.fleamarket.advert.Advert", "showMaybe",
                new String[]{"android.app.Activity"}, "xy_splash_showmaybe");
        // 三条 load 入口（冷 / 热 / 路由唤起）
        block(loader, "com.taobao.fleamarket.splashad.SplashAdController", "loadSplashAd",
                new String[]{"android.app.Activity"}, "xy_splash_load");
        block(loader, "com.taobao.fleamarket.splashad.SplashAdController", "loadSplashAdHotStart",
                new String[0], "xy_splash_load_hot");
        block(loader, "com.taobao.fleamarket.splashad.SplashAdController", "loadToCSplashAd",
                new String[0], "xy_splash_load_toc");
        // 五路 DSP（mama/toc/beizis/ylh/csj）唯一共同的渲染出口
        block(loader, "com.taobao.fleamarket.splashad.SplashAdShower", "showSplashAd",
                new String[]{"android.app.Activity", "android.view.View", "boolean"}, "xy_splash_shower");
    }

    /** 弹窗广告：首页浮层的所有触发源 + 展示决策点 + 配置拉取点。 */
    private void installPopup(ClassLoader loader, boolean isTaobao) {
        if (isTaobao) {
            // 淘宝首页弹窗引擎（com.taobao.infoflow.*pop*）
            // 证据：docs/RECON-TAOBAO.md B1/B2/B3
            block(loader, "com.taobao.infoflow.taobao.subservice.biz.pop.TbPopViewServiceImpl",
                    "scheduleTBHomepagePop",
                    new String[]{"android.content.Context", "android.widget.FrameLayout"}, "tb_pop_schedule");
            block(loader, "com.taobao.infoflow.taobao.subservice.biz.pop.TbPopViewServiceImpl",
                    "checkInitPop", new String[]{"android.content.Context"}, "tb_pop_init");
            // 四条并列触发入口：B1 的 false = 未展示（官方非法数据分支就是 false）
            force(loader, "com.taobao.infoflow.taobao.subservice.biz.pop.TbPopViewServiceImpl",
                    "triggerPopShowByPopData",
                    new String[]{"int", "com.alibaba.fastjson.JSONObject"}, false, "tb_pop_trigger_data");
            block(loader, "com.taobao.infoflow.taobao.subservice.biz.pop.TbPopViewServiceImpl",
                    "triggerPopShow", new String[]{"int", "java.lang.String"}, "tb_pop_trigger_id");
            block(loader, "com.taobao.infoflow.taobao.subservice.biz.pop.TbPopViewServiceImpl",
                    "triggerPopShowByCustomData",
                    new String[]{"com.alibaba.fastjson.JSONObject"}, "tb_pop_trigger_custom");
            block(loader, "com.taobao.infoflow.taobao.subservice.biz.pop.TbPopViewServiceImpl",
                    "triggerPopShowWithDataReset",
                    new String[]{"int", "java.lang.String"}, "tb_pop_trigger_reset");
            // 阿里 PopLayer 底座的服务端准入校验：恒 false = 逐条判"不许弹"。
            // 开屏联动浮层 SmallPopView 的父类就是 PopLayer 原生渲染基类，这一层同时压掉联动浮层。
            // 证据：docs/RECON-TAOBAO.md B11/B12/B13
            force(loader, "com.taobao.tbpoplayer.filter.MtopPopCheckHelper", "u",
                    new String[]{"com.alibaba.poplayer.layermanager.PopRequest",
                            "com.alibaba.fastjson.JSONObject",
                            "com.alibaba.poplayer.norm.IUserCheckRequestListener"},
                    false, "tb_pop_check_u");
            force(loader, "com.taobao.tbpoplayer.filter.MtopPopCheckHelper", "e",
                    new String[]{"com.alibaba.poplayer.layermanager.PopRequest"}, false, "tb_pop_check_e");
            force(loader, "com.taobao.tbpoplayer.filter.MtopGroupPreCheckManager", "o",
                    new String[]{"com.alibaba.poplayer.layermanager.PopRequest",
                            "com.alibaba.fastjson.JSONObject",
                            "com.alibaba.poplayer.norm.IUserCheckRequestListener"},
                    false, "tb_pop_group_o");
            force(loader, "com.taobao.tbpoplayer.filter.MtopGroupPreCheckManager", "j",
                    new String[]{"com.alibaba.poplayer.layermanager.PopRequest"}, false, "tb_pop_group_j");
            return;
        }
        // 闲鱼：登录成功 / 新人引导 / 主容器 idle 三个触发源的统一入口
        block(loader, "com.taobao.idlefish.popwindow.PopWindowController", "requestAndShowPopWindow",
                new String[]{"long"}, "xy_pop_request_and_show");
        // 真正决定「弹不弹」的那一步
        block(loader, "com.taobao.idlefish.popwindow.PopWindowController", "doHandleShowPopWindow",
                new String[0], "xy_pop_handle");
        // 弹窗配置的 MTop 拉取点：空实现后连这一次网络请求都省掉
        block(loader, "com.taobao.idlefish.popwindow.PopWindowRequestController", "request",
                new String[]{"android.content.ContextWrapper", "boolean",
                        "com.taobao.idlefish.protocol.net.ApiCallBack"}, "xy_pop_config_request");
        // 「活动弹窗」是另一套（Weex/H5 渲染的 fun.activepopup 家族，与上面的 PopWindow 无关），
        // 展示入口只有 PopupView 的三个 show 重载；空实现即这一族整体不出现。
        // 证据：scratch-recon/jadx-xy/PopupView.java（show / show(String) / show(Rect, String)）
        block(loader, "com.taobao.idlefish.fun.activepopup.PopupView", "show",
                new String[0], "xy_activepopup_show");
        block(loader, "com.taobao.idlefish.fun.activepopup.PopupView", "show",
                new String[]{"java.lang.String"}, "xy_activepopup_show_str");
        block(loader, "com.taobao.idlefish.fun.activepopup.PopupView", "show",
                new String[]{"android.graphics.Rect", "java.lang.String"}, "xy_activepopup_show_rect");
    }

    /**
     * 悬浮广告与广告球：按**资源 id 名**隐藏（id 名比混淆类名稳）。
     * 闲鱼/淘宝的信息流卡片本体由服务端 DynamicX 模板渲染，Java 侧没有渲染闸门，
     * 因此本特性只清理有独立容器的浮层广告位，不做全树遍历兜底（那会牺牲流畅度）。
     */
    private void installWidget(final ClassLoader loader, boolean isTaobao) {
        // 淘宝侧：开屏广告浮层的资源 id（R 已内联，只能从 resources.arsc 取，见 RECON-TAOBAO.md E 节）
        final String[] taobaoNames = {
                "bootimage_ad_top_layer", "bootimage_ad_pop_skip", "bootimage_interact_card_container",
                "bootimage_pop_lottie",
        };
        // 闲鱼侧：悬浮广告位与广告球（闲鱼自有资源段，见 RECON-IDLEFISH.md 第 4 节）
        final String[] idlefishNames = {
                "advert_suspend_layout", "advert_suspend_stub", "advert_suspend_img",
                "adball_layout", "adball",
        };
        final String[] names = isTaobao ? taobaoNames : idlefishNames;
        try {
            Class<?> activityClass = loader.loadClass("android.app.Activity");
            Method resume = activityClass.getDeclaredMethod("onResume");
            hook(resume).setId(HOOK_PREFIX + "widget_resume").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        Activity activity = (Activity) self;
                        sweep(activity, names);
                        // 数据晚到的浮层会「复现」：固定次数补隐藏，随视图创建触发，非轮询
                        if (sweptLate.compareAndSet(false, true)) scheduleLateSweeps(activity, names);
                    }
                    return result;
                }
            });
            ruleOk++;
            log(Log.INFO, TAG, "[" + BUILD + "] rule armed id=widget_ids -> Activity.onResume sweep");
        } catch (Throwable error) {
            ruleFail++;
            lastFailure = "widget_ids: " + error;
            log(Log.WARN, TAG, "[" + BUILD + "] rule unavailable id=widget_ids (" + error + ")");
        }
    }

    /** 广告 SDK：只拦预加载与初始化这类一次性闸门，省电/省流量。 */
    private void installAdSdk(ClassLoader loader, boolean isTaobao) {
        if (isTaobao) {
            // 淘宝侧 base.apk 内没有可靠的第三方广告 SDK 初始化锚点（已穷举证伪，见 RECON-TAOBAO.md D2），
            // 改为断掉开屏数据的后台预加载：两次调用都是后台任务投递，不碰任何高频路径。
            block(loader, "com.taobao.bootimage.BootImageDataMgr", "j",
                    new String[0], "tb_bootimage_prefetch");
            block(loader, "com.taobao.bootimage.BootImageDataMgr", "k",
                    new String[0], "tb_bootimage_post_task");
            return;
        }
        // 闲鱼自带的「关掉某家开屏」配置闸门（非混淆、跨版本最稳）
        force(loader, "com.taobao.fleamarket.swtch.NewSplashAdOrange", "thirdCsjOn",
                new String[0], false, "xy_sdk_csj_cfg");
        force(loader, "com.taobao.fleamarket.swtch.NewSplashAdOrange", "thirdYlhOn",
                new String[0], false, "xy_sdk_ylh_cfg");
        force(loader, "com.taobao.fleamarket.swtch.NewSplashAdOrange", "thirdAdPlusOn",
                new String[0], false, "xy_sdk_beizi_cfg");
        // 两家第三方 SDK 的唯一启动入口（不改它 SDK 仍会被拉起做后台初始化）
        block(loader, "com.taobao.fleamarket.splashad.tool.ThirdSdkUtil", "initCsjSdk",
                new String[]{"com.taobao.fleamarket.splashad.interfaces.SdkInitListener"}, "xy_sdk_csj_init");
        block(loader, "com.taobao.fleamarket.splashad.tool.ThirdSdkUtil", "initYlhSdk",
                new String[]{"com.taobao.fleamarket.splashad.interfaces.SdkInitListener"}, "xy_sdk_ylh_init");
    }

    // ══════════════════════ 规则安装原语 ══════════════════════

    /**
     * 严格签名安装：名字对了但形状不对就当作没找到，绝不硬 hook。
     * 参数个数 / 参数类型 / 返回类型全部校验；歧义即失败。
     */
    private void block(ClassLoader loader, String owner, String name, String[] shape, String id) {
        try {
            Class<?> cls = loader.loadClass(owner);
            Method target = findMethod(cls, name, resolve(loader, shape));
            if (target == null) throw new NoSuchMethodException("shape mismatch: " + owner + "#" + name);
            if (target.getReturnType() != void.class) {
                throw new NoSuchMethodException("expected void: " + target);
            }
            hook(target).setId(HOOK_PREFIX + id).intercept(new Blocker(id));
            ruleOk++;
            log(Log.INFO, TAG, "[" + BUILD + "] rule armed id=" + id + " -> " + target);
        } catch (Throwable error) {
            ruleFail++;
            lastFailure = id + ": " + error;
            log(Log.WARN, TAG, "[" + BUILD + "] rule unavailable id=" + id + " (" + error + ")");
        }
    }

    /** 同 block，但目标返回 boolean：强制常量返回（配置型开关 / 准入校验）。 */
    private void force(ClassLoader loader, String owner, String name, String[] shape, boolean value, String id) {
        try {
            Class<?> cls = loader.loadClass(owner);
            Method target = findMethod(cls, name, resolve(loader, shape));
            if (target == null || target.getReturnType() != boolean.class) {
                throw new NoSuchMethodException("shape mismatch: " + owner + "#" + name);
            }
            hook(target).setId(HOOK_PREFIX + id).intercept(new BooleanBlocker(id, value));
            ruleOk++;
            log(Log.INFO, TAG, "[" + BUILD + "] rule armed id=" + id + " -> " + target);
        } catch (Throwable error) {
            ruleFail++;
            lastFailure = id + ": " + error;
            log(Log.WARN, TAG, "[" + BUILD + "] rule unavailable id=" + id + " (" + error + ")");
        }
    }

    /**
     * 把形状里的类名字符串解析成 Class。
     * 注意：基本类型**不能**走 loadClass —— "boolean"/"long" 会抛 ClassNotFoundException，
     * 让规则被误判成 shape mismatch（本机实测踩过，日志里就是 `Didn't find class "boolean"`）。
     */
    private static Class<?>[] resolve(ClassLoader loader, String[] names) throws ClassNotFoundException {
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

    /**
     * 按形状找方法：只比参数类型数组。
     * 歧义（多个同形状候选）即失败——挑错的后果是行为异常，比不生效更糟。
     */
    private static Method findMethod(Class<?> owner, String name, Class<?>[] shape) {
        Method found = null;
        for (Method candidate : owner.getDeclaredMethods()) {
            if (!candidate.getName().equals(name)) continue;
            Class<?>[] actual = candidate.getParameterTypes();
            if (actual.length != shape.length) continue;
            boolean same = true;
            for (int i = 0; i < actual.length; i++) {
                if (!actual[i].equals(shape[i])) { same = false; break; }
            }
            if (!same) continue;
            if (found != null) return null;   // ambiguous -> refuse
            found = candidate;
        }
        return found;
    }

    private void scheduleLateSweeps(final Activity activity, final String[] names) {
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final long[] delays = {2000L, 6000L, 12000L};
        for (final long delay : delays) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    try { sweep(activity, names); }
                    catch (Throwable ignored) { }
                }
            }, delay);
        }
    }

    /** 按 id 名隐藏：只隐藏确实存在的那几个 id，找不到就什么都不做（fail-open）。 */
    private void sweep(Activity activity, String[] names) {
        android.content.res.Resources resources;
        String pkg;
        try {
            resources = activity.getResources();
            pkg = activity.getPackageName();
        } catch (Throwable ignored) { return; }
        for (String name : names) {
            try {
                int id = resources.getIdentifier(name, "id", pkg);
                if (id == 0) continue;
                View view = activity.findViewById(id);
                if (view == null || view.getVisibility() == View.GONE) continue;
                view.setVisibility(View.GONE);
                log(Log.INFO, TAG, "[" + BUILD + "] hid id/" + name);
            } catch (Throwable ignored) { }
        }
    }

    /** void 方法：直接不执行原实现（走到这里说明它就是闸门）。 */
    private final class Blocker implements XposedInterface.Hooker {
        private final String id;
        private final AtomicBoolean logged = new AtomicBoolean(false);
        Blocker(String id) { this.id = id; }
        @Override public Object intercept(XposedInterface.Chain chain) {
            if (logged.compareAndSet(false, true)) log(Log.INFO, TAG, "blocked id=" + id);
            return null;
        }
    }

    /** boolean 方法：常量返回用装箱缓存，零分配。 */
    private final class BooleanBlocker implements XposedInterface.Hooker {
        private final String id;
        private final boolean value;
        private final AtomicBoolean logged = new AtomicBoolean(false);
        BooleanBlocker(String id, boolean value) { this.id = id; this.value = value; }
        @Override public Object intercept(XposedInterface.Chain chain) {
            if (logged.compareAndSet(false, true)) log(Log.INFO, TAG, "blocked id=" + id);
            return value ? Boolean.TRUE : Boolean.FALSE;
        }
    }

    // ══════════════════════ 探针与上报 ══════════════════════

    /** 跑一个特性的安装动作，并把结果记成 off / matched / partial / miss。 */
    private void probe(String feature, boolean enabled, Runnable action) {
        ruleOk = 0;
        ruleFail = 0;
        lastFailure = null;
        if (enabled) {
            try { action.run(); }
            catch (Throwable error) {
                ruleFail++;
                lastFailure = error.toString();
                log(Log.WARN, TAG, "[" + BUILD + "] " + feature + " probe failed", error);
            }
        }
        reportDone++;
        String state;
        String detail;
        if (!enabled) {
            state = "off";
            detail = "";
        } else if (ruleOk == 0) {
            state = "miss";
            detail = lastFailure == null ? "no rule matched" : lastFailure;
        } else if (ruleFail > 0) {
            state = "partial";
            detail = ruleOk + "/" + (ruleOk + ruleFail) + " armed; " + lastFailure;
        } else {
            state = "matched";
            detail = ruleOk + " rule(s) armed";
        }
        states.put(feature, state);
        report("running", feature, state, detail);
        log(Log.INFO, TAG, "[" + BUILD + "] feature=" + feature + " result=" + state
                + " armed=" + ruleOk + " failed=" + ruleFail);
    }

    private String summary() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < Config.FEATURES.length; i++) {
            String key = Config.FEATURES[i];
            if (i > 0) builder.append(' ');
            String state = states.get(key);
            builder.append(key).append('=').append(state == null ? "?" : state);
        }
        return builder.toString();
    }

    /** 上报到设置页；失败不影响目标 App。 */
    private void report(String phase, String feature, String state, String detail) {
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
            // 收件人必须是**模块自己**的组件。这里用 Config.MODULE 而不是 reportContext.getPackageName()
            // ——后者在目标进程里返回的是目标 App 的包名，拼出来的组件不存在，广播会被静默丢弃。
            intent.setComponent(new ComponentName(Config.MODULE, Config.MODULE + ".StatusReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtras(extras);
            if (Build.VERSION.SDK_INT >= 34) {
                // API 34+ 不这样发就收不到：接收端会因身份不明丢弃
                Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
                reportContext.sendBroadcast(intent, null, options);
            } else {
                reportContext.sendBroadcast(intent);
            }
        } catch (Throwable error) { log(Log.WARN, TAG, "status report unavailable", error); }
    }

    private boolean read(SharedPreferences prefs, String key) {
        if (prefs == null) return Config.DEFAULT_ON;
        try { return prefs.getBoolean(key, Config.DEFAULT_ON); }
        catch (Throwable ignored) { return Config.DEFAULT_ON; }
    }
}
