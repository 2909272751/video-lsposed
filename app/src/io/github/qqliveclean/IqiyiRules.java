package io.github.qqliveclean;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** iQIYI: one splash request gate and one five-button navigation view pass. */
final class IqiyiRules {
    private static final AtomicBoolean SPLASH_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean TABS_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean HOME_TOP_AD_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean HOME_BANNER_HIT = new AtomicBoolean(false);
    private static WeakReference<ViewGroup> lastBar = new WeakReference<ViewGroup>(null);
    private static WeakReference<View> lastTopCard = new WeakReference<View>(null);
    private static WeakReference<View> lastMemberBanner = new WeakReference<View>(null);

    private IqiyiRules() {}

    static void install(MainHook module, ClassLoader loader, Config.Settings settings) {
        if (settings.iqiyiBlockSplash) installSplash(module, loader);
        else H.skipped("iqiyi_splash", "disabled in settings");
        installHomeUi(module, loader, settings);
        H.skipped("iqiyi_player_ads", "live player route not yet identified");
    }

    private static void installSplash(MainHook module, ClassLoader loader) {
        final String rule = "iqiyi_splash";
        try {
            Class<?> api = R.load(loader, "org.qiyi.video.module.api.ISplashScreenApi");
            Method request = null;
            Class<?> manager = null;
            for (String candidate : new String[]{"lz1.v", "ry1.h"}) {
                try {
                    Class<?> found = R.load(loader, candidate);
                    if (!api.isAssignableFrom(found)) continue;
                    Method method = R.find(found, "requestAdAndDownload", void.class);
                    if (method != null) { manager = found; request = method; break; }
                } catch (ClassNotFoundException ignored) {}
            }
            if (request == null) {
                H.miss(rule, "no known ISplashScreenApi implementation has requestAdAndDownload()V");
                return;
            }
            final String owner = manager.getName();
            module.hook(request).setId("iqiyi_splash_request").intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    H.hit(rule, "requestAdAndDownload suppressed", SPLASH_HIT);
                    return null;
                }
            });
            H.hooked(rule, owner + ".requestAdAndDownload()V suppressed");
        } catch (Throwable error) {
            H.miss(rule, H.describe(error));
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
