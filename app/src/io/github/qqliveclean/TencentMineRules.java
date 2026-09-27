package io.github.qqliveclean;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Personal-center banner request and response, outside the generic QAdRequestManager. */
final class TencentMineRules {
    private static final AtomicBoolean REQUEST_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean RESPONSE_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean PROVIDER_HIT = new AtomicBoolean(false);

    private TencentMineRules() {}

    static void install(MainHook module, ClassLoader loader, boolean enabled) {
        if (!enabled) {
            H.skipped("mine_ad_card", "disabled in settings");
            H.skipped("mine_banner_request", "disabled in settings");
            H.skipped("mine_banner_response", "disabled in settings");
            return;
        }
        installCardProvider(module, loader);
        try {
            Class<?> service = R.load(loader,
                    "com.tencent.qqlive.kmm.init.bridge.QAdUserCenterRequestServiceImpl");
            Class<?> scene = R.load(loader,
                    "com.tencent.qqlive.protocol.pb.kmm.AdPersonalCenterBannerEnterRequestScene");
            Method request = R.find(service, "c", void.class, scene);
            if (request == null) {
                H.miss("mine_banner_request", "personal banner request signature changed");
            } else {
                module.hook(request).setId("qqlive_mine_banner_request").intercept(
                        new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        H.hit("mine_banner_request", "personal banner request suppressed", REQUEST_HIT);
                        return null;
                    }
                });
                H.hooked("mine_banner_request", "QAdUserCenterRequestServiceImpl.c(scene)V suppressed");
            }
            Class<?> responseType = R.load(loader,
                    "com.tencent.qqlive.protocol.pb.AdPersonalCenterBannerResponse");
            Method response = R.find(service, "b", void.class, responseType);
            if (response == null) {
                H.miss("mine_banner_response", "personal banner response signature changed");
            } else {
                module.hook(response).setId("qqlive_mine_banner_response").intercept(
                        new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        H.hit("mine_banner_response", "personal banner payload suppressed", RESPONSE_HIT);
                        return null;
                    }
                });
                H.hooked("mine_banner_response", "QAdUserCenterRequestServiceImpl.b(response)V suppressed");
            }
        } catch (Throwable error) {
            H.miss("mine_banner_request", H.describe(error));
            H.miss("mine_banner_response", H.describe(error));
        }
    }

    /** The KMM user-center ad card returns immediately when its compose provider is null.
     * Prevent only the one-time provider injection, avoiding a Compose render-path hook. */
    private static void installCardProvider(MainHook module, ClassLoader loader) {
        final String rule = "mine_ad_card";
        try {
            Class<?> holder = R.load(loader, "com.tencent.qqlive.kmm.usercenter.ad.k");
            Class<?> provider = R.load(loader, "com.tencent.qqlive.kmm.usercenter.ad.l");
            Method inject = R.find(holder, "c", void.class, provider);
            if (inject == null) {
                H.miss(rule, "user-center ad provider injection signature changed");
                return;
            }
            module.hook(inject).setId("qqlive_mine_ad_card").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "user-center ad provider injection suppressed", PROVIDER_HIT);
                    return null;
                }
            });
            H.hooked(rule, "UserCenterAdCard provider injection suppressed");
        } catch (Throwable error) {
            H.miss(rule, H.describe(error));
        }
    }
}
