package io.github.qqliveclean;

import android.content.Context;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 寮€灞忎互澶栫殑骞垮憡鎷︽埅锛堣创鐗?/ 鎾斁涓?/ 鏆傚仠 / 搴曟爮鎸備欢 / 鍏ㄥ眬骞垮憡璇锋眰锛?
 *
 * Evidence was taken from freshly decompiled classes6.dex (mediaad player ads) and
 * classes15.dex (the common load checker).
 *
 * 1. 鎾斁鍓?涓?鍚庤创鐗?+ 鏆傚仠骞垮憡 - {@code rb6.e.a(Context, QAdRequestInfo)} (log tag
 *    "QAdCommonLoadChecker") returns {@code mediaad.data.f}: {@code null} means "OK, request
 *    the ad", a non-null verdict means "DO NOT REQUEST" with a reason code. A per-ad-type
 *    factory {@code rb6.i.a(int)} feeds it: 1=pre-roll, 2=pause, 3=mid-roll, 4=post-roll
 *    (+ corners). All 11 call sites were verified, so ONE hook covers pre/mid/post-roll,
 *    pause and rich-media corners.
 *    CAVEAT: {@code rb6} / {@code rb6.e} are single-letter OBFUSCATED names, so a renamed
 *    build logs a miss and the non-obfuscated fallbacks below take over.
 *
 * 2. 搴曟爮/鎸備欢骞垮憡 - {@code QAdChannelPendantRequestController.l(List) : int}
 *    ("canRequestPushAdOrder"; 0 = REQUEST_SUCCESS). Returning 105
 *    (REQUEST_FAIL_HAS_SEND_REQUEST) blocks it; the manager only requests when it sees 0.
 *
 * 3. 鍏滃簳 - {@code com.tencent.qqlive.mediaad.impl.u.v1(AdInsideVideoRequest, boolean)}
 *    ("loadAdInner", shared by the pre/mid/post-roll impls, ends by sending the ad CGI) and
 *    {@code QAdBaseVideoImpl.x0()} ("loadAd"). Both keep their real names.
 *
 * 4. 鍏ㄥ眬骞垮憡璇锋眰闂搁棬 - {@code QAdRequestManager.i(?,?) : int} -> 0, which covers the feed /
 *    focus-card / poster ads this app serves so many of.
 *
 * All are one-shot decision gates, not render-path hooks. The verdict object is built once at
 * install time and returned as a cached constant, so no intercept allocates.
 */
final class AdRules {
    private static final AtomicBoolean CHECKER_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean PLAYER_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean PENDANT_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean REQUEST_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean STRATEGY_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean HLS_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean FEED_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean HOME_PROMO_HIT = new AtomicBoolean(false);

    /** The app's own ad-suppression value; the framework itself sets it for some scenes. */
    private static final String STRATEGY_KEY = "PLAY_STRATEGY";
    private static final String NO_AD_REQUEST = "NO_AD_REQUEST";

    private AdRules() {}

    // ------------------------------------------------------- 1. player + pause gate

    static void installPlayerAndPause(MainHook module, ClassLoader loader) {
        final String rule = "player_pause_gate";
        // The verdict payload is NOT obfuscated and survives version bumps, so it is the stable
        // anchor; the builder IS obfuscated (rb6.e died in 9.04.55). An earlier attempt picked a
        // variant by "the class merely loads", which locked onto rb6.d and reported
        // "methods: (none)" - it existed but was not the builder. Select by SHAPE instead.
        Class<?> verdict;
        try {
            verdict = R.load(loader, "com.tencent.qqlive.mediaad.data.f");
        } catch (Throwable error) {
            H.miss(rule, "verdict payload class not found: " + H.describe(error));
            installPlayerFallback(module, loader);
            return;
        }
        Class<?> checker = null;
        Method gate = null;
        for (String candidate : new String[]{"rb6.e", "rb6.d", "rb6.c", "rb6.b", "rb6.a"}) {
            Class<?> owner;
            try {
                owner = R.load(loader, candidate);
            } catch (Throwable ignored) {
                continue;   // absent in this build: not a failure, just not a candidate
            }
            // Exact Context-first shape first; then the same method with the obfuscated
            // QAdRequestInfo parameter pinned only by arity.
            Method found = R.findByShape(owner, new String[]{"a"}, verdict, Context.class, null);
            if (found == null) {
                found = R.findByShape(owner, new String[]{"a"}, verdict, null, null);
            }
            if (found != null) {
                checker = owner;
                gate = found;
                break;
            }
        }
        if (gate == null) {
            // WITHDRAWN rather than left as a permanent miss. Evidence: the payload survived
            // (mediaad.data.f is still declared) but NOTHING in rb6.* builds it any more, and
            // chasing R8's reassigned letters is futile. The replacement - pause_ad_gate,
            // anchored on the NON-obfuscated mediaad.impl.QAdVideoPauseAdImpl.K()Z - is armed
            // AND has hit on device, so this rule is deliberately OFF, not broken.
            H.skipped(rule, "withdrawn on 9.04.55: rb6.* no longer builds mediaad.data.f;"
                    + " superseded by pause_ad_gate (armed and verified hitting on device)");
            installPlayerFallback(module, loader);
            return;
        }
        H.info("event=pause_gate_builder class=" + checker.getName() + " method=" + gate.getName());
        final Object blocked = buildBlockedVerdict(verdict);
        if (blocked == null) {
            H.miss(rule, "could not construct a non-null " + verdict.getName() + " verdict");
            installPlayerFallback(module, loader);
            return;
        }
        try {
            module.hook(gate).setId("qqlive_player_pause_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "ad request refused (pre/mid/post-roll + pause + corners)", CHECKER_HIT);
                    return blocked;
                }
            });
            H.hooked(rule, "rb6.e.a(?,?) -> cached 'do not request' verdict");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    /**
     * Builds the "do not request" verdict ONCE, so the intercept only returns a cached
     * constant. Uses the same code family the app itself uses for its own no-ad decisions.
     */
    private static Object buildBlockedVerdict(Class<?> verdict) {
        try {
            Constructor<?> constructor =
                    verdict.getDeclaredConstructor(int.class, int.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(116, 3102, "blocked by QQLiveClean");
        } catch (Throwable first) {
            try {
                Constructor<?> constructor = verdict.getDeclaredConstructor(int.class, String.class);
                constructor.setAccessible(true);
                return constructor.newInstance(116, "blocked by QQLiveClean");
            } catch (Throwable second) {
                return null;
            }
        }
    }

    /** Non-obfuscated fallbacks if the checker was renamed. */
    private static void installPlayerFallback(MainHook module, ClassLoader loader) {
        final String rule = "player_ads";
        try {
            Class<?> base = R.load(loader, "com.tencent.qqlive.mediaad.impl.u");
            Method loadAdInner = R.findByShape(base, new String[]{"v1"}, void.class, null, boolean.class);
            if (loadAdInner != null) {
                module.hook(loadAdInner).setId("qqlive_player_ad_request").intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        H.hit(rule, "loadAdInner suppressed (pre/mid/post-roll)", PLAYER_HIT);
                        return null;
                    }
                });
                H.hooked(rule, "mediaad.impl.u.v1(?,boolean)V suppressed");
                return;
            }
            Class<?> orchestrator = R.load(loader, "com.tencent.qqlive.mediaad.impl.QAdBaseVideoImpl");
            Method loadAd = R.findByShape(orchestrator, new String[]{"x0"}, void.class);
            if (loadAd == null) {
                H.miss(rule, "neither u.v1 nor QAdBaseVideoImpl.x0() found");
                return;
            }
            module.hook(loadAd).setId("qqlive_player_ad_orchestrator").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "loadAd orchestrator suppressed", PLAYER_HIT);
                    return null;
                }
            });
            H.hooked(rule, "QAdBaseVideoImpl.x0()V suppressed");
        } catch (Throwable error) {
            H.miss(rule, "install failed: " + H.describe(error));
        }
    }

    // ------------------------------------------------------- 2. bottom-bar / pendant ads

    static void installPendantGate(MainHook module, ClassLoader loader) {
        final String rule = "pendant_ads";
        try {
            Class<?> controller = R.load(loader,
                    "com.tencent.qqlive.mediaad.controller.push.channel.QAdChannelPendantRequestController");
            Method canRequest = R.findByShape(controller, new String[]{"l"}, int.class, java.util.List.class);
            if (canRequest == null) {
                H.miss(rule, "l(List)I not found; int(): " + R.describeShapes(controller, int.class));
                return;
            }
            module.hook(canRequest).setId("qqlive_pendant_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "push/pendant ad request refused", PENDANT_HIT);
                    // 105 = REQUEST_FAIL_HAS_SEND_REQUEST: anything != 0 blocks the request.
                    return Integer.valueOf(105);
                }
            });
            H.hooked(rule, "QAdChannelPendantRequestController.l(List)I -> 105");
        } catch (Throwable error) {
            H.miss(rule, "install failed: " + H.describe(error));
        }
    }

    // ------------------------------------------------------- 4. app-wide request gate

    static void installRequestGate(MainHook module, ClassLoader loader) {
        final String rule = "ad_request_gate";
        Class<?> manager;
        try {
            manager = R.load(loader, "com.tencent.qqlive.qadcore.network.manager.QAdRequestManager");
        } catch (Throwable error) {
            H.miss(rule, "QAdRequestManager not found: " + H.describe(error));
            return;
        }
        Method request = R.findByShape(manager, new String[]{"i", "j"}, int.class, null, null);
        if (request == null) {
            H.miss(rule, "QAdRequestManager.i(?,?)I not found; int(): " + R.describeShapes(manager, int.class));
            return;
        }
        try {
            module.hook(request).setId("qqlive_ad_request_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "outgoing ad request suppressed -> requestId 0", REQUEST_HIT);
                    return Integer.valueOf(0);
                }
            });
            H.hooked(rule, "QAdRequestManager." + request.getName() + "(?,?)I -> 0 (app-wide ad requests blocked)");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    // ------------------------------------------------------- 5. the app's own kill switch

    /**
     * {@code TVKPlayerVideoInfo.addAdParamsMap(String, Object)} - the app's OWN, non-obfuscated
     * ad-suppression API. Its own framework calls it to stop ad requests:
     * {@code addAdParamsMap("PLAY_STRATEGY", "NO_AD_REQUEST")} immediately before
     * {@code openMediaPlayer}. The resolver {@code com.tencent.qqlive.util.t0} maps that literal
     * to strategy id 4. Because it is public and named, it is far more durable than the
     * obfuscated internals; it acts upstream of every other player-ad gate here.
     */
    static void installPlayStrategy(MainHook module, ClassLoader loader) {
        final String rule = "play_strategy";
        try {
            Class<?> info = R.load(loader, "com.tencent.qqlive.tvkplayer.api.TVKPlayerVideoInfo");
            Method add = R.find(info, "addAdParamsMap", void.class, String.class, Object.class);
            if (add == null) {
                H.miss(rule, "addAdParamsMap(String,Object)V not found");
                return;
            }
            module.hook(add).setId("qqlive_play_strategy").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object key = chain.getArg(0);
                    if (STRATEGY_KEY.equals(key)) {
                        H.hit(rule, "PLAY_STRATEGY forced to NO_AD_REQUEST", STRATEGY_HIT);
                        return chain.proceed(new Object[]{key, NO_AD_REQUEST});
                    }
                    return chain.proceed();
                }
            });
            H.hooked(rule, "TVKPlayerVideoInfo.addAdParamsMap -> PLAY_STRATEGY=NO_AD_REQUEST");
        } catch (Throwable error) {
            H.miss(rule, "install failed: " + H.describe(error));
        }
    }

    /**
     * Mid-roll delivered as in-stream HLS takes a completely separate path that never reaches
     * the mediaad gates: {@code HLSAdCalculateUtils.parseHLSAdList} builds the list from
     * {@code TVKNetVideoInfo.getAdInfo().getPluginAdInfos()} and has a single consumer in
     * {@code VMTAdHandler.onNetVideoInfo}. Returning an empty list removes those breaks.
     */
    static void installHlsMidRoll(MainHook module, ClassLoader loader) {
        final String rule = "hls_midroll";
        try {
            Class<?> utils = R.load(loader,
                    "com.tencent.qqlive.modules.vb.playerplugin.impl.utils.HLSAdCalculateUtils");
            Class<?> netInfo = R.load(loader, "com.tencent.qqlive.tvkplayer.api.vinfo.TVKNetVideoInfo");
            Method parse = R.find(utils, "parseHLSAdList", java.util.List.class, netInfo);
            if (parse == null) {
                H.miss(rule, "parseHLSAdList(TVKNetVideoInfo)List not found");
                return;
            }
            module.hook(parse).setId("qqlive_hls_midroll").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "in-stream HLS mid-roll breaks suppressed", HLS_HIT);
                    return java.util.Collections.emptyList();
                }
            });
            H.hooked(rule, "HLSAdCalculateUtils.parseHLSAdList -> empty list");
        } catch (Throwable error) {
            H.miss(rule, "install failed: " + H.describe(error));
        }
    }

    /**
     * One switch for every feed-family ad cell.
     *
     * <p>The app has several sibling gates, all with the SAME shape - a private
     * {@code boolean(com.tencent.qqlive.protocol.pb.AdFeedInfo)} whose true branch means "drop this
     * ad feed", so no ad cell is produced. Only one of them was hooked before, which is why ads in
     * the player page's below-episode feed survived while the home feed was clean. Read from the
     * shipped 9.04.51 dex, not guessed:
     *
     * <pre>
     *   com.tencent.qqlive.ona.ad.universal.g   ?(AdFeedInfo)Z   home feed (already hooked)
     *   com.tencent.qqlive.ona.ad.b            y(AdFeedInfo)Z
     *   com.tencent.qqlive.ona.ad.feed.a       v(AdFeedInfo)Z
     * </pre>
     *
     * <p>{@code ad.feed.a} is the one the player page needs: it also takes
     * {@code (BaseCellVM, AdFeedInfo)} and {@code (BaseCellVM, AdFeedInfo, BaseSectionController)},
     * i.e. it is the cell view-model layer where the card is actually built.
     *
     * <p>Obfuscated names, so a rename logs a miss and the other gates keep working.
     */
    static void installFeedAdCell(MainHook module, ClassLoader loader) {
        final String rule = "feed_ad_cell";
        // Each entry is a class holding one gate. The method name is shape-matched, so a rebuild
        // that renames the method is still caught as long as the class and the parameter survive.
        String[] classes = {
            "com.tencent.qqlive.ona.ad.universal.g",
            "com.tencent.qqlive.ona.ad.b",
            "com.tencent.qqlive.ona.ad.feed.a",
        };
        String[] names = {"m", "b", "j", "k", "y", "v"};
        Class<?> adFeedInfo;
        try {
            adFeedInfo = R.load(loader, "com.tencent.qqlive.protocol.pb.AdFeedInfo");
        } catch (Throwable error) {
            H.miss(rule, "AdFeedInfo not loadable: " + H.describe(error));
            return;
        }
        int hooked = 0;
        StringBuilder namesOut = new StringBuilder();
        for (String className : classes) {
            try {
                Class<?> dispatcher = R.load(loader, className);
                Method reject = R.findByShape(dispatcher, names, boolean.class, adFeedInfo);
                if (reject == null) {
                    H.diag(rule + ": no boolean(AdFeedInfo) gate on " + className);
                    continue;
                }
                module.hook(reject).setId("qqlive_feed_ad_cell_" + hooked)
                        .intercept(new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) {
                                H.hit(rule, "ad feed rejected -> no ad cell (feed/focus/card/bottom)",
                                        FEED_HIT);
                                return Boolean.TRUE;
                            }
                        });
                if (hooked > 0) {
                    namesOut.append(", ");
                }
                namesOut.append(reject.getName());
                hooked++;
            } catch (Throwable error) {
                H.diag(rule + ": " + className + " skipped - " + H.describe(error));
            }
        }
        if (hooked == 0) {
            H.miss(rule, "no boolean(AdFeedInfo) gate found on any ad class");
            return;
        }
        H.hooked(rule, hooked + " ad-feed gates -> true: " + namesOut
                + " (home feed / feed / cell-VM surfaces)");
    }


    private static final AtomicBoolean PAUSE_GATE_HIT = new AtomicBoolean(false);

    /**
     * The real pause-ad gate for 9.04.55, chosen from DEVICE EVIDENCE: during playback+pause the
     * read-only sweep observed exactly QAdVideoPauseAdImpl.K()Z and r(?,?)Z being consulted.
     * K()Z is the no-arg decision, so answering false suppresses the pause ad.
     *
     * Anchored on a NON-obfuscated class on purpose: rb6.* died with the version bump while the
     * mediaad.impl.* names survived, so this anchor is expected to outlive R8 reshuffles.
     */
    static void installPauseAdGate(MainHook module, ClassLoader loader) {
        final String rule = "pause_ad_gate";
        Class<?> impl;
        try {
            impl = R.load(loader, "com.tencent.qqlive.mediaad.impl.QAdVideoPauseAdImpl");
        } catch (Throwable error) {
            H.miss(rule, "QAdVideoPauseAdImpl not found: " + H.describe(error));
            return;
        }
        Method decision = R.find(impl, "K", boolean.class);
        if (decision == null) {
            H.miss(rule, "K()Z not found; boolean(): " + R.describeMethods(impl));
            return;
        }
        try {
            module.hook(decision).setId("qqlive_pause_ad_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "pause-ad decision suppressed (QAdVideoPauseAdImpl.K)Z)", PAUSE_GATE_HIT);
                    return Boolean.FALSE;
                }
            });
            H.hooked(rule, "QAdVideoPauseAdImpl.K()Z -> false");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }

    // ------------------------------------------------------- 6. channel-level promo overlays

    /**
     * Suppresses the channel-level 运营 overlay funnel: big banner / lottie / foot banner /
     * big H5 / TMS banner / shake / 饭团 all hang off this one private check, which runs once per
     * channel load. Skipping it never removes anything the UI is building from, so the failure
     * mode is "promos still appear" rather than a broken layout.
     */
    static void installHomePromoGate(MainHook module, ClassLoader loader) {
        final String rule = "home_promo";
        Class<?> plugin;
        try {
            plugin = R.load(loader, "com.tencent.qqlive.ona.channel.ChannelFullFloatPlugin");
        } catch (Throwable error) {
            H.miss(rule, "ChannelFullFloatPlugin not found: " + H.describe(error));
            return;
        }
        Method check = R.find(plugin, "onCheckView", void.class);
        if (check == null) {
            H.miss(rule, "onCheckView()V not found; void(): "
                    + R.describeCandidates(plugin, void.class));
            return;
        }
        try {
            module.hook(check).setId("qqlive_home_promo_gate").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "channel promo overlays suppressed", HOME_PROMO_HIT);
                    return null;
                }
            });
            H.hooked(rule, "ChannelFullFloatPlugin.onCheckView()V suppressed (banner/lottie/H5/TMS/shake)");
        } catch (Throwable error) {
            H.miss(rule, "hook failed: " + H.describe(error));
        }
    }
}
