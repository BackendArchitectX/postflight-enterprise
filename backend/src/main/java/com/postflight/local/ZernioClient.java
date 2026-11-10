package com.postflight.local;

import com.postflight.core.Json;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, no-persistence Zernio inspection for visitors using their own restricted API keys. */
public final class ZernioClient {
    private static final URI ACCOUNTS = URI.create("https://zernio.com/api/v1/accounts");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static long windowStart = System.nanoTime();
    private static int windowRequests;

    private ZernioClient() {}

    public static final class Failure extends Exception {
        public final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }

    /** Global budget for the free demo, not a replacement for authenticated per-user rate limiting. */
    private static synchronized void checkBudget() throws Failure {
        long now = System.nanoTime();
        if (now - windowStart > Duration.ofMinutes(1).toNanos()) {
            windowStart = now;
            windowRequests = 0;
        }
        if (++windowRequests > 20) throw new Failure(429, "Public demo request limit reached. Try again shortly.");
    }

    public static Map<String, Object> inspectAccounts(String authorization) throws Failure {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new Failure(400, "Provide your own read-only Zernio API key.");
        }
        String key = authorization.substring(7);
        if (key.length() < 16 || key.length() > 512 || key.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw new Failure(400, "Invalid API key format. Create a restricted read-only key in Zernio.");
        }
        checkBudget();
        HttpRequest request = HttpRequest.newBuilder(ACCOUNTS)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + key)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream stream = response.body()) {
                bytes = stream.readNBytes(65537);
            }
            int status = response.statusCode();
            if (status == 401 || status == 403) throw new Failure(401, "Zernio rejected the key. Check its permissions and expiry.");
            if (status == 429) throw new Failure(429, "Zernio rate limit reached. Retry later.");
            if (status != 200) throw new Failure(502, "Unable to fetch Zernio accounts at this time.");
            if (bytes.length > 65536) throw new Failure(502, "Zernio response exceeds the demo limit.");

            Map<String, Object> result = Json.object(Json.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
            List<Object> raw = Json.array(result.get("accounts"));
            List<Map<String, Object>> accounts = new ArrayList<>();
            for (Object entry : raw) {
                if (accounts.size() >= 30) break;
                Map<String, Object> account = Json.object(entry);
                Map<String, Object> safe = new LinkedHashMap<>();
                for (String name : List.of("platform", "username", "displayName", "profileUrl", "isActive")) {
                    Object value = account.get(name);
                    if (value instanceof String text) safe.put(name, text.length() > 300 ? text.substring(0, 300) : text);
                    else if (value instanceof Boolean) safe.put(name, value);
                }
                accounts.add(safe);
            }
            return Map.of("provider", "Zernio", "mode", "read-only", "connectedAccounts", accounts,
                    "shown", accounts.size(), "notStoredByPostFlight", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(503, "Zernio request interrupted.");
        } catch (IOException e) {
            throw new Failure(502, "Zernio network request failed.");
        } catch (IllegalArgumentException e) {
            throw new Failure(502, "Unexpected Zernio response format.");
        }
    }

    private static final URI LIVE_HEALTH = URI.create("https://zernio.com/api/v1/accounts/health");
    private static final URI LIVE_POSTS = URI.create("https://zernio.com/api/v1/posts?limit=10");

    /** Fresh, authorized account-health and recent-post snapshot; never a simulated feed. */
    public static Map<String, Object> inspectLive(String authorization) throws Failure {
        validateAuthorization(authorization);
        checkBudget();
        Map<String, Object> health = getJson(LIVE_HEALTH, authorization);
        Map<String, Object> recent = getJson(LIVE_POSTS, authorization);
        if (!(recent.get("posts") instanceof List<?> posts)) {
            throw new Failure(502, "Zernio returned an unexpected posts format.");
        }
        Map<String, Object> healthSummary = safeMap(health.get("summary"));
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String property : List.of("total", "healthy", "warning", "error", "needsReconnect")) {
            Object value = healthSummary.get(property);
            if (value instanceof Number) summary.put(property, value);
        }

        List<Map<String, Object>> accounts = new ArrayList<>();
        Object healthAccounts = health.get("accounts");
        if (healthAccounts instanceof List<?> list) {
            for (Object entry : list) {
                if (accounts.size() >= 20) break;
                Map<String, Object> account = safeMap(entry);
                Map<String, Object> safe = new LinkedHashMap<>();
                for (String property : List.of("platform", "username", "status", "accountId")) {
                    copySafe(safe, account, property, 180);
                }
                for (String property : List.of("canPost", "canFetchAnalytics", "tokenValid", "needsReconnect")) {
                    copySafe(safe, account, property, 180);
                }
                accounts.add(safe);
            }
        }

        List<Map<String, Object>> postsSafe = new ArrayList<>();
        for (Object item : posts) {
            if (postsSafe.size() >= 10) break;
            Map<String, Object> post = safeMap(item);
            Map<String, Object> safe = new LinkedHashMap<>();
            for (String property : List.of("_id", "id", "status", "content", "title",
                    "createdAt", "scheduledFor", "publishedAt", "timezone")) {
                copySafe(safe, post, property, property.equals("content") ? 550 : 160);
            }
            List<Map<String, Object>> targets = new ArrayList<>();
            if (post.get("platforms") instanceof List<?> platforms) {
                for (Object target : platforms) {
                    if (targets.size() >= 8) break;
                    Map<String, Object> rawTarget = safeMap(target);
                    Map<String, Object> safeTarget = new LinkedHashMap<>();
                    for (String property : List.of("platform", "status", "errorMessage")) {
                        copySafe(safeTarget, rawTarget, property, 160);
                    }
                    targets.add(safeTarget);
                }
            }
            safe.put("platforms", targets);
            postsSafe.add(safe);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", "Zernio API — actual user-authorized response");
        response.put("checkedAt", java.time.Instant.now().toString());
        response.put("mode", "READ_ONLY_LIVE_SNAPSHOT");
        response.put("accountHealth", summary);
        response.put("accounts", accounts);
        response.put("posts", postsSafe);
        response.put("notPersistedByPostFlight", true);
        response.put("publishingEnabled", false);
        return response;
    }

    private static void validateAuthorization(String authorization) throws Failure {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new Failure(400, "Use your own restricted read-only Zernio API key.");
        }
        String key = authorization.substring(7);
        if (key.length() < 16 || key.length() > 512 || key.chars().anyMatch(ch -> ch <= 32 || ch >= 127)) {
            throw new Failure(400, "Invalid Zernio API key format.");
        }
    }

    private static Map<String, Object> getJson(URI endpoint, String authorization) throws Failure {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("Authorization", authorization)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream stream = response.body()) {
                bytes = stream.readNBytes(131073);
            }
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new Failure(403, "Zernio denied access. Check read-only key permissions and profile scope.");
            }
            if (status == 429) throw new Failure(429, "Zernio rate limit reached. Try later.");
            if (status != 200) throw new Failure(502, "Zernio live data request failed (upstream status " + status + ").");
            if (bytes.length > 131072) throw new Failure(502, "Zernio live response too large for public demo.");
            return Json.object(Json.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(503, "Zernio live request was interrupted.");
        } catch (IOException e) {
            throw new Failure(502, "Unable to contact Zernio.");
        } catch (IllegalArgumentException e) {
            throw new Failure(502, "Zernio returned invalid response data.");
        }
    }

    private static Map<String, Object> safeMap(Object value) {
        return value instanceof Map<?, ?> ? Json.object(value) : Map.of();
    }

    private static void copySafe(Map<String, Object> destination, Map<String, Object> source,
                                 String field, int maxLength) {
        Object value = source.get(field);
        if (value instanceof String text) {
            destination.put(field, text.length() > maxLength ? text.substring(0, maxLength) : text);
        } else if (value instanceof Number || value instanceof Boolean) {
            destination.put(field, value);
        }
    }

}
