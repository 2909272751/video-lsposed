package io.github.qqliveclean.apps.weibo;

final class AdSignals {
 static boolean promotion(String type,String id){
  if(type==null||id==null||id.trim().isEmpty()||id.trim().equals("0"))return false;
  try{return Integer.parseInt(type.trim())>0;}catch(NumberFormatException e){return false;}
 }
}
