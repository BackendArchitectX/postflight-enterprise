package com.postflight.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Isolated authenticated-workspace domain. No legacy demo drafts/data are treated as tenants.
 * The caller MUST supply a principal obtained from a validated Clerk session JWT.
 */
public final class WorkspaceRegistry {
    public record Principal(String sub, String username) {
        public Principal {
            if (sub == null || sub.isBlank() || username == null || username.isBlank())
                throw new IllegalArgumentException("Verified Cognito identity required");
        }
    }
    public static final class Failure extends RuntimeException {
        public final int status;
        public Failure(int status, String message) { super(message); this.status=status; }
    }
    private final Path storage;
    private final SecureRandom rng = new SecureRandom();
    private Map<String,Object> data;

    public WorkspaceRegistry(Path storage) throws IOException {
        this.storage=storage;
        Files.createDirectories(storage.toAbsolutePath().getParent());
        if(Files.isRegularFile(storage)){
            data=Json.object(Json.parse(Files.readString(storage, StandardCharsets.UTF_8)));
        } else data=new LinkedHashMap<>();
        data.putIfAbsent("workspaces",new ArrayList<>());
        data.putIfAbsent("invitations",new ArrayList<>());
        data.putIfAbsent("audit",new ArrayList<>());
    }
    private static String value(Object o) {return o==null?"":String.valueOf(o);}
    private static Map<String,Object> row(Object o){return Json.object(o);}
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> list(String key){
        return (List<Map<String,Object>>)(List<?>)Json.array(data.get(key));
    }
    private static Map<String,Object> obj(Object...args){
        Map<String,Object> out=new LinkedHashMap<>();
        for(int i=0;i<args.length;i+=2)out.put((String)args[i],args[i+1]);
        return out;
    }
    private static Map<String,Object> safeCopy(Map<String,Object> m){
        return Json.object(Json.parse(Json.stringify(m)));
    }
    private static String required(Map<String,Object> body,String key,int max){
        String v=value(body.get(key)).trim();
        if(v.isBlank()||v.length()>max)throw new Failure(400,key+" must be 1–"+max+" characters");
        return v;
    }
    private static String sha256(String text){return PostFlightService.sha256(text);}
    private synchronized void save(){
        try {
            Path tmp=storage.resolveSibling(storage.getFileName()+".tmp");
            Files.writeString(tmp,Json.stringify(data),StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
            try {Files.move(tmp,storage,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(tmp,storage,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException e){throw new Failure(500,"Unable to persist secure workspace records");}
    }
    private void audit(String action,String workspace,String actor,String detail){
        List<Map<String,Object>> entries=list("audit");
        String prev=entries.isEmpty()?"GENESIS":value(entries.get(entries.size()-1).get("hash"));
        String at=Instant.now().toString(),id=UUID.randomUUID().toString();
        Map<String,Object> event=obj("id",id,"at",at,"action",action,"workspaceId",workspace,
            "actorSub",actor,"detail",detail,"previousHash",prev);
        event.put("hash",sha256(prev+"|"+id+"|"+at+"|"+action+"|"+workspace+"|"+actor+"|"+detail));
        entries.add(event);
    }
    private Map<String,Object> get(String workspaceId){
        for(Map<String,Object> w:list("workspaces"))
            if(workspaceId.equals(value(w.get("id"))))return w;
        throw new Failure(404,"Workspace not found");
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> members(Map<String,Object> workspace) {
        return (List<Map<String,Object>>)(List<?>)Json.array(workspace.get("members"));
    }
    private static String role(Map<String,Object> workspace,Principal user){
        for(Map<String,Object> m:members(workspace))
            if(user.sub().equals(value(m.get("sub"))))return value(m.get("role"));
        return "";
    }
    private Map<String,Object> authorized(String id,Principal user,String...allowed){
        Map<String,Object> w=get(id);
        String userRole=role(w,user);
        if(userRole.isBlank())throw new Failure(404,"Workspace not found");
        if(allowed.length>0&&!Arrays.asList(allowed).contains(userRole))
            throw new Failure(403,"Role does not permit this operation");
        return w;
    }
    private static Map<String,Object> summary(Map<String,Object> w,String role){
        return obj("id",w.get("id"),"name",w.get("name"),"createdAt",w.get("createdAt"),
            "memberCount",members(w).size(),"yourRole",role);
    }
    public synchronized Map<String,Object> create(Principal actor,Map<String,Object> request){
        String name=required(request,"name",100);
        int count=0;
        for(Map<String,Object> w:list("workspaces"))if(!role(w,actor).isBlank())count++;
        if(count>=25)throw new Failure(409,"Workspace membership limit reached");
        for(Map<String,Object> w:list("workspaces"))
            if(!role(w,actor).isBlank()&&name.equalsIgnoreCase(value(w.get("name"))))
                throw new Failure(409,"Workspace name already exists in your account");
        String id="ws_"+UUID.randomUUID().toString().replace("-","");
        Map<String,Object> w=obj("id",id,"name",name,"createdAt",Instant.now().toString(),
            "ownerSub",actor.sub(),"members",new ArrayList<>(List.of(
                obj("sub",actor.sub(),"username",actor.username(),"role","OWNER","joinedAt",Instant.now().toString()))));
        list("workspaces").add(w);
        audit("WORKSPACE_CREATED",id,actor.sub(),name);
        save();
        return summary(w,"OWNER");
    }
    public synchronized List<Map<String,Object>> myWorkspaces(Principal actor){
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> w:list("workspaces")){
            String r=role(w,actor);
            if(!r.isBlank())out.add(summary(w,r));
        }
        return out;
    }
    public synchronized Map<String,Object> workspace(Principal actor,String id){
        Map<String,Object> w=authorized(id,actor);
        return summary(w,role(w,actor));
    }
    public synchronized List<Map<String,Object>> members(Principal actor,String id){
        Map<String,Object> w=authorized(id,actor,"OWNER","ADMIN");
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> m:members(w))out.add(safeCopy(m));
        return out;
    }
    public synchronized Map<String,Object> invite(Principal actor,String id,Map<String,Object> req){
        Map<String,Object> w=authorized(id,actor,"OWNER","ADMIN");
        String target=required(req,"targetSub",100),selectedRole=required(req,"role",16);
        if(!List.of("ADMIN","REVIEWER","VIEWER").contains(selectedRole))
            throw new Failure(400,"Invited role must be ADMIN, REVIEWER or VIEWER");
        if(!target.matches("user_[A-Za-z0-9]{8,96}"))
            throw new Failure(400,"Target must be a Clerk user ID (user_...)");
        if(target.equals(actor.sub()))throw new Failure(400,"Cannot invite yourself");
        if(members(w).size()>=100)throw new Failure(409,"Workspace member limit reached");
        for(Map<String,Object> m:members(w))
            if(target.equals(value(m.get("sub"))))throw new Failure(409,"User is already a member");
        for(Map<String,Object> inv:list("invitations"))
            if(id.equals(value(inv.get("workspaceId")))&&target.equals(value(inv.get("targetSub")))
                &&"Pending".equals(value(inv.get("status")))
                &&Instant.parse(value(inv.get("expiresAt"))).isAfter(Instant.now()))
                throw new Failure(409,"An unexpired invitation already exists for this user");
        byte[] random=new byte[32];rng.nextBytes(random);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Map<String,Object> invitation=obj("id","inv_"+UUID.randomUUID().toString().replace("-",""),
            "workspaceId",id,"targetSub",target,"role",selectedRole,"status","Pending",
            "createdBySub",actor.sub(),"createdAt",Instant.now().toString(),
            "expiresAt",Instant.now().plus(24,ChronoUnit.HOURS).toString(),
            "tokenHash",sha256(token),"acceptedAt","");
        list("invitations").add(invitation);
        audit("WORKSPACE_INVITE_CREATED",id,actor.sub(),value(invitation.get("id"))+" for "+target);
        save();
        Map<String,Object> result=safeCopy(invitation);
        result.remove("tokenHash");
        result.put("inviteToken",token); // one-time display ONLY, never returned by list endpoint
        return result;
    }
    public synchronized List<Map<String,Object>> invitations(Principal actor,String id){
        authorized(id,actor,"OWNER","ADMIN");
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> inv:list("invitations"))
            if(id.equals(value(inv.get("workspaceId")))){
                Map<String,Object> record=safeCopy(inv);record.remove("tokenHash");out.add(record);
            }
        return out;
    }
    public synchronized Map<String,Object> accept(Principal actor,Map<String,Object> req){
        String id=required(req,"invitationId",100),token=required(req,"inviteToken",150);
        Map<String,Object> inv=null;
        for(Map<String,Object> candidate:list("invitations"))
            if(id.equals(value(candidate.get("id")))){inv=candidate;break;}
        if(inv==null||!actor.sub().equals(value(inv.get("targetSub"))))
            throw new Failure(404,"Invitation not found");
        if(!"Pending".equals(value(inv.get("status"))))
            throw new Failure(409,"Invitation has already been used");
        if(!Instant.parse(value(inv.get("expiresAt"))).isAfter(Instant.now()))
            throw new Failure(410,"Invitation expired");
        if(!MessageDigest.isEqual(sha256(token).getBytes(StandardCharsets.US_ASCII),
            value(inv.get("tokenHash")).getBytes(StandardCharsets.US_ASCII)))
            throw new Failure(403,"Invalid invitation token");
        Map<String,Object> workspace=get(value(inv.get("workspaceId")));
        if(!role(workspace,actor).isBlank())throw new Failure(409,"Already a member");
        if(members(workspace).size()>=100)throw new Failure(409,"Workspace member limit reached");
        members(workspace).add(obj("sub",actor.sub(),"username",actor.username(),
            "role",inv.get("role"),"joinedAt",Instant.now().toString()));
        inv.put("status","Accepted");inv.put("acceptedAt",Instant.now().toString());
        audit("WORKSPACE_INVITE_ACCEPTED",value(workspace.get("id")),actor.sub(),id);
        save();
        return summary(workspace,value(inv.get("role")));
    }
    public synchronized List<Map<String,Object>> workspaceAudit(Principal actor,String id){
        authorized(id,actor,"OWNER","ADMIN");
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> ev:list("audit"))
            if(id.equals(value(ev.get("workspaceId"))))out.add(safeCopy(ev));
        return out;
    }
}
