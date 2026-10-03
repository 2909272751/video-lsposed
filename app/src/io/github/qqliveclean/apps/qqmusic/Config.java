package io.github.qqliveclean.apps.qqmusic;

import android.content.SharedPreferences;

final class Config {
    static final String PACKAGE = "com.tencent.qqmusic";
    static final String[] FEATURES = {"cold", "hot", "tabs", "home", "promo", "recognizer", "benefits", "preload"};
    static final String[] FEATURE_LABELS = {"冷启动开屏", "热启动开屏", "底部标签", "首页仅推荐", "首页推广区", "听歌识曲入口", "福利入口", "预加载"};
    static final String REPORT_SCHEMA = "8";
    static final String GROUP = "qqmusic_clean_settings";
    static final String BLOCK_SPLASH = "block_splash";
    static final String REDUCE_PRELOAD = "reduce_preload";
    static final String TAB_VIDEO = "tab_video";
    static final String TAB_KSONG = "tab_ksong";
    static final String TAB_STAR = "tab_star";
    static final String TAB_MY = "tab_my";
    static final String HOME_ONLY_RECOMMEND = "home_only_recommend";
    static final String HIDE_HOME_PROMO = "hide_home_promo";
    static final String SHOW_RECOGNIZER = "show_recognizer";
    static final String SHOW_BENEFITS = "show_benefits";
    static final String DIAGNOSTICS = "diagnostics";

    private Config() {}

    static boolean read(SharedPreferences prefs, String key, boolean defaultValue) {
        try { return prefs.getBoolean(key, defaultValue); }
        catch (Throwable ignored) { return defaultValue; }
    }
}
