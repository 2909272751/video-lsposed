package io.github.qqliveclean;

import android.view.View;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** The exact native carousel view is attached only when the first home card is built. */
final class TencentHomeRules {
    private static final String RULE = "tencent_home_top_ad";
    private static final AtomicBoolean HIT = new AtomicBoolean(false);

    private TencentHomeRules() {}

    static void install(MainHook module, ClassLoader loader, boolean enabled) {
        if (!enabled) {
            H.skipped(RULE, "disabled in settings");
            return;
        }
        try {
            Class<?> carousel = R.load(loader,
                    "com.tencent.qqlive.modules.universal.groupcells.carousel.InnerRoundCarouselView");
            Method attach = R.find(carousel, "onAttachedToWindow", void.class);
            if (attach == null) {
                H.miss(RULE, "InnerRoundCarouselView.onAttachedToWindow unavailable");
                return;
            }
            module.hook(attach).setId("qqlive_home_carousel_attach").intercept(
                    new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    View view = (View) chain.getThisObject();
                    if (view != null) collapseFirstHomeCard(view);
                    return result;
                }
            });
            H.hooked(RULE, "InnerRoundCarouselView.onAttachedToWindow -> first home card collapsed");
        } catch (Throwable error) {
            H.miss(RULE, H.describe(error));
        }
    }

    private static void collapseFirstHomeCard(View carousel) {
        try {
            View current = carousel;
            while (current != null && current.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) current.getParent();
                if (parent.getClass().getName().contains("RecyclerView")) {
                    if (parent.getChildCount() == 0 || parent.getChildAt(0) != current)
                        return;
                    int[] location = new int[2];
                    current.getLocationOnScreen(location);
                    int height = current.getResources().getDisplayMetrics().heightPixels;
                    if (location[1] <= 0 || location[1] >= height / 2) return;
                    ViewGroup.LayoutParams params = current.getLayoutParams();
                    if (params == null) return;
                    params.height = 0;
                    current.setLayoutParams(params);
                    current.setVisibility(View.GONE);
                    H.hit(RULE, "first home carousel card collapsed", HIT);
                    return;
                }
                current = parent;
            }
        } catch (Throwable error) {
            H.warn("event=tencent_home_top_ad_runtime_error " + H.describe(error));
        }
    }
}
