package io.github.qqliveclean.apps.weibo;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.View;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Original host rules; no dependency on, injection into, or configuration writes to WeiboHelper. */
public final class MainHook extends io.github.qqliveclean.RuleHost {

    public MainHook(io.github.libxposed.api.XposedModule host) {
        super(host);
    }
 private String process;
 private ClassLoader loader;
 private Context context;
 Bundle options;
 AdRules ads;
 String modernDetail="";
 private Method statusText,statusUid,statusName;
 List<String> keywordRules=Collections.emptyList(),userRules=Collections.emptyList(),locationRules=Collections.emptyList();
 private String version="unknown", settingsSource="", fingerprint="";
 private final AtomicBoolean started=new AtomicBoolean(), reportPending=new AtomicBoolean();
 private final Map<String,String> states=new LinkedHashMap<>(), details=new LinkedHashMap<>();
 private final Map<String,Long> hits=new LinkedHashMap<>();
 private final Set<String> installed=new HashSet<>();
 private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"WeiboClean-report");t.setDaemon(true);return t;});
 private int progress;
 private long start, elapsed;

 public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam p){process=p.getProcessName();}
 public void onPackageReady(XposedModuleInterface.PackageReadyParam p){
  if(!Config.HOST.equals(p.getPackageName())||!Config.HOST.equals(process)||!started.compareAndSet(false,true))return;
  loader=p.getClassLoader();
  try{
   Context app=(Context)Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null);
   if(app!=null){configure(app);return;}
   Method method=Instrumentation.class.getDeclaredMethod("callApplicationOnCreate",Application.class);
   hook(method).intercept(chain->{configure((Context)chain.getArg(0));return chain.proceed();});
  }catch(Throwable e){log(6,"WeiboClean","Initialization failed: "+e.getClass().getSimpleName());}
 }

 private synchronized void configure(Context c){
  if(context!=null)return;
  context=c.getApplicationContext();start=SystemClock.elapsedRealtime();
  try{
   android.content.pm.PackageInfo info=c.getPackageManager().getPackageInfo(Config.HOST,0);
   version=info.versionName+" ("+info.getLongVersionCode()+")";
   fingerprint=info.lastUpdateTime+":"+info.getLongVersionCode()+":1";
  }catch(Exception ignored){}
  // Settings come from the one merged provider now. The old content://io.github.weiboclean
  // .settings/state authority died with the standalone module, which is why this app kept
  // reporting "设置连接未成功" while every other merged rule set worked.
  try{
   android.content.SharedPreferences wp=io.github.qqliveclean.FamilySettings.prefs(c,io.github.qqliveclean.FamilySettings.WEIBO);
   if(wp==null)throw new IllegalStateException("no prefs");
   options=new android.os.Bundle();
   for(String key:Config.KEYS)options.putBoolean(key,wp.getBoolean(key,Config.defaultOn(key)));
   // The provider only carries booleans. The keyword/user/region filters are free text with no
   // settings UI yet, and revision is weibo's own cache marker, so both stay in the local copy.
   android.os.Bundle cached=loadSettings();
   for(String key:Config.TEXT_KEYS)options.putString(key,cached.getString(key,""));
   options.putLong("revision",cached.getLong("revision",0));
   settingsSource="模块设置";
   saveSettings(options);
  }catch(Throwable e){options=loadSettings();settingsSource="本机缓存（设置连接未成功，请先打开模块）";}
  for(String key:Config.KEYS){states.put(key,on(key)?"checking":"disabled");hits.put(key,0L);}
  try{keywordRules=TextRules.parse(options.getString("keyword_rules",""));userRules=TextRules.parse(options.getString("user_rules",""));locationRules=TextRules.parse(options.getString("location_rules",""));}catch(IllegalArgumentException e){fault("keywords",e);fault("users",e);fault("comment_filter",e);fault("comment_location",e);}
  ads=new AdRules(this);
  submit();
  rule("redpacket",()->{
   int count=0;StringBuilder targets=new StringBuilder();
   // Names move between releases; require the original semantic field before accepting a candidate.
   for(char suffix='a';suffix<='z';suffix++)try{
    Class<?> candidate=type("com.sina.weibo.feed.ug."+suffix);
    candidate.getDeclaredField("StreamFloatRedpacketManagerV2__fields__");
    for(Method m:candidate.getDeclaredMethods()){
     Class<?>[] p=m.getParameterTypes();
     if(!Modifier.isStatic(m.getModifiers())&&m.getReturnType()==View.class&&
       ((p.length==1&&p[0]==Context.class)||(p.length==2&&p[0]==Context.class&&p[1]==String.class))){
      attach(m,chain->{if(!on("redpacket"))return chain.proceed();hit("redpacket",1);return null;});count++;
     }
    }
    targets.append(candidate.getName()).append("；");
   }catch(ClassNotFoundException|NoSuchFieldException ignored){}
   try{Class<?> ad=type("com.sina.weibo.business.floating.AdFloatingRedPacketView");
    for(Method m:ad.getDeclaredMethods())if(m.getReturnType()==android.widget.FrameLayout.class&&
      Arrays.equals(m.getParameterTypes(),new Class<?>[]{Activity.class,int.class,int.class})){
     attach(m,chain->{if(!on("redpacket"))return chain.proceed();hit("redpacket",1);return null;});count++;
    }
   }catch(ClassNotFoundException ignored){}
   if(count==0)throw new NoSuchMethodException("红包组件入口未识别");
   return count+" 个红包创建入口；"+targets;
  });
  rule("splash",()->{
   int found=0;
   for(String cls:new String[]{"com.sina.weibo.mobileads.controller.AdSdk","com.sina.weibo.mobileads.view.FlashAd"}){
    try{Method m=type(cls).getDeclaredMethod("isReady");if(m.getReturnType()!=boolean.class)continue;
     attach(m,chain->{hit("splash",1);return false;});found++;}catch(NoSuchMethodException|ClassNotFoundException ignored){}
   }
   
   try{found+=AdRules.installSplashDisplay(this);}catch(ClassNotFoundException|NoSuchMethodException ignored){}
   if(found==0)throw new NoSuchMethodException("开屏准备判断入口未找到");return found+" 个开屏入口已挂接";
  });
  rule("preload",()->{
   Method m=type("com.sina.weibo.mobileads.controller.RefreshService").getDeclaredMethod("reload",int.class);
   if(m.getReturnType()!=void.class||!Modifier.isStatic(m.getModifiers()))throw new NoSuchMethodException("广告刷新签名变化");
   attach(m,chain->{hit("preload",1);return null;});return "开屏 SDK 刷新入口已挂接";
  });
  boolean feedInstalled=false;String feedFailure="";
  if(on("feed")||on("keywords")||on("users"))try{
   Class<?> base=type("com.sina.weibo.models.MBlogListBaseObject"),status=type("com.sina.weibo.models.Status");
   statusText=status.getDeclaredMethod("getText");statusUid=status.getDeclaredMethod("getUserId");statusName=status.getDeclaredMethod("getUserScreenName");
   Method isAd=status.getDeclaredMethod("isAd");if(isAd.getReturnType()!=boolean.class)throw new NoSuchMethodException("isAd signature");
   isAd.setAccessible(true);
   int count=0;
   for(String name:new String[]{"getStatuses","getStatusesCopy"}){
    Method m=base.getDeclaredMethod(name);if(!List.class.isAssignableFrom(m.getReturnType()))continue;
    attach(m,chain->{Object original=chain.proceed();try{return filterFeed(original,isAd);}catch(Throwable e){faultFeed(e);return original;}});count++;
   }
   Method setter=base.getDeclaredMethod("setStatuses",List.class);
   attach(setter,chain->{Object data=chain.getArg(0);try{data=filterFeed(data,isAd);}catch(Throwable e){faultFeed(e);}return chain.proceed(new Object[]{data});});
   if(count==0)throw new NoSuchMethodException("微博列表读取入口未找到");feedInstalled=true;
  }catch(Throwable e){feedFailure=e.getClass().getSimpleName()+": "+e.getMessage();}
  for(String key:new String[]{"feed","keywords","users"}){set(key,feedInstalled?"hooked":"missing",feedInstalled?"列表读写入口已挂接，等待匹配内容":feedFailure);progress++;submit();}
  boolean cardsInstalled=false;String cardFailure="";
  if(on("cards")||on("banners")||on("floating")||Enhancements.cardsEnabled(this))try{
   Method m=type("com.sina.weibo.models.CardList").getDeclaredMethod("initFromJsonObject",JSONObject.class);
   attach(m,chain->{Object input=chain.getArg(0);if(!(input instanceof JSONObject))return chain.proceed();
    Object prepared=input;
    try{JSONObject copy=new JSONObject(input.toString());boolean changed=false;
     if(on("cards")){int n=CardFilter.clean(copy,0);if(n>0){hit("cards",n);changed=true;}}
     if(on("banners")&&copy.optJSONArray("banners")!=null&&copy.optJSONArray("banners").length()>0){copy.put("banners",new JSONArray());hit("banners",1);changed=true;}
     if(on("floating")&&copy.has("leading_float_card")&&!copy.isNull("leading_float_card")){copy.remove("leading_float_card");hit("floating",1);changed=true;}
     if(Enhancements.cardsEnabled(this)&&Enhancements.cleanCards(copy,this,0)>0)changed=true;
     if(changed)prepared=copy;
    }catch(Throwable e){for(String key:new String[]{"cards","banners","floating","keywords","users","mine_ads","mine_wallet","mine_tasks","mine_creator","mine_recommend"})if(on(key))fault(key,e);}
    return prepared==input?chain.proceed():chain.proceed(new Object[]{prepared});});cardsInstalled=true;
  }catch(Throwable e){cardFailure=e.getClass().getSimpleName()+": "+e.getMessage();}
  for(String key:new String[]{"cards","banners","floating"}){set(key,cardsInstalled?"hooked":"missing",cardsInstalled?"卡片解析入口已挂接":cardFailure);progress++;submit();}
  if(on("cards"))try{Method list=type("com.sina.weibo.models.CardList").getDeclaredMethod("getCardList");if(list.getReturnType()==List.class)attach(list,chain->ads.filter(chain.proceed()));}catch(Throwable e){log(5,"WeiboClean","旧卡片对象入口未识别："+e.getClass().getSimpleName());}
  boolean modernInstalled=FlowRules.install(this);
  if(modernInstalled)for(String key:new String[]{"feed","keywords","users"})set(key,"hooked","列表过滤入口已挂接；等待实际匹配");
  Enhancements.install(this,cardsInstalled||modernInstalled);
  ads.install(modernInstalled);
  boolean navInstalled=false;int dotHooks=0;String navError="";
  if(on("video")||on("discover")||on("messages")||on("dots"))try{
   int n=0;
   for(String cls:new String[]{"com.sina.weibo.bottombar.view.TabViewGroupV2","com.sina.weibo.view.TabViewGroupV2"}){
    try{Class<?> t=type(cls);
     Method text=t.getDeclaredMethod("setText",String.class);
     attach(text,chain->{Object result=chain.proceed();try{hideTab((View)chain.getThisObject());}catch(Throwable e){faultNavigation(e);}return result;});
     Method measure=t.getDeclaredMethod("onMeasure",int.class,int.class);
     attach(measure,chain->{try{hideTab((View)chain.getThisObject());}catch(Throwable e){faultNavigation(e);}return chain.proceed();});
     n++;
     if(on("dots"))try{Method dot=t.getDeclaredMethod("setmNewMessageCount",int.class);attach(dot,chain->{if(!on("dots"))return chain.proceed();int v=(Integer)chain.getArg(0);if(v!=0)hit("dots",1);return chain.proceed(new Object[]{0});});dotHooks++;}catch(NoSuchMethodException ignored){}
    }catch(ClassNotFoundException|NoSuchMethodException ignored){}
   }
   if(n==0)throw new NoSuchMethodException("导航按钮入口未找到");navInstalled=true;
  }catch(Throwable e){navError=e.getClass().getSimpleName()+": "+e.getMessage();}
  for(String key:new String[]{"video","discover","messages"}){set(key,navInstalled?"hooked":"missing",navInstalled?"导航按钮已挂接；等待页面验证":navError);progress++;submit();}
  set("dots",dotHooks>0?"hooked":"missing",dotHooks>0?"未读数字入口已挂接":"未读数字入口未找到");progress++;submit();
  elapsed=SystemClock.elapsedRealtime()-start;submit();
  log(4,"WeiboClean","Host="+version+"; hooks="+installed.size()+"; installMs="+elapsed+"; "+settingsSource);
 }
 Object filterFeed(Object data,Method isAd)throws Exception{
  if(!(data instanceof List))return data;
  List<?> old=(List<?>)data;ArrayList<Object> next=null;
  for(int i=0;i<old.size();i++){
   Object item=old.get(i);String reason=null;
   if(item!=null&&isAd.getDeclaringClass().isInstance(item)){
    if(on("feed")&&Boolean.TRUE.equals(isAd.invoke(item)))reason="feed";
    else if(on("keywords")&&TextRules.contains(keywordRules,(String)statusText.invoke(item)))reason="keywords";
    else if(on("users")&&(TextRules.exact(userRules,(String)statusUid.invoke(item))||TextRules.exact(userRules,(String)statusName.invoke(item))))reason="users";
   }
   if(reason!=null){if(next==null)next=new ArrayList<>(old.subList(0,i));hit(reason,1);}else if(next!=null)next.add(item);
  }
  return next==null?data:next;
 }
 private void hideTab(View v){
  CharSequence desc=v.getContentDescription();if(desc==null)return;
  String label=desc.toString().trim();String key="视频".equals(label)?"video":"发现".equals(label)?"discover":"消息".equals(label)?"messages":null;
  if(key!=null&&on(key)&&v.getVisibility()!=View.GONE){v.setVisibility(View.GONE);hit(key,1);
   v.post(()->{try{compactNavigation(v,key);}catch(Throwable e){fault(key,e);}});
  }
 }
 private void faultNavigation(Throwable e){for(String key:new String[]{"video","discover","messages"})if(on(key))fault(key,e);}
 private void compactNavigation(View v,String key){
  if(!(v.getParent() instanceof android.widget.FrameLayout))return;
  android.widget.FrameLayout wrapper=(android.widget.FrameLayout)v.getParent();
  if(wrapper.getChildCount()!=1||!(wrapper.getParent() instanceof android.widget.LinearLayout))return;
  android.widget.LinearLayout bar=(android.widget.LinearLayout)wrapper.getParent();
  if(bar.getOrientation()!=android.widget.LinearLayout.HORIZONTAL||bar.getChildCount()<2||bar.getChildCount()>7)return;
  boolean home=false,me=false;
  for(int i=0;i<bar.getChildCount();i++){
   View child=bar.getChildAt(i);if(!(child instanceof android.widget.FrameLayout)||!(child.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams))return;
   android.widget.FrameLayout slot=(android.widget.FrameLayout)child;if(slot.getChildCount()!=1)return;
   View tab=slot.getChildAt(0);String name=tab.getClass().getName();
   if(!name.startsWith("com.sina.weibo.bottombar.view.Tab")&&!name.startsWith("com.sina.weibo.view.Tab"))return;
   String label=String.valueOf(tab.getContentDescription());home|="首页".equals(label);me|="我".equals(label);
  }
  if(!home||!me)return;
  // Keep original child indexes and click listeners; remove only the empty visual slots.
  bar.setWeightSum(0);
  for(int i=0;i<bar.getChildCount();i++){
   android.widget.FrameLayout slot=(android.widget.FrameLayout)bar.getChildAt(i);View tab=slot.getChildAt(0);
   String label=String.valueOf(tab.getContentDescription());String option="视频".equals(label)?"video":"发现".equals(label)?"discover":"消息".equals(label)?"messages":null;
   if(option!=null&&on(option))slot.setVisibility(View.GONE);
   else{android.widget.LinearLayout.LayoutParams p=(android.widget.LinearLayout.LayoutParams)slot.getLayoutParams();p.width=0;p.weight=1;slot.setLayoutParams(p);}
  }
  synchronized(this){details.put(key,"按钮及空位已隐藏，剩余入口均分底栏");}submit();
 }
 private void faultFeed(Throwable e){for(String k:new String[]{"feed","keywords","users"})if(on(k))fault(k,e);}
 Class<?> type(String n)throws ClassNotFoundException{return Class.forName(n,false,loader);}
 void attach(Method m,XposedInterface.Hooker h){
  String id=m.toGenericString();if(installed.contains(id))return;
  m.setAccessible(true);hook(m).setId("weiboclean:"+id).intercept(h);installed.add(id);
 }
 interface Installer{String install()throws Throwable;}
 void rule(String key,Installer installer){
  if(on(key))try{set(key,"hooked",installer.install());}catch(Throwable e){set(key,"missing",e.getClass().getSimpleName()+": "+e.getMessage());}
  progress++;submit();
 }
 synchronized boolean on(String key){return options!=null&&options.getBoolean(key,Config.defaultOn(key))&&!"error".equals(states.get(key));}
 private synchronized void set(String k,String state,String detail){if("error".equals(states.get(k)))return;if(!on(k)){states.put(k,"disabled");details.put(k,"已关闭");}else{states.put(k,state);details.put(k,detail);}}
 synchronized void hit(String k,long n){hits.put(k,hits.get(k)+n);submit();}
 synchronized void fault(String k,Throwable e){if(!"error".equals(states.get(k))){states.put(k,"error");details.put(k,"运行异常，已保留原始数据："+e.getClass().getSimpleName());log(5,"WeiboClean",k+": "+e.getClass().getSimpleName());submit();}}
 private void submit(){if(reportPending.compareAndSet(false,true))worker.schedule(()->{
  reportPending.set(false);
  try{String json=report();context.getSharedPreferences("weiboclean_local",0).edit().putString("report",json).apply();
   Bundle b=new Bundle();b.putString("json",json);context.getContentResolver().call(Config.URI,"report",null,b);
  }catch(Throwable ignored){}
 },1,TimeUnit.SECONDS);}
 private synchronized String report()throws JSONException{
  JSONObject j=new JSONObject();j.put("version",version);j.put("fingerprint",fingerprint);j.put("revision",options.getLong("revision",0));j.put("source",settingsSource);j.put("progress",progress);j.put("total",Config.KEYS.length);j.put("hooks",installed.size());j.put("installMs",elapsed);j.put("time",System.currentTimeMillis());
  JSONArray rows=new JSONArray();for(String k:Config.KEYS){JSONObject row=new JSONObject();row.put("key",k);row.put("state",states.get(k));String detail=details.containsKey(k)?details.get(k):"";if(on(k)&&(k.equals("feed")||k.equals("keywords")||k.equals("users")||k.startsWith("mine_"))&&!modernDetail.isEmpty())detail+="；"+modernDetail;row.put("detail",detail);row.put("hits",hits.get(k));rows.put(row);}j.put("rows",rows);return j.toString();
 }
 private void saveSettings(Bundle b){android.content.SharedPreferences.Editor e=context.getSharedPreferences("weiboclean_local",0).edit();for(String k:Config.KEYS)e.putBoolean(k,b.getBoolean(k));for(String k:Config.TEXT_KEYS)e.putString(k,b.getString(k,""));e.putLong("revision",b.getLong("revision"));e.apply();}
 private Bundle loadSettings(){Bundle b=new Bundle();android.content.SharedPreferences p=context.getSharedPreferences("weiboclean_local",0);for(String k:Config.KEYS)b.putBoolean(k,p.getBoolean(k,Config.defaultOn(k)));for(String k:Config.TEXT_KEYS)b.putString(k,p.getString(k,""));b.putLong("revision",p.getLong("revision",0));return b;}
}
