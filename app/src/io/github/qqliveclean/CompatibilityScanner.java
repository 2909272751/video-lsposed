package io.github.qqliveclean;

import android.content.Context;
import android.content.SharedPreferences;

/** Presents the latest report that a target sent to the module app. */
final class CompatibilityScanner {
    private CompatibilityScanner() {}

    static String scan(Context context, String packageName) {
        SharedPreferences report = context.getSharedPreferences(ReportReceiver.PREFS, Context.MODE_PRIVATE);
        String prefix = packageName + ".";
        String rows = report.getString(prefix + "rows", null);
        if (rows == null || rows.length() == 0)
            return "还没有收到这款应用的兼容结果。请打开一次目标应用，返回后重试。";
        long age = System.currentTimeMillis() - report.getLong(prefix + "time", 0);
        String version = report.getString(prefix + "version", "未知");
        String source = report.getString(prefix + "source", "未知");
        boolean verified = report.getBoolean(prefix + "verified", false);
        StringBuilder detail = new StringBuilder();
        int found = 0, missed = 0, off = 0, unsupported = 0;
        for (String row : rows.split("\n")) {
            String[] parts = row.split("\t", 4);
            if (parts.length < 3) continue;
            String key = parts[0], outcome = parts[1];
            boolean hit = "1".equals(parts[2]);
            String why = parts.length > 3 ? parts[3] : "";
            if ("matched".equals(outcome)) {
                found++;
                detail.append(hit ? "✓ 已触发 " : "○ 已找到 ");
            } else if ("miss".equals(outcome)) {
                missed++;
                detail.append("✕ 失败 ");
            } else {
                if (why.startsWith("withdrawn") || why.startsWith("not armed")
                        || why.startsWith("live player route")) {
                    unsupported++;
                    detail.append("△ 暂不支持 ");
                } else {
                    off++;
                    detail.append("— 已关闭 ");
                }
            }
            detail.append(name(key));
            if (("miss".equals(outcome) || ("off".equals(outcome)
                    && (why.startsWith("withdrawn") || why.startsWith("not armed")
                    || why.startsWith("live player route")))) && why.length() != 0)
                detail.append("\n    ").append(why);
            detail.append('\n');
        }
        StringBuilder result = new StringBuilder();
        result.append("宿主版本：").append(version)
                .append(verified ? "（已验证版本）" : "（新版本，逐项适配）").append('\n');
        result.append("设置来源：").append(source).append('\n');
        if (age > 86400000L) result.append("这份结果已超过一天，请重新打开应用更新。\n");
        result.append("已找到 ").append(found).append(" 项 · 失败 ").append(missed)
                .append(" 项 · 暂不支持 ").append(unsupported)
                .append(" 项 · 已关闭 ").append(off).append(" 项\n\n");
        result.append(detail);
        result.append("\n已找到表示规则已安装；已触发表示本次运行实际走到该规则。");
        return result.toString();
    }

    private static String name(String id) {
        if (id.equals("mine_ad_card")) return "腾讯视频 · 我的广告卡片";
        if (id.equals("mine_banner_request")) return "腾讯视频 · 我的横幅请求";
        if (id.equals("mine_banner_response")) return "腾讯视频 · 我的横幅返回";
        if (id.equals("youku_mine_vip_promo")) return "优酷 · 我的活动条";
        if (id.equals("youku_mine_carousel")) return "优酷 · 我的活动轮播";
        if (id.equals("iqiyi_mine_banner")) return "爱奇艺 · 我的广告横幅";
        if (id.equals("iqiyi_home_member_banner")) return "爱奇艺 · 首页会员促销横幅";
        if (id.equals("feed_autoplay")) return "腾讯视频 · 首页片段自动播放";
        if (id.contains("splash")) return "开屏广告（" + id + "）";
        if (id.contains("top_ad")) return "首页顶部轮播广告";
        if (id.contains("tab_filter")) return "底部标签";
        if (id.contains("pause_ad")) return "暂停广告";
        if (id.contains("player") || id.contains("midroll")) return "播放广告（" + id + "）";
        if (id.contains("ad_request") || id.contains("ad_slot") || id.contains("dsp"))
            return "广告请求（" + id + "）";
        return id;
    }
}
