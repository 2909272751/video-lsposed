package io.github.qqliveclean.apps.didi;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 滴滴去广告模块（LSPosed API 102 语义，但**不使用 PackageReadyParam.getApplication()**：
 * 实测本机 LSPosed 的 PackageReadyParam 没有该方法，会抛 NoSuchMethodError 且异常发生在日志之前 → 表现为"模块没加载"）。
 * Context 改为通过一次性 Application.attach 钩子获取（脚手架同款）。
 *
 * 规则（逐特性，互不牵连）：
 *   no_ads  —— 广告总闸：AdSdk.d(AdRequest) 恒返回 false（"广告 SDK 未就绪"），
 *              所有广告展示路径按"无广告"自然退出；AdSdk.f() 会因此返回 true（叫它继续）。
 *   popup   —— 弹窗/通知广告：AdSdk.g 不执行；AdSdk.h/i/j 返回 null（原实现本就允许返回 null）。
 *   splash  —— 开屏广告：QuickSplashShow.f（展示流程）与 c（接受资源）不执行。
 *   probes  —— 只读探针：只打 hit 日志、原样放行。
 *
 * 性能规范：只挂一次性决策闸门；拦截体内零反射、零分配；每个 id 只打一次日志（CAS）。
 */
public final class MainHook extends io.github.qqliveclean.RuleHost {

    public MainHook(io.github.libxposed.api.XposedModule host) {
        super(host);
    }
    private final AtomicBoolean installed = new AtomicBoolean(false);
    private final AtomicBoolean contextHooked = new AtomicBoolean(false);
    private final AtomicBoolean configured = new AtomicBoolean(false);
    private volatile String processName;
    private volatile String compatDetail;
    private volatile boolean compatOk;

    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try { processName = param.getProcessName(); } catch (Throwable ignored) {}
    }

    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        // 直写一行，证明回调进来了（不依赖任何静态状态；任何异常都在这一行之后）
        try { log(Log.INFO, Config.TAG, "[schema=" + Config.REPORT_SCHEMA + "] onPackageReady entered pkg=" + safePackage(param)); } catch (Throwable ignored) {}
        String pkg = null;
        try { pkg = param.getPackageName(); } catch (Throwable ignored) {}
        if (!Config.PACKAGE.equals(pkg)) return;
        String process = processName;
        if (process != null && !Config.PACKAGE.equals(process)) return;
        if (!installed.compareAndSet(false, true)) return;
        try {
            ClassLoader loader = param.getClassLoader();
            H.bind(this, null, "unknown", System.currentTimeMillis());
            logFrameworkInfo();
            install(loader);
            H.summary();
            compatScan(loader);
            installContextHook(loader);
        } catch (Throwable t) {
            try { log(Log.ERROR, Config.TAG, "[schema=" + Config.REPORT_SCHEMA + "] setup failed", t); } catch (Throwable ignored) {}
            H.summary();
        }
    }

    private static String safePackage(XposedModuleInterface.PackageReadyParam param) {
        try { return String.valueOf(param.getPackageName()); } catch (Throwable t) { return "?(" + t + ")"; }
    }

    /** 一次性诊断：框架名/版本/API + PackageReadyParam 实际提供的方法（本机差异就靠这个看出来）。 */
    private void logFrameworkInfo() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("framework=").append(getFrameworkName()).append(' ').append(getFrameworkVersion())
              .append(" api=").append(getApiVersion());
            H.info(sb.toString());
        } catch (Throwable t) {
            H.warn("framework info unavailable: " + t);
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (Method m : XposedModuleInterface.PackageReadyParam.class.getDeclaredMethods()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(m.getName());
            }
            H.info("PackageReadyParam methods: " + sb);
        } catch (Throwable t) {
            H.warn("PackageReadyParam introspection failed: " + t);
        }
    }

    /** 用一次性 Application.attach 钩子拿 Context（本机 PackageReadyParam 没有 getApplication）。 */
    private void installContextHook(final ClassLoader loader) {
        if (!contextHooked.compareAndSet(false, true)) return;
        try {
            final Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).setId("capture_context").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (configured.compareAndSet(false, true)) {
                        try {
                            Context context = (Context) chain.getArg(0);
                            Application application = (Application) chain.getThisObject();
                            H.attachContext(application != null ? application : context);
                            H.info("context captured: " + (context != null));
                            H.detectVersion(loader);
                        } catch (Throwable t) {
                            H.warn("context capture failed: " + t);
                        }
                    }
                    return result;
                }
            });
            H.installed("capture_context");
        } catch (Throwable t) {
            H.miss("capture_context", t.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- install

    private void install(ClassLoader loader) {
        SharedPreferences prefs = null;
        try { prefs = getRemotePreferences(Config.GROUP); } catch (Throwable t) { H.warn("getRemotePreferences failed: " + t); }
        H.info("config source=" + (prefs != null ? "remote_prefs" : "defaults"));
        H.setSource(prefs != null ? "remote_prefs（设置页写入）" : "defaults（未读到设置，用默认值）");

        final Class<?> adSdk = R.load(loader, "com.didi.ad.AdSdk");
        final Class<?> splashShow = R.load(loader, "com.didi.ad.splash.QuickSplashShow");
        final SharedPreferences prefsRef = prefs;

        feature("no_ads", prefsRef, new Installer() {
            @Override public void install() {
                gateBoolean(adSdk, "d", "no_ads", "AdSdk.d(AdRequest)->false");
            }
        });
        feature("popup", prefsRef, new Installer() {
            @Override public void install() {
                gateByName(adSdk, "g", "popup", "AdSdk.g");
                gateByName(adSdk, "h", "popup", "AdSdk.h");
                gateByName(adSdk, "i", "popup", "AdSdk.i");
                gateByName(adSdk, "j", "popup", "AdSdk.j");
                gateByName(adSdk, "k", "popup", "AdSdk.k");
            }
        });
        feature("splash", prefsRef, new Installer() {
            @Override public void install() {
                gateByName(splashShow, "f", "splash", "QuickSplashShow.f");
                gateByName(splashShow, "c", "splash", "QuickSplashShow.c");
            }
        });
        feature("block_dialogs", prefsRef, new Installer() {
            @Override public void install() {
                Class<?> dialogBase = R.load(loader, "com.didi.sdk.view.dialog.b");
                if (dialogBase == null) { H.miss("DidiDialogBase", "ClassNotFound"); return; }
                gateByName(dialogBase, "show", "block_dialogs", "DidiDialog.show");
            }
        });
        feature("probes", prefsRef, new Installer() {
            @Override public void install() {
                probe(loader, "com.didi.ad.splash.QuickSplashLoad", "probes");
                probe(loader, "com.didi.ad.base.net.HttpSender", "probes");
                probe(loader, "com.didi.ad.api.AdRequest", "probes");
            }
        });

        // ── 兜底：首页 Fragment 视图创建后，延迟再隐藏一遍（广告/营销位是数据到达后才挂上去的）──
        feature("hide_promo_card", prefsRef, new Installer() {
            @Override public void install() {
                installHideDeferred(loader, HOME_FRAGMENTS, "hide_promo_card",
                        new String[]{"v8_smart_card_container", "home_main_card_activity_image"});
            }
        });
        feature("hide_home_banner", prefsRef, new Installer() {
            @Override public void install() {
                installHideDeferred(loader, HOME_FRAGMENTS, "hide_home_banner",
                        new String[]{"home_banner_proxy_view", "ch_banner_casper_container",
                                "banner_parent_container", "banner_main_title",
                                "ch_home_banner_big_image_view", "ch_home_banner_vertical_small_image_view_v1",
                                "card_layout", "titleView", "subView", "recycler_view"});
            }
        });
        feature("hide_bottom_nav", prefsRef, new Installer() {
            @Override public void install() {
                installHideDeferred(loader, HOME_CONTAINERS, "hide_bottom_nav",
                        new String[]{"v6x_home_bottom_nav", "v6x_home_bottom", "v6x_home_bottom_blur", "shadow_view"});
            }
        });
        feature("keep_home_tabs", prefsRef, new Installer() {
            @Override public void install() {
                installKeepHomeTabs(loader);
            }
        });
        feature("hide_top_tabs", prefsRef, new Installer() {
            @Override public void install() {
                installHideDeferred(loader, HOME_CONTAINERS, "hide_top_tabs", new String[]{"tabLayout"});
            }
        });
        feature("hide_top_tools", prefsRef, new Installer() {
            @Override public void install() {
                installHideDeferred(loader, HOME_CONTAINERS, "hide_top_tools",
                        new String[]{"home_v8x_action_bar_riding_container", "riding_code", "scan", "ch_v8_scan_img"});
            }
        });
        feature("hide_scene_row", prefsRef, new Installer() {
            @Override public void install() {
                installHideDeferred(loader, HOME_FRAGMENTS, "hide_scene_row", new String[]{"ch_scene_layout"});
            }
        });
    }

    /**
     * 版本兼容自检：逐个探测锚点是否还在（换版本后 R8 会改方法名/类名）。
     * 结果写日志一行 + 上报设置页（"版本兼容自检：ok/总数"）；缺失项自动跳过，不影响其它功能。
     */
    private void compatScan(ClassLoader loader) {
        int ok = 0;
        StringBuilder missing = new StringBuilder();
        for (String[] anchor : Config.COMPAT_ANCHORS) {
            String className = anchor[0];
            String methodName = anchor.length > 1 ? anchor[1] : "";
            String shape = anchor.length > 2 ? anchor[2] : "";
            boolean found;
            Class<?> owner = R.load(loader, className);
            if (owner == null) {
                found = false;
            } else if (methodName == null || methodName.isEmpty()) {
                found = true; // 只查类
            } else {
                found = find(owner, methodName, shape) != null;
            }
            if (found) {
                ok++;
            } else {
                if (missing.length() > 0) missing.append(',');
                missing.append(shortName(className));
                if (methodName != null && !methodName.isEmpty()) missing.append('#').append(methodName);
            }
        }
        int total = Config.COMPAT_ANCHORS.length;
        compatOk = ok == total;
        compatDetail = "锚点 " + ok + "/" + total
                + (missing.length() == 0 ? "（本版本全部匹配）" : "（缺失: " + missing + "，已自动跳过）");
        H.info("event=compat_scan ok=" + ok + "/" + total + " missing=[" + missing + "]");
        // 此时可能还没拿到 Context（上报会被丢弃）→ 先存起来，等 Context 到手后在 detectVersion 里补报
        H.setCompat(compatOk, compatDetail);
    }

    /** 按形状找方法：shape="" 表示任意、其余见 Config.COMPAT_ANCHORS 注释。 */
    private static Method find(Class<?> owner, String name, String shape) {
        for (Method m : owner.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            if ("1".equals(shape) && p.length != 1) continue;
            if ("view".equals(shape)) {
                if (p.length < 2 || p.length > 3) continue;
                if (!android.view.View.class.isAssignableFrom(m.getReturnType())) continue;
                if (!android.view.LayoutInflater.class.isAssignableFrom(p[0])) continue;
                if (!android.view.ViewGroup.class.isAssignableFrom(p[1])) continue;
            }
            if ("list".equals(shape)) {
                if (p.length != 2 || !java.util.List.class.isAssignableFrom(p[1])) continue;
            }
            return m;
        }
        return null;
    }

    private static final String[] HOME_FRAGMENTS = {
            "com.didi.carhailing.framework.v8.home.V8HomeFragment",
            "com.didi.carhailing.framework.v8.home.V8xHomeFragment",
    };
    private static final String[] HOME_CONTAINERS = {
            "com.didi.carhailing.framework.v8.home.V8xHomeContainerFragment",
            "com.didi.carhailing.framework.common.app.HomeContainer",
    };

    /**
     * 底部导航栏逐项精简：在 BottomNavigationView 构建 tab **之前**过滤数据列表，
     * 只保留 home_page / user_center（宽度按过滤后的数量计算，不会出现"除以零/整条消失"）。
     */
    private void installKeepHomeTabs(ClassLoader loader) {
        Class<?> owner = R.load(loader, "com.didi.carhailing.framework.common.bottombar.bottom.widget.BottomNavigationView");
        if (owner == null) { H.miss("BottomNavigationView", "ClassNotFound"); return; }
        Method target = null;
        for (Method m : owner.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 2) continue;
            if (!java.util.List.class.isAssignableFrom(p[1])) continue;
            target = m;
            break;
        }
        if (target == null) { H.miss("BottomNavigationView.setItems", "NoShapeMatch"); return; }
        final String label = shortName(target.getDeclaringClass().getName()) + "." + target.getName() + "(List)";
        try { target.setAccessible(true); } catch (Throwable ignored) {}
        try {
            hook(target).setId("keep_home_tabs").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object arg = chain.getArg(1);
                        if (arg instanceof java.util.List) {
                            java.util.List list = (java.util.List) arg;
                            int removed = 0;
                            for (int i = list.size() - 1; i >= 0; i--) {
                                String id = itemId(list.get(i));
                                if (id != null && !"home_page".equals(id) && !"user_center".equals(id)) {
                                    list.remove(i);
                                    removed++;
                                }
                            }
                            if (removed > 0) H.hit("keep_home_tabs", label + " removed=" + removed);
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                }
            });
            H.installed("keep_home_tabs@" + label);
        } catch (Throwable t) {
            H.miss("keep_home_tabs", t.getClass().getSimpleName());
        }
    }

    /** 反射取 BottomNavItem.getId()（只在底部栏数据更新时调用，非热路径）。 */
    private static String itemId(Object item) {
        if (item == null) return null;
        try {
            Method getId = item.getClass().getMethod("getId");
            Object value = getId.invoke(item);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 界面简化闸门：在这些"创建视图"方法返回 root view 后按资源 id 隐藏，
     * 并在 1.5s / 4s 各补一次（营销位是数据到达后才挂上去的）。一次性延迟，不是轮询。
     */
    private void installHideDeferred(ClassLoader loader, String[] classNames, final String feature, final String[] idNames) {
        for (String className : classNames) {
            installHide(loader, className, null, feature, idNames, true);
        }
    }

    /** 通用"界面简化"闸门：按**形状**找"创建视图"方法（名字跨版本会变）。 */
    private void installHide(ClassLoader loader, String className, String methodHint, final String feature,
                             final String[] idNames, final boolean deferred) {
        Class<?> owner = R.load(loader, className);
        if (owner == null) { H.miss(className, "ClassNotFound"); return; }
        Method target = null;
        for (Method m : owner.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 2 || p.length > 3) continue;
            if (!android.view.View.class.isAssignableFrom(m.getReturnType())) continue;
            if (!android.view.LayoutInflater.class.isAssignableFrom(p[0])) continue;
            if (!android.view.ViewGroup.class.isAssignableFrom(p[1])) continue;
            if (methodHint != null && m.getName().equals(methodHint)) { target = m; break; }
            if (target == null) target = m;
        }
        if (target == null) { H.miss(className + "." + methodHint, "NoShapeMatch"); return; }
        final String label = shortName(className) + "." + target.getName() + "(" + target.getParameterTypes().length + ")";
        try { target.setAccessible(true); } catch (Throwable ignored) {}
        try {
            hook(target).setId("hide_" + feature + "_" + label).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        if (result instanceof android.view.View) {
                            final android.view.View root = (android.view.View) result;
                            final Runnable task = new Runnable() {
                                @Override public void run() {
                                    try {
                                        int hidden = hideIds(root, idNames);
                                        if (hidden > 0) H.hit(feature, label + " hide=" + hidden);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            };
                            task.run();
                            if (deferred) {
                                android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                                handler.postDelayed(task, 1500);
                                handler.postDelayed(task, 4000);
                                handler.postDelayed(task, 8000);
                                handler.postDelayed(task, 15000);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            H.installed("hide_" + feature + "@" + label);
        } catch (Throwable t) {
            H.miss("hide_" + feature + "@" + label, t.getClass().getSimpleName());
        }
    }

    /** 通用"界面简化"闸门：按**形状**找"创建视图"方法（名字跨版本会变），返回 root view 后按资源 id 一次性隐藏。 */
    private void installHide(ClassLoader loader, String className, String methodHint, final String feature, final String[] idNames) {
        Class<?> owner = R.load(loader, className);
        if (owner == null) { H.miss(className, "ClassNotFound"); return; }
        Method target = null;
        for (Method m : owner.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 2 || p.length > 3) continue;
            if (!android.view.View.class.isAssignableFrom(m.getReturnType())) continue;
            if (!android.view.LayoutInflater.class.isAssignableFrom(p[0])) continue;
            if (!android.view.ViewGroup.class.isAssignableFrom(p[1])) continue;
            if (methodHint != null && m.getName().equals(methodHint)) { target = m; break; }
            if (target == null) target = m;
        }
        if (target == null) { H.miss(className + "." + methodHint, "NoShapeMatch"); return; }
        final String label = shortName(className) + "." + target.getName() + "(" + target.getParameterTypes().length + ")";
        try { target.setAccessible(true); } catch (Throwable ignored) {}
        try {
            hook(target).setId("hide_" + feature + "_" + label).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        if (result instanceof android.view.View) {
                            int hidden = hideIds((android.view.View) result, idNames);
                            H.hit(feature, label + " hide=" + hidden);
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                }
            });
            H.installed("hide_" + feature + "@" + label);
        } catch (Throwable t) {
            H.miss("hide_" + feature + "@" + label, t.getClass().getSimpleName());
        }
    }

    /** 按资源 id 名隐藏；返回成功隐藏的个数。id 名解析结果缓存，不做全树遍历。 */
    private static int hideIds(android.view.View root, String[] idNames) {
        android.content.Context context = root.getContext();
        if (context == null) return 0;
        android.content.res.Resources res = context.getResources();
        String pkg = context.getPackageName();
        int hidden = 0;
        for (String name : idNames) {
            try {
                int id = res.getIdentifier(name, "id", pkg);
                if (id == 0) continue;
                android.view.View view = root.findViewById(id);
                if (view != null && view.getVisibility() != android.view.View.GONE) {
                    view.setVisibility(android.view.View.GONE);
                    hidden++;
                }
            } catch (Throwable ignored) {
            }
        }
        return hidden;
    }

    private interface Installer { void install(); }

    private void feature(String key, SharedPreferences prefs, Installer installer) {
        boolean enabled = Config.read(prefs, key, Config.defaultOf(key));
        int before = H.hooked();
        if (!enabled) {
            H.info("feature=" + key + " result=off");
            H.report("running", key, "off", "开关已关闭");
            H.row(key, "off", "开关已关闭");
            return;
        }
        String failure = null;
        try {
            installer.install();
        } catch (Throwable t) {
            failure = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        int hooked = H.hooked() - before;
        if (failure != null) {
            H.warn("feature=" + key + " result=miss reason=" + failure);
            H.report("running", key, "miss", failure);
            H.row(key, "miss", failure);
        } else if (hooked <= 0) {
            H.warn("feature=" + key + " result=miss reason=no anchor");
            H.report("running", key, "miss", "锚点未找到");
            H.row(key, "miss", "这个版本找不到锚点，已自动跳过");
        } else {
            H.info("feature=" + key + " result=matched hooks=" + hooked);
            H.report("running", key, "matched", "已装 " + hooked + " 条");
            H.row(key, "matched", "已装 " + hooked + " 条钩子");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Method find(Class<?> owner, String name, int paramCount) {
        if (owner == null) return null;
        Method found = null;
        for (Method m : owner.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterTypes().length != paramCount) continue;
            if (found != null) return null; // 歧义即失败
            found = m;
        }
        return found;
    }

    private void gateBoolean(Class<?> owner, String name, final String feature, final String label) {
        Method target = find(owner, name, 1);
        if (target == null) { H.miss(label, "NoSuchMethod"); return; }
        gateReturn(target, Boolean.FALSE, feature, label);
    }

    /** 按名字挂闸门（参数个数不限，跨版本稳）：void→跳过原实现；boolean→false；其它→null。 */
    private void gateByName(Class<?> owner, String name, final String feature, String label) {
        if (owner == null) { H.miss(label, "NoOwner"); return; }
        Method target = null;
        for (Method m : owner.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (target != null) { H.miss(label, "ambiguous"); return; }
            target = m;
        }
        if (target == null) { H.miss(label, "NoSuchMethod"); return; }
        Class<?> ret = target.getReturnType();
        Object value = ret == boolean.class || ret == Boolean.class ? Boolean.FALSE : null;
        gateReturn(target, value, feature, label + "/" + target.getParameterTypes().length);
    }

    private void gateReturn(final Method target, final Object value, final String feature, final String label) {
        try { target.setAccessible(true); } catch (Throwable ignored) {}
        try {
            hook(target).setId("gate_" + label).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    H.hit(feature, label);
                    return value;
                }
            });
            H.installed(label);
        } catch (Throwable t) {
            H.miss(label, t.getClass().getSimpleName());
        }
    }

    private void probe(ClassLoader loader, String className, final String feature) {
        Class<?> owner = R.load(loader, className);
        if (owner == null) { H.miss(className, "ClassNotFound"); return; }
        int before = H.hooked();
        for (Method m : owner.getDeclaredMethods()) {
            if (H.hooked() - before >= 8) break;
            if (!R.isInteresting(m)) continue;
            final String label = shortName(className) + "." + m.getName();
            try { m.setAccessible(true); } catch (Throwable ignored) {}
            try {
                hook(m).setId("probe_" + label).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        H.hit(feature, label);
                        return chain.proceed();
                    }
                });
                H.installed(label);
            } catch (Throwable t) {
                H.miss(label, t.getClass().getSimpleName());
            }
        }
        if (H.hooked() == before) H.miss(className, "noInterestingMethod");
    }

    private static String shortName(String className) {
        int i = className.lastIndexOf('.');
        return i < 0 ? className : className.substring(i + 1);
    }
}
