package io.github.qqliveclean.apps.weibo;

import java.util.*;

/** Bounded literal rules: no regular expression execution on the scrolling thread. */
final class TextRules {
 static final int MAX_TERMS=100, MAX_TERM=100, MAX_INPUT=10000;
 static List<String> parse(String input){
  if(input==null||input.trim().isEmpty())return Collections.emptyList();
  if(input.length()>MAX_INPUT)throw new IllegalArgumentException("最多输入 10000 个字符");
  LinkedHashSet<String> out=new LinkedHashSet<>();
  for(String raw:input.split("[\\r\\n|]")){
   String term=raw.trim().toLowerCase(Locale.ROOT);if(term.isEmpty())continue;
   if(term.length()>MAX_TERM)throw new IllegalArgumentException("每项最多 100 个字符");
   out.add(term);if(out.size()>MAX_TERMS)throw new IllegalArgumentException("每组最多 100 项");
  }
  return new ArrayList<>(out);
 }
 static boolean contains(List<String> terms,String value){if(value==null||terms.isEmpty())return false;String text=value.toLowerCase(Locale.ROOT);for(String t:terms)if(text.contains(t))return true;return false;}
 static boolean exact(List<String> terms,String value){return value!=null&&terms.contains(value.trim().toLowerCase(Locale.ROOT));}
 static String cleanCopy(String text){if(text==null)return null;return text.replace("[cp]", "").replace("[/cp]", "");}
}
