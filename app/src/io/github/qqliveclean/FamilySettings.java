package io.github.qqliveclean;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * 合并进来的四套规则集（QQ 音乐 / 滴滴 / 淘系 / 微博）的设置通道。
 *
 * <p>它们原本各自调用 {@code getRemotePreferences(GROUP)}。那条路在本机上是不通的：
 * {@link ConfigProvider} 的注释已经写明，lspd 从不对本模块的 XposedService 发 SendBinder，
 * onServiceBind 永远不触发，于是每个开关都会悄悄退回内置默认值。微博在日志里报的
 * 「本机缓存（设置连接未成功，请先打开模块）」就是这条通道失效的直接证据。
 *
 * <p>所以这里复用宿主那条已经验证能用的路：设置页写普通 SharedPreferences，
 * {@link ConfigProvider} 通过 call() 下发，进程启动时读一次。
 *
 * <p>键必须带前缀。宿主的键是平铺在同一个 SharedPreferences 里的，而 QQ音乐 的 block_splash /
 * reduce_preload 与宿主的同名；不加前缀的话，QQ音乐的开关会被视频应用的设置驱动，
 * 而且两边都看不出异常。四个前缀彼此也不冲突（微博和淘系同样都有 splash / preload）。
 *
 * <p>读取端仍是 SharedPreferences，是为了让三个调用点各只改一行；缺失的键直接回落到
 * 调用点自己传的默认值，所以本类不需要为每个键再存一份默认值。
 */
public final class FamilySettings {

    public static final String QQMUSIC = "qqmusic";
    public static final String DIDI = "didi";
    public static final String TAOBAO = "taobao";
    public static final String WEIBO = "weibo";

    public static final String[] FAMILIES = {QQMUSIC, DIDI, TAOBAO, WEIBO};
    private static final String[] PREFIXES = {"qm_", "dd_", "tb_", "wb_"};

    public static final String[] FAMILY_LABELS = {"QQ 音乐", "滴滴出行", "淘宝 / 闲鱼", "微博"};

    private static final String[][] KEYS = {
            {"block_splash", "reduce_preload", "tab_video", "tab_ksong", "tab_star", "tab_my",
             "home_only_recommend", "hide_home_promo", "show_recognizer", "show_benefits", "diagnostics"},
            {"no_ads", "popup", "splash", "block_dialogs", "hide_promo_card", "hide_home_banner",
             "hide_bottom_nav", "keep_home_tabs", "hide_top_tabs", "hide_top_tools", "hide_scene_row",
             "probes"},
            {"splash_enabled", "popup_enabled", "widget_enabled", "sdk_enabled"},
            {"splash", "preload", "feed", "cards", "banners", "floating", "video", "discover", "messages",
             "dots", "redpacket", "keywords", "users", "comment_filter", "comment_location",
             "decor_background", "decor_avatar", "decor_keywords", "follow_recommend", "feed_trends",
             "feed_headers", "original_images", "clipboard_guard", "copy_clean", "mine_ads", "mine_wallet",
             "mine_tasks", "mine_creator", "mine_recommend", "flow_ads", "mine_vip_ads",
             "video_preroll_ads", "video_overlay_ads", "carousel_ads"},
    };

    private static final String[][] LABELS = {
            {"拦截开屏广告", "减少预加载", "保留底部「视频」", "保留底部「K歌」", "保留底部「明星」",
             "保留底部「我的」", "首页仅推荐", "隐藏首页推广区", "显示听歌识曲入口", "显示福利入口", "诊断信息"},
            {"全局关闭广告请求", "拦截广告弹窗", "拦截开屏广告", "拦截通用弹窗（默认关）",
             "隐藏首页推广卡", "隐藏首页横幅", "隐藏底部导航栏", "底部栏只留首页/我的", "隐藏顶部标签",
             "隐藏顶部工具区", "隐藏场景入口行", "诊断信息"},
            {"拦截开屏广告", "拦截广告弹窗", "拦截广告组件", "拦截广告 SDK"},
            {"拦截开屏广告", "阻止开屏预加载", "过滤信息流广告", "过滤页面广告卡片", "隐藏页面顶部横幅",
             "隐藏页面悬浮推广", "隐藏底栏「视频」", "隐藏底栏「发现」", "隐藏底栏「消息」",
             "隐藏底栏红点", "隐藏红包活动悬浮窗", "按关键词过滤微博", "按用户过滤微博",
             "评论内容与用户过滤", "按地区过滤评论", "隐藏博文右上角装饰", "隐藏头像装饰",
             "移除关键词推广链接", "隐藏关注推荐卡片", "隐藏信息流趋势推荐", "隐藏首页顶部推荐区",
             "大图优先读取原图", "禁止微博读取剪贴板", "复制时清理评论标记", "隐藏我的页面广告卡片",
             "隐藏我的钱包卡片", "隐藏我的任务卡片", "隐藏创作者中心卡片", "隐藏我的页面用户推荐",
             "过滤新版页面商业广告", "隐藏个人中心会员促销", "过滤视频插播广告片段",
             "隐藏视频广告浮层", "过滤发现页轮播广告"},
    };

    /**
     * 微博自带 defaultOn()：只有广告类默认开启，其余默认关闭。这里保持一致，
     * 免得设置页显示的初始状态和规则实际采用的默认值对不上。
     */
    private static final java.util.Set<String> WEIBO_DEFAULT_ON = new java.util.HashSet<String>(
            java.util.Arrays.asList("splash", "preload", "feed", "cards", "redpacket", "mine_ads",
                    "flow_ads", "mine_vip_ads", "video_preroll_ads", "video_overlay_ads", "carousel_ads"));

    private static final boolean[][] DEFAULTS = {
            {true, true, true, true, true, true, false, true, true, false, false},
            {true, true, true, false, true, true, false, false, true, true, false, true},
            {true, true, true, true},
            {},
    };

    private FamilySettings() {}

    public static int indexOf(String family) {
        for (int i = 0; i < FAMILIES.length; i++) {
            if (FAMILIES[i].equals(family)) return i;
        }
        return -1;
    }

    public static String[] keysOf(String family) {
        int index = indexOf(family);
        return index < 0 ? new String[0] : KEYS[index];
    }

    public static String[] labelsOf(String family) {
        int index = indexOf(family);
        return index < 0 ? new String[0] : LABELS[index];
    }

    public static String labelOf(String family, String key) {
        int index = indexOf(family);
        if (index < 0) return key;
        String[] keys = KEYS[index];
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) return LABELS[index][i];
        }
        return key;
    }

    /** Provider whitelist + settings page default; never throws for an unknown key. */
    public static boolean defaultFor(String family, String key) {
        int index = indexOf(family);
        if (index < 0) return false;
        if (WEIBO.equals(family)) return WEIBO_DEFAULT_ON.contains(key);
        String[] keys = KEYS[index];
        boolean[] values = DEFAULTS[index];
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key) && i < values.length) return values[i];
        }
        return false;
    }

    /** Unprefixed key -> the key actually published by ConfigProvider. */
    public static String prefixed(String family, String key) {
        int index = indexOf(family);
        return index < 0 ? key : PREFIXES[index] + key;
    }

    /** Every family key, prefixed, for {@link Config#EXPOSED_KEYS}. */
    public static String[] exposedKeys() {
        java.util.ArrayList<String> all = new java.util.ArrayList<String>();
        for (String family : FAMILIES) {
            for (String key : keysOf(family)) all.add(prefixed(family, key));
        }
        return all.toArray(new String[0]);
    }

    /** True when {@code key} (prefixed or not) belongs to one of the merged rule sets. */
    public static boolean isFamilyKey(String key) {
        for (int i = 0; i < PREFIXES.length; i++) {
            if (key.startsWith(PREFIXES[i])) return true;
        }
        return false;
    }

    /** Resolve a prefixed key back to its family, or null. */
    public static String familyOfPrefix(String key) {
        for (int i = 0; i < PREFIXES.length; i++) {
            if (key.startsWith(PREFIXES[i])) return FAMILIES[i];
        }
        return null;
    }

    public static boolean defaultForPrefixed(String key) {
        String family = familyOfPrefix(key);
        if (family == null) return false;
        return defaultFor(family, key.substring(PREFIXES[indexOf(family)].length()));
    }

    /**
     * A read-only SharedPreferences over the provider payload, translating the rule sets'
     * unprefixed key names onto the prefixed ones that were published.
     */
    public static SharedPreferences prefs(Context context, String family) {
        Bundle bundle = context == null ? null : Config.readFromProvider(context);
        final String prefix = indexOf(family) < 0 ? "" : PREFIXES[indexOf(family)];
        final Map<String, Object> values = new HashMap<String, Object>();
        if (bundle != null) {
            for (String key : bundle.keySet()) {
                if (key == null || !key.startsWith(prefix)) continue;
                Object value = bundle.get(key);
                if (value != null) values.put(key.substring(prefix.length()), value);
            }
        }
        return new View(values);
    }

    private static final class View implements SharedPreferences {
        private final Map<String, ?> values;

        View(Map<String, ?> values) {
            this.values = values;
        }

        private Object raw(String key) {
            return key == null ? null : values.get(key);
        }

        @Override public boolean getBoolean(String key, boolean fallback) {
            Object value = raw(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override public String getString(String key, String fallback) {
            Object value = raw(key);
            return value instanceof String ? (String) value : fallback;
        }

        @Override public int getInt(String key, int fallback) {
            Object value = raw(key);
            return value instanceof Integer ? (Integer) value : fallback;
        }

        @Override public long getLong(String key, long fallback) {
            Object value = raw(key);
            return value instanceof Long ? (Long) value : fallback;
        }

        @Override public float getFloat(String key, float fallback) {
            Object value = raw(key);
            return value instanceof Float ? (Float) value : fallback;
        }

        @Override public java.util.Set<String> getStringSet(String key, java.util.Set<String> fallback) {
            Object value = raw(key);
            return value instanceof java.util.Set<?> ? (java.util.Set<String>) value : fallback;
        }

        @Override public Map<String, ?> getAll() {
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            copy.putAll(values);
            return copy;
        }

        @Override public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override public SharedPreferences.Editor edit() {
            throw new UnsupportedOperationException("rule sets only read settings");
        }

        @Override public void registerOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener listener) {}

        @Override public void unregisterOnSharedPreferenceChangeListener(
                SharedPreferences.OnSharedPreferenceChangeListener listener) {}
    }
}