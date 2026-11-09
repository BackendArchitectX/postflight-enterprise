import com.postflight.core.Json;
import com.postflight.local.LocalServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Standalone dependency-free QA runner. Starts isolated localhost server on an OS-assigned port. */
public class PostFlightQARunner {
  record Result(String section, String name, String outcome, String detail) {}
  record Reply(int status, Object data, String raw, HttpHeaders headers) {
    Map<String,Object> obj(){return Json.object(data);}
    List<Object> arr(){return Json.array(data);}
    String val(String k){return String.valueOf(obj().get(k));}
  }
  static final List<Result> results=new ArrayList<>();
  static String section="Startup";
  static String base;
  static HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  static Path project, reportDir, sandbox;
  static int pass=0,fail=0;
  static String titleTag="PostFlight Enterprise";
  static void group(String s){section=s;System.out.println("\n["+s+"]");}
  static void test(String name,boolean ok){result(name,ok,ok?"":"Expected condition did not hold");}
  static void result(String name,boolean ok,String detail){results.add(new Result(section,name,ok?"PASS":"FAIL",detail));if(ok){pass++;System.out.println("  PASS "+name);}else{fail++;System.out.println("  FAIL "+name+" — "+detail);}}
  static void expectStatus(String name,Reply r,int status){result(name,r.status==status,"Expected HTTP "+status+", got "+r.status+" "+r.raw.substring(0,Math.min(180,r.raw.length())));}
  static String s(Object o){return o==null?"":String.valueOf(o);}
  static Map<String,Object> data(Object... kv){Map<String,Object> v=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)v.put(s(kv[i]),kv[i+1]);return v;}
  static Reply req(String method,String path,Object body){return req(method,path,body,Map.of());}
  static Reply req(String method,String path,Object body,Map<String,String> extra){
    try{
      HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(8));
      b.header("Accept","application/json");
      for(var e:extra.entrySet())b.header(e.getKey(),e.getValue());
      String json=body==null?"":Json.stringify(body);
      if(body!=null){if(!extra.containsKey("Content-Type"))b.header("Content-Type","application/json");b.method(method,HttpRequest.BodyPublishers.ofString(json,StandardCharsets.UTF_8));}
      else if(method.equals("GET"))b.GET();else b.method(method,HttpRequest.BodyPublishers.noBody());
      HttpResponse<String> response=http.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      Object parsed;
      try{parsed=Json.parse(response.body());}catch(Exception e){parsed=response.body();}
      return new Reply(response.statusCode(),parsed,response.body(),response.headers());
    }catch(Exception e){throw new RuntimeException("Request "+method+" "+path+" failed",e);}
  }
  static Reply get(String p){return req("GET",p,null);}
  static Reply post(String p,Object body){return req("POST",p,body);}
  static Reply put(String p,Object body){return req("PUT",p,body);}
  static Reply patch(String p,Object body){return req("PATCH",p,body);}
  static boolean containsFinding(Reply r,String id){return findings(r).stream().anyMatch(f->id.equals(s(f.get("id"))));}
  static List<Map<String,Object>> findings(Reply r){List<Map<String,Object>> out=new ArrayList<>();for(Object o:Json.array(r.obj().get("findings")))out.add(Json.object(o));return out;}
  static Object pathObj(Map<String,Object> o,String key){return o.get(key);}
  static boolean truth(Object x){return Boolean.TRUE.equals(x);}
  static boolean strContains(Reply r,String phrase){return r.raw.contains(phrase);}
  static String id(Reply r){return r.val("id");}
  static String pngBase64(int width,int height){try{BufferedImage i=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(i,"png",bytes);return Base64.getEncoder().encodeToString(bytes.toByteArray());}catch(Exception e){throw new RuntimeException(e);}}
  static void copyTree(Path src,Path dst)throws Exception{try(var paths=Files.walk(src)){for(Path p:paths.toList()){Path to=dst.resolve(src.relativize(p).toString());if(Files.isDirectory(p))Files.createDirectories(to);else Files.copy(p,to,StandardCopyOption.REPLACE_EXISTING);}}}
  static void check(String name, TestBody body){try{test(name,body.ok());}catch(Exception e){result(name,false,e.getClass().getSimpleName()+": "+e.getMessage());}}
  interface TestBody{boolean ok()throws Exception;}
  static void run()throws Exception {
    group("Environment and HTTP security");
    Reply health=get("/api/health");expectStatus("Health endpoint returns 200",health,200);
    test("Service health reports UP","UP".equals(health.val("status")));
    test("Service identifies postflight-core","postflight-core".equals(health.val("service")));
    test("Local storage mode advertised","file-backed".equals(health.val("storage")));
    test("Publishing disabled","DISABLED".equals(health.val("publishing")));
    test("Java runtime announced","17+".equals(health.val("java")));
    Reply sess=get("/api/session");expectStatus("Session endpoint available",sess,200);
    test("Unauthenticated local demo session",Boolean.FALSE.equals(sess.obj().get("authenticated")));
    test("Demo session warns about public exposure",sess.val("message").contains("never expose"));
    Reply boot=get("/api/bootstrap");expectStatus("Bootstrap loads",boot,200);
    test("No certificate secret in bootstrap",!boot.raw.contains("certificateSecret"));
    test("Bootstrap exposes runtime mode",s(Json.object(boot.obj().get("runtime")).get("mode")).equals("local-development"));
    test("Seed workspaces and drafts are present",Json.array(boot.obj().get("drafts")).size()>=7);
    test("Bootstrap declares no login",Boolean.FALSE.equals(Json.object(boot.obj().get("runtime")).get("authentication")));
    Reply home=get("/");expectStatus("Browser homepage serves HTML",home,200);
    test("Homepage loads app JavaScript",home.raw.contains("assets/app.js"));
    test("Homepage forbids being framed",s(home.headers.firstValue("x-frame-options").orElse("")).equals("DENY"));
    test("Homepage includes Content Security Policy",home.headers.firstValue("content-security-policy").isPresent());
    test("Homepage has no-sniff header","nosniff".equals(home.headers.firstValue("x-content-type-options").orElse("")));
    test("Homepage is an HTML response",home.headers.firstValue("content-type").orElse("").contains("text/html"));
    Reply js=get("/assets/app.js");expectStatus("JavaScript is served",js,200);
    test("JavaScript has JS MIME type",js.headers.firstValue("content-type").orElse("").contains("javascript"));
    Reply css=get("/assets/styles.css");expectStatus("CSS is served",css,200);
    test("CSS has CSS MIME type",css.headers.firstValue("content-type").orElse("").contains("text/css"));
    expectStatus("Unknown API endpoint rejected",get("/api/unknown-path"),404);
    expectStatus("Unknown asset path rejected",get("/assets/does-not-exist.js"),404);
    expectStatus("Invalid static POST rejected",post("/",data("hello","world")),405);
    expectStatus("Mutations reject cross-site origins",req("POST","/api/drafts",data("title","bad"),Map.of("Origin","https://example.com")),403);
    expectStatus("Mutations reject wrong content type",req("POST","/api/drafts",data("title","bad"),Map.of("Content-Type","text/plain")),415);
    expectStatus("Missing draft title rejected",post("/api/drafts",data("body","test")),400);
    expectStatus("Whitespace-only draft title rejected",post("/api/drafts",data("title","    ")),400);
    expectStatus("Long draft title rejected",post("/api/drafts",data("title","x".repeat(181))),400);
    expectStatus("Overlong body rejected",post("/api/drafts",data("title","a","body","x".repeat(12001))),400);

    group("Draft creation and version safety");
    Reply risky=post("/api/drafts",data("title","QA automated restricted content","body","Get guaranteed returns on investments today!","campaign","QA isolated test campaign","platforms","LinkedIn"));
    expectStatus("Creates a risky draft",risky,201);String rid=id(risky);
    test("Draft ID uses PF prefix",rid.startsWith("PF-"));
    test("Draft starts at version 1",s(risky.obj().get("version")).equals("1"));
    test("Custom campaign saved",risky.val("campaign").equals("QA isolated test campaign"));
    test("Title persisted immediately",risky.val("title").contains("QA automated"));
    test("Body persisted immediately",risky.val("body").contains("guaranteed returns"));
    test("Draft starts in Draft state",risky.val("status").equals("Draft"));
    Reply drafts=get("/api/drafts");expectStatus("Draft list returns 200",drafts,200);
    check("New draft appears in list",()->drafts.raw.contains(rid));
    expectStatus("Unknown draft ID returns 404",put("/api/drafts/PF-no-such-draft",data("body","test")),404);
    expectStatus("Cannot approve draft with no validation",post("/api/reviews/"+rid+"/decision",data("decision","Approved")),409);
    expectStatus("Cannot issue release with no validation",post("/api/releases",data("draftId",rid)),409);
    Reply r1=put("/api/drafts/"+rid,data("expectedVersion",1,"title","QA version update","altText","Test illustration"));
    expectStatus("Update draft with matching version",r1,200);
    test("Version incremented to 2",s(r1.obj().get("version")).equals("2"));
    test("Alt text updated",r1.val("altText").equals("Test illustration"));
    expectStatus("Stale expectedVersion produces conflict",put("/api/drafts/"+rid,data("expectedVersion",1,"body","wrong")),409);
    Reply un=put("/api/drafts/"+rid,data("expectedVersion",2,"body",r1.val("body")));
    test("No-op update does not increment version",s(un.obj().get("version")).equals("2"));
    test("No-op update preserves body",un.val("body").equals(r1.val("body")));
    expectStatus("Alt text length limit enforced",put("/api/drafts/"+rid,data("altText","x".repeat(1001))),400);
    expectStatus("Post title length limit enforced",put("/api/drafts/"+rid,data("title","x".repeat(181))),400);
    Reply ch=put("/api/drafts/"+rid,data("body","Get guaranteed returns on investments today!","altText",""));
    test("Edited draft changed to version 3",s(ch.obj().get("version")).equals("3"));

    group("Content validation and finding severities");
    Reply first=post("/api/validations",data("draftId",rid,"platform","LinkedIn","altText",""));expectStatus("Risky validation created",first,201);
    test("Guarantee causes BLOCKED result",first.val("verdict").equals("BLOCKED"));
    test("Guarantee rule FIN-MKT-001 detected",containsFinding(first,"FIN-MKT-001"));
    test("Disclosure rule FIN-MKT-014 detected",containsFinding(first,"FIN-MKT-014"));
    test("Alt-text rule A11Y-004 detected",containsFinding(first,"A11Y-004"));
    test("Three initial findings returned",findings(first).size()==3);
    test("Run ID uses PF-RUN prefix",id(first).startsWith("PF-RUN-"));
    test("Validation is tied to draft",first.val("draftId").equals(rid));
    test("Validation records current draft version",s(first.obj().get("draftVersion")).equals("3"));
    test("Policy version included",first.val("policyVersion").contains("FIN-MKT-v3.4"));
    test("Content hash recorded",first.val("contentHash").length()==64);
    check("Critical severity in guaranteed rule",()->findings(first).stream().anyMatch(f->"FIN-MKT-001".equals(f.get("id"))&&"Critical".equals(f.get("severity"))));
    check("High severity in disclosure rule",()->findings(first).stream().anyMatch(f->"FIN-MKT-014".equals(f.get("id"))&&"High".equals(f.get("severity"))));
    check("Medium severity in alt-text rule",()->findings(first).stream().anyMatch(f->"A11Y-004".equals(f.get("id"))&&"Medium".equals(f.get("severity"))));
    expectStatus("Blocking validation prevents review approval",post("/api/reviews/"+rid+"/decision",data("decision","Approved")),409);
    expectStatus("Blocking validation prevents release",post("/api/releases",data("draftId",rid)),409);
    Reply second=post("/api/validations",data("draftId",rid,"platform","LinkedIn","altText",""));
    test("Rerun gets a distinct run ID",!id(second).equals(id(first)));
    test("Rerun still blocks unchanged risky content",second.val("verdict").equals("BLOCKED"));
    Reply validationList=get("/api/validations");test("Validation history includes both runs",validationList.raw.contains(id(first))&&validationList.raw.contains(id(second)));
    expectStatus("Unknown draft cannot be validated",post("/api/validations",data("draftId","PF-0000")),404);
    Reply safeUpdate=put("/api/drafts/"+rid,data("body","Explore financial products with care. Investing involves risk and outcomes may vary.","altText","A financial-planning illustration"));
    test("Remediation increments content version",s(safeUpdate.obj().get("version")).equals("4"));
    expectStatus("Stale validation blocks release",post("/api/releases",data("draftId",rid)),409);
    Reply safe=post("/api/validations",data("draftId",rid,"platform","LinkedIn","altText",safeUpdate.val("altText")));
    test("Corrected content gets PASS",safe.val("verdict").equals("PASS"));
    test("No critical findings remain",!containsFinding(safe,"FIN-MKT-001"));
    test("No high findings remain",!containsFinding(safe,"FIN-MKT-014"));
    test("No alt-text findings remain",!containsFinding(safe,"A11Y-004"));
    test("PASS has zero findings",findings(safe).isEmpty());
    test("PASS score is 100",s(safe.obj().get("score")).equals("100"));
    Reply warn=post("/api/drafts",data("title","QA medium warning","body","Plan a better budget today."));String wid=id(warn);
    Reply warning=post("/api/validations",data("draftId",wid,"platform","LinkedIn","altText",""));
    test("Missing alt-text yields WARN",warning.val("verdict").equals("WARN"));
    test("Only accessibility warning remains",findings(warning).size()==1&&containsFinding(warning,"A11Y-004"));
    Reply empty=post("/api/drafts",data("title","QA empty body test"));
    Reply emptyRun=post("/api/validations",data("draftId",id(empty),"altText",""));
    test("Empty body results in BLOCKED",emptyRun.val("verdict").equals("BLOCKED"));
    test("Empty body rule CONTENT-001 detected",containsFinding(emptyRun,"CONTENT-001"));
    Reply xd=post("/api/drafts",data("title","QA X limit","body","x".repeat(290),"platforms","X","altText","X illustration"));
    Reply xr=post("/api/validations",data("draftId",id(xd),"platform","X","altText","X illustration"));
    test("X length rule triggers over 280 chars",containsFinding(xr,"PLT-X-011"));
    test("X over-limit verdict BLOCKED",xr.val("verdict").equals("BLOCKED"));
    Reply ig=post("/api/drafts",data("title","QA Instagram link","body","Learn more https://example.com","platforms","Instagram","altText","Illustration"));
    Reply igr=post("/api/validations",data("draftId",id(ig),"platform","Instagram","altText","Illustration"));
    test("Instagram URL usability warning detected",containsFinding(igr,"LINK-006"));
    test("Instagram link has non-blocking WARN",igr.val("verdict").equals("WARN"));

    group("Reviews, release gating and evidence");
    expectStatus("Cannot release safe draft before approval",post("/api/releases",data("draftId",rid)),409);
    Reply approve=post("/api/reviews/"+rid+"/decision",data("decision","Approved","reviewer","Automated QA"));expectStatus("Approved safe validated draft",approve,200);
    test("Approval response points to correct draft",approve.val("draftId").equals(rid));
    test("Approval decision recorded",approve.val("decision").equals("Approved"));
    Reply finalRel=post("/api/releases",data("draftId",rid));expectStatus("Release certificate issued",finalRel,201);String relId=id(finalRel);
    test("Certificate ID PF-REL",relId.startsWith("PF-REL-"));
    test("Certificate references the same draft",finalRel.val("draftId").equals(rid));
    test("Certificate records approved draft version",s(finalRel.obj().get("draftVersion")).equals("4"));
    test("Release status ready for handoff",finalRel.val("status").equals("READY_FOR_HANDOFF"));
    test("Actual publishing remains unconfigured",finalRel.val("publishing").equals("NOT_CONFIGURED"));
    test("Certificate uses local HMAC-SHA256",finalRel.val("signatureAlgorithm").equals("HMAC-SHA256-LOCAL-DEMO"));
    test("Certificate contains SHA256-like signature",finalRel.val("signature").length()==64);
    Reply verify=get("/api/releases/"+relId+"/verify");expectStatus("Verification endpoint responds",verify,200);
    test("Certificate signature verifies",truth(verify.obj().get("signatureValid")));
    test("Certificate has not been published",Boolean.FALSE.equals(verify.obj().get("published")));
    test("Verification retains version 4",s(verify.obj().get("historicalVersion")).equals("4"));
    expectStatus("Unknown certificate verification returns 404",get("/api/releases/PF-REL-INVALID/verify"),404);
    Reply relList=get("/api/releases");test("Release register includes new record",relList.raw.contains(relId));
    Reply dup=post("/api/releases",data("draftId",rid));
    test("Repeated issue creates another certificate (current behavior)",dup.status==201&&!id(dup).equals(relId));
    Reply edit=put("/api/drafts/"+rid,data("body","Different content after approval"));
    test("Editing approved draft increments version",s(edit.obj().get("version")).equals("5"));
    test("Editing approved draft resets status",edit.val("status").equals("Draft"));
    expectStatus("Edited draft cannot re-release without validation",post("/api/releases",data("draftId",rid)),409);
    expectStatus("Edited draft cannot approve before validation",post("/api/reviews/"+rid+"/decision",data("decision","Approved")),409);
    Reply prior=get("/api/releases/"+relId+"/verify");test("Historical certificate still verifies",truth(prior.obj().get("signatureValid")));
    Reply change=post("/api/reviews/"+rid+"/decision",data("decision","Changes requested"));test("Request changes decision saved",change.val("decision").equals("Changes requested"));

    group("Policy Studio and workflow simulator");
    Reply policy=get("/api/policies");expectStatus("Policy rules endpoint available",policy,200);
    test("Base policy is FIN-MKT-v3.4",policy.val("baseVersion").equals("FIN-MKT-v3.4"));
    test("Initial custom rules are empty",Json.array(policy.obj().get("customRules")).isEmpty());
    expectStatus("Blank rule title rejected",post("/api/policies/rules",data("title","","phrase","prohibited")),400);
    expectStatus("Blank rule phrase rejected",post("/api/policies/rules",data("title","Disallowed","phrase","")),400);
    expectStatus("Unknown rule severity rejected",post("/api/policies/rules",data("title","Disallowed","phrase","banned","severity","Mega")),400);
    Reply rule=post("/api/policies/rules",data("title","Block magic profits","phrase","magical profits guaranteed","severity","High"));expectStatus("Custom policy rule saved",rule,201);
    String customId=s(Json.object(rule.obj().get("rule")).get("id"));
    test("Custom policy rule ID assigned",customId.startsWith("ORG-"));
    test("New policy version increments",rule.val("policyVersion").endsWith("+r1"));
    expectStatus("Duplicate rule case-insensitive rejected",post("/api/policies/rules",data("title","Duplicate","phrase","MAGICAL PROFITS GUARANTEED","severity","High")),409);
    Reply newRules=get("/api/policies");test("Created rule appears in policy list",newRules.raw.contains(customId));
    Reply customDraft=post("/api/drafts",data("title","QA custom rule","body","See magical profits guaranteed every day","altText","Graphic"));
    Reply customValidation=post("/api/validations",data("draftId",id(customDraft),"platform","LinkedIn","altText","Graphic"));
    test("Custom prohibited phrase generates finding",containsFinding(customValidation,customId));
    test("High-severity custom rule blocks release",customValidation.val("verdict").equals("BLOCKED"));
    expectStatus("Older policy approval is stale after policy change",post("/api/reviews/"+wid+"/decision",data("decision","Approved")),409);
    for(int level=1;level<=5;level++){
      Reply sim=post("/api/policies/simulate",data("strictness",level));
      test("Policy simulation level "+level+" responds",sim.status==200&&truth(sim.obj().get("simulation"))&&s(sim.obj().get("strictness")).equals(String.valueOf(level)));
    }
    Reply clamp=post("/api/policies/simulate",data("strictness",12));test("Policy simulation clamps to max 5",s(clamp.obj().get("strictness")).equals("5"));
    for(String risk:List.of("low","medium","high","critical")){
      Reply flow=post("/api/workflows/test",data("risk",risk));
      test("Workflow "+risk+" simulates without publishing",flow.status==200&&truth(flow.obj().get("simulation"))&&Boolean.FALSE.equals(flow.obj().get("published")));
      List<Object> trace=Json.array(flow.obj().get("trace"));
      test("Workflow "+risk+" has expected number of steps",trace.size()==(risk.equals("high")||risk.equals("critical")?5:4));
    }

    group("Media Intelligence and storage");
    Reply media=get("/api/media");expectStatus("Media list endpoint available",media,200);
    String png=pngBase64(64,48);
    expectStatus("Blank upload name rejected",post("/api/media",data("name","","mime","image/png","base64",png)),400);
    expectStatus("Unsupported MIME rejected",post("/api/media",data("name","example.pdf","mime","application/pdf","base64",png)),415);
    expectStatus("Corrupt base64 rejected",post("/api/media",data("name","bad.png","mime","image/png","base64","NOT-BASE64!!")),400);
    expectStatus("MIME/signature mismatch rejected",post("/api/media",data("name","bad.jpg","mime","image/jpeg","base64",png)),415);
    Reply asset=post("/api/media",data("name","QA generated image.png","mime","image/png","base64",png,"altText","A colored sample image"));
    expectStatus("Valid 64x48 PNG uploaded",asset,201);String assetId=id(asset);
    test("Asset ID assigned",assetId.startsWith("ASSET-"));
    test("Uploaded asset dimensions validated",s(asset.obj().get("width")).equals("64")&&s(asset.obj().get("height")).equals("48"));
    test("Upload with alt text Ready",asset.val("status").equals("Ready"));
    test("Uploaded asset includes alt text",asset.val("altText").contains("sample image"));
    Reply assetBody=get("/api/media/"+assetId);expectStatus("Asset content retrievable",assetBody,200);
    test("Downloaded asset bytes match upload",assetBody.val("base64").equals(png));
    Reply missingAlt=post("/api/media",data("name","Needs accessibility.png","mime","image/png","base64",png));
    test("Asset without alt text needs review",missingAlt.status==201&&missingAlt.val("status").equals("Needs review"));
    test("Media list contains new asset",get("/api/media").raw.contains(assetId));
    expectStatus("Unknown asset rejected",get("/api/media/ASSET-UNKNOWN"),404);
    String oversized=Base64.getEncoder().encodeToString(new byte[700001]);
    expectStatus("Oversized image rejected (413)",post("/api/media",data("name","large.png","mime","image/png","base64",oversized)),413);

    group("Incidents, integrations, audit, persistence");
    Reply integrations=get("/api/integrations");expectStatus("Integrations API available",integrations,200);
    Map<String,Object> z=Json.object(integrations.obj().get("zernio"));
    test("Zernio publishing is OFF",Boolean.FALSE.equals(z.get("publishingEnabled")));
    test("Zernio credentials not configured",Boolean.FALSE.equals(z.get("configured")));
    test("Webhooks not configured",Boolean.FALSE.equals(Json.object(integrations.obj().get("webhooks")).get("configured")));
    test("Object storage not configured",Boolean.FALSE.equals(Json.object(integrations.obj().get("objectStorage")).get("configured")));
    expectStatus("Unknown incident rejected",patch("/api/incidents/CMP-UNKNOWN",data("status","Resolved")),404);
    expectStatus("Invalid incident status rejected",patch("/api/incidents/CMP-018",data("status","NotReal")),400);
    Reply incident=patch("/api/incidents/CMP-018",data("status","Resolved"));test("Incident can be marked Resolved",incident.status==200&&incident.val("status").equals("Resolved"));
    Reply reopen=patch("/api/incidents/CMP-018",data("status","Investigating"));test("Incident can move to Investigating",reopen.status==200&&reopen.val("status").equals("Investigating"));
    Reply metric=get("/api/metrics");expectStatus("Metrics API responds",metric,200);
    test("Draft metrics reflect created drafts",Integer.parseInt(metric.val("totalDrafts"))>=12);
    test("Certificate count reflects issued releases",Integer.parseInt(metric.val("releaseCertificates"))>=2);
    test("Dashboard illustration explicitly labeled",truth(metric.obj().get("illustrativeChart")));
    Reply audit=get("/api/audit");expectStatus("Audit endpoint responds",audit,200);
    test("Audit chain is valid",truth(audit.obj().get("chainValid")));
    test("Audit captures draft create",audit.raw.contains("DRAFT_CREATED"));
    test("Audit captures validation result",audit.raw.contains("VALIDATION_PASS"));
    test("Audit captures approval",audit.raw.contains("REVIEW_APPROVED"));
    test("Audit captures issued release",audit.raw.contains("RELEASE_CERTIFICATE_ISSUED"));
    test("Audit captures media upload",audit.raw.contains("MEDIA_UPLOADED"));
    test("Audit captures policy rule",audit.raw.contains("POLICY_RULE_CREATED"));
    test("Audit events are hash-linked",audit.raw.contains("previousHash")&&audit.raw.contains("GENESIS"));
    test("Certificate secret never appears in API audit",!audit.raw.contains("certificateSecret"));
    String persisted=Files.readString(sandbox.resolve("data/postflight-data.json"),StandardCharsets.UTF_8);
    test("Isolated state written to disk",persisted.contains(relId)&&persisted.contains(rid));
    test("Isolated media bytes written to disk",Files.exists(sandbox.resolve("data/media/"+assetId+".png")));
    Reply seedDrafts=get("/api/drafts");test("Seed was not erased by tests",seedDrafts.raw.contains("PF-2048"));

    group("UI assets and declared routes (static checks, NOT browser interaction)");
    String content=Files.readString(project.resolve("web/assets/app.js"));
    for(String route:List.of("overview","workspaces","studio","validation","media","policies","workflows","reviews","releases","incidents","integrations","settings"))
      test("Frontend defines route "+route,content.contains("'"+route+"'")||content.contains("\""+route+"\""));
    for(String label:List.of("Content Studio","Validation Center","Review Center","Release Center","Policy Studio","Media Intelligence","Workflow Builder","Mission Control","Workspaces","Integrations","Administration"))
      test("Frontend source contains "+label,content.contains(label));
    test("Client fetch logic present",content.contains("fetch("));
    test("Client has browser navigation",content.contains("location.hash")||content.contains("hashchange"));
    test("Client has local-dev warning",content.contains("LOCAL")||content.contains("local"));
    test("CSS is not empty",Files.size(project.resolve("web/assets/styles.css"))>10000);
    test("Bootstrap web page has viewport metadata",home.raw.contains("viewport"));
  }
  static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
  static void report()throws Exception{
    Files.createDirectories(reportDir);
    Path html=reportDir.resolve("POSTFLIGHT_QA_RESULTS.html"), csv=reportDir.resolve("POSTFLIGHT_QA_RESULTS.csv"), json=reportDir.resolve("POSTFLIGHT_QA_RESULTS.json");
    List<Map<String,Object>> rows=new ArrayList<>();StringBuilder csvOut=new StringBuilder("Section,Test,Outcome,Detail\r\n"),body=new StringBuilder();
    for(Result r:results){rows.add(data("section",r.section,"test",r.name,"status",r.outcome,"detail",r.detail));
      for(String val:List.of(r.section,r.name,r.outcome,r.detail))csvOut.append('"').append(val.replace("\"","\"\"")).append('"').append(val.equals(r.detail)?"\r\n":",");
      body.append("<tr><td>").append(escape(r.section)).append("</td><td>").append(escape(r.name)).append("</td><td class='").append(r.outcome.equals("PASS")?"pass":"fail").append("'>").append(r.outcome).append("</td><td>").append(escape(r.detail)).append("</td></tr>");
    }
    Files.writeString(csv,csvOut.toString(),StandardCharsets.UTF_8);
    Files.writeString(json,Json.stringify(data("passed",pass,"failed",fail,"automatedAssertions",results.size(),"scope","Java17 live isolated backend, HTTP and static frontend checks; not full manual 148-case UI checklist","results",rows)),StandardCharsets.UTF_8);
    String page="""
      <!doctype html><html lang='en'><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'><title>PostFlight QA Results</title>
      <style>body{font:14px system-ui,Segoe UI,Arial;background:#f5f7fb;color:#18243a;max-width:1250px;margin:auto;padding:30px}h1{font-size:30px}h2{font-size:17px;font-weight:500}.kpi{display:inline-flex;background:white;border:1px solid #e1e5ed;padding:16px 24px;border-radius:12px;margin:0 12px 16px 0;font-size:20px;font-weight:700}.kpi span{font-size:12px;font-weight:400;display:block;color:#6c7480}table{border-collapse:collapse;background:white;width:100%;border-radius:12px;overflow:hidden}td,th{padding:12px;border-bottom:1px solid #e3e7ee;text-align:left}th{background:#eaeefa}.pass{color:#067a60;font-weight:700}.fail{color:#c33546;font-weight:700}input{padding:12px;border:1px solid #ddd;border-radius:8px;width:300px;margin:12px 0}.warning{background:#fef9e8;border:1px solid #f2df9e;padding:12px;border-radius:8px}</style>
      <h1>PostFlight Enterprise — Automated QA</h1><h2>Java 17 local build · Standalone isolated run</h2>
      <div class='kpi'><div><span>Passed</span>PASS_COUNT</div></div><div class='kpi'><div><span>Failed</span>FAIL_COUNT</div></div><div class='kpi'><div><span>Checks</span>TOTAL_COUNT</div></div>
      <p class='warning'>This report covers backend API contracts, release guards, security checks and frontend source/route checks. It is NOT proof that every clickable UI control or every one of the 148 manual checklist cases passed. Tests run against a separate temporary dataset; your live drafts and releases were not modified.</p>
      <input id='q' placeholder='Filter tests...' oninput="document.querySelectorAll('tbody tr').forEach(r=>r.hidden=!r.innerText.toLowerCase().includes(this.value.toLowerCase()))">
      <table><thead><tr><th>Area</th><th>Check</th><th>Result</th><th>Details</th></tr></thead><tbody>ROWS</tbody></table>
      </html>
      """.replace("PASS_COUNT",String.valueOf(pass)).replace("FAIL_COUNT",String.valueOf(fail)).replace("TOTAL_COUNT",String.valueOf(results.size())).replace("ROWS",body.toString());
    Files.writeString(html,page,StandardCharsets.UTF_8);
    System.out.println("\n==== POSTFLIGHT QA SUMMARY ====");
    System.out.println("Passed: "+pass+"   Failed: "+fail+"   Total checks: "+results.size());
    System.out.println("HTML report: "+html.toAbsolutePath());
    System.out.println("CSV report:  "+csv.toAbsolutePath());
    System.out.println("JSON report: "+json.toAbsolutePath());
    System.out.println("Note: the 148-case browser/manual checklist is a different scope; not all 148 are automated.");
  }
  public static void main(String[] args) throws Exception {
    if(args.length<2){System.err.println("Usage: PostFlightQARunner <project-root> <report-dir>");System.exit(2);}
    project=Path.of(args[0]).toAbsolutePath().normalize();reportDir=Path.of(args[1]).toAbsolutePath().normalize();
    sandbox=Files.createTempDirectory("postflight-qa-isolated-");
    Files.createDirectories(sandbox.resolve("docs"));
    Files.copy(project.resolve("docs/seed.json"),sandbox.resolve("docs/seed.json"));
    copyTree(project.resolve("web"),sandbox.resolve("web"));
    LocalServer server=null;
    try{
      server=new LocalServer(0,sandbox);server.start();base="http://127.0.0.1:"+server.port();
      System.out.println("QA isolated app started at "+base+" (your normal 8080 app is untouched)");
      try{run();}catch(Throwable e){result("QA runner execution",false,e.toString());e.printStackTrace(System.err);}
      report();
    }finally{
      if(server!=null)server.close();
      // Leave temporary directory available only within the running task; cleanup all isolated data.
      try(var walk=Files.walk(sandbox)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(Exception e){System.err.println("Temporary cleanup warning: "+e);}
    }
    System.exit(fail==0?0:1);
  }
}
