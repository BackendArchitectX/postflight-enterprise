package com.postflight.local;

import com.postflight.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;

/** Runs only against a temporary directory. No Cognito/SMTP account is required. */
public final class WorkspaceSecurityCheck {
    private static int passed=0;
    private static void ok(boolean condition,String message){
        if(!condition)throw new AssertionError(message);
        passed++;
    }
    private static void fails(int status,Runnable work){
        try{work.run();throw new AssertionError("Expected "+status+" but operation succeeded");}
        catch(WorkspaceRegistry.Failure e){ok(e.status==status,"Expected "+status+" but got "+e.status+": "+e.getMessage());}
    }
    private static void invalidProxy(boolean cognito,String origin){
        try { LocalServer.verifiedProxyOrigin(true,cognito,origin); }
        catch(IllegalArgumentException expected){ok(true,"Bad private proxy origin rejected");return;}
        throw new AssertionError("Unsafe private proxy config accepted: "+origin);
    }
    private static void rejectJwt(Runnable work){
        try{work.run();throw new AssertionError("Invalid JWT was accepted");}
        catch(ClerkSessionVerifier.AuthFailure e){ok(e.status==401,"Expected unauthorized JWT");}
    }
    private static String token(Map<String,Object> claims,PrivateKey key,Map<String,Object> header)throws Exception{
        Base64.Encoder b=Base64.getUrlEncoder().withoutPadding();
        String body=b.encodeToString(Json.stringify(header).getBytes(StandardCharsets.UTF_8))+"."+
            b.encodeToString(Json.stringify(claims).getBytes(StandardCharsets.UTF_8));
        Signature signature=Signature.getInstance("SHA256withRSA");
        signature.initSign(key);signature.update(body.getBytes(StandardCharsets.US_ASCII));
        return body+"."+b.encodeToString(signature.sign());
    }
    public static void main(String[] args)throws Exception{
        Path folder=Files.createTempDirectory("postflight-workspaces-check-");
        String alice="user_2RfWKJREkjKbHZy0Wqa5qrHe";
        String bob="user_2RfWKJREkjKbHZy0Wqa5qrHd";
        String carol="user_2RfWKJREkjKbHZy0Wqa5qrHc";
        String eve="user_2RfWKJREkjKbHZy0Wqa5qrHb";
        WorkspaceRegistry.Principal owner=new WorkspaceRegistry.Principal(alice,"alice"),
            viewer=new WorkspaceRegistry.Principal(bob,"bob"),
            admin=new WorkspaceRegistry.Principal(carol,"carol"),
            outsider=new WorkspaceRegistry.Principal(eve,"eve");
        // HTTPS origin trust is configured by the operator, never inferred from Host headers.
        ok(LocalServer.verifiedProxyOrigin(false,false,null).isEmpty(),"Default local bind should be unchanged");
        ok("https://private.example.com".equals(LocalServer.verifiedProxyOrigin(
            true,true,"https://private.example.com")),"Trusted HTTPS origin not accepted");
        invalidProxy(false,"https://private.example.com");
        invalidProxy(true,"http://private.example.com");
        invalidProxy(true,"https://private.example.com:8080");
        invalidProxy(true,"https://private.example.com/unsafe/path");
        invalidProxy(true,"https://user@private.example.com");
        invalidProxy(true,"https://private.example.com/?role=admin");
        invalidProxy(true,null);
        try{
            Path file=folder.resolve("secure.json");
            WorkspaceRegistry store=new WorkspaceRegistry(file);
            Map<String,Object> workspace=store.create(owner,Map.of("name","Tenant A"));
            String ws=(String)workspace.get("id");
            ok("OWNER".equals(workspace.get("yourRole")),"Owner role not assigned");
            ok(store.myWorkspaces(viewer).isEmpty(),"Other tenant can list workspace");
            fails(404,()->store.workspace(viewer,ws));
            fails(404,()->store.members(viewer,ws));
            fails(404,()->store.invite(viewer,ws,Map.of("targetSub",carol,"role","ADMIN")));
            Map<String,Object> invitation=store.invite(owner,ws,Map.of("targetSub",bob,"role","VIEWER"));
            String token=(String)invitation.get("inviteToken"),id=(String)invitation.get("id");
            ok(token.length()>=40,"Invitation token entropy too low");
            ok(!Files.readString(file).contains(token),"Raw invitation token leaked to disk");
            ok(!store.invitations(owner,ws).get(0).containsKey("tokenHash"),"Invitation hash leaked");
            fails(404,()->store.accept(outsider,Map.of("invitationId",id,"inviteToken",token)));
            fails(403,()->store.accept(viewer,Map.of("invitationId",id,"inviteToken","badtoken")));
            Map<String,Object> joined=store.accept(viewer,Map.of("invitationId",id,"inviteToken",token));
            ok("VIEWER".equals(joined.get("yourRole")),"Viewer role not persisted");
            fails(409,()->store.accept(viewer,Map.of("invitationId",id,"inviteToken",token)));
            fails(403,()->store.invite(viewer,ws,Map.of("targetSub",carol,"role","ADMIN")));
            fails(403,()->store.members(viewer,ws));
            Map<String,Object> forAdmin=store.invite(owner,ws,Map.of("targetSub",carol,"role","ADMIN"));
            store.accept(admin,Map.of("invitationId",forAdmin.get("id"),
                "inviteToken",forAdmin.get("inviteToken")));
            ok(store.members(admin,ws).size()==3,"Admin cannot see own workspace roster");
            Map<String,Object> forEve=store.invite(admin,ws,Map.of("targetSub",eve,"role","REVIEWER"));
            store.accept(outsider,Map.of("invitationId",forEve.get("id"),
                "inviteToken",forEve.get("inviteToken")));
            ok(store.myWorkspaces(outsider).size()==1,"Invited reviewer cannot list own tenant");
            fails(403,()->store.invite(outsider,ws,Map.of("targetSub",bob,"role","ADMIN")));
            fails(409,()->store.invite(owner,ws,Map.of("targetSub",bob,"role","VIEWER")));
            fails(400,()->store.invite(owner,ws,Map.of("targetSub",UUID.randomUUID().toString(),"role","OWNER")));
            Map<String,Object> separate=store.create(viewer,Map.of("name","Tenant B"));
            String wsB=(String)separate.get("id");
            fails(404,()->store.workspace(owner,wsB));
            fails(404,()->store.workspace(outsider,wsB));
            ok(store.myWorkspaces(owner).size()==1,"Owner can list unrelated tenant");
            ok(store.myWorkspaces(viewer).size()==2,"Member cannot list joined tenant");
            WorkspaceRegistry reloaded=new WorkspaceRegistry(file);
            ok(reloaded.myWorkspaces(owner).size()==1,"Storage did not persist");
            ok(reloaded.members(owner,ws).size()==4,"Membership did not persist");
            ok(reloaded.workspaceAudit(owner,ws).size()>=6,"Audit events missing");

            // Verify Clerk-like session JWT signatures and claims without a real account.
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);KeyPair pair=generator.generateKeyPair();
            ClerkSessionVerifier verifier=new ClerkSessionVerifier(
                "https://example.clerk.accounts.dev","http://127.0.0.1:8080");
            long now=Instant.now().getEpochSecond();
            Map<String,Object> claims=new LinkedHashMap<>(Map.of(
                "iss",verifier.issuer(),"azp",verifier.allowedOrigin(),
                "sub","user_2RfWKJREkjKbHZy0Wqa5qrHe", "sid","sess_2Ro7e2IxrffdqBboq8KfB6eGb",
                "exp",now+300,"iat",now-10,"nbf",now-10));
            Map<String,Object> header=Map.of("alg","RS256","kid","test-key","typ","JWT");
            String good=token(claims,pair.getPrivate(),header);
            ok(verifier.verifyWithPublicKeyForTest(good,(RSAPublicKey)pair.getPublic()).sub().equals(claims.get("sub")),
                "Valid Clerk-like RS256 token rejected");
            String[] jwt=good.split("\\.");
            Map<String,Object> altered=new LinkedHashMap<>(claims);altered.put("sub","user_Evil00000000000000000000");
            String fake=jwt[0]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(
                Json.stringify(altered).getBytes(StandardCharsets.UTF_8))+"."+jwt[2];
            rejectJwt(()->verifier.verifyWithPublicKeyForTest(fake,(RSAPublicKey)pair.getPublic()));
            for(String field:List.of("iss","azp","sub","sid","sts","exp")){
                Map<String,Object> bad=new LinkedHashMap<>(claims);
                switch(field){
                    case "iss" -> bad.put("iss","https://attacker.invalid");
                    case "azp" -> bad.put("azp","https://attacker.invalid");
                    case "sub" -> bad.put("sub","not_a_clerk_user");
                    case "sid" -> bad.put("sid","not_a_session");
                    case "sts" -> bad.put("sts","pending");
                    case "exp" -> bad.put("exp",now-120);
                }
                final String invalid=token(bad,pair.getPrivate(),header);
                rejectJwt(()->verifier.verifyWithPublicKeyForTest(invalid,(RSAPublicKey)pair.getPublic()));
            }
            Map<String,Object> unsigned=Map.of("alg","none","kid","test-key");
            String none=token(claims,pair.getPrivate(),unsigned);
            rejectJwt(()->verifier.verifyWithPublicKeyForTest(none,(RSAPublicKey)pair.getPublic()));
            System.out.println("PASS - "+passed+" tenant/RBAC/invitation/RS256 security assertions");
            System.out.println("PASS - isolated storage, cryptographic invitations, cross-tenant access denied");
        }finally{
            try(var stream=Files.walk(folder)){stream.sorted(Comparator.reverseOrder()).forEach(p->{
                try{Files.deleteIfExists(p);}catch(Exception ignored){}
            });}
        }
    }
}
