package com.postflight.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import javax.crypto.spec.SecretKeySpec;

/** Local-dev application service: persisted, versioned content, validation, review, releases and hash-linked audit. */
public final class PostFlightService {
    private final Path dataFile,seedFile;
    private final AtomicLong sequence=new AtomicLong();
    private Map<String,Object> state;
    public static final String DEV_WARNING="LOCAL DEMO ONLY — no authentication or tenant isolation; never expose the listener to a public network";

    public PostFlightService(Path dataDirectory,Path initialSeed) throws IOException {
        Files.createDirectories(dataDirectory);
        dataFile=dataDirectory.resolve("postflight-data.json");seedFile=initialSeed;
        if(Files.isRegularFile(dataFile))state=Json.object(Json.parse(Files.readString(dataFile,StandardCharsets.UTF_8)));
        else {state=Json.object(Json.parse(Files.readString(seedFile,StandardCharsets.UTF_8)));normalize();save();}
        normalize();
    }
    private static String str(Object v){return v==null?"":String.valueOf(v);}
    private static String get(Map<String,Object> m,String k){return str(m.get(k));}
    private static Map<String,Object> obj(Object o){return Json.object(o);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object o){return (List<Map<String,Object>>)(List<?>)Json.array(o);}
    @SuppressWarnings("unchecked") private Map<String,Object> map(String name){return (Map<String,Object>)state.get(name);}
    private List<Map<String,Object>> list(String name){return rows(state.get(name));}
    private static Map<String,Object> doc(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static String now(){return Instant.now().toString();}
    public static String sha256(String input){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(b);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hmac(String secret,String message){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(HexFormat.of().parseHex(secret),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String required(Map<String,Object> m,String key,int max){String val=get(m,key).trim();if(val.isEmpty())throw new ApiError(400,key+" is required");if(val.length()>max)throw new ApiError(400,key+" exceeds "+max+" characters");return val;}
    private static String optional(Map<String,Object> m,String key,String fallback,int max){String v=m.containsKey(key)?get(m,key):fallback;if(v.length()>max)throw new ApiError(400,key+" exceeds "+max+" characters");return v;}
    private static long longValue(Object value,long fallback){if(value instanceof Number n)return n.longValue();try{return Long.parseLong(str(value));}catch(Exception e){return fallback;}}
    private void normalize(){
        state.putIfAbsent("drafts",new ArrayList<>());state.putIfAbsent("reviewStatus",new LinkedHashMap<>());state.putIfAbsent("alerts",new ArrayList<>());
        state.putIfAbsent("mediaAssets",new ArrayList<>());state.putIfAbsent("customRules",new ArrayList<>());state.putIfAbsent("policyRevision",0L);state.putIfAbsent("validationRuns",new ArrayList<>());state.putIfAbsent("releasesIssued",new ArrayList<>());state.putIfAbsent("audit",new ArrayList<>());state.putIfAbsent("lastRuns",new LinkedHashMap<>());
        if(!state.containsKey("certificateSecret")){byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);state.put("certificateSecret",HexFormat.of().formatHex(secret));}
        for(Map<String,Object> d:list("drafts")){d.putIfAbsent("version",1L);d.putIfAbsent("altText","");}
        sequence.set(Math.max(longValue(state.get("sequence"),2100),2100));
        state.put("policyVersion",activePolicy());
    }
    private void save(){
        try{Path temp=dataFile.resolveSibling(dataFile.getFileName()+".tmp");Files.writeString(temp,Json.stringify(state),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);try{Files.move(temp,dataFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,dataFile,StandardCopyOption.REPLACE_EXISTING);}}
        catch(IOException e){throw new ApiError(500,"Unable to persist data: "+e.getMessage());}
    }
    private void audit(String kind,String entity,String actor,String detail){
        List<Map<String,Object>> log=list("audit");String previous=log.isEmpty()?"GENESIS":get(log.get(log.size()-1),"hash");
        Map<String,Object> event=doc("id","AUD-"+(sequence.incrementAndGet()),"at",now(),"action",kind,"entityId",entity,"actor",actor,"detail",detail,"previousHash",previous);
        event.put("hash",sha256(previous+"|"+get(event,"id")+"|"+get(event,"at")+"|"+kind+"|"+entity+"|"+actor+"|"+detail));log.add(event);
        state.put("sequence",sequence.get());
    }
    private Map<String,Object> draft(String id){for(Map<String,Object> d:list("drafts"))if(id.equals(get(d,"id")))return d;throw new ApiError(404,"Draft not found");}
    private Map<String,Object> latestRun(String id){Object run=map("lastRuns").get(id);return run==null?null:obj(run);}
    private String activePolicy(){return PreflightRules.POLICY_VERSION+"+r"+longValue(state.get("policyRevision"),0);}
    private String digest(Map<String,Object> d){return sha256(get(d,"id")+"|"+get(d,"version")+"|"+get(d,"title")+"|"+get(d,"body")+"|"+get(d,"altText")+"|"+get(d,"platforms"));}
    private String nextId(String prefix){long n=sequence.incrementAndGet();state.put("sequence",sequence.get());return prefix+n;}
    public synchronized Map<String,Object> bootstrap(){Map<String,Object> output=new LinkedHashMap<>(state);output.remove("certificateSecret");output.put("runtime",doc("mode","true".equalsIgnoreCase(System.getenv("POSTFLIGHT_PUBLIC_DEMO")) ? "public-read-only" : "local-development","persistent",true,"authentication",false,"publishing",false,"warning",DEV_WARNING));return Json.object(Json.parse(Json.stringify(output)));}
    public synchronized Map<String,Object> session(){return doc("principal","local-developer","authenticated",false,"role","DEMO_OPERATOR","message",DEV_WARNING);}
    public synchronized Map<String,Object> health(){return doc("status","UP","service","postflight-core","java","17+","storage","file-backed","policy",PreflightRules.POLICY_VERSION,"publishing","DISABLED");}
    public synchronized List<Map<String,Object>> drafts(){return copy(list("drafts"));}
    private List<Map<String,Object>> copy(List<Map<String,Object>> l){return rows(Json.parse(Json.stringify(l)));}
    public synchronized Map<String,Object> createDraft(Map<String,Object> req){
        String title=required(req,"title",180);String body=optional(req,"body","",12000);String id=nextId("PF-");
        Map<String,Object> d=doc("id",id,"title",title,"body",body,"campaign",optional(req,"campaign","Q4 Investor Awareness",160),"status","Draft","platforms",optional(req,"platforms","LinkedIn",100),"updated",now(),"author","Local developer","version",1L,"altText",optional(req,"altText","",1000));
        list("drafts").add(0,d);map("reviewStatus").put(id,"Draft");state.put("selectedDraft",id);audit("DRAFT_CREATED",id,"local-developer",title);save();return d;
    }
    public synchronized Map<String,Object> updateDraft(String id,Map<String,Object> req){
        Map<String,Object> d=draft(id);if(req.containsKey("expectedVersion")&&longValue(req.get("expectedVersion"),-1)!=longValue(d.get("version"),1))throw new ApiError(409,"Version conflict. Reload the draft.");
        String title=optional(req,"title",get(d,"title"),180),body=optional(req,"body",get(d,"body"),12000),alt=optional(req,"altText",get(d,"altText"),1000),platforms=optional(req,"platforms",get(d,"platforms"),100);
        boolean changed=!title.equals(get(d,"title"))||!body.equals(get(d,"body"))||!alt.equals(get(d,"altText"))||!platforms.equals(get(d,"platforms"));
        if(changed){d.put("title",title);d.put("body",body);d.put("altText",alt);d.put("platforms",platforms);d.put("version",longValue(d.get("version"),1)+1);d.put("updated",now());d.put("status","Draft");map("reviewStatus").put(id,"Changes requested");audit("DRAFT_UPDATED",id,"local-developer","Updated to version "+d.get("version")+"; prior approval invalidated");save();}
        return d;
    }
    public synchronized Map<String,Object> validate(Map<String,Object> req){
        String id=required(req,"draftId",100);Map<String,Object> d=draft(id);String platform=optional(req,"platform",get(d,"platforms"),60);String alt=optional(req,"altText",get(d,"altText"),1000);
        List<Map<String,Object>> findings=PreflightRules.inspect(get(d,"body"),platform,alt,true);
        for(Map<String,Object> rule:list("customRules")){
            String phrase=get(rule,"phrase");if(!phrase.isBlank()&&get(d,"body").toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT)))
               findings.add(doc("id",rule.get("id"),"title",rule.get("title"),"severity",rule.get("severity"),"channel","All channels","detail","Custom organization policy found a prohibited phrase","match",phrase,"fix","Revise the prohibited phrase and rerun validation."));
        }
        boolean blocked=PreflightRules.blocking(findings);String verdict=blocked?"BLOCKED":findings.isEmpty()?"PASS":"WARN";
        Map<String,Object> run=doc("id",nextId("PF-RUN-"),"draftId",id,"policyVersion",activePolicy(),"platform",platform,"contentHash",digest(d),"draftVersion",d.get("version"),"createdAt",now(),"findings",findings,"verdict",verdict,"score",PreflightRules.score(findings));
        list("validationRuns").add(0,run);map("lastRuns").put(id,run);state.put("findings",findings);state.put("selectedFinding",findings.isEmpty()?"":findings.get(0).get("id"));audit("VALIDATION_"+verdict,id,"policy-engine",get(run,"id")+" "+findings.size()+" findings");save();return run;
    }
    public synchronized Map<String,Object> review(String id,Map<String,Object> req){
        Map<String,Object> d=draft(id);String decision=required(req,"decision",30);String reviewer=optional(req,"reviewer","Local demo reviewer",80);String comment=optional(req,"comment","",500);
        if(!List.of("Approved","Changes requested").contains(decision))throw new ApiError(400,"Unsupported review decision");
        if("Approved".equals(decision)){Map<String,Object> r=latestRun(id);if(r==null||!get(r,"policyVersion").equals(activePolicy())||!get(r,"contentHash").equals(digest(d)))throw new ApiError(409,"A current validation run is required before approval");if("BLOCKED".equals(get(r,"verdict")))throw new ApiError(409,"Blocking findings must be resolved before approval");}
        map("reviewStatus").put(id,decision);d.put("status",decision);audit("REVIEW_"+decision.toUpperCase(Locale.ROOT).replace(' ','_'),id,reviewer,comment.isBlank()?"Review decision":comment);save();return doc("draftId",id,"decision",decision,"reviewer",reviewer,"at",now());
    }
    public synchronized Map<String,Object> issueRelease(Map<String,Object> req){
        String id=required(req,"draftId",100);Map<String,Object> d=draft(id),run=latestRun(id);
        if(run==null||!activePolicy().equals(get(run,"policyVersion"))||!digest(d).equals(get(run,"contentHash")))throw new ApiError(409,"Current version must pass validation before release");
        if("BLOCKED".equals(get(run,"verdict")))throw new ApiError(409,"Blocking policy findings prevent release");
        if(!"Approved".equals(get(map("reviewStatus"),id)))throw new ApiError(409,"Human review approval is required before release");
        String releaseId=nextId("PF-REL-");String stamp=now(),base=releaseId+"|"+id+"|"+get(run,"contentHash")+"|"+get(run,"policyVersion")+"|"+stamp;
        Map<String,Object> certificate=doc("id",releaseId,"draftId",id,"draftVersion",d.get("version"),"runId",run.get("id"),"contentHash",run.get("contentHash"),"policyVersion",activePolicy(),"approvedBy","Local demo reviewer","issuedAt",stamp,"status","READY_FOR_HANDOFF","publishing","NOT_CONFIGURED","signatureAlgorithm","HMAC-SHA256-LOCAL-DEMO","signature",hmac(get(state,"certificateSecret"),base));
        list("releasesIssued").add(0,certificate);audit("RELEASE_CERTIFICATE_ISSUED",id,"local-developer",releaseId);save();return certificate;
    }
    public synchronized Map<String,Object> verifyRelease(String id){for(Map<String,Object> r:list("releasesIssued"))if(id.equals(get(r,"id"))){String base=id+"|"+get(r,"draftId")+"|"+get(r,"contentHash")+"|"+get(r,"policyVersion")+"|"+get(r,"issuedAt");boolean genuine=MessageDigest.isEqual(hmac(get(state,"certificateSecret"),base).getBytes(StandardCharsets.UTF_8),get(r,"signature").getBytes(StandardCharsets.UTF_8));return doc("releaseId",id,"signatureValid",genuine,"historicalVersion",r.get("draftVersion"),"published",false,"meaning","Local demo HMAC verifies stored evidence; no external identity assurance or regulatory certification");}throw new ApiError(404,"Release certificate not found");}
    public synchronized Map<String,Object> getPolicies(){return doc("version",activePolicy(),"baseVersion",PreflightRules.POLICY_VERSION,"customRules",copy(list("customRules")),"activeCustomRules",list("customRules").size());}
    public synchronized Map<String,Object> createPolicyRule(Map<String,Object> req){
        String phrase=required(req,"phrase",80),title=required(req,"title",160),severity=optional(req,"severity","High",12);
        if(!List.of("Critical","High","Medium","Low").contains(severity))throw new ApiError(400,"Invalid severity");
        for(Map<String,Object> r:list("customRules"))if(phrase.equalsIgnoreCase(get(r,"phrase")))throw new ApiError(409,"A rule for that phrase already exists");
        Map<String,Object> rule=doc("id",nextId("ORG-"),"title",title,"phrase",phrase,"severity",severity,"enabled",true,"createdAt",now());
        list("customRules").add(rule);state.put("policyRevision",longValue(state.get("policyRevision"),0)+1);state.put("policyVersion",activePolicy());audit("POLICY_RULE_CREATED",get(rule,"id"),"local-developer",title+": "+severity);save();
        return doc("rule",rule,"policyVersion",activePolicy(),"warning","Rule is local demo configuration, not an approved regulatory policy");
    }
    public synchronized List<Map<String,Object>> getMedia(){return copy(list("mediaAssets"));}
    public synchronized Map<String,Object> uploadMedia(Map<String,Object> req){
        String name=required(req,"name",160),mime=required(req,"mime",64),encoded=required(req,"base64",1000000);
        if(!mime.equals("image/png")&&!mime.equals("image/jpeg"))throw new ApiError(415,"Only PNG and JPEG uploads are supported in local mode");
        byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){throw new ApiError(400,"Invalid base64 image");}
        if(bytes.length>700000)throw new ApiError(413,"Images must be no larger than 700 kB in local mode");
        if(bytes.length<20)throw new ApiError(400,"Invalid image data");
        boolean png=(bytes[0]&255)==137&&(bytes[1]&255)==80&&(bytes[2]&255)==78&&(bytes[3]&255)==71;
        boolean jpeg=(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255;
        if((mime.equals("image/png")&&!png)||(mime.equals("image/jpeg")&&!jpeg))throw new ApiError(415,"MIME type and image signature do not match");
        BufferedImage img;try{img=ImageIO.read(new ByteArrayInputStream(bytes));}catch(IOException e){throw new ApiError(400,"Unreadable image");}
        if(img==null||img.getWidth()>4096||img.getHeight()>4096||img.getWidth()<1||img.getHeight()<1)throw new ApiError(400,"Unsupported image dimensions");
        String alt=optional(req,"altText","",1000),id=nextId("ASSET-");
        Map<String,Object> asset=doc("id",id,"name",name,"mime",mime,"width",img.getWidth(),"height",img.getHeight(),"bytes",bytes.length,"altText",alt,"status",alt.isBlank()?"Needs review":"Ready","createdAt",now());
        try{Path dir=dataFile.getParent().resolve("media");Files.createDirectories(dir);Files.write(dir.resolve(id+(png?".png":".jpg")),bytes,StandardOpenOption.CREATE_NEW);}catch(IOException e){throw new ApiError(500,"Failed to store uploaded asset");}
        list("mediaAssets").add(0,asset);audit("MEDIA_UPLOADED",id,"local-developer",name);save();return asset;
    }
    public synchronized Map<String,Object> getMediaAsset(String id){
        for(Map<String,Object> m:list("mediaAssets"))if(id.equals(get(m,"id"))){Path file=dataFile.getParent().resolve("media").resolve(id+(get(m,"mime").equals("image/png")?".png":".jpg"));
            try{return doc("asset",m,"base64",Base64.getEncoder().encodeToString(Files.readAllBytes(file)));}catch(IOException e){throw new ApiError(500,"Asset content unavailable");}
        }throw new ApiError(404,"Asset not found");
    }
    public synchronized Map<String,Object> workflowTest(Map<String,Object> req){String risk=optional(req,"risk","high",20);boolean high=risk.equalsIgnoreCase("high")||risk.equalsIgnoreCase("critical");List<Object> trace=new ArrayList<>();trace.add(doc("step","Content validation","status","PASS"));trace.add(doc("step","Risk classification","status",risk.toUpperCase(Locale.ROOT)));if(high)trace.add(doc("step","Legal review","status","REQUIRED"));trace.add(doc("step","Brand review","status","REQUIRED"));trace.add(doc("step","Release approval","status","PENDING"));return doc("simulation",true,"policy",PreflightRules.POLICY_VERSION,"trace",trace,"published",false);}
    public synchronized Map<String,Object> policySimulate(Map<String,Object> req){int strict=(int)longValue(req.get("strictness"),2);strict=Math.max(1,Math.min(5,strict));int affected=Math.min(list("drafts").size(),Math.max(0,strict-1)*2);return doc("simulation",true,"strictness",strict,"candidatePolicy","FIN-MKT-v3.5-draft","currentPolicy",activePolicy(),"draftsAffected",affected,"note","Estimated demonstration impact, not a server-wide regulatory assessment");}
    public synchronized Map<String,Object> updateIncident(String id,Map<String,Object> req){String status=required(req,"status",30);if(!List.of("Open","Investigating","Resolved").contains(status))throw new ApiError(400,"Unsupported incident status");for(Map<String,Object> a:list("alerts"))if(id.equals(get(a,"id"))){a.put("status",status);audit("INCIDENT_STATUS",id,"local-developer",status);save();return a;}throw new ApiError(404,"Incident not found");}
    public synchronized Map<String,Object> metrics(){int open=0,approved=0;for(Map<String,Object> d:list("drafts"))if("Approved".equals(get(d,"status")))approved++;for(Map<String,Object> a:list("alerts"))if(!"Resolved".equals(get(a,"status")))open++;return doc("totalDrafts",list("drafts").size(),"validations",list("validationRuns").size(),"approvedDrafts",approved,"releaseCertificates",list("releasesIssued").size(),"openIncidents",open,"illustrativeChart",true);}
    public synchronized Map<String,Object> auditReport(){List<Map<String,Object>> events=list("audit");String prev="GENESIS";boolean valid=true;for(Map<String,Object> e:events){String expected=sha256(prev+"|"+get(e,"id")+"|"+get(e,"at")+"|"+get(e,"action")+"|"+get(e,"entityId")+"|"+get(e,"actor")+"|"+get(e,"detail"));if(!prev.equals(get(e,"previousHash"))||!expected.equals(get(e,"hash"))){valid=false;break;}prev=get(e,"hash");}return doc("chainValid",valid,"events",copy(events),"note","File-backed hash chain is tamper-evident, not tamper-proof or WORM storage");}
    public synchronized Map<String,Object> reset()throws IOException{state=obj(Json.parse(Files.readString(seedFile,StandardCharsets.UTF_8)));normalize();audit("DEMO_RESET","workspace","local-developer","Seed data restored");save();return bootstrap();}
    public synchronized List<Map<String,Object>> validations(){return copy(list("validationRuns"));}
    public synchronized List<Map<String,Object>> releases(){return copy(list("releasesIssued"));}
    public synchronized Map<String,Object> integrations(){return doc("zernio",doc("configured",false,"publishingEnabled",false,"docs","https://docs.zernio.com/"),"webhooks",doc("configured",false),"objectStorage",doc("configured",false));}
    public static final class ApiError extends RuntimeException{public final int status;public ApiError(int status,String msg){super(msg);this.status=status;}}
}
