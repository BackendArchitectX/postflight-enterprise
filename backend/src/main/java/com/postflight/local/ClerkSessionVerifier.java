package com.postflight.local;

import com.postflight.core.Json;
import com.postflight.core.WorkspaceRegistry;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Verifies Clerk session JWTs from an explicitly trusted Clerk instance. */
public final class ClerkSessionVerifier {
    public static final class AuthFailure extends RuntimeException {
        public final int status;
        public AuthFailure(int status, String message) {
            super(message);
            if (status != 401 && status != 503) throw new IllegalArgumentException("Invalid auth status");
            this.status = status;
        }
    }

    private final String issuer;
    private final String allowedOrigin;
    private final String jwksUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private Map<String, RSAPublicKey> cachedKeys = Map.of();
    private long fetchedAt;
    private long lastAttempt;

    public ClerkSessionVerifier(String issuer, String allowedOrigin) {
        this.issuer = validateIssuer(issuer);
        this.allowedOrigin = validateOrigin(allowedOrigin);
        this.jwksUrl = this.issuer + "/.well-known/jwks.json";
    }

    private static String validateIssuer(String value) {
        if (value == null) throw new IllegalArgumentException("Clerk issuer is required");
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid Clerk issuer", e); }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() != -1
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty())
                || !uri.getHost().matches("[A-Za-z0-9.-]{5,253}"))
            throw new IllegalArgumentException("Clerk issuer must be an HTTPS host without a path");
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static String validateOrigin(String value) {
        if (value == null) throw new IllegalArgumentException("App origin required");
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid app origin", e); }
        boolean dev = "http".equals(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
        if (!("https".equals(uri.getScheme()) || dev) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty())
                || uri.getPort() < -1 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Expected HTTPS app origin or HTTP loopback origin");
        return uri.toString();
    }

    public static boolean authRequested() {
        for (String name : System.getenv().keySet())
            if (name.startsWith("POSTFLIGHT_CLERK_") || name.startsWith("POSTFLIGHT_COGNITO_")) return true;
        return false;
    }

    public static boolean configured() {
        String key = System.getenv("POSTFLIGHT_CLERK_PUBLISHABLE_KEY");
        if (key == null || !key.matches("pk_(test|live)_[A-Za-z0-9_-]{10,500}")) return false;
        try {
            new ClerkSessionVerifier(System.getenv("POSTFLIGHT_CLERK_ISSUER"),
                    System.getenv("POSTFLIGHT_APP_ORIGIN"));
            return true;
        } catch (IllegalArgumentException e) { return false; }
    }

    public static ClerkSessionVerifier fromEnvironment() {
        if (!configured()) throw new AuthFailure(503, "Clerk is not completely configured");
        return new ClerkSessionVerifier(System.getenv("POSTFLIGHT_CLERK_ISSUER"),
                System.getenv("POSTFLIGHT_APP_ORIGIN"));
    }

    public String issuer() { return issuer; }
    public String allowedOrigin() { return allowedOrigin; }

    private static String field(Map<String, Object> object, String key) {
        return object.get(key) instanceof String s ? s : "";
    }

    private static long number(Map<String, Object> object, String key) {
        return object.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    private static byte[] base64(String value) {
        try { return Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException e) { throw new AuthFailure(401, "Invalid JWT encoding"); }
    }

    private static Map<String, Object> jsonSegment(String value) {
        byte[] bytes = base64(value);
        if (bytes.length > 8192) throw new AuthFailure(401, "JWT segment too large");
        try { return Json.object(Json.parse(new String(bytes, StandardCharsets.UTF_8))); }
        catch (RuntimeException e) { throw new AuthFailure(401, "Malformed JWT JSON"); }
    }

    private static RSAPublicKey rsaKey(Map<String, Object> jwk) throws Exception {
        if (!"RSA".equals(field(jwk, "kty"))) throw new IllegalArgumentException("Not RSA");
        if (jwk.containsKey("use") && !"sig".equals(field(jwk, "use")))
            throw new IllegalArgumentException("Not a signature key");
        if (jwk.containsKey("alg") && !"RS256".equals(field(jwk, "alg")))
            throw new IllegalArgumentException("Unexpected algorithm");
        BigInteger n = new BigInteger(1, base64(field(jwk, "n")));
        BigInteger e = new BigInteger(1, base64(field(jwk, "e")));
        RSAPublicKey result = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(n, e));
        if (result.getModulus().bitLength() < 2048) throw new IllegalArgumentException("RSA key too short");
        return result;
    }

    private synchronized RSAPublicKey signingKey(String keyId) {
        long now = Instant.now().getEpochSecond();
        if (now - fetchedAt < 600 && cachedKeys.containsKey(keyId)) return cachedKeys.get(keyId);
        if (now - lastAttempt < 5) {
            if (cachedKeys.containsKey(keyId)) return cachedKeys.get(keyId);
            throw new AuthFailure(503, "Clerk key refresh temporarily unavailable");
        }
        lastAttempt = now;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUrl))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > 65536)
                throw new IllegalArgumentException("JWKS unavailable or too large");
            Map<String, Object> payload = Json.object(Json.parse(
                    new String(response.body(), StandardCharsets.UTF_8)));
            Map<String, RSAPublicKey> fresh = new HashMap<>();
            for (Object entry : Json.array(payload.get("keys"))) {
                try {
                    Map<String, Object> jwk = Json.object(entry);
                    String id = field(jwk, "kid");
                    if (!id.isBlank() && id.length() <= 150 && fresh.size() < 16)
                        fresh.put(id, rsaKey(jwk));
                } catch (Exception ignored) {
                    // Unsupported or malformed keys are never accepted.
                }
            }
            if (fresh.isEmpty()) throw new IllegalArgumentException("No usable signing keys");
            cachedKeys = Map.copyOf(fresh);
            fetchedAt = now;
        } catch (Exception e) {
            if (cachedKeys.containsKey(keyId)) return cachedKeys.get(keyId);
            throw new AuthFailure(503, "Clerk signing keys unavailable");
        }
        RSAPublicKey result = cachedKeys.get(keyId);
        if (result == null) throw new AuthFailure(401, "Unknown Clerk signing key");
        return result;
    }

    private WorkspaceRegistry.Principal verified(String token, RSAPublicKey key) {
        if (token == null || token.length() > 12000) throw new AuthFailure(401, "Invalid token size");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank())
            throw new AuthFailure(401, "Malformed Clerk JWT");
        Map<String, Object> header = jsonSegment(parts[0]);
        if (!"RS256".equals(field(header, "alg")) || header.containsKey("crit")
                || field(header, "kid").isBlank())
            throw new AuthFailure(401, "Unsupported JWT header");
        Map<String, Object> claims = jsonSegment(parts[1]);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(base64(parts[2]))) throw new AuthFailure(401, "Bad JWT signature");
        } catch (AuthFailure e) { throw e; }
          catch (Exception e) { throw new AuthFailure(401, "Bad JWT signature"); }

        long now = Instant.now().getEpochSecond();
        long iat = number(claims, "iat"), exp = number(claims, "exp"), nbf = number(claims, "nbf");
        if (!issuer.equals(field(claims, "iss"))) throw new AuthFailure(401, "Wrong Clerk issuer");
        if (iat <= 0 || iat > now + 60 || exp <= now - 60 || exp <= iat
                || (claims.containsKey("nbf") && nbf > now + 60))
            throw new AuthFailure(401, "Invalid or expired Clerk session");
        // Clerk allows 'azp' to be absent; reject any present but unexpected origin.
        if (claims.containsKey("azp") && !allowedOrigin.equals(field(claims, "azp")))
            throw new AuthFailure(401, "Unauthorized JWT origin");
        if ("pending".equals(field(claims, "sts")))
            throw new AuthFailure(401, "Clerk session is pending");
        String userId = field(claims, "sub");
        String sessionId = field(claims, "sid");
        if (!userId.matches("user_[A-Za-z0-9]{8,96}")
                || !sessionId.matches("sess_[A-Za-z0-9]{8,96}"))
            throw new AuthFailure(401, "Valid Clerk user session required");
        // Sub is authoritative; workspace roles are read only from PostFlight's registry.
        return new WorkspaceRegistry.Principal(userId, userId);
    }

    public WorkspaceRegistry.Principal verifyBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 12007)
            throw new AuthFailure(401, "Bearer Clerk session token required");
        String token = authorization.substring(7);
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw new AuthFailure(401, "Malformed bearer token");
        Map<String, Object> header = jsonSegment(parts[0]);
        if (!"RS256".equals(field(header, "alg")) || header.containsKey("crit"))
            throw new AuthFailure(401, "Unsupported signing algorithm");
        String kid = field(header, "kid");
        if (kid.isBlank() || kid.length() > 150) throw new AuthFailure(401, "Missing key identifier");
        return verified(token, signingKey(kid));
    }

    /** Package-private testing seam. Production always verifies against trusted JWKS. */
    WorkspaceRegistry.Principal verifyWithPublicKeyForTest(String token, RSAPublicKey key) {
        return verified(token, key);
    }
}
