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
    private static void rejectJwt(Runnable work){
        try{work.run();throw new AssertionError("Invalid JWT was accepted");}
        catch(CognitoAccessVerifier.AuthFailure e){ok(e.status==401,"Expected unauthorized JWT");}
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
        String alice="a8de7ff2-3b87-4e47-a319-dd8ce1168e90";
        String bob="b7cc17dc-1d42-4ea4-bb45-cc28ed44be2e";
        String carol="52b44098-4ab5-4969-ae7b-1766e8730fe2";
        String eve="ea3df2ea-af41-4862-a050-450db123143d";
        WorkspaceRegistry.Principal owner=new WorkspaceRegistry.Principal(alice,"alice"),
            viewer=new WorkspaceRegistry.Principal(bob,"bob"),
            admin=new WorkspaceRegistry.Principal(carol,"carol"),
            outsider=new WorkspaceRegistry.Principal(eve,"eve");
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

            // Verify RSA signature and Cognito identity claims without a real user-pool account.
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);KeyPair pair=generator.generateKeyPair();
            CognitoAccessVerifier verifier=new CognitoAccessVerifier("us-east-1_Test123","appClient123");
            long now=Instant.now().getEpochSecond();
            Map<String,Object> claims=new LinkedHashMap<>(Map.of(
                "iss",verifier.issuer(),"client_id",verifier.clientId(),"token_use","access",
                "sub",alice,"username","alice","exp",now+3600,"iat",now-10));
            Map<String,Object> header=Map.of("alg","RS256","kid","test-key","typ","JWT");
            String good=token(claims,pair.getPrivate(),header);
            ok(verifier.verifyWithPublicKeyForTest(good,(RSAPublicKey)pair.getPublic()).sub().equals(alice),
                "Valid Cognito-like RS256 token rejected");
            String[] jwt=good.split("\\.");
            Map<String,Object> altered=new LinkedHashMap<>(claims);altered.put("username","admin");
            String fake=jwt[0]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(
                Json.stringify(altered).getBytes(StandardCharsets.UTF_8))+"."+jwt[2];
            rejectJwt(()->verifier.verifyWithPublicKeyForTest(fake,(RSAPublicKey)pair.getPublic()));
            Map<String,Object> expired=new LinkedHashMap<>(claims);expired.put("exp",now-120);
            rejectJwt(()->{try{verifier.verifyWithPublicKeyForTest(token(expired,pair.getPrivate(),header),(RSAPublicKey)pair.getPublic());}catch(CognitoAccessVerifier.AuthFailure e){throw e;}catch(Exception e){throw new AssertionError(e);}});
            Map<String,Object> idClaims=new LinkedHashMap<>(claims);idClaims.put("token_use","id");
            rejectJwt(()->{try{verifier.verifyWithPublicKeyForTest(token(idClaims,pair.getPrivate(),header),(RSAPublicKey)pair.getPublic());}catch(CognitoAccessVerifier.AuthFailure e){throw e;}catch(Exception e){throw new AssertionError(e);}});
            Map<String,Object> otherClient=new LinkedHashMap<>(claims);otherClient.put("client_id","foreignClient");
            rejectJwt(()->{try{verifier.verifyWithPublicKeyForTest(token(otherClient,pair.getPrivate(),header),(RSAPublicKey)pair.getPublic());}catch(CognitoAccessVerifier.AuthFailure e){throw e;}catch(Exception e){throw new AssertionError(e);}});
            Map<String,Object> wrongIssuer=new LinkedHashMap<>(claims);wrongIssuer.put("iss","https://attacker.invalid");
            rejectJwt(()->{try{verifier.verifyWithPublicKeyForTest(token(wrongIssuer,pair.getPrivate(),header),(RSAPublicKey)pair.getPublic());}catch(CognitoAccessVerifier.AuthFailure e){throw e;}catch(Exception e){throw new AssertionError(e);}});
            Map<String,Object> unsignedHeader=Map.of("alg","none","kid","test-key");
            rejectJwt(()->{try{verifier.verifyWithPublicKeyForTest(token(claims,pair.getPrivate(),unsignedHeader),(RSAPublicKey)pair.getPublic());}catch(CognitoAccessVerifier.AuthFailure e){throw e;}catch(Exception e){throw new AssertionError(e);}});
            Map<String,Object> m2m=new LinkedHashMap<>(claims);m2m.remove("username");m2m.put("sub",verifier.clientId());
            rejectJwt(()->{try{verifier.verifyWithPublicKeyForTest(token(m2m,pair.getPrivate(),header),(RSAPublicKey)pair.getPublic());}catch(CognitoAccessVerifier.AuthFailure e){throw e;}catch(Exception e){throw new AssertionError(e);}});
            System.out.println("PASS - "+passed+" tenant/RBAC/invitation/RS256 security assertions");
            System.out.println("PASS - isolated storage, cryptographic invitations, cross-tenant access denied");
        }finally{
            try(var stream=Files.walk(folder)){stream.sorted(Comparator.reverseOrder()).forEach(p->{
                try{Files.deleteIfExists(p);}catch(Exception ignored){}
            });}
        }
    }
}
