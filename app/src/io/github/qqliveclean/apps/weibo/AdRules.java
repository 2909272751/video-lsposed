package io.github.qqliveclean.apps.weibo;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.*;
import java.util.*;

/** Advertising-only additions. Ordinary recommendations and account tools are not classified by text. */
final class AdRules {
 private final MainHook h;
 private final CarouselRules carousel;
 private Method cardAd,cellData,promotion,adType,adId;
 AdRules(MainHook hook){h=hook;carousel=new CarouselRules(h);
  try{cardAd=h.type("com.sina.weibo.card.model.PageCardInfo").getMethod("isAd");if(cardAd.getReturnType()!=boolean.class)cardAd=null;}catch(Exception ignored){}
  try{
   cellData=h.type("com.sina.weibo.flow.cell.base.CellItem").getMethod("getAdData");
   Class<?> p=h.type("com.sina.weibo.compat.IPromotion");
   for(Method m:cellData.getReturnType().getMethods())if(m.getParameterTypes().length==0&&m.getReturnType()==p){if(promotion!=null)throw new NoSuchMethodException("ambiguous promotion");promotion=m;}
   Class<?> concrete=h.type("com.sina.weibo.models.Promotion");adType=concrete.getMethod("getAdtype");adId=concrete.getMethod("getId");
  }catch(Exception ignored){cellData=null;promotion=null;}
 }
 String reason(Object item){
  if(item==null)return null;
  if(carousel.allAds(item))return "carousel_ads";
  if(h.on("cards")&&cardAd!=null&&cardAd.getDeclaringClass().isInstance(item))try{if(Boolean.TRUE.equals(cardAd.invoke(item)))return "cards";}catch(Exception e){h.fault("cards",e);}
  if(h.on("flow_ads")&&cellData!=null&&promotion!=null&&cellData.getDeclaringClass().isInstance(item))try{
   Object data=cellData.invoke(item),p=data==null?null:promotion.invoke(data);
   if(p!=null&&adType.getDeclaringClass().isInstance(p)&&AdSignals.promotion((String)adType.invoke(p),(String)adId.invoke(p)))return "flow_ads";
  }catch(Exception e){h.fault("flow_ads",e);}
  return null;
 }
 Object filter(Object raw){
  if(!(raw instanceof List))return raw;List<?> old=(List<?>)raw;ArrayList<Object> next=null;
  for(int i=0;i<old.size();i++){Object item=old.get(i);String key=reason(item);if(key!=null){if(next==null)next=new ArrayList<>(old.subList(0,i));h.hit(key,1);}else if(next!=null)next.add(item);}
  return next==null?raw:next;
 }
 void install(boolean modern){
  carousel.install();
  h.rule("flow_ads",()->{if(!modern||cellData==null||promotion==null)throw new NoSuchMethodException("新版商业广告标记入口未识别");return "新版列表过滤：需要广告类型及广告 ID；不按内容文字判断";});
  h.rule("mine_vip_ads",()->{
   Class<?> c=h.type("com.sina.weibo.minev2.cell.header.HeaderCellItem");
   Method get=c.getDeclaredMethod("getVipCenterData");if(!get.getReturnType().getName().endsWith(".VipCenterData"))throw new NoSuchMethodException("会员推广结构变化");
   h.attach(get,chain->{Object old=chain.proceed();if(!h.on("mine_vip_ads"))return old;if(old!=null)h.hit("mine_vip_ads",1);return null;});
   Method component=c.getDeclaredMethod("getComponentData",String.class);
   h.attach(component,chain->{if(h.on("mine_vip_ads")&&("vipCenter".equals(chain.getArg(0))||"vipView".equals(chain.getArg(0)))){Object old=chain.proceed();if(old!=null)h.hit("mine_vip_ads",1);return null;}return chain.proceed();});
   Class<?> view=h.type("com.sina.weibo.minev2.component.vipview.VipLayoutView");view.getDeclaredField("VipLayoutView__fields__");
   int bindings=0;
   for(Method m:view.getDeclaredMethods())if(Modifier.isPublic(m.getModifiers())&&m.getReturnType()==void.class&&m.getParameterTypes().length==1&&m.getParameterTypes()[0].getName().startsWith("com.sina.weibo.minev2.component.vipview.")){
    h.attach(m,chain->{if(!h.on("mine_vip_ads"))return chain.proceed();View v=(View)chain.getThisObject();if(v.getVisibility()!=View.GONE){v.setVisibility(View.GONE);ViewGroup.LayoutParams lp=v.getLayoutParams();if(lp!=null){lp.height=0;v.setLayoutParams(lp);}h.hit("mine_vip_ads",1);}return null;});bindings++;
   }
   if(bindings==0)throw new NoSuchMethodException("会员促销绑定入口未识别");
   return "仅会员促销横幅；保留头像、会员标识与功能网格";
  });
  h.rule("video_preroll_ads",()->{
   Class<?> c=h.type("com.sina.weibo.models.MediaDataObject");Method list=c.getDeclaredMethod("getAd_videos");
   if(list.getReturnType()!=List.class)throw new NoSuchMethodException("广告片段列表类型变化");
   h.attach(list,chain->{Object old=chain.proceed();if(!h.on("video_preroll_ads"))return old;if(old instanceof List&&!((List<?>)old).isEmpty())h.hit("video_preroll_ads",((List<?>)old).size());return new ArrayList<>();});
   return "广告插播片段列表返回空，保留正片地址与普通播放流程";
  });
  h.rule("video_overlay_ads",()->{
   Class<?> status=h.type("com.sina.weibo.models.Status"),video=h.type("com.sina.weibo.models.VideoInfo");
   Field banner=video.getField("adBanner"),info=video.getField("ad_info");
   List<Field> fields=new ArrayList<>();for(String name:new String[]{"float_view","top_banner","scroll_banner","bottom_button","mask_view"})try{Field f=info.getType().getField(name);if(!f.getType().isPrimitive())fields.add(f);}catch(NoSuchFieldException ignored){}
   if(fields.isEmpty())throw new NoSuchMethodException("视频广告浮层字段未识别");int count=0;
   for(Method m:status.getDeclaredMethods())if(!m.isBridge()&&m.getParameterTypes().length==0&&m.getReturnType()==video&&(m.getName().equals("getMainVideoInfo")||m.getName().equals("getChildVideoInfo"))){
    h.attach(m,chain->{Object result=chain.proceed();if(result==null||!h.on("video_overlay_ads"))return result;
     try{int n=0;if(banner.get(result)!=null){banner.set(result,null);n++;}Object ad=info.get(result);if(ad!=null)for(Field f:fields)if(f.get(ad)!=null){f.set(ad,null);n++;}if(n>0)h.hit("video_overlay_ads",n);}catch(Throwable e){h.fault("video_overlay_ads",e);}return result;});count++;
   }
   if(count==0)throw new NoSuchMethodException("视频数据入口未识别");return "视频广告横幅、商品浮层与广告按钮；保留正常视频信息";
  });
 }
 static int installSplashDisplay(MainHook h)throws Exception{
  Class<?> c=h.type("com.sina.weibo.mobileads.view.FlashAd");Method show=c.getDeclaredMethod("showFromLoadManager",Activity.class,Intent.class);
  if(show.getReturnType()!=void.class)throw new NoSuchMethodException("开屏展示签名变化");
  h.attach(show,chain->{if(!h.on("splash"))return chain.proceed();Activity activity=(Activity)chain.getArg(0);Intent next=(Intent)chain.getArg(1);
   if(next!=null){if(activity==null||activity.isFinishing())return chain.proceed();try{activity.startActivity(next);}catch(Throwable e){h.fault("splash",e);return chain.proceed();}}
   h.hit("splash",1);return null;
  });return 1;
 }
}
