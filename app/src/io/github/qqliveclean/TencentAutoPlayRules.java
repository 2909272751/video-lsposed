package io.github.qqliveclean;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses Tencent Video's own feed-autoplay switches, leaving explicit playback untouched. */
final class TencentAutoPlayRules {
    private static final String RULE = "feed_autoplay";
    private static final AtomicBoolean HIT = new AtomicBoolean(false);

    private TencentAutoPlayRules() {}

    static void install(MainHook module, ClassLoader loader, boolean enabled) {
        if (!enabled) {
            H.skipped(RULE, "disabled in settings");
            return;
        }
        String primaryFailure = null;
        try {
            Class<?> manager = R.load(loader, "cr4.y");
            Class<?> mode = R.load(loader, "com.tencent.qqlive.ona.usercenter.model.AutoPlayMode");
            Method mobileMode = R.find(manager, "k", mode);
            Method wifiMode = R.find(manager, "m", mode);
            if (mobileMode == null || wifiMode == null
                    || !Modifier.isStatic(mobileMode.getModifiers())
                    || !Modifier.isStatic(wifiMode.getModifiers())) {
                primaryFailure = "homepage mode getters changed: " + R.describeShapes(manager, mode);
            } else {
                Object close = Enum.valueOf((Class) mode, "CLOSE");
                XposedInterface.Hooker refuseMode = new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) {
                        H.hit(RULE, "homepage autoplay mode CLOSE", HIT);
                        return close;
                    }
                };
                module.hook(mobileMode).setId("qqlive_home_autoplay_mobile").intercept(refuseMode);
                module.hook(wifiMode).setId("qqlive_home_autoplay_wifi").intercept(refuseMode);
                H.hooked(RULE, "homepage mobile/Wi-Fi mode getters return CLOSE");
                return;
            }
        } catch (Throwable error) {
            primaryFailure = H.describe(error);
        }
        // Older/newer builds may move the obfuscated setting manager. This named service
        // publishes the same feed switches and gives us a cheap compatibility fallback.
        try {
            Class<?> service = R.load(loader, "com.tencent.qqlive.ona.share.l");
            Method wifi = R.find(service, "getFeedAutoPlaySwitchInWifiOrFreeNet", boolean.class);
            Method mobile = R.find(service, "getFeedAutoPlaySwitchInMobileNet", boolean.class);
            if (wifi == null || mobile == null) {
                H.miss(RULE, "mode gate: " + primaryFailure + "; feed switches changed: "
                        + R.describeCandidates(service, boolean.class));
                return;
            }
            XposedInterface.Hooker refuse = new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(RULE, "feed autoplay switch off", HIT);
                    return false;
                }
            };
            module.hook(wifi).setId("qqlive_feed_autoplay_wifi").intercept(refuse);
            module.hook(mobile).setId("qqlive_feed_autoplay_mobile").intercept(refuse);
            H.hooked(RULE, "feed Wi-Fi/mobile switches return false; primary=" + primaryFailure);
        } catch (Throwable error) {
            H.miss(RULE, "mode gate: " + primaryFailure + "; feed gate: " + H.describe(error));
        }
    }

}
