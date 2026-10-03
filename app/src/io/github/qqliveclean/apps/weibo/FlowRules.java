package io.github.qqliveclean.apps.weibo;

import java.lang.reflect.*;
import java.util.*;

/** New host list framework. Only accepts the semantic marker and exact list contract. */
final class FlowRules {
 static boolean install(MainHook h){
  if(!h.on("feed")&&!h.on("cards")&&!h.on("flow_ads")&&!Enhancements.cardsEnabled(h))return false;
  try{
   Class<?> base=null;
   for(char suffix='a';suffix<='z';suffix++)try{
    Class<?> c=h.type("com.sina.weibo.flow.model."+suffix);c.getDeclaredField("BaseFlowListData__fields__");
    if(c.getDeclaredMethod("getItems").getReturnType()!=List.class||c.getDeclaredField("items").getType()!=List.class)continue;
    base=c;break;
   }catch(ClassNotFoundException|NoSuchFieldException|NoSuchMethodException ignored){}
   if(base==null)throw new NoSuchMethodException("新版列表标记未识别");
   Method isAd=h.type("com.sina.weibo.models.Status").getDeclaredMethod("isAd");
   Class<?> item=h.type("com.sina.weibo.flow.model.IFlowItem");
   Method id=item.getMethod("getFlowItemId");
   Method get=base.getDeclaredMethod("getItems"),set=base.getDeclaredMethod("setItems",List.class);
   h.attach(get,chain->{Object old=chain.proceed();try{return filter(old,chain.getThisObject(),h,isAd,id);}catch(Throwable e){fail(h,e);return old;}});
   h.attach(set,chain->{Object old=chain.getArg(0),next=old;try{next=filter(old,chain.getThisObject(),h,isAd,id);}catch(Throwable e){fail(h,e);}return next==old?chain.proceed():chain.proceed(new Object[]{next});});
   h.modernDetail="新版列表入口已挂接："+base.getName();return true;
  }catch(Throwable e){h.modernDetail="新版列表入口未适配："+e.getClass().getSimpleName()+"；仅尝试旧入口";return false;}
 }
 private static Object filter(Object raw,Object owner,MainHook h,Method isAd,Method id)throws Exception{
  Object data=h.ads.filter(h.filterFeed(raw,isAd));if(!(data instanceof List)||!owner.getClass().getName().startsWith("com.sina.weibo.minev2."))return data;
  List<?> old=(List<?>)data;ArrayList<Object> next=null;
  for(int i=0;i<old.size();i++){
   Object item=old.get(i);String key=null;
   if(item!=null&&id.getDeclaringClass().isInstance(item)){
    String identifier=String.valueOf(id.invoke(item));
    if(h.on("mine_ads")&&(identifier.equals("100505_-_advideo")||identifier.equals("100505_-_adphoto")))key="mine_ads";
    else if(h.on("mine_wallet")&&identifier.equals("100505_-_mypay_new"))key="mine_wallet";
    else if(h.on("mine_tasks")&&(identifier.equals("100505_-_newusertask")||identifier.equals("100505_-_hongbao2022")))key="mine_tasks";
    else if(h.on("mine_creator")&&identifier.equals("100505_-_newcreator"))key="mine_creator";
    else if(h.on("mine_recommend")&&identifier.startsWith("100505_-_")&&identifier.contains("attent"))key="mine_recommend";
   }
   if(key!=null){if(next==null)next=new ArrayList<>(old.subList(0,i));h.hit(key,1);}else if(next!=null)next.add(item);
  }
  return next==null?data:next;
 }
 private static void fail(MainHook h,Throwable e){for(String key:new String[]{"feed","keywords","users","mine_ads","mine_wallet","mine_tasks","mine_creator","mine_recommend"})if(h.on(key))h.fault(key,e);}
}
