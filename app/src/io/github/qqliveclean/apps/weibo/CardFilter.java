package io.github.qqliveclean.apps.weibo;

import org.json.*;

/** Bounded, structural card filtering. Never searches the body text of an ordinary post. */
final class CardFilter {
 static int clean(JSONObject node,int depth)throws JSONException{
  if(depth>5)return 0;int removed=0;
  for(String field:new String[]{"cards","card_group"}){
   JSONArray a=node.optJSONArray(field);if(a==null)continue;
   JSONArray kept=new JSONArray();
   for(int i=0;i<a.length();i++){
    Object raw=a.opt(i);JSONObject card=raw instanceof JSONObject?(JSONObject)raw:null;
    if(card!=null&&advert(card)){removed++;continue;}
    if(card!=null)removed+=clean(card,depth+1);kept.put(raw);
   }
   if(kept.length()!=a.length())node.put(field,kept);
  }
  return removed;
 }
 static boolean advert(JSONObject card){
  if(card.optBoolean("is_ad",false)||card.optInt("is_ad",0)==1||card.optInt("is_ad_card",0)==1)return true;
  JSONObject tag=card.optJSONObject("ad_tag");if(tag!=null&&"广告".equals(tag.optString("text")))return true;
  JSONObject m=card.optJSONObject("mblog");return m!=null&&m.optInt("mblogtype",0)==1;
 }
}
