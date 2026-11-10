package com.postflight.local;

import com.postflight.core.Json;
import com.postflight.core.WorkspaceRegistry;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/**
 * Cognito user-pool access JWT verifier. NEVER accepts an unsigned token, ID token,
 * client credentials (M2M), caller-provided JWK URL, or user-supplied role claim.
 * The only remote JWKS URL is generated from the trusted server configuration.
 */
public final class CognitoAccessVerifier {
    public static final class AuthFailure extends RuntimeException {
        public final int status;
        public AuthFailure(int status,String msg){super(msg);statusCheck(status);this.status=status;}
        private static void statusCheck(int code){if(code!=401&&code!=503)throw new IllegalArgumentException();}
    }
    private static final Pattern POOL=Pattern.compile("^([a-z]{2}(?:-gov)?-[a-z]+-\\d)_[a-zA-Z0-9]+$");
    private static final Pattern CLIENT=Pattern.compile("^[a-zA-Z0-9]{6,128}$");
    private final String issuer,clientId,jwksUrl;
    private final HttpClient http=HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(java.time.Duration.ofSeconds(3)).build();
    private Map<String,RSAPublicKey> cache=Map.of();
    private long fetchedAt=0;
    private long lastAttempt=0;

    public CognitoAccessVerifier(String poolId,String clientId) {
        if(poolId==null||!POOL.matcher(poolId).matches()||clientId==null||!CLIENT.matcher(clientId).matches())
            throw new IllegalArgumentException("Cognito user pool and client ID must be configured");
        this.clientId=clientId;
        String region=poolId.substring(0,poolId.indexOf('_'));
        this.issuer="https://cognito-idp."+region+".amazonaws.com/"+poolId;
        this.jwksUrl=issuer+"/.well-known/jwks.json";
    }
    public static boolean configured(){
        String pool=System.getenv("POSTFLIGHT_COGNITO_USER_POOL_ID");
        String client=System.getenv("POSTFLIGHT_COGNITO_CLIENT_ID");
        return pool!=null&&POOL.matcher(pool).matches()&&client!=null&&CLIENT.matcher(client).matches();
    }
    public static CognitoAccessVerifier fromEnvironment(){
        if(!configured())throw new AuthFailure(503,"Cognito is not configured");
        return new CognitoAccessVerifier(System.getenv("POSTFLIGHT_COGNITO_USER_POOL_ID"),
            System.getenv("POSTFLIGHT_COGNITO_CLIENT_ID"));
    }
    public String clientId(){return clientId;}
    public String issuer(){return issuer;}
    private static String attr(Map<String,Object> m,String key) {
        Object o=m.get(key);return o instanceof String s?s:"";
    }
    private static long num(Map<String,Object> m,String key){
        Object o=m.get(key);return o instanceof Number n?n.longValue():0L;
    }
    private static byte[] b64(String v){
        try {return Base64.getUrlDecoder().decode(v);}
        catch(IllegalArgumentException e){throw new AuthFailure(401,"Invalid access token encoding");}
    }
    private static Map<String,Object> decode(String data){
        byte[] raw=b64(data);
        if(raw.length>8192)throw new AuthFailure(401,"Token payload too large");
        try {return Json.object(Json.parse(new String(raw,StandardCharsets.UTF_8)));}
        catch(RuntimeException e){throw new AuthFailure(401,"Invalid access token claims");}
    }
    private static RSAPublicKey key(Map<String,Object> jwk) throws Exception {
        if(!"RSA".equals(attr(jwk,"kty")))throw new IllegalArgumentException("Not RSA");
        if(jwk.containsKey("use")&&!"sig".equals(attr(jwk,"use")))throw new IllegalArgumentException("Not signing key");
        if(jwk.containsKey("alg")&&!"RS256".equals(attr(jwk,"alg")))throw new IllegalArgumentException("Unsupported algorithm");
        BigInteger n=new BigInteger(1,b64(attr(jwk,"n")));
        BigInteger e=new BigInteger(1,b64(attr(jwk,"e")));
        RSAPublicKey pub=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n,e));
        if(pub.getModulus().bitLength()<2048)throw new IllegalArgumentException("RSA key too small");
        return pub;
    }
    @SuppressWarnings("unchecked")
    private synchronized RSAPublicKey signingKey(String kid){
        long now=Instant.now().getEpochSecond();
        if(now-fetchedAt<600&&cache.containsKey(kid))return cache.get(kid);
        if(now-lastAttempt<5){
            if(cache.containsKey(kid))return cache.get(kid);
            throw new AuthFailure(503,"Cognito signing key refresh unavailable");
        }
        lastAttempt=now;
        try{
            HttpRequest request=HttpRequest.newBuilder(URI.create(jwksUrl)).timeout(java.time.Duration.ofSeconds(5))
                .GET().header("Accept","application/json").build();
            HttpResponse<byte[]> response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()!=200||response.body().length>65536)
                throw new IllegalArgumentException("JWKS unavailable or oversized");
            Map<String,Object> body=Json.object(Json.parse(new String(response.body(),StandardCharsets.UTF_8)));
            Map<String,RSAPublicKey> fresh=new HashMap<>();
            for(Object item:Json.array(body.get("keys"))){
                Map<String,Object> jwk=Json.object(item);
                try{
                    String id=attr(jwk,"kid");
                    if(id.length()>0&&id.length()<=150&&fresh.size()<16)fresh.put(id,key(jwk));
                }catch(Exception ignored){/* Skip unsupported key types */ }
            }
            if(fresh.isEmpty())throw new IllegalArgumentException("No valid RSA keys");
            cache=Map.copyOf(fresh);fetchedAt=now;
        }catch(Exception e){
            if(cache.containsKey(kid))return cache.get(kid);
            throw new AuthFailure(503,"Cognito public signing keys unavailable");
        }
        RSAPublicKey result=cache.get(kid);
        if(result==null)throw new AuthFailure(401,"Unknown Cognito signing key");
        return result;
    }
    private WorkspaceRegistry.Principal verified(String token,RSAPublicKey pub){
        if(token==null||token.length()>12000)throw new AuthFailure(401,"Invalid bearer token");
        String[] parts=token.split("\\.",-1);
        if(parts.length!=3||parts[0].isBlank()||parts[1].isBlank()||parts[2].isBlank())
            throw new AuthFailure(401,"Malformed JWT");
        Map<String,Object> header=decode(parts[0]);
        if(!"RS256".equals(attr(header,"alg"))||header.containsKey("crit"))
            throw new AuthFailure(401,"Unsupported token algorithm");
        String kid=attr(header,"kid");
        if(kid.isBlank()||kid.length()>150)throw new AuthFailure(401,"Missing Cognito key ID");
        Map<String,Object> claims=decode(parts[1]);
        try{
            Signature s=Signature.getInstance("SHA256withRSA");s.initVerify(pub);
            s.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
            if(!s.verify(b64(parts[2])))throw new AuthFailure(401,"Invalid token signature");
        }catch(AuthFailure e){throw e;}
         catch(Exception e){throw new AuthFailure(401,"Invalid token signature");}
        long now=Instant.now().getEpochSecond(),iat=num(claims,"iat"),exp=num(claims,"exp"),nbf=num(claims,"nbf");
        if(!issuer.equals(attr(claims,"iss"))||!clientId.equals(attr(claims,"client_id"))
            ||!"access".equals(attr(claims,"token_use")))
            throw new AuthFailure(401,"Incorrect access token issuer, client or usage");
        if(exp<=now-60||iat<=0||iat>now+60||exp<=iat||exp-iat>86500
            ||(claims.containsKey("nbf")&&nbf>now+60))
            throw new AuthFailure(401,"Access token expired or invalid time claims");
        String sub=attr(claims,"sub"),username=attr(claims,"username");
        if(!sub.matches("[0-9a-fA-F-]{36}")||username.isBlank()||sub.equals(clientId))
            throw new AuthFailure(401,"Cognito user identity required");
        return new WorkspaceRegistry.Principal(sub,username);
    }
    /** Public verifier entrypoint; no subject or role is taken from request parameters. */
    public WorkspaceRegistry.Principal verifyBearer(String authHeader){
        if(authHeader==null||!authHeader.startsWith("Bearer ")||authHeader.length()>12008)
            throw new AuthFailure(401,"Bearer access token required");
        String token=authHeader.substring(7);
        String[] parts=token.split("\\.",-1);
        if(parts.length!=3)throw new AuthFailure(401,"Malformed access token");
        Map<String,Object> h=decode(parts[0]);
        if(!"RS256".equals(attr(h,"alg"))||h.containsKey("crit"))
            throw new AuthFailure(401,"Unsupported token algorithm");
        String kid=attr(h,"kid");
        if(kid.isBlank()||kid.length()>150)throw new AuthFailure(401,"Invalid Cognito key ID");
        return verified(token,signingKey(kid));
    }
    /** Isolated cryptographic test seam; production always obtains keys from trusted Cognito JWKS. */
    WorkspaceRegistry.Principal verifyWithPublicKeyForTest(String token,RSAPublicKey publicKey){
        return verified(token,publicKey);
    }
}
