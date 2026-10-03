package io.github.qqliveclean.apps.didi;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 兼容结果渲染（参考 video-lsposed 的 CompatibilityScanner）。
 * 读取目标进程回传的 rows（key \t outcome \t hit \t why），渲染成人能看懂的报告。
 */
final class CompatibilityScanner {
    private CompatibilityScanner() {}

    static String scan(Context context) {
        SharedPreferences report = context.getSharedPreferences(Config.COMPAT_FILE, Context.MODE_PRIVATE);
        String rows = report.getString("rows", null);
        if (rows == null || rows.length() == 0) {
            return "还没收到兼容结果。\n请先「强制停止滴滴」，再打开一次滴滴，然后回到这里重试。";
        }
        long age = System.currentTimeMillis() - report.getLong("time", 0);
        String version = report.getString("version", "未知");
        String source = report.getString("source", "未知");
        String compat = report.getString("compat", "");

        StringBuilder detail = new StringBuilder();
        int found = 0, hit = 0, off = 0, missed = 0;
        for (String row : rows.split("\n")) {
            String[] parts = row.split("\t", 4);
            if (parts.length < 3) continue;
            String key = parts[0];
            String outcome = parts[1];
            boolean fired = "1".equals(parts[2]);
            String why = parts.length > 3 ? parts[3] : "";
            if ("matched".equals(outcome)) {
                if (fired) { hit++; detail.append("✓ 已触发 "); }
                else { found++; detail.append("○ 已布防 "); }
            } else if ("off".equals(outcome)) {
                off++;
                detail.append("— 已关闭 ");
            } else {
                missed++;
                detail.append("✕ 未匹配 ");
            }
            detail.append(Config.labelOf(key));
            if (why.length() > 0 && !"matched".equals(outcome)) detail.append("\n    ").append(why);
            if (fired && why.length() > 0 && "matched".equals(outcome)) detail.append("\n    ").append(why);
            detail.append('\n');
        }

        StringBuilder result = new StringBuilder();
        result.append("宿主版本：").append(version).append('\n');
        result.append("设置来源：").append(source).append('\n');
        if (compat != null && compat.length() > 0) result.append("锚点自检：").append(compat).append('\n');
        if (age > 86400000L) result.append("这份结果已超过一天，请重新打开应用更新。\n");
        result.append("已触发 ").append(hit).append(" 项 · 已布防 ").append(found)
                .append(" 项 · 已关闭 ").append(off).append(" 项 · 未匹配 ").append(missed).append(" 项\n\n");
        result.append(detail);
        result.append("\n说明：已触发 = 本次运行真的走到这条规则；已布防 = 钩子装好但本次没被调用；");
        result.append("已关闭 = 你把这个开关关了；未匹配 = 这个版本找不到锚点（会自动跳过，不影响其它功能）。");
        return result.toString();
    }
}
