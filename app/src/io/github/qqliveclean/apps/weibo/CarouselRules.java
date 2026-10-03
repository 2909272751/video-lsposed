package io.github.qqliveclean.apps.weibo;
import java.lang.reflect.*;
import java.util.*;

final class CarouselRules {
 private final MainHook h;
 private Method children,mark,title;
 private Field source, pages;
 CarouselRules(MainHook hook){h=hook;try{
  Class<?> c=h.type("com.sina.weibo.card.model.CardSpliceMultiple");c.getDeclaredField("CardSpliceMultiple__fields__");
  children=c.getDeclaredMethod("getSubItems");source=c.getDeclaredField("mSubItems");
  if(children.getReturnType()!=List.class||source.getType()!=List.class)throw new NoSuchMethodException();source.setAccessible(true);
  mark=h.type("com.sina.weibo.card.model.CardSpliceMultipleItem").getDeclaredMethod("getAdCornerMarkData");title=mark.getReturnType().getMethod("getTitle");
  Class<?> marquee=h.type("com.sina.weibo.card.model.CardMarqueeAlpha");marquee.getDeclaredField("CardMarqueeAlpha__fields__");pages=marquee.getDeclaredField("mItems");if(pages.getType()!=List.class)throw new NoSuchFieldException();pages.setAccessible(true);
 }catch(Exception e){children=null;}}
 private boolean advert(Object item)throws Exception{
  if(item==null||!mark.getDeclaringClass().isInstance(item))return false;
  Object data=mark.invoke(item);return data!=null&&"广告".equals(title.invoke(data));
 }
 boolean allAds(Object card){
  if(!h.on("carousel_ads")||children==null||card==null)return false;
  if(pages!=null&&pages.getDeclaringClass().isInstance(card))try{Object raw=pages.get(card);if(!(raw instanceof List)||((List<?>)raw).isEmpty())return false;for(Object item:(List<?>)raw)if(!allAds(item))return false;return true;}catch(Exception e){h.fault("carousel_ads",e);return false;}
  if(!source.getDeclaringClass().isInstance(card))return false;
  try{Object raw=source.get(card);if(!(raw instanceof List)||((List<?>)raw).isEmpty())return false;for(Object item:(List<?>)raw)if(!advert(item))return false;return true;}catch(Exception e){h.fault("carousel_ads",e);return false;}
 }
 void install(){h.rule("carousel_ads",()->{
  if(children==null)throw new NoSuchMethodException("轮播广告标记未识别");
  Method pageList=pages.getDeclaringClass().getDeclaredMethod("getItems");if(pageList.getReturnType()!=List.class)throw new NoSuchMethodException("轮播页面结构变化");
  h.attach(pageList,chain->h.ads.filter(chain.proceed()));
  h.attach(children,chain->{Object raw=chain.proceed();if(!h.on("carousel_ads")||!(raw instanceof List))return raw;List<?> old=(List<?>)raw;ArrayList<Object> kept=null;
   try{for(int i=0;i<old.size();i++){Object item=old.get(i);if(advert(item)){if(kept==null)kept=new ArrayList<>(old.subList(0,i));h.hit("carousel_ads",1);}else if(kept!=null)kept.add(item);}}catch(Exception e){h.fault("carousel_ads",e);return raw;}return kept==null?raw:kept;
  });
  Method group=h.type("com.sina.weibo.card.model.CardGroup").getDeclaredMethod("getCardsList");if(group.getReturnType()!=List.class)throw new NoSuchMethodException("轮播分组签名变化");
  h.attach(group,chain->h.ads.filter(chain.proceed()));
  return "按轮播子项广告标识过滤；纯广告页整体移除，混合页保留普通项";
 });}
}
