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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/**
 * Verify Clerk browser session JWTs at the trusted Frontend API JWKS endpoint.
 * Never trust identity/roles supplied by request bodies, unsigned tokens or cookies.
 * The publishable key is PUBLIC; a Clerk secret key is not required or read.
 */
public final class ClerkSessionVerifier {
    public static final class AuthFailure extends RuntimeException {
        public final int status;
        public AuthFailure(int status,String message){
            super(message);if(status!=401&&status!=503)throw new IllegalArgumentException();this.status=status;
        }
    }
    private static final Pattern KEY=Pattern.compile("^pk_(test|live)_([A-Za-z0-9+/=]+)$");
    private static final Pattern USER=Pattern.compile("^user_[A-Za-z0-9]{8,128}$");
    private static final Pattern SESSION=Pattern.compile("^sess_[A-Za-z0-9]{8,128}$");
    private final String issuer,jwksUrl;
    private final Set<String> allowedOrigins;
    private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(3)).build();
    private Map<String,RSAPublicKey> cache=Map.of();
    private long fetchedAt=0,lastAttempt=0;

    /** Extracts the trusted HTTPS Frontend API host encoded by Clerk in its publishable key. */
    public static String frontendOrigin(String publishableKey){
        Matcher matcher=KEY.matcher(publishableKey==null?"":publishableKey);
        if(!matcher.matches())throw new IllegalArgumentException("Invalid Clerk publishable key");
        try {
            String decoded=new String(Base64.getDecoder().decode(matcher.group(2)),StandardCharsets.UTF_8);
            if(!decoded.endsWith("$"))throw new IllegalArgumentException("Invalid Clerk key suffix");
            String hostname=decoded.substring(0,decoded.length()-1);
            if(hostname.length()>253||hostname.length()<5||hostname.contains("..")||!hostname.contains(".")
                ||!hostname.matches("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+"))
                throw new IllegalArgumentException("Invalid Clerk Frontend API hostname");
            for(String label:hostname.split("\\."))
                if(label.startsWith("-")||label.endsWith("-")||label.length()>63)
                    throw new IllegalArgumentException("Invalid Clerk hostname label");
            URI uri=URI.create("https://"+hostname);
            if(uri.getHost()==null||!uri.getHost().equalsIgnoreCase(hostname))
                throw new IllegalArgumentException("Invalid Clerk Frontend API origin");
            return "https://"+hostname.toLowerCase(Locale.ROOT);
        }catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid Clerk publishable key domain",e);}
    }
    public static boolean configured(){
        try {frontendOrigin(System.getenv("POSTFLIGHT_CLERK_PUBLISHABLE_KEY"));return true;}
        catch(IllegalArgumentException e){return false;}
    }
    public static ClerkSessionVerifier fromEnvironment(Set<String> allowedOrigins){
        if(!configured())throw new AuthFailure(503,"Clerk is not configured");
        return new ClerkSessionVerifier(System.getenv("POSTFLIGHT_CLERK_PUBLISHABLE_KEY"),allowedOrigins);
    }
    public ClerkSessionVerifier(String publishableKey,Set<String> allowedOrigins){
        issuer=frontendOrigin(publishableKey);
        jwksUrl=issuer+"/.well-known/jwks.json";
        if(allowedOrigins==null||allowedOrigins.isEmpty()||allowedOrigins.stream().anyMatch(
            v->v==null||!v.matches("https?://[A-Za-z0-9.:-]+")))
            throw new IllegalArgumentException("Explicit authorized browser origins required");
        this.allowedOrigins=Set.copyOf(allowedOrigins);
    }
    public String issuer(){return issuer;}
    private static String attr(Map<String,Object> m,String k){Object v=m.get(k);return v instanceof String s?s:"";}
    private static long num(Map<String,Object> m,String k){Object v=m.get(k);return v instanceof Number n?n.longValue():0;}
    private static byte[] b64(String s){
        try{return Base64.getUrlDecoder().decode(s);}
        catch(IllegalArgumentException e){throw new AuthFailure(401,"Invalid JWT encoding");}
    }
    private static Map<String,Object> decode(String s){
        byte[] raw=b64(s);if(raw.length>8192)throw new AuthFailure(401,"JWT section too large");
        try{return Json.object(Json.parse(new String(raw,StandardCharsets.UTF_8)));}
        catch(RuntimeException e){throw new AuthFailure(401,"Invalid JWT JSON");}
    }
    private static RSAPublicKey jwkKey(Map<String,Object> jwk)throws Exception{
        if(!"RSA".equals(attr(jwk,"kty"))||jwk.containsKey("use")&&!"sig".equals(attr(jwk,"use"))
            ||jwk.containsKey("alg")&&!"RS256".equals(attr(jwk,"alg")))
            throw new IllegalArgumentException("Unsupported signing key");
        BigInteger n=new BigInteger(1,b64(attr(jwk,"n")));
        BigInteger e=new BigInteger(1,b64(attr(jwk,"e")));
        RSAPublicKey key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n,e));
        if(key.getModulus().bitLength()<2048)throw new IllegalArgumentException("RSA key too small");
        return key;
    }
    private synchronized RSAPublicKey signingKey(String kid){
        long now=Instant.now().getEpochSecond();
        if(now-fetchedAt<600&&cache.containsKey(kid))return cache.get(kid);
        if(now-lastAttempt<5){
            if(cache.containsKey(kid))return cache.get(kid);
            throw new AuthFailure(503,"Clerk signing key refresh unavailable");
        }
        lastAttempt=now;
        try {
            HttpRequest request=HttpRequest.newBuilder(URI.create(jwksUrl)).timeout(Duration.ofSeconds(5))
                .header("Accept","application/json").GET().build();
            HttpResponse<byte[]> response=http.send(request,HttpResponse.BodyHandlers.ofByteArray());
            if(response.statusCode()!=200||response.body().length>65536)
                throw new IllegalArgumentException("Clerk JWKS unavailable");
            Map<String,Object> body=Json.object(Json.parse(new String(response.body(),StandardCharsets.UTF_8)));
            Map<String,RSAPublicKey> fresh=new HashMap<>();
            for(Object item:Json.array(body.get("keys"))){
                try {
                    Map<String,Object> jwk=Json.object(item);
                    String id=attr(jwk,"kid");
                    if(!id.isBlank()&&id.length()<=150&&fresh.size()<16)fresh.put(id,jwkKey(jwk));
                }catch(Exception ignored){/* Invalid keys cannot authorize callers. */}
            }
            if(fresh.isEmpty())throw new IllegalArgumentException("No supported signing keys");
            cache=Map.copyOf(fresh);fetchedAt=now;
        }catch(Exception e){
            if(cache.containsKey(kid))return cache.get(kid);
            throw new AuthFailure(503,"Clerk signing keys unavailable");
        }
        RSAPublicKey key=cache.get(kid);
        if(key==null)throw new AuthFailure(401,"Unknown Clerk key ID");
        return key;
    }
    private WorkspaceRegistry.Principal verified(String token,RSAPublicKey key){
        if(token==null||token.length()>12000)throw new AuthFailure(401,"Invalid session token");
        String[] parts=token.split("\\.",-1);
        if(parts.length!=3||Arrays.stream(parts).anyMatch(String::isBlank))
            throw new AuthFailure(401,"Malformed Clerk JWT");
        Map<String,Object> header=decode(parts[0]),claims=decode(parts[1]);
        if(!"RS256".equals(attr(header,"alg"))||header.containsKey("crit"))
            throw new AuthFailure(401,"Unsupported JWT algorithm");
        String kid=attr(header,"kid");
        if(kid.isBlank()||kid.length()>150)throw new AuthFailure(401,"Invalid Clerk key ID");
        try {
            Signature verify=Signature.getInstance("SHA256withRSA");verify.initVerify(key);
            verify.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
            if(!verify.verify(b64(parts[2])))throw new AuthFailure(401,"Invalid JWT signature");
        }catch(AuthFailure e){throw e;}
         catch(Exception e){throw new AuthFailure(401,"Invalid JWT signature");}
        long now=Instant.now().getEpochSecond(),issued=num(claims,"iat"),
            nbf=num(claims,"nbf"),expires=num(claims,"exp");
        if(!issuer.equals(attr(claims,"iss"))||expires<=now||issued<=0||issued>now+30
            ||nbf<=0||nbf>now+30||expires<=issued||expires-issued>3600)
            throw new AuthFailure(401,"Invalid Clerk issuer or token lifetime");
        // Fail closed on missing azp even though some Clerk clients may omit it.
        if(!allowedOrigins.contains(attr(claims,"azp")))
            throw new AuthFailure(401,"Clerk token not issued for this browser origin");
        String sub=attr(claims,"sub"),sid=attr(claims,"sid");
        if(!USER.matcher(sub).matches()||!SESSION.matcher(sid).matches()||claims.containsKey("act")
            ||"pending".equals(attr(claims,"sts")))
            throw new AuthFailure(401,"Active Clerk user session required");
        return new WorkspaceRegistry.Principal(sub,sub);
    }
    public WorkspaceRegistry.Principal verifyBearer(String authHeader){
        if(authHeader==null||!authHeader.startsWith("Bearer ")||authHeader.length()>12008)
            throw new AuthFailure(401,"Clerk bearer session token required");
        String token=authHeader.substring(7),parts[]=token.split("\\.",-1);
        if(parts.length!=3)throw new AuthFailure(401,"Malformed Clerk JWT");
        Map<String,Object> header=decode(parts[0]);
        if(!"RS256".equals(attr(header,"alg"))||header.containsKey("crit"))
            throw new AuthFailure(401,"Unsupported JWT algorithm");
        String kid=attr(header,"kid");
        if(kid.isBlank()||kid.length()>150)throw new AuthFailure(401,"Invalid Clerk key ID");
        return verified(token,signingKey(kid));
    }
    /** Test only: production always retrieves signing keys from trusted HTTPS Clerk JWKS. */
    WorkspaceRegistry.Principal verifyWithPublicKeyForTest(String token,RSAPublicKey key){
        return verified(token,key);
    }
}
