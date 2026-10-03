package io.github.qqliveclean.apps.weibo;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

final class Config {
 static final String HOST="com.sina.weibo", SELF="io.github.qqliveclean.apps.weibo";
 static final Uri URI=Uri.parse("content://io.github.qqliveclean.apps.weibo.settings/state");
 static final String[] TEXT_KEYS={"keyword_rules","user_rules","location_rules"};
 static final String[] KEYS={"splash","preload","feed","cards","banners","floating","video","discover","messages","dots","redpacket","keywords","users","comment_filter","comment_location","decor_background","decor_avatar","decor_keywords","follow_recommend","feed_trends","feed_headers","original_images","clipboard_guard","copy_clean","mine_ads","mine_wallet","mine_tasks","mine_creator","mine_recommend","flow_ads","mine_vip_ads","video_preroll_ads","video_overlay_ads","carousel_ads"};
 static final String[] TITLES={"拦截开屏广告","阻止开屏广告预加载","过滤信息流广告","过滤页面广告卡片","隐藏页面顶部横幅","隐藏页面悬浮推广","隐藏底栏「视频」","隐藏底栏「发现」","隐藏底栏「消息」","隐藏底栏红点","隐藏红包活动悬浮窗","按关键词过滤微博","按用户过滤微博","评论内容与用户过滤","按地区过滤评论","隐藏博文右上角装饰","隐藏头像装饰","移除关键词推广链接","隐藏关注推荐卡片","隐藏信息流趋势推荐","隐藏首页顶部推荐区","大图优先读取原图","禁止微博读取剪贴板","复制时清理评论标记","隐藏我的页面广告卡片","隐藏我的钱包卡片","隐藏我的任务卡片","隐藏创作者中心卡片","隐藏我的页面用户推荐","过滤新版页面商业广告","隐藏个人中心会员促销","过滤视频插播广告片段","隐藏视频广告浮层","过滤发现页轮播广告"};
 static final String[] DETAILS={"冷启动及返回前台时的开屏展示","减少开屏广告刷新任务，不影响正常视频缓存","保留普通微博，过滤已识别为广告的内容","处理卡片列表中的明确广告标记","会隐藏卡片页顶部的全部横幅，包括普通活动","移除卡片页悬浮卡片，也可能包含非广告活动","保留首页与我；设置后重启微博","保留首页与我；设置后重启微博","隐藏入口不会删除消息，也不会阻止接收","仅隐藏导航上的未读数字提示","阻止首页右下角红包活动入口创建；不处理聊天红包","一行一个词，匹配正文；仅影响已适配列表，不删除内容","用户名或 UID，一行一项，精确匹配","使用同一组关键词、用户规则；未支持的评论列表会跳过","匹配评论显示的发布地区；一行一个地区","移除博文背景装饰，保留正文与图片","隐藏头像圈与扩展装饰，保留头像","移除正文关键词结构，不改普通网页链接","移除博文附带的推荐关注模块","移除信息流 trends 推荐项","移除信息流 headers 区，可能包含直播和普通推荐","大图地址可用时替换为原图，可能增加流量；不修改水印","包含主动粘贴也会被阻止，需要粘贴时请关闭并重启","复制文本时移除 [cp] 与 [/cp]，保留其余文字","按已识别卡片标识过滤图片和活动广告","仅隐藏独立钱包卡片；顶部网格入口可能仍保留","隐藏任务中心和红包活动卡片","仅隐藏独立卡片，不影响账号功能","过滤明确标识的用户推荐卡片","过滤发现、详情等新版列表中带广告类型和广告 ID 的卡片，保留普通推荐","只移除会员中心促销横幅，保留头像、会员标识和功能入口","清空独立广告片段列表，保留正片；不改视频中的创作者口播","移除广告横幅、广告商品浮层与广告按钮，保留正常播放","只过滤带广告角标的轮播子项，保留热搜与普通轮播内容"};
 static final String[] AD_KEYS={"splash","preload","feed","cards","redpacket","mine_ads","flow_ads","mine_vip_ads","video_preroll_ads","video_overlay_ads","carousel_ads"};
 static boolean defaultOn(String key){for(String ad:AD_KEYS)if(ad.equals(key))return true;return false;}
 static SharedPreferences prefs(Context c){return c.getSharedPreferences("settings",0);}
 static Bundle bundle(Context c){Bundle b=new Bundle();for(String k:KEYS)b.putBoolean(k,prefs(c).getBoolean(k,defaultOn(k)));for(String k:TEXT_KEYS)b.putString(k,prefs(c).getString(k,""));b.putLong("revision",prefs(c).getLong("revision",0));return b;}
 static void grant(Context c){try{c.grantUriPermission(HOST,URI,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}
 static String title(String k){for(int i=0;i<KEYS.length;i++)if(KEYS[i].equals(k))return TITLES[i];return k;}
}
