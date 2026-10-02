package io.github.qqliveclean;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 信息流里的广告卡整块隐藏。
 *
 * <p>起因是用户指出「按时间变化的视频推荐里面，推荐中间藏了广告」。
 * 真机确实存在：优酷首页「更多精彩」里两张卡右上角带「广告 ∨」角标
 * （找茬婆娘 / 轻松就通关），爱奇艺推荐流里同样有一张带「广告」角标的卡。
 * 这类广告不是独立页面，而是**夹在正常推荐流里的一整块卡片**，
 * 所以按整块收起，而不是只盖掉角标。
 *
 * <p>为什么必须走文本而不是 resource-id：爱奇艺把控件 id 全部混淆成
 * {@code unused_res_a}（dumpsys 出来的每个 view 都是同一个 id），
 * id 方案在它身上完全失效。优酷页面又有持续动画，uiautomator 抓不到树。
 * 于是两边统一用**角标文本 + 结构**定位：全树找文本是「广告」的 TextView，
 * 再向上找到那张卡片。
 *
 * <p>卡片判定不用固定层数（两家的层数不一样，且改版就会变），而是按几何：
 * 从角标向上，找到第一个**宽度不小于屏幕 1/4、高度不小于 1/14 屏高**、
* 且不在 RecyclerView/ListView 之上的祖先——那就是整块卡片。
 * 这样版式微调也不会认错。
 */
final class FeedAdCardRules {
    private FeedAdCardRules() {}

    private static final String IQIYI = "iqiyi_feed_ad_card";
    private static final String YOUKU = "youku_feed_ad_card";
    private static final AtomicBoolean IQIYI_HIT = new AtomicBoolean(false);
    private static final AtomicBoolean YOUKU_HIT = new AtomicBoolean(false);
    /** 已经收起的卡片，避免每轮重复做同样的事。 */
    private static final List<WeakReference<View>> COLLAPSED = new ArrayList<WeakReference<View>>();

    /** 对着活动视图树跑一轮，返回收起了几块。 */
    static int sweep(Activity activity, boolean youku) {
        if (activity == null) return 0;
        View root;
        try {
            root = activity.getWindow() == null ? null
                    : activity.getWindow().getDecorView().getRootView();
        } catch (Throwable error) {
            return 0;
        }
        if (root == null) return 0;
        List<View> badges = new ArrayList<View>();
        try {
            collectBadges(root, badges);
        } catch (Throwable error) {
            return 0;
        }
        int hidden = 0;
        for (int i = 0; i < badges.size(); i++) {
            View card = cardOf(badges.get(i));
            if (card == null) continue;
            if (collapse(card)) {
                hidden++;
                H.hit(youku ? YOUKU : IQIYI,
                        "feed ad card collapsed: " + card.getWidth() + "x" + card.getHeight(),
                        youku ? YOUKU_HIT : IQIYI_HIT);
            }
        }
        // A pass that runs and finds nothing must not look like a rule that never fires. This
        // round's first attempt skipped the home page entirely and produced a silent, empty log
        // that read exactly like "there are no feed ads" - there were two on screen.
        if (hidden == 0) {
            StringBuilder shape = new StringBuilder();
            for (int i = 0; i < badges.size() && i < 3; i++) {
                shape.append(" [");
                View probe = badges.get(i);
                for (int depth = 0; depth < 5; depth++) {
                    if (probe == null) break;
                    shape.append(probe.getClass().getSimpleName()).append('=')
                            .append(probe.getWidth()).append('x').append(probe.getHeight());
                    if (depth > 0) shape.append(',');
                    ViewParent up = probe.getParent();
                    probe = up instanceof View ? (View) up : null;
                }
                shape.append(']');
            }
            H.miss(youku ? YOUKU : IQIYI, "sweep ran on " + activity.getClass().getSimpleName()
                    + "; badges=" + badges.size() + " collapsed=" + hidden + " ancestry=" + shape);
        }
        return hidden;
    }

    private static void collectBadges(View view, List<View> out) {
        if (view == null || out.size() > 64) return;
        if (isAdBadge(view)) {
            out.add(view);
            return;                       // 角标本身当根，不必再往里找
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectBadges(group.getChildAt(i), out);
        }
    }

    /** 角标判定：只认短文本的「广告」，免得把「开通黄金VIP关闭此广告」这种长句误当成角标。 */
    private static boolean isAdBadge(View view) {
        if (!(view instanceof TextView)) return false;
        CharSequence text = ((TextView) view).getText();
        if (text == null) text = view.getContentDescription();
        return text != null && isAdBadgeText(text.toString());
    }

    /** 角标判定：只认短文本的「广告」，免得把「开通黄金VIP关闭此广告」这种长句误当成角标。 */
    static boolean isAdBadgeText(String raw) {
        if (raw == null) return false;
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 6) return false;
        return value.contains("广告");
    }

    /** 从角标向上找到所属卡片。找不到就返回 null——绝不猜。 */
    private static View cardOf(View badge) {
        int widthPixels;
        int heightPixels;
        try {
            widthPixels = badge.getResources().getDisplayMetrics().widthPixels;
            heightPixels = badge.getResources().getDisplayMetrics().heightPixels;
        } catch (Throwable error) {
            return null;
        }
        if (widthPixels <= 0 || heightPixels <= 0) return null;
        int minWidth = widthPixels / 4;
        int minHeight = heightPixels / 14;
        View current = badge;
        ViewParent parent = badge.getParent();
        for (int depth = 0; depth < 6 && parent instanceof View; depth++) {
            View next = (View) parent;
            // 别越过列表容器：那是整个信息流，不是卡片
            if (isListContainer(next)) return current;
            if (next.getWidth() >= minWidth && next.getHeight() >= minHeight
                    && next.getWidth() < widthPixels) {
                // 安全闸：一张卡片永远不会同时又几乎占满整屏。第一版缺这道闸，
                // 从角标一路走到根节点返回了 1264x2269 的整页内容视图，收起它等于把首页清空——
                // 而日志里那一行 hit= 看起来完全像成功。命中不等于命中得对。
                if (next.getWidth() < widthPixels * 9 / 10
                        || next.getHeight() < heightPixels * 7 / 10) {
                    return next;
                }
                return null;
            }
            current = next;
            parent = next.getParent();
        }
        // 没有符合几何条件的祖先：如实放弃。以前这里 return current，于是把整页收走了。
        return null;
    }

    private static boolean isListContainer(View view) {
        String name = view.getClass().getName();
        return name.contains("RecyclerView") || name.contains("ListView")
                || name.contains("PinnedSectionRecyclerView") || name.contains("ViewPager");
    }

    private static boolean collapse(View card) {
        if (card == null) return false;
        try {
            if (card.getVisibility() == View.GONE) return false;
            for (int i = 0; i < COLLAPSED.size(); i++) {
                if (COLLAPSED.get(i).get() == card) return false;
            }
            // 独立的第二道闸：不管调用方怎么定位，都不收起「几乎占满整屏」的东西。
            // 收起根视图等于让用户面对白屏，而这种破坏在日志里长得和一次成功拦截一模一样。
            int widthPixels = card.getResources().getDisplayMetrics().widthPixels;
            int heightPixels = card.getResources().getDisplayMetrics().heightPixels;
            if (widthPixels > 0 && heightPixels > 0
                    && card.getWidth() >= widthPixels * 9 / 10
                    && card.getHeight() >= heightPixels * 7 / 10) {
                H.warn("event=feed_ad_card_refused ref=" + card.getWidth() + "x"
                        + card.getHeight() + " screen=" + widthPixels + "x" + heightPixels
                        + " — looks like a page root, not a card");
                return false;
            }
            ViewGroup.LayoutParams params = card.getLayoutParams();
            if (params != null) {
                params.height = 0;
                card.setLayoutParams(params);
            }
            card.setVisibility(View.GONE);
            COLLAPSED.add(new WeakReference<View>(card));
            if (COLLAPSED.size() > 256) COLLAPSED.remove(0);
            collapseEmptyRow(card);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * 一行里往往并排放着两张广告卡（优酷「更多精彩」就是两列）。
     * 只收起自己会留下一个空位，行高还在，看起来像缺了一块。
     * 所以：父容器里如果已经没有可见子项，就把整行也收掉——
     * 这正是「整个块隐藏」，而只对**确实空了**的行做，不会误伤混排的正常内容。
     */
    private static void collapseEmptyRow(View card) {
        try {
            ViewParent parent = card.getParent();
            if (!(parent instanceof ViewGroup)) return;
            ViewGroup row = (ViewGroup) parent;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child == null) continue;
                if (child.getVisibility() == View.VISIBLE) return;
            }
            if (row.getChildCount() == 0) return;
            // 同样的页面根闸：空行也不该大到占满整屏
            int w = row.getResources().getDisplayMetrics().widthPixels;
            int h = row.getResources().getDisplayMetrics().heightPixels;
            if (w > 0 && h > 0 && row.getWidth() >= w * 9 / 10 && row.getHeight() >= h * 7 / 10) {
                return;
            }
            ViewGroup.LayoutParams params = row.getLayoutParams();
            if (params != null) {
                params.height = 0;
                row.setLayoutParams(params);
            }
            row.setVisibility(View.GONE);
        } catch (Throwable error) {
            // 收不掉空行只是留个空位，不影响正确性
        }
    }

    /**
     * 登录态变化或复用视图后缓存要作废，否则新出现的同款卡片会被误认为已处理。
     */
    static void resetCards() {
        COLLAPSED.clear();
    }
}
