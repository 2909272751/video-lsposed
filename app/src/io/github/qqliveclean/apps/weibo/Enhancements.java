package io.github.qqliveclean.apps.weibo;

import android.content.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Independent rules based on host model contracts; never copies another module's executable code. */
final class Enhancements {
 static final String[] CARD_KEYS={"mine_ads","mine_wallet","mine_tasks","mine_creator","mine_recommend"};
 static boolean cardsEnabled(MainHook h){for(String k:CARD_KEYS)if(h.on(k))return true;return h.on("keywords")||h.on("users");}
 static void install(MainHook h,boolean cardsInstalled){
  getter(h,"decor_background","com.sina.weibo.models.Status","getPicBg",String.class,"");
  h.rule("decor_avatar",()->{Class<?> c=h.type("com.sina.weibo.models.JsonUserInfo");int n=0;
   for(String name:new String[]{"getAvatarCircle","getUserAvatarExtendInfo"})try{Method m=c.getDeclaredMethod(name);if(m.getReturnType().isPrimitive())continue;replace(h,"decor_avatar",m,null);n++;}catch(NoSuchMethodException ignored){}
   if(n==0)throw new NoSuchMethodException("头像装饰入口未找到");return n+" 个头像装饰读取入口";
  });
  getter(h,"decor_keywords","com.sina.weibo.models.Status","getKeyword_struct",List.class,Collections.emptyList());
  h.rule("follow_recommend",()->{Class<?> c=h.type("com.sina.weibo.models.Status");
   Method has=c.getDeclaredMethod("hasFollowRecommendData");if(has.getReturnType()!=boolean.class)throw new NoSuchMethodException();replace(h,"follow_recommend",has,false);
   Method get=c.getDeclaredMethod("getFollowRecommendData");if(get.getReturnType().isPrimitive())throw new NoSuchMethodException();replace(h,"follow_recommend",get,null);return "关注推荐判断及数据入口";
  });
  getter(h,"feed_trends","com.sina.weibo.models.MBlogListBaseObject","getTrends",List.class,Collections.emptyList());
  getter(h,"feed_headers","com.sina.weibo.models.MBlogListObject","getHeaders",List.class,Collections.emptyList());
  h.rule("original_images",()->{
   Class<?> c=h.type("com.sina.weibo.models.PicInfo");Method original=c.getDeclaredMethod("getOriginal"),url=c.getDeclaredMethod("getOriginalUrl");
   for(String name:new String[]{"getLarge","getLargeUrl"}){Method target=c.getDeclaredMethod(name);Method replacement=name.equals("getLarge")?original:url;
    if(target.getReturnType()!=replacement.getReturnType())throw new NoSuchMethodException("图片结构不一致");
    h.attach(target,chain->{Object fallback=chain.proceed();if(!h.on("original_images"))return fallback;try{String link=(String)url.invoke(chain.getThisObject());if(link==null||link.isEmpty())return fallback;
     Object result=replacement.invoke(chain.getThisObject());if(result!=null&&!Objects.equals(result,fallback)){h.hit("original_images",1);return result;}return fallback;
    }catch(Throwable e){h.fault("original_images",e);return fallback;}});
   }return "原图有有效地址时替换大图；缩略图保持原样";
  });
  h.rule("clipboard_guard",()->{
   Method read=ClipboardManager.class.getDeclaredMethod("getPrimaryClip"),has=ClipboardManager.class.getDeclaredMethod("hasPrimaryClip");
   h.attach(read,chain->{if(!h.on("clipboard_guard"))return chain.proceed();h.hit("clipboard_guard",1);return null;});
   h.attach(has,chain->{if(!h.on("clipboard_guard"))return chain.proceed();return false;});return "仅微博进程；读取剪贴板返回空";
  });
  h.rule("copy_clean",()->{
   Method write=ClipboardManager.class.getDeclaredMethod("setPrimaryClip",ClipData.class);
   h.attach(write,chain->{Object arg=chain.getArg(0);if(!h.on("copy_clean")||!(arg instanceof ClipData))return chain.proceed();
    ClipData data=(ClipData)arg;ClipData next=null;
    try{if(data.getItemCount()==1){ClipData.Item item=data.getItemAt(0);if(item.getText()!=null&&item.getIntent()==null&&item.getUri()==null&&item.getHtmlText()==null){String old=item.getText().toString(),clean=TextRules.cleanCopy(old);if(!old.equals(clean)){next=ClipData.newPlainText(data.getDescription().getLabel(),clean);h.hit("copy_clean",1);}}}}catch(Throwable e){h.fault("copy_clean",e);}
    return next==null?chain.proceed():chain.proceed(new Object[]{next});});return "仅清理单条纯文本复制，不改图片和链接剪贴板";
  });
  final boolean[] commentsInstalled={false};
  for(String key:new String[]{"comment_filter","comment_location"})h.rule(key,()->{
   if(!commentsInstalled[0]){Class<?> c=h.type("com.sina.weibo.models.JsonCommentList");Method m=null;
    for(Method candidate:c.getDeclaredMethods())if(!candidate.isBridge()&&candidate.getName().equals("initFromJsonObject")&&Arrays.equals(candidate.getParameterTypes(),new Class<?>[]{JSONObject.class})){m=candidate;break;}
    if(m==null)throw new NoSuchMethodException("评论解析入口未找到");
    h.attach(m,chain->{Object raw=chain.getArg(0);if(!(raw instanceof JSONObject)||(!(h.on("comment_filter")&&(!h.keywordRules.isEmpty()||!h.userRules.isEmpty()))&&!(h.on("comment_location")&&!h.locationRules.isEmpty())))return chain.proceed();Object ready=raw;
     try{JSONObject copy=new JSONObject(raw.toString());if(cleanComments(copy,h)>0)ready=copy;}catch(Throwable e){if(h.on("comment_filter"))h.fault("comment_filter",e);if(h.on("comment_location"))h.fault("comment_location",e);}
     return ready==raw?chain.proceed():chain.proceed(new Object[]{ready});});commentsInstalled[0]=true;
   }return "评论 JSON 解析入口；等待页面命中";
  });
  for(String key:CARD_KEYS)h.rule(key,()->{if(!cardsInstalled)throw new NoSuchMethodException("卡片共享入口安装失败");return "卡片列表入口；按卡片标识过滤，等待实机命中";});
 }
 private static void getter(MainHook h,String key,String cls,String name,Class<?> result,Object replacement){h.rule(key,()->{Method m=h.type(cls).getDeclaredMethod(name);if(m.getReturnType()!=result)throw new NoSuchMethodException("返回类型变化");replace(h,key,m,replacement);return "已找到 "+name+"；等待实际调用";});}
 private static void replace(MainHook h,String key,Method m,Object replacement){h.attach(m,chain->{Object old=chain.proceed();if(!h.on(key))return old;boolean changed=old!=null&&!Objects.equals(old,replacement)&&(!(old instanceof Collection)||!((Collection<?>)old).isEmpty());if(changed)h.hit(key,1);return replacement;});}
 static int cleanComments(JSONObject root,MainHook h)throws JSONException{
  int count=0;for(String field:new String[]{"comments","hot_comments","related_user_comments"}){
   JSONArray old=root.optJSONArray(field);if(old==null)continue;JSONArray kept=new JSONArray();
   for(int i=0;i<old.length();i++){Object value=old.opt(i);JSONObject c=value instanceof JSONObject?(JSONObject)value:null;String key=null;
    if(c!=null){if(h.on("comment_filter")&&(TextRules.contains(h.keywordRules,c.optString("text"))||userMatches(c.optJSONObject("user"),h.userRules)))key="comment_filter";
     else if(h.on("comment_location")&&TextRules.contains(h.locationRules,c.optString("source")))key="comment_location";}
    if(key==null)kept.put(value);else{count++;h.hit(key,1);}
   }if(kept.length()!=old.length())root.put(field,kept);
  }return count;
 }
 static boolean userMatches(JSONObject user,List<String> rules){return user!=null&&(TextRules.exact(rules,user.optString("idstr",user.optString("id")))||TextRules.exact(rules,user.optString("screen_name")));}
 static int cleanCards(JSONObject root,MainHook h,int depth)throws JSONException{
  if(depth>5)return 0;int count=0;for(String field:new String[]{"cards","card_group"}){
   JSONArray old=root.optJSONArray(field);if(old==null)continue;JSONArray kept=new JSONArray();
   for(int i=0;i<old.length();i++){Object value=old.opt(i);JSONObject c=value instanceof JSONObject?(JSONObject)value:null;String reason=null;
    if(c!=null){String id=c.optString("itemid");
     if(h.on("mine_ads")&&(id.equals("100505_-_advideo")||id.equals("100505_-_adphoto")))reason="mine_ads";
     else if(h.on("mine_wallet")&&id.equals("100505_-_mypay_new"))reason="mine_wallet";
     else if(h.on("mine_tasks")&&(id.equals("100505_-_newusertask")||id.equals("100505_-_hongbao2022")))reason="mine_tasks";
     else if(h.on("mine_creator")&&id.equals("100505_-_newcreator"))reason="mine_creator";
     else if(h.on("mine_recommend")&&id.startsWith("100505_-_")&&id.contains("attent"))reason="mine_recommend";
     JSONObject blog=c.optJSONObject("mblog");if(reason==null&&blog!=null){if(h.on("keywords")&&TextRules.contains(h.keywordRules,blog.optString("text")))reason="keywords";else if(h.on("users")&&userMatches(blog.optJSONObject("user"),h.userRules))reason="users";}
     if(reason==null)count+=cleanCards(c,h,depth+1);
    }
    if(reason!=null){h.hit(reason,1);count++;}else kept.put(value);
   }if(kept.length()!=old.length())root.put(field,kept);
  }return count;
 }
}
