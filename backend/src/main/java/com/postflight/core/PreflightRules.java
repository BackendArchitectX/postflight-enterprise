package com.postflight.core;

import java.util.*;
import java.util.regex.*;

/** Transparent deterministic policies; not legal advice or a substitute for platform API validation. */
public final class PreflightRules {
    public static final String POLICY_VERSION="FIN-MKT-v3.4";
    private static final Pattern GUARANTEE=Pattern.compile("\\b(guaranteed?\\s+(?:(?:up\\s+to\\s+)?\\d+(?:\\.\\d+)?\\s*%\\s*)?(?:returns?|profits?|yield)|risk[ -]?free (?:returns?|profits?|investments?)|no risk (?:investment|returns?))\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern FINANCIAL=Pattern.compile("\\b(invest(?:ment|ing|ments)?|returns?|profits?|financial products?|securities|yield)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern DISCLAIMER=Pattern.compile("(?i)(invest(?:ing|ment|ments)? involves? risk|capital (?:is |may be )?at risk|past performance (?:is not|does not)|not investment advice)");
    private PreflightRules(){}
    public static List<Map<String,Object>> inspect(String content,String platform,String altText,boolean hasMedia){
        if(content==null)content="";
        List<Map<String,Object>> results=new ArrayList<>();
        Matcher guarantee=GUARANTEE.matcher(content);
        if(guarantee.find()) results.add(f("FIN-MKT-001","Unverified guarantee of returns","Critical","All channels","Absolute financial-performance claims require approved substantiation and legal review",guarantee.group(),"Remove absolute promises or provide approved evidence; request legal sign-off."));
        if(FINANCIAL.matcher(content).find()&&!DISCLAIMER.matcher(content).find())results.add(f("FIN-MKT-014","Mandatory risk disclosure absent","High","All channels","The financial-marketing policy requires risk-language in context.","Risk language not found","Add an organization-approved risk disclosure in the same version."));
        if(platform!=null&&platform.equalsIgnoreCase("X")&&weightedLength(content)>280)results.add(f("PLT-X-011","X character limit exceeded","High","X","Text exceeds the 280-weight character budget; verify actual entitlement with the platform API.","Weighted length: "+weightedLength(content),"Create an X-specific variant and rerun validation."));
        if(hasMedia&&(altText==null||altText.isBlank()))results.add(f("A11Y-004","Missing alternative text","Medium",platform==null?"All channels":platform,"Attached campaign art lacks a meaningful text alternative.","Alt text not provided","Describe the essential content of the image."));
        if(platform!=null&&platform.equalsIgnoreCase("Instagram")&&content.matches("(?s).*https?://\\S+.*"))results.add(f("LINK-006","Instagram caption link usability","Low","Instagram","Inline caption URLs may not behave as clickable links.","URL in caption","Use the supported profile-link or sticker workflow."));
        if(content.isBlank())results.add(f("CONTENT-001","Content body is empty","High","All channels","A release must contain a nonblank message.","Empty body","Provide content before approval."));
        return results;
    }
    // Conservative local approximation; official X counting has more nuanced URL and Unicode weights.
    public static int weightedLength(String s){return s==null?0:s.codePointCount(0,s.length());}
    private static Map<String,Object> f(String id,String title,String severity,String channel,String detail,String match,String fix){Map<String,Object> m=new LinkedHashMap<>();m.put("id",id);m.put("title",title);m.put("severity",severity);m.put("channel",channel);m.put("detail",detail);m.put("match",match);m.put("fix",fix);return m;}
    public static boolean blocking(List<Map<String,Object>> findings){return findings.stream().anyMatch(x->"Critical".equals(x.get("severity"))||"High".equals(x.get("severity")));}
    public static int score(List<Map<String,Object>> findings){int score=100;for(Map<String,Object> f:findings)score-= switch(String.valueOf(f.get("severity"))){case "Critical"->35;case "High"->20;case "Medium"->7;default->2;};return Math.max(0,score);}
}
