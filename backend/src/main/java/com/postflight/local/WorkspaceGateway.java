package com.postflight.local;

import com.postflight.core.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/**
 * Authenticated API boundary. All workspace CRUD lives under /api/secure/*, never
 * under the existing public/shared /api/bootstrap demo namespace.
 */
public final class WorkspaceGateway {
    private final WorkspaceRegistry registry;
    private final WorkspaceDraftStore drafts;
    private final ClerkSessionVerifier verifier;
    public WorkspaceGateway(Path root) throws IOException {
        registry=new WorkspaceRegistry(root.resolve("data/secure-workspaces.json"));
        drafts=new WorkspaceDraftStore(root.resolve("data/workspace-drafts"));
        verifier=ClerkSessionVerifier.configured()?ClerkSessionVerifier.fromEnvironment():null;
    }
    private static ApiDispatcher.Answer out(int status,Object payload){
        return new ApiDispatcher.Answer(status,Json.stringify(payload));
    }
    private static ApiDispatcher.Answer error(int status,String message){
        return out(status,Map.of("error",message,"status",status));
    }
    public ApiDispatcher.Answer handle(String method,String path,String body,String authorization){
        boolean publicDemo="true".equalsIgnoreCase(System.getenv("POSTFLIGHT_PUBLIC_DEMO"));
        // Configuration reveals only public OAuth identifiers, never credentials.
        if(path.equals("/api/secure/config")&&method.equals("GET")){
            boolean enabled=verifier!=null&&!publicDemo;
            return out(200,Map.of("enabled",enabled,"provider","Clerk",
                "publishableKey",enabled?System.getenv("POSTFLIGHT_CLERK_PUBLISHABLE_KEY"):"",
                "issuer",enabled?verifier.issuer():"",
                "authFlow","clerk_session",
                "workspaceApi",enabled?"authenticated":"disabled",
                "publicReadOnly",publicDemo));
        }
        if(publicDemo&&!List.of("GET","HEAD").contains(method))
            return error(403,"Public demo is read-only");
        if(verifier==null||publicDemo)
            return error(503,"Authenticated workspaces are not enabled on this runtime");
        try{
            WorkspaceRegistry.Principal user=verifier.verifyBearer(authorization);
            if(method.equals("GET")&&path.equals("/api/secure/me"))
                return out(200,Map.of("sub",user.sub(),"username",user.username(),"authenticated",true));
            if(method.equals("GET")&&path.equals("/api/secure/workspaces"))
                return out(200,registry.myWorkspaces(user));
            if(method.equals("POST")&&path.equals("/api/secure/workspaces"))
                return out(201,registry.create(user,Json.object(Json.parse(body))));
            if(method.equals("POST")&&path.equals("/api/secure/invitations/accept"))
                return out(200,registry.accept(user,Json.object(Json.parse(body))));
            String[] parts=path.split("/",-1);
            if(parts.length>=5&&parts[1].equals("api")&&parts[2].equals("secure")&&parts[3].equals("workspaces")){
                String id=parts[4];
                if(!id.matches("ws_[0-9a-f]{32}"))return error(404,"Workspace not found");
                if(parts.length>=6&&parts[5].equals("drafts")){
                    // Resolve membership BEFORE looking up any workspace-specific data.
                    Map<String,Object> workspace=registry.workspace(user,id);
                    String role=String.valueOf(workspace.get("yourRole"));
                    boolean writable=List.of("OWNER","ADMIN").contains(role);
                    if(parts.length==6){
                        if(method.equals("GET"))return out(200,drafts.list(id));
                        if(method.equals("POST")){
                            if(!writable)return error(403,"Role does not permit draft changes");
                            return out(201,drafts.create(id,user.sub(),Json.object(Json.parse(body))));
                        }
                    }
                    if(parts.length==7){
                        String draftId=parts[6];
                        if(method.equals("GET"))return out(200,drafts.get(id,draftId));
                        if(method.equals("PUT")){
                            if(!writable)return error(403,"Role does not permit draft changes");
                            return out(200,drafts.update(id,draftId,user.sub(),Json.object(Json.parse(body))));
                        }
                    }
                    return error(404,"Secure draft endpoint not found");
                }
                if(parts.length==5&&method.equals("GET"))
                    return out(200,registry.workspace(user,id));
                if(parts.length==6&&parts[5].equals("members")&&method.equals("GET"))
                    return out(200,registry.members(user,id));
                if(parts.length==6&&parts[5].equals("invitations")){
                    if(method.equals("POST"))
                        return out(201,registry.invite(user,id,Json.object(Json.parse(body))));
                    if(method.equals("GET"))
                        return out(200,registry.invitations(user,id));
                }
                if(parts.length==6&&parts[5].equals("audit")&&method.equals("GET"))
                    return out(200,registry.workspaceAudit(user,id));
            }
            return error(404,"Secure endpoint not found");
        }catch(ClerkSessionVerifier.AuthFailure e){return error(e.status,e.getMessage());}
         catch(WorkspaceRegistry.Failure e){return error(e.status,e.getMessage());}
         catch(IllegalArgumentException e){return error(400,"Invalid request payload");}
         catch(Exception e){return error(500,"Secure request could not be processed");}
    }
}
