package com.postflight.core;

import java.util.*;

/** Framework-independent HTTP routing; both Java SE and Spring Boot use identical handlers. */
public final class ApiDispatcher {
    public record Answer(int status,String json) {}
    private final PostFlightService service;
    public ApiDispatcher(PostFlightService service){this.service=service;}
    public Answer handle(String method,String rawPath,String body){
      // Deliberately narrow public exception: pure in-memory analysis, no side effects.
      if ("POST".equals(method) && "/api/demo/preflight".equals(rawPath)) {
        try {
          return out(200, DemoPreflight.inspect(Json.object(Json.parse(body))));
        } catch (IllegalArgumentException e) {
          return out(400, Map.of("error", e.getMessage()));
        }
      }
      // Public demo mode: block every state-changing API request.
      if ("true".equalsIgnoreCase(System.getenv("POSTFLIGHT_PUBLIC_DEMO"))
          && !"GET".equals(method) && !"HEAD".equals(method)) {
        return out(403, Map.of("error", "Public demo is read-only"));
      }
      String path=rawPath.split("\\?",2)[0];
      try{
        Map<String,Object> payload=(body==null||body.isBlank())?new LinkedHashMap<>():Json.object(Json.parse(body));
        String[] seg=path.split("/");
        Object response;
        if(method.equals("GET")&&path.equals("/api/health"))response=service.health();
        else if(method.equals("GET")&&path.equals("/api/session"))response=service.session();
        else if(method.equals("GET")&&path.equals("/api/bootstrap"))response=service.bootstrap();
        else if(method.equals("GET")&&path.equals("/api/metrics"))response=service.metrics();
        else if(method.equals("GET")&&path.equals("/api/drafts"))response=service.drafts();
        else if(method.equals("POST")&&path.equals("/api/drafts"))return out(201,service.createDraft(payload));
        else if(method.equals("PUT")&&seg.length==4&&seg[2].equals("drafts"))response=service.updateDraft(seg[3],payload);
        else if(method.equals("POST")&&path.equals("/api/validations"))return out(201,service.validate(payload));
        else if(method.equals("GET")&&path.equals("/api/validations"))response=service.validations();
        else if(method.equals("POST")&&seg.length==5&&seg[2].equals("reviews")&&seg[4].equals("decision"))response=service.review(seg[3],payload);
        else if(method.equals("POST")&&path.equals("/api/releases"))return out(201,service.issueRelease(payload));
        else if(method.equals("GET")&&path.equals("/api/releases"))response=service.releases();
        else if(method.equals("GET")&&seg.length==5&&seg[2].equals("releases")&&seg[4].equals("verify"))response=service.verifyRelease(seg[3]);
        else if(method.equals("GET")&&path.equals("/api/audit"))response=service.auditReport();
        else if(method.equals("POST")&&path.equals("/api/workflows/test"))response=service.workflowTest(payload);
        else if(method.equals("POST")&&path.equals("/api/policies/simulate"))response=service.policySimulate(payload);
        else if(method.equals("GET")&&path.equals("/api/policies"))response=service.getPolicies();
        else if(method.equals("GET")&&path.equals("/api/media"))response=service.getMedia();
        else if(method.equals("POST")&&path.equals("/api/media"))return out(201,service.uploadMedia(payload));
        else if(method.equals("GET")&&seg.length==4&&seg[2].equals("media"))response=service.getMediaAsset(seg[3]);
        else if(method.equals("POST")&&path.equals("/api/policies/rules"))return out(201,service.createPolicyRule(payload));
        else if(method.equals("PATCH")&&seg.length==4&&seg[2].equals("incidents"))response=service.updateIncident(seg[3],payload);
        else if(method.equals("GET")&&path.equals("/api/integrations"))response=service.integrations();
        else if(method.equals("POST")&&path.equals("/api/admin/reset"))response=service.reset();
        else return out(404,Map.of("error","Endpoint not found","path",path));
        return out(200,response);
      }catch(PostFlightService.ApiError e){return out(e.status,Map.of("error",e.getMessage(),"status",e.status));}
       catch(IllegalArgumentException e){return out(400,Map.of("error",e.getMessage(),"status",400));}
       catch(Exception e){return out(500,Map.of("error","Internal processing error","status",500));}
    }
    private static Answer out(int status,Object result){return new Answer(status,Json.stringify(result));}
}
