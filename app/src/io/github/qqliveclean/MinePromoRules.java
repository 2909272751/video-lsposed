package io.github.qqliveclean;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import android.widget.ScrollView;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small, page-scoped passes for promotions that are inserted after Mine is opened. */
final class MinePromoRules {
    private static final AtomicBoolean YK_VIP_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean YK_CAROUSEL_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean IQ_BANNER_HIT = new AtomicBoolean(false);
    private static WeakReference<View> lastYkVip = new WeakReference<View>(null);
    private static WeakReference<View> lastYkCarousel = new WeakReference<View>(null);
    private static WeakReference<View> lastIqBanner = new WeakReference<View>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MinePromoRules() {}

    static void install(final MainHook module, ClassLoader loader, final String packageName,
                        boolean enabled) {
        final boolean youku = Config.PACKAGE_YOUKU.equals(packageName);
        if (!enabled) {
            report(youku, false, "disabled in settings");
            return;
        }
        try {
            Method click = R.find(View.class, "performClick", boolean.class);
            if (click == null) throw new NoSuchMethodException("View.performClick");
            module.hook(click).setId(youku ? "youku_mine_promo_click" : "iqiyi_mine_banner_click")
                    .intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    View clicked = (View) chain.getThisObject();
                    boolean mine = isMineNavigation(clicked);
                    Object result = chain.proceed();
                    if (mine) schedule(clicked.getRootView(), youku);
                    return result;
                }
            });
            Method resume = R.find(android.app.Instrumentation.class,
                    "callActivityOnResume", void.class, Activity.class);
            if (resume != null) {
                module.hook(resume).setId(youku ? "youku_mine_promo_resume" : "iqiyi_mine_banner_resume")
                        .intercept(new XposedInterface.Hooker() {
                    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        Activity activity = (Activity) chain.getArg(0);
                        if (activity != null) schedule(activity.getWindow().getDecorView(), youku);
                        return result;
                    }
                });
            }
            report(youku, true, "Mine navigation and resumed Activity; bounded delayed passes");
        } catch (Throwable error) {
            report(youku, false, H.describe(error));
        }
    }

    private static void report(boolean youku, boolean matched, String detail) {
        if (youku) {
            if (matched) {
                H.hooked("youku_mine_vip_promo", detail);
                H.hooked("youku_mine_carousel", detail);
            } else if (detail.startsWith("disabled")) {
                H.skipped("youku_mine_vip_promo", detail);
                H.skipped("youku_mine_carousel", detail);
            } else {
                H.miss("youku_mine_vip_promo", detail);
                H.miss("youku_mine_carousel", detail);
            }
        } else if (matched) H.hooked("iqiyi_mine_banner", detail);
        else if (detail.startsWith("disabled")) H.skipped("iqiyi_mine_banner", detail);
        else H.miss("iqiyi_mine_banner", detail);
    }

    private static boolean isMineNavigation(View clicked) {
        int[] pos = new int[2];
        clicked.getLocationOnScreen(pos);
        int screen = clicked.getResources().getDisplayMetrics().heightPixels;
        if (pos[1] <= screen * 3 / 4) return false;
        View current = clicked;
        for (int i = 0; i < 4 && current != null; i++) {
            if (hasMineLabel(current, 0) && (current.isClickable() || current instanceof TextView)) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private static boolean hasMineLabel(View view, int depth) {
        if (view instanceof TextView) return "我的".contentEquals(((TextView) view).getText());
        if (!(view instanceof ViewGroup) || depth > 3) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++)
            if (hasMineLabel(group.getChildAt(i), depth + 1)) return true;
        return false;
    }

    private static void schedule(final View root, final boolean youku) {
        if (root == null) return;
        for (int delay : new int[]{150, 700, 2000, 5000}) {
            MAIN.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (youku) hideYouku(root);
                        else hideIqiyi(root);
                    } catch (Throwable error) {
                        H.warn("event=mine_promo_runtime_error " + H.describe(error));
                    }
                }
            }, delay);
        }
    }

    private static int id(View view, String name) {
        return view.getResources().getIdentifier(name, "id", view.getContext().getPackageName());
    }

    private static View directChild(View view, ViewGroup container) {
        View current = view;
        while (current != null && current.getParent() instanceof ViewGroup) {
            if (current.getParent() == container) return current;
            current = (View) current.getParent();
        }
        return null;
    }

    private static void collapse(View card) {
        if (card == null || card.getVisibility() == View.GONE) return;
        ViewGroup.LayoutParams params = card.getLayoutParams();
        if (params == null) return;
        params.height = 0;
        card.setLayoutParams(params);
        card.setVisibility(View.GONE);
    }

    private static void hideYouku(View root) {
        int hostId = id(root, "one_arch_recyclerView");
        if (hostId == 0) return;
        View host = root.findViewById(hostId);
        if (!(host instanceof ViewGroup) || host.getVisibility() != View.VISIBLE) return;
        ViewGroup recycler = (ViewGroup) host;
        int vipId = id(root, "vip_info_txt");
        int carouselId = id(root, "horizontal_card_recyclerview");
        View vip = vipId == 0 ? null : recycler.findViewById(vipId);
        View carousel = carouselId == 0 ? null : recycler.findViewById(carouselId);
        View vipCard = vip == null ? null : directChild(vip, recycler);
        View carouselCard = carousel == null ? null : directChild(carousel, recycler);
        if (vipCard != null && vipCard != lastYkVip.get()) {
            collapse(vipCard);
            lastYkVip = new WeakReference<View>(vipCard);
            H.hit("youku_mine_vip_promo", "Mine membership campaign card collapsed", YK_VIP_HIT);
        }
        if (carouselCard != null && carouselCard != lastYkCarousel.get()) {
            collapse(carouselCard);
            lastYkCarousel = new WeakReference<View>(carouselCard);
            H.hit("youku_mine_carousel", "Mine campaign carousel collapsed", YK_CAROUSEL_HIT);
        }
    }

    private static void hideIqiyi(View root) {
        int footId = id(root, "phoneFootLayout");
        if (footId == 0 || root.findViewById(footId) == null) return;
        View mineLabel = findText(root, "我的", true, 0, new int[]{0});
        if (mineLabel == null) return;
        View ad = findText(root, "广告", false, 0, new int[]{0});
        if (ad == null) return;
        int[] pos = new int[2];
        ad.getLocationOnScreen(pos);
        int screen = root.getResources().getDisplayMetrics().heightPixels;
        int width = root.getResources().getDisplayMetrics().widthPixels;
        if (pos[1] > screen / 4) return;
        ViewParent parent = ad.getParent();
        while (parent instanceof ViewGroup) {
            ViewGroup candidate = (ViewGroup) parent;
            if (candidate instanceof ScrollView && candidate.getWidth() > width * 7 / 10
                    && candidate.getHeight() > 50
                    && candidate.getHeight() < screen / 5) {
                if (candidate != lastIqBanner.get()) {
                    collapse(candidate);
                    lastIqBanner = new WeakReference<View>(candidate);
                    H.hit("iqiyi_mine_banner", "marked Mine ad banner collapsed", IQ_BANNER_HIT);
                }
                return;
            }
            parent = candidate.getParent();
        }
    }

    private static View findText(View view, String text, boolean selected, int depth, int[] visited) {
        if (++visited[0] > 500 || depth > 18) return null;
        if (!view.isShown()) return null;
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())
                && (!selected || view.isSelected())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findText(group.getChildAt(i), text, selected, depth + 1, visited);
            if (found != null) return found;
        }
        return null;
    }
}
