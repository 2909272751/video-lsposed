package io.github.qqliveclean;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** iQIYI: one splash request gate and one five-button navigation view pass. */
final class IqiyiRules {
    private static final AtomicBoolean SPLASH_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean TABS_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean HOME_TOP_AD_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean HOME_BANNER_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean SLOT_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean AD_LOADED = new AtomicBoolean(false);
    /** 每个类名只挂一次，避免重复挂钩。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> AD_HOOKED =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
    private static final AtomicBoolean PROBE_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean PUMA_AD_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean PUMA_AD_ONCE = new AtomicBoolean(false);
    private static final AtomicBoolean AD_REQUEST_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean AD_DATA_HIT = new AtomicBoolean(false);
    /** 开屏实现类按类名去重，避免 getter 每次返回都重复挂钩。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> SPLASH_ARMED =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
    private static WeakReference<ViewGroup> lastBar = new WeakReference<ViewGroup>(null);
    private static WeakReference<View> lastTopCard = new WeakReference<View>(null);
    private static WeakReference<View> lastMemberBanner = new WeakReference<View>(null);

    private IqiyiRules() {}

    static void install(MainHook module, ClassLoader loader, Config.Settings settings) {
        if (settings.iqiyiBlockSplash) {
            installSplash(module, loader);
            installSplashSdkGate(module, loader);
        } else {
            H.skipped("iqiyi_splash", "disabled in settings");
            H.skipped("iqiyi_splash_sdk", "disabled in settings");
        }
        installHomeUi(module, loader, settings);
        if (settings.iqiyiBlockPlayerAds) {
                        // Only the AdsClient request gate stays armed. Everything that used to sit
                        // here was observed at 0 hits on 17.9.2 while the pre-roll was demonstrably
                        // on screen (Disney "13 秒后可跳过", a wallpaper-app download page, and
                        // 11/25/57/103/107s countdowns all captured with these rules active), so
                        // they are reported as withdrawn rather than as working gates: a row that
                        // says "matched" must not be read as "ads are blocked".
                        installAdRequestGate(module, loader);
        } else {
            H.skipped("iqiyi_ad_request", "disabled in settings");
        }
        // Re-armed on 17.9.5. These three were withdrawn on 17.9.2 because their hooks never
        // executed while ads were demonstrably on screen. That is a statement about 17.9.2,
        // and the device now runs 17.9.5, so "never called on the old build" is not a
        // prediction about this one. checkRegister is the highest-value anchor left in Java:
        // it is the bitmask gate that decides whether a slot registers at all
        // (C_SLOT_TYPE_PRE_ROLL=2 ... C_SLOT_TYPE_ALL=0xFFFFF), so zeroing it would stop the
        // native Cupid SDK from ever being handed the slot, upstream of libgdtqjs.so.
        // Nothing here claims it will work - a miss must be able to mean "still dead".
        installPlayerAds(module, loader);
        installAdPolicyFields(module, loader, 0);
        H.info("iqiyi_rearm: player-ad anchors re-armed; previously withdrawn on "
                + Config.VERIFIED_IQIYI_VERSION_NAME);
    }

    /**
     * 播放页广告闸门。
     *
     * <p>两层，都挂在播放器 SDK 的广告控制器上：
     * <ol>
     *   <li>{@code QYPlayerADConfig.checkRegister(int,int)}——广告位注册闸门。播放器把广告位定义成
     *       位掩码（{@code C_SLOT_TYPE_PRE_ROLL=2}…{@code C_SLOT_TYPE_ALL=0xFFFFF}），这个方法决定
     *       某次播放是否注册该广告位。</li>
     *   <li>{@code installAdReadyGate}——数据就绪闸门，见该方法注释。</li>
     * </ol>
     *
     * <p>两层分开报告，是为了在 App 改版后仍能分辨是哪一层失效，而不是只看到一个笼统的 miss。
     *
     * <p>不拦 {@code C_SLOT_TYPE_PAGE}：页面级广告由首页/个人中心规则处理，拦在这里会连带影响
     * 播放器自身的埋点与返回行为。
     */
    private static void installPlayerAds(MainHook module, ClassLoader loader) {
        final String rule = "iqiyi_player_ads";
        try {
            Class<?> config = R.load(loader, "com.iqiyi.video.qyplayersdk.model.QYPlayerADConfig");
            Method check = R.find(config, "checkRegister", boolean.class, int.class, int.class);
            if (check == null) {
                H.miss(rule, "QYPlayerADConfig.checkRegister(II)Z not found; boolean(): "
                        + R.describeShapes(config, boolean.class));
                return;
            }
            // 读取一次常量，插桩体里只做整数比较。反射只发生在安装期。
            final int blocked = blockedSlots(config);
            module.hook(check).setId("iqiyi_player_ad_slot_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    int requested = ((Integer) chain.getArg(0)).intValue();
                    if (blocked != 0 && (requested & blocked) != 0) {
                        H.hit(rule, "ad slot mask suppressed: 0x" + Integer.toHexString(requested), SLOT_HIT);
                        return Boolean.FALSE;
                    }
                    return chain.proceed();
                }
            });
            H.hooked(rule, "QYPlayerADConfig.checkRegister gate; blocked slots mask=0x"
                    + Integer.toHexString(blocked));
            installAdPolicyFields(module, loader, blocked);
        } catch (Throwable error) {
            H.miss(rule, H.describe(error));
        }
        installAdReadyGate(module, loader);
    }

    /**
     * 盯住广告真正落地的播放器层。
     *
     * <p>前面的闸门全部落在播放器 SDK 的「决策方法」上，但真机上那些方法一次都没被调用；
     * 而 {@code PumaPlayer} 是广告视频实际播放的 Java 封装，它有一对**只属于广告**的回调：
     * {@code OnAdPrepared()}（广告准备完成，即将上屏）和 {@code OnAdCallback(int, String)}。
     * 这两个方法的签名就说明「这是广告」，所以在这里既不需要猜广告位，也不需要认 App 的类型标记。
     *
     * <p>先只做探测：确认这两个回调在有广告的样本上真的会触发。触发后就能确定广告播放链路
     * 确实经过 Java 层，那么从「广告已就绪」这一刻关掉它就是可行且精确的拦截点。
     */
    /**
     * 把广告相关的真实运行时方法签名落进报告。
     *
     * <p>存在的理由：17.9.2 上凭静态分析挑的每一个锚点都是错的，而反编译出的签名又和运行时
     * 对得上——靠再猜一次没有意义。把真机上的形状读出来，才能一眼看出哪个方法才是活的入口，
     * 也让下一次改版后的重新定位有据可依。
     */
    /**
     * 广告请求闸门，挂在 {@code com.mcto.ads.AdsClient} 上。
     *
     * <p>这三个方法不是猜的：先在真机上把该类的运行时方法签名落进报告
     * （见 {@code iqiyi_ad_api2}），再照着真实形状挂。17.9.2 上更「显眼」的播放器 SDK 入口
     * （{@code IAdInvoker.updateCupidAd}、{@code AdsController.onAdDataSourceReady}、
     * {@code QYPlayerADConfig.checkRegister/getDefault}、{@code PumaPlayer.OnAdPrepared}）
     * 实测在有广告的样本上一次都没被调用，继续在那上面加规则没有意义。
     *
     * <p>拦截方式：不发起请求。没有请求就没有广告数据，播放器也就无从拿到广告——
     * 这比事后过滤广告列表更靠前，也不需要识别「哪条是广告」。
     *
     * <p>用形状匹配而不是按参数类型找：第三个参数是混淆的内部类（{@code r91.o} / {@code r91.q}），
     * 混淆后就会变，用 {@code null} 当通配符可以避开这种依赖。
     */
    private static void installAdRequestGate(MainHook module, ClassLoader loader) {
        final String rule = "iqiyi_ad_request";
        try {
            Class<?> client = R.load(loader, "com.mcto.ads.AdsClient");
            StringBuilder armed = new StringBuilder();
            Method two = R.findByShape(client, new String[] { "requestAd" }, void.class,
                    int.class, java.util.Map.class);
            Method three = R.findByShape(client, new String[] { "requestAd" }, void.class,
                    int.class, java.util.Map.class, null);
            if (two != null) {
                module.hook(two).setId("iqiyi_ads_request2").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        H.hit(rule, "ad request dropped (2-arg)", AD_REQUEST_HIT);
                        return null;
                    }
                });
                armed.append("requestAd(2); ");
            }
            if (three != null) {
                module.hook(three).setId("iqiyi_ads_request3").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        H.hit(rule, "ad request dropped (3-arg)", AD_REQUEST_HIT);
                        return null;
                    }
                });
                armed.append("requestAd(3); ");
            }
            // Observation only: proves the gate is upstream of the ad data arriving at the app.
            Method arrived = R.findByShape(client, new String[] { "onRequestMobileServerSucceededWithAdData" },
                    int.class, String.class, String.class, String.class);
            if (arrived != null) {
                module.hook(arrived).setId("iqiyi_ads_data_probe").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        H.hit("iqiyi_ad_data", "ad data reached AdsClient", AD_DATA_HIT);
                        return chain.proceed();
                    }
                });
                armed.append("onRequestMobileServerSucceededWithAdData(probe); ");
            }
            if (armed.length() == 0) {
                H.miss(rule, "no requestAd overload found on AdsClient; requestAd-ish: "
                        + signature(client, "requestAd"));
                return;
            }
            H.hooked(rule, "AdsClient ad request blocked: " + armed);
            // Decisive reachability probe. logcat prints "Debug:OnMctoPlayerCallback,..." while the
            // ad plays, so that method demonstrably runs. Four classes declare it, so every
            // candidate is hooked: whichever one reports a hit is the class that actually
            // executes, and that is the only place a gate can be attached. If none report, hooks
            // into this whole ad path are inert and the ad cannot be gated from Java.
            // The obfuscated prefix moves every release (fw0.a0 on 17.9.2, nw0.a0 on 17.9.5), so a
            // candidate that no longer exists must only be reported, never abort the loop: doing so
            // used to escape to the outer catch and mark the armed request gate itself as a miss.
            String[] candidates = {
                "com.mcto.player.mctoplayer.PumaPlayer",
                "com.mcto.player.mctoplayer.IMctoPlayerHandler",
                "com.mcto.player.nativemediaplayer.MediaPlayerHandlerFunctionID",
                "nw0.a0",
                "fw0.a0",
            };
            StringBuilder probeReport = new StringBuilder();
            for (String owner : candidates) {
                Class<?> host;
                try {
                    host = R.load(loader, owner);
                } catch (Throwable missing) {
                    if (probeReport.length() > 0) probeReport.append(", ");
                    probeReport.append(owner).append("(absent)");
                    continue;
                }
                Method reach = R.findByShape(host, new String[] { "OnMctoPlayerCallback" },
                        void.class, int.class, String.class);
                // An interface method has no body, so there is nothing to intercept: libxposed
                // rejects it with IllegalArgumentException, which would abort the rest of this
                // install and mislabel the request gate as a miss even though it is armed.
                if (reach == null || java.lang.reflect.Modifier.isAbstract(reach.getModifiers())) continue;
                final String who = owner;
                try {
                    module.hook(reach).setId("iqiyi_mcto_reach_" + who).intercept(new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            H.hit("iqiyi_mcto_reach", "live: " + who, AD_REQUEST_HIT);
                            return chain.proceed();
                        }
                    });
                    if (probeReport.length() > 0) probeReport.append(", ");
                    probeReport.append(who).append("(armed)");
                } catch (Throwable ignored) {
                    // A probe that cannot be armed must never change the state of the real gate.
                    if (probeReport.length() > 0) probeReport.append(", ");
                    probeReport.append(who).append("(not armable)");
                }
            }
            H.hooked("iqiyi_ad_reach", "OnMctoPlayerCallback probes: " + probeReport);
        } catch (Throwable error) {
            H.miss(rule, H.describe(error));
        }
    }

    /**
     * 开屏广告的第二层闸门：拦在联盟广告 SDK 的「取开屏素材」入口上。
     *
     * <p>17.9.5 实测 {@code ISplashScreenApi.requestAdAndDownload()V} 已经不是开屏广告的请求点了
     * （挂上后 0 次命中，广告照常出现）。真正的链路是聚合 SDK：{@code ya1.i.loadSplashAd(ya1.p, ya1.h)}
     * 这一对方法在 {@code com.mcto.unionsdk} 里被 Pangle/穿山甲、GDT 等渠道实现，请求打进去才会拿到
     * 开屏素材。把它变成空实现，就是「不取素材」，应用随后只能走自己的默认开屏。
     *
     * <p>接口方法没有方法体，不能挂；所以逐个挂实现类，并且把「挂了哪些、哪些不存在」写进报告——
     * 这一层要靠命中行来判定有效，没有命中就说明它和上一���样属于失效锚点。
     */
    private static void installSplashSdkGate(MainHook module, ClassLoader loader) {
        final String rule = "iqiyi_splash_sdk";
        try {
            Class<?> slot = R.load(loader, "ya1.p");
            Class<?> listener = R.load(loader, "ya1.h");
            String[] hosts = {
                "ya1.j",
                "ab1.l",
                "bb1.a",
                "cb1.d",
                "com.mcto.unionsdk.PangleAdapter.PangleNativeAdapter",
            };
            StringBuilder report = new StringBuilder();
            int armed = 0;
            for (String owner : hosts) {
                Class<?> host;
                try {
                    host = R.load(loader, owner);
                } catch (Throwable missing) {
                    if (report.length() > 0) report.append(", ");
                    report.append(owner).append("(absent)");
                    continue;
                }
                Method load = R.findByShape(host, new String[] { "loadSplashAd" },
                        void.class, slot, listener);
                if (load == null || java.lang.reflect.Modifier.isAbstract(load.getModifiers())) {
                    if (report.length() > 0) report.append(", ");
                    report.append(owner).append("(no concrete loadSplashAd)");
                    continue;
                }
                module.hook(load).setId("youku_like_" + owner).intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        H.hit(rule, "splash material request dropped on " + owner, SPLASH_HIT);
                        return null;
                    }
                });
                armed++;
                if (report.length() > 0) report.append(", ");
                report.append(owner).append("(armed)");
            }
            if (armed == 0) {
                H.miss(rule, "no loadSplashAd implementation armed: " + report);
                return;
            }
            H.hooked(rule, "union SDK splash requests dropped: " + report);
        } catch (Throwable error) {
            H.miss(rule, H.describe(error));
        }
    }

    /** 列出该类里名字含 needle 的方法及其参数个数，用于一次性看清真实 API 形状。 */
    private static String signature(Class<?> owner, String needle) {
        if (owner == null) {
            return "(class not found)";
        }
        StringBuilder out = new StringBuilder();
        int shown = 0;
        try {
            for (Method m : owner.getDeclaredMethods()) {
                if (!m.getName().toLowerCase().contains(needle.toLowerCase())) continue;
                if (shown >= 14) break;
                if (out.length() > 0) out.append(", ");
                out.append(m.getName()).append('/').append(m.getParameterTypes().length);
                shown++;
            }
        } catch (Throwable ignored) {
            return "?";
        }
        return out.length() == 0 ? "(none)" : out.toString();
    }
    /**
     * 直接把播放器广告配置上的策略字段清零。
     *
     * <p>为什么必须走到字段：{@code checkRegister} 只在播放器注册广告位时调用一次，命中与否
     * 取决于 App 何时构造这个对象；而 {@code mAddAdPolicy} / {@code mRemoveAdPolicy} 是播放器
     * 每次决定「这条广告要不要上屏」时都会读的掩码。把它们清零等于在真正的决策点上生效，
     * 且不依赖任何一次方法调用是否刚好发生。
     *
     * <p>做法：在 {@code getDefault()} 之后跟一次单遍清理。字段只在配置对象构造期被写入，
     * 构造完成后是稳定的，所以清理一次即可；再配合 {@code checkRegister} 闸门覆盖运行期注册。
     */
    private static void installAdPolicyFields(MainHook module, ClassLoader loader, final int blocked) {
        final String rule = "iqiyi_ad_policy";
        try {
            Class<?> config = R.load(loader, "com.iqiyi.video.qyplayersdk.model.QYPlayerADConfig");
            Method getDefault = R.find(config, "getDefault", config);
            if (getDefault == null) {
                H.miss(rule, "QYPlayerADConfig.getDefault() not found");
                return;
            }
            module.hook(getDefault).setId("iqiyi_ad_policy_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        if (result instanceof Config) {
                            int cleared = 0;
                            cleared += zero(Config.class, (Config) result, "mAddAdPolicy");
                            cleared += zero(Config.class, (Config) result, "mRemoveAdPolicy");
                            cleared += zero(Config.class, (Config) result, "mAddAdUiPolicy");
                            cleared += zero(Config.class, (Config) result, "mRemoveAdUiPolicy");
                            cleared += zero(Config.class, (Config) result, "mAdButtonShowPolicy");
                            if (cleared > 0) {
                                H.hit(rule, "ad policy fields cleared: " + cleared
                                        + " (blocked slots 0x" + Integer.toHexString(blocked) + ")", SLOT_HIT);
                            }
                        }
                    } catch (Throwable ignored) {
                        // never break config creation
                    }
                    return result;
                }
            });
            H.hooked(rule, "QYPlayerADConfig ad policy fields zeroed on getDefault()");
        } catch (Throwable error) {
            H.miss(rule, H.describe(error));
        }
    }

    /** 把一个 int 字段清零，返回 1 表示字段存在且已处理。字段缺失直接跳过，不让整条规则失败。 */
    private static int zero(Class<?> owner, Object target, String field) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            f.setInt(target, 0);
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 真正的前贴广告决策点。
     *
     * <p>{@code nx0.a} 是播放器 SDK 的 {@code IAdInvoker} 实现，
     * {@code updateCupidAd(QYAdDataSource)} 把广告数据推进广告控制器，
     * 控制器再回调 {@code AdsController.onAdDataSourceReady}——这条调用链由 App 自己的异常堆栈证实。
     * 拦在 {@code updateCupidAd} 更上游：广告数据根本进不了播放器。
     *
     * <p>{@code getAdCountDown()} 返回播放前横幅上的秒数（截图里的 103 / 107 就是它）。
     * 保留为兜底：万一广告仍被展示，至少倒计时为 0 不会被困住。
     */
    private static void installAdReadyGate(MainHook module, ClassLoader loader) {
        final String rule = "iqiyi_player_ads";
        try {
            Class<?> dataSource = R.load(loader, "com.iqiyi.video.qyplayersdk.cupid.QYAdDataSource");
            StringBuilder attached = new StringBuilder();

            Class<?> invoker = R.load(loader, "nx0.a");
            Method update = R.find(invoker, "updateCupidAd", void.class, dataSource);
            if (update == null) {
                H.miss(rule, "nx0.a.updateCupidAd(" + dataSource.getSimpleName() + ")V not found; void(): "
                        + R.describeShapes(invoker, void.class));
                return;
            }
            module.hook(update).setId("iqiyi_cupid_update_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "ad data blocked before reaching the player", SLOT_HIT);
                    return null;      // never call through: the player never receives ad data
                }
            });
            attached.append("IAdInvoker.updateCupidAd suppressed; ");

            Class<?> controller = R.load(loader, "com.iqiyi.video.qyplayersdk.cupid.AdsController");
            // Liveness probe. getCurrentPosition() is polled by the progress UI on every tick
            // while a video plays, so it is the one method on this class that is certain to be
            // on the path. If it never reports a hit, Java hooks on the player SDK are not
            // landing on the Class that runs; if it does, the ad anchors above were simply the
            // wrong methods and need re-derivation from real evidence.
            Method candidate = R.find(invoker, "getCurrentPosition", long.class);
            if (candidate == null) candidate = R.find(invoker, "getDuration", long.class);
            if (candidate != null) {
                final String probeName = candidate.getName();
                // Report the resolved Class and its loader identity. If the hook never fires while
                // the same-named method is demonstrably being called, the only remaining
                // explanation is that this Class object is not the one the app executes: a
                // child loader holding its own copy of nx0.a. The identity strings make that
                // directly readable from the report instead of requiring more guesswork.
                H.hooked("iqiyi_player_sdk_probe", probeName + " declared on "
                        + candidate.getDeclaringClass().getName() + " @ "
                        + invoker.getClassLoader()
                        + " classIdentity=" + System.identityHashCode(invoker)
                        + " / " + System.identityHashCode(candidate.getDeclaringClass()));
                module.hook(candidate).setId("iqiyi_player_sdk_probe").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        H.hit("iqiyi_player_sdk_probe", probeName + " is live", SLOT_HIT);
                        return chain.proceed();
                    }
                });
            } else {
                H.miss("iqiyi_player_sdk_probe", "no always-called IAdInvoker method found");
            }
            Method ready = R.find(controller, "onAdDataSourceReady", void.class, dataSource);
            if (ready != null) {
                module.hook(ready).setId("iqiyi_ad_ready_gate").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        H.hit(rule, "ad data suppressed before playback", SLOT_HIT);
                        return null;
                    }
                });
                attached.append("AdsController.onAdDataSourceReady suppressed; ");
            }
            Method countdown = R.find(controller, "getAdCountDown", int.class);
            if (countdown != null) {
                module.hook(countdown).setId("iqiyi_ad_countdown_gate").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        return Integer.valueOf(0);
                    }
                });
                attached.append("getAdCountDown->0");
            }
            H.hooked(rule, attached.toString());
        } catch (Throwable error) {
            H.miss(rule, H.describe(error));
        }
    }

        /**
     * 汇总要屏蔽的广告位掩码。逐个读常量而不是写死数字，混淆改名后仍能对上。
     * 读不到的常量直接跳过，不会让整条规则失效。
     */
    private static int blockedSlots(Class<?> config) {
        int mask = 0;
        String[] names = {
            "C_SLOT_TYPE_PRE_ROLL", "C_SLOT_TYPE_MID_ROLL", "C_SLOT_TYPE_POST_ROLL",
            "C_SLOT_TYPE_BRIEF_ROLL", "C_SLOT_TYPE_OVERLAY", "C_SLOT_TYPE_COMMON_OVERLAY",
            "C_SLOT_TYPE_COMMON_OVERLAY_INNER", "C_SLOT_TYPE_MARK", "C_SLOT_TYPE_CORNER",
            "C_SLOT_TYPE_WHOLECORNER", "C_SLOT_TYPE_PAUSE", "C_SLOT_TYPE_TOOLBAR",
            "C_SLOT_TYPE_CACHE_BANNER",
        };
        for (String name : names) {
            try {
                Field field = config.getField(name);
                mask |= field.getInt(null);
            } catch (Throwable ignored) {
                // 该常量在这个版本里不存在，跳过即可
            }
        }
        return mask;
    }

    /**
     * 开屏广告闸门。
     *
     * <p>实现类名每次发版都会漂：17.9.2 是 {@code lz1.v}，17.9.5 变成了 {@code x02.v}，硬编码的
     * 候选名单必然过期。所以这里改成运行时发现——{@code ModuleFetcher.getSplashScreenModule()}
     * 属于插件框架的公开入口，类名方法名都不混淆，它返回的对象就是 {@code ISplashScreenApi}
     * 的实现；在它上面再挂 {@code requestAdAndDownload()V}，等于每次发版自动跟上。
     *
     * <p>候选名单只作为兜底（万一 getter 也被混淆），并且 miss 时会把「存在但没实现该接口」的
     * 候选列出来，让下一轮能直接看到该换成哪个类，而不用再猜。
     */
    private static void installSplash(MainHook module, ClassLoader loader) {
        final String rule = "iqiyi_splash";
        try {
            Class<?> api = R.load(loader, "org.qiyi.video.module.api.ISplashScreenApi");
            Method getter = null;
            try {
                getter = R.find(R.load(loader, "org.qiyi.android.plugin.mm.ModuleFetcher"),
                        "getSplashScreenModule", api);
            } catch (ClassNotFoundException ignored) {
                // 插件框架类不存在时退回候选名单
            }
            if (getter != null) {
                module.hook(getter).setId("iqiyi_splash_module").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object implementation = chain.proceed();
                        if (implementation != null) {
                            armSplashRequestGate(module, api, implementation);
                        }
                        return implementation;
                    }
                });
                H.hooked("iqiyi_splash_getter",
                        "ModuleFetcher.getSplashScreenModule() observed; arms the gate on whatever class it returns");
            }
            // Both paths run. The candidates arm the gate immediately, which is what keeps the
            // ad blocked on the version we just shipped; the getter is what keeps it working
            // after the next rename. Returning early once the getter was found would leave the
            // candidates untried - and on 17.9.5 the getter is never called during start-up,
            // so the ad played even though the rule reported "hooked".
            StringBuilder rejected = new StringBuilder();
            boolean armedFromCandidate = false;
            for (String candidate : new String[]{"x02.v", "lz1.v", "ry1.h"}) {
                Class<?> found;
                try {
                    found = R.load(loader, candidate);
                } catch (ClassNotFoundException missing) {
                    continue;
                }
                if (!api.isAssignableFrom(found)) {
                    rejected.append(rejected.length() > 0 ? ", " : "").append(candidate)
                            .append("(not ISplashScreenApi)");
                    continue;
                }
                Method method = R.find(found, "requestAdAndDownload", void.class);
                if (method == null) {
                    rejected.append(rejected.length() > 0 ? ", " : "").append(candidate)
                            .append("(no requestAdAndDownload)");
                    continue;
                }
                armSplashRequestGate(module, api, found);
                armedFromCandidate = true;
                break;
            }
            if (armedFromCandidate) return;
            if (getter != null) {
                // Nothing to arm yet; the gate will be armed the first time the app asks for the
                // module. Report it as hooked rather than missed, because the hook is in place.
                return;
            }
            H.miss(rule, "no ISplashScreenApi implementation reachable (getter missing, candidates: "
                    + (rejected.length() == 0 ? "(none of x02.v/lz1.v/ry1.h exist)" : rejected) + ")");
        } catch (Throwable error) {
            H.miss(rule, H.describe(error));
        }
    }

    /** 在开屏实现类上挂 requestAdAndDownload 闸门；同一实现类只挂一次。 */
    private static void armSplashRequestGate(MainHook module, Class<?> api, Object implementation) {
        final String rule = "iqiyi_splash";
        Class<?> owner = implementation instanceof Class ? (Class<?>) implementation : implementation.getClass();
        if (!api.isAssignableFrom(owner)) return;
        if (SPLASH_ARMED.putIfAbsent(owner.getName(), Boolean.TRUE) != null) return;
        try {
            Method request = R.find(owner, "requestAdAndDownload", void.class);
            if (request == null) {
                H.miss(rule, owner.getName() + " implements ISplashScreenApi but has no requestAdAndDownload()V");
                return;
            }
            module.hook(request).setId("iqiyi_splash_request").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "requestAdAndDownload suppressed on " + owner.getName(), SPLASH_HIT);
                    return null;
                }
            });
            H.hooked(rule, owner.getName() + ".requestAdAndDownload()V suppressed");
        } catch (Throwable error) {
            H.miss(rule, owner.getName() + ": " + H.describe(error));
        }
    }

    /**
     * 17.9.2 inlines t.b(List) and t.a(int,String), so direct hooks never execute.
     * The actual bar is a five-child CropTopLinearLayout. Hide only its identified
     * children after the Activity resumes; route objects and pager indices stay intact.
     */
    private static void installHomeUi(MainHook module, ClassLoader loader,
                                    final Config.Settings settings) {
        final String rule = "iqiyi_tab_filter";
        final boolean filtering = !settings.iqiyiShowFree || !settings.iqiyiShowPlus
                || !settings.iqiyiShowMember;
        if (!filtering && !settings.iqiyiHideHomeTopAd) {
            H.skipped(rule, "all tabs visible");
            H.skipped("iqiyi_home_top_ad", "disabled in settings");
            return;
        }
        try {
            Class<?> instrumentation = Class.forName("android.app.Instrumentation", false, loader);
            Method resume = R.find(instrumentation, "callActivityOnResume", void.class, Activity.class);
            if (resume == null) {
                if (filtering) H.miss(rule, "Instrumentation.callActivityOnResume unavailable");
                if (settings.iqiyiHideHomeTopAd) H.miss("iqiyi_home_top_ad", "Activity resume unavailable");
                return;
            }
            module.hook(resume).setId("iqiyi_bottom_tabs_view").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    final Activity activity = (Activity) chain.getArg(0);
                    if (activity != null) {
                        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                        handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (filtering) filterBar(activity, settings);
                                if (settings.iqiyiHideHomeTopAd) {
                                    hideHomeTopAd(activity);
                                    hideHomeMemberBanner(activity);
                                }
                            }
                        }, 500);
                        if (settings.iqiyiHideHomeTopAd) handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                hideHomeTopAd(activity);
                                hideHomeMemberBanner(activity);
                            }
                        }, 3000);
                        if (settings.iqiyiHideHomeTopAd) handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                hideHomeTopAd(activity);
                                hideHomeMemberBanner(activity);
                            }
                        }, 5000);
                        if (settings.iqiyiHideHomeTopAd) handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                hideHomeTopAd(activity);
                                hideHomeMemberBanner(activity);
                            }
                        }, 10000);
                    }
                    return result;
                }
            });
            if (filtering) H.hooked(rule, "five-button navigation view; runs after Activity resume");
            else H.skipped(rule, "all tabs visible");
            if (settings.iqiyiHideHomeTopAd) H.hooked("iqiyi_home_top_ad", "first home video carousel collapse");
            else H.skipped("iqiyi_home_top_ad", "disabled in settings");
            if (settings.iqiyiHideHomeTopAd) installMemberBannerAttach(module, loader);
            else H.skipped("iqiyi_home_member_banner", "disabled in settings");
        } catch (Throwable error) {
            if (filtering) H.miss(rule, H.describe(error));
            if (settings.iqiyiHideHomeTopAd) H.miss("iqiyi_home_top_ad", H.describe(error));
            if (settings.iqiyiHideHomeTopAd) H.miss("iqiyi_home_member_banner", H.describe(error));
        }
    }

    /** The rotating membership promotion is a separate, narrow first row above the feed. */
    private static void installMemberBannerAttach(MainHook module, ClassLoader loader) {
        final String rule = "iqiyi_home_member_banner";
        try {
            Class<?> pager = R.load(loader, "org.qiyi.basecore.widget.ultraviewpager.UltraViewPager");
            Method attached = pager.getDeclaredMethod("onAttachedToWindow");
            attached.setAccessible(true);
            module.hook(attached).setId("iqiyi_home_member_banner_attach")
                    .intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object owner = chain.getThisObject();
                    if (owner instanceof View && "org.qiyi.basecard.common.widget.row.UltraViewPagerRow"
                            .equals(owner.getClass().getName())) {
                        final View view = (View) owner;
                        collapseHomeMemberBanner(view);
                        view.postDelayed(new Runnable() {
                            @Override public void run() { collapseHomeMemberBanner(view); }
                        }, 300);
                        view.postDelayed(new Runnable() {
                            @Override public void run() { collapseHomeMemberBanner(view); }
                        }, 3000);
                    }
                    return result;
                }
            });
            H.hooked(rule, "identified home feed row collapsed when its native pager attaches");
        } catch (Throwable error) {
            // The two bounded Activity-resume passes above remain a useful fallback.
            H.hooked(rule, "Activity-resume fallback; attach hook unavailable: " + H.describe(error));
        }
    }

    private static void hideHomeMemberBanner(Activity activity) {
        try {
            int labelId = activity.getResources().getIdentifier("meta0", "id", activity.getPackageName());
            if (labelId != 0) {
                View label = activity.findViewById(labelId);
                if (label != null) collapseHomeMemberBanner(label);
            }
        } catch (Throwable error) {
            H.warn("event=iqiyi_home_member_banner_runtime_error " + H.describe(error));
        }
    }

    private static void collapseHomeMemberBanner(View descendant) {
        try {
            int labelId = descendant.getResources().getIdentifier(
                    "meta0", "id", descendant.getContext().getPackageName());
            int buttonId = descendant.getResources().getIdentifier(
                    "button0", "id", descendant.getContext().getPackageName());
            if (labelId == 0 || buttonId == 0) return;
            View item = findHomeFeedRow(descendant);
            if (!(item instanceof ViewGroup) || lastMemberBanner.get() == item) return;
            ViewGroup banner = (ViewGroup) item;
            int screenHeight = item.getResources().getDisplayMetrics().heightPixels;
            int screenWidth = item.getResources().getDisplayMetrics().widthPixels;
            if (!"org.qiyi.basecard.common.widget.row.LinearLayoutRow"
                    .equals(item.getClass().getName())
                    || item.getHeight() < 30 || item.getHeight() > screenHeight / 8
                    || item.getWidth() < screenWidth * 9 / 10
                    || banner.findViewById(labelId) == null
                    || banner.findViewById(buttonId) == null
                    || !containsPager(banner, 3)) return;
            ViewGroup.LayoutParams params = item.getLayoutParams();
            if (params == null) return;
            params.height = 0;
            item.setLayoutParams(params);
            item.setVisibility(View.GONE);
            lastMemberBanner = new WeakReference<View>(item);
            H.hit("iqiyi_home_member_banner", "top membership banner collapsed", HOME_BANNER_HIT);
        } catch (Throwable error) {
            H.warn("event=iqiyi_home_member_banner_runtime_error " + H.describe(error));
        }
    }

    private static boolean containsPager(ViewGroup group, int depth) {
        if (depth <= 0) return false;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if ("org.qiyi.basecard.common.widget.row.UltraViewPagerRow"
                    .equals(child.getClass().getName())) return true;
            if (child instanceof ViewGroup && containsPager((ViewGroup) child, depth - 1)) return true;
        }
        return false;
    }

    private static boolean inHomeFeed(View row) {
        int wrapperId = row.getResources().getIdentifier(
                "content_recycler_view_data", "id", row.getContext().getPackageName());
        ViewParent parent = row.getParent();
        for (int depth = 0; depth < 4 && parent instanceof View; depth++) {
            View view = (View) parent;
            if (wrapperId != 0 && view.getId() == wrapperId) return true;
            parent = view.getParent();
        }
        return false;
    }

    private static View findHomeFeedRow(View descendant) {
        View item = descendant;
        while (item != null && item.getParent() instanceof View) {
            View parent = (View) item.getParent();
            if ("org.qiyi.basecore.widget.ptr.widget.PinnedSectionRecyclerView"
                    .equals(parent.getClass().getName()) && inHomeFeed(item)) return item;
            item = parent;
        }
        return null;
    }

    private static void hideHomeTopAd(Activity activity) {
        try {
            int badgeId = activity.getResources().getIdentifier(
                    "tv_dsp_badge", "id", activity.getPackageName());
            if (badgeId == 0) return;
            View badge = activity.findViewById(badgeId);
            View card = badge == null ? null : findHomeFeedRow(badge);
            if (!(card instanceof ViewGroup) || lastTopCard.get() == card) return;
            int screenHeight = card.getResources().getDisplayMetrics().heightPixels;
            int screenWidth = card.getResources().getDisplayMetrics().widthPixels;
            int[] position = new int[2];
            card.getLocationOnScreen(position);
            if (position[1] > screenHeight / 2 || card.getWidth() < screenWidth * 9 / 10
                    || card.getHeight() < screenHeight / 8
                    || ((ViewGroup) card).findViewById(badgeId) == null) return;
            ViewGroup.LayoutParams params = card.getLayoutParams();
            if (params == null) return;
            params.height = 0;
            card.setLayoutParams(params);
            card.setVisibility(View.GONE);
            lastTopCard = new WeakReference<View>(card);
            H.hit("iqiyi_home_top_ad", "advertising carousel row collapsed", HOME_TOP_AD_HIT);
        } catch (Throwable error) { H.warn("event=iqiyi_home_top_ad_runtime_error " + H.describe(error)); }
    }

    private static void filterBar(Activity activity, Config.Settings settings) {
        try {
            int homeId = activity.getResources().getIdentifier("navi0", "id", activity.getPackageName());
            if (homeId == 0) return;
            View home = activity.findViewById(homeId);
            if (home == null) return;
            ViewParent parent = home.getParent();
            if (!(parent instanceof ViewGroup)) return;
            ViewGroup bar = (ViewGroup) parent;
            if (!"org.qiyi.video.navigation.view.CropTopLinearLayout".equals(bar.getClass().getName())
                    || bar.getChildCount() != 5 || bar.getChildAt(0) != home) return;
            if (lastBar.get() == bar) return;
            if (!"首页".equals(label(bar.getChildAt(0)))
                    || !"免费".equals(label(bar.getChildAt(1)))
                    || !"会员".equals(label(bar.getChildAt(3)))
                    || !"我的".equals(label(bar.getChildAt(4)))) return;
            int removed = 0;
            if (!settings.iqiyiShowFree) { bar.getChildAt(1).setVisibility(View.GONE); removed++; }
            if (!settings.iqiyiShowPlus) { bar.getChildAt(2).setVisibility(View.GONE); removed++; }
            if (!settings.iqiyiShowMember) { bar.getChildAt(3).setVisibility(View.GONE); removed++; }
            lastBar = new WeakReference<ViewGroup>(bar);
            if (removed > 0) H.hit("iqiyi_tab_filter", "hidden=" + removed + " from five-button bar", TABS_HIT);
        } catch (Throwable error) { H.warn("event=iqiyi_tab_filter_runtime_error " + H.describe(error)); }
    }

    private static String label(View view) {
        if (view instanceof TextView) return String.valueOf(((TextView) view).getText());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String text = label(group.getChildAt(i));
                if (text.length() != 0) return text;
            }
        }
        return "";
    }
}
