package com.postflight.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless policy preview. It does not save submissions or create an approval.
 * All findings are explainable deterministic heuristics, not legal advice.
 */
public final class DemoPreflight {
    private DemoPreflight() {}
    private static final Set<String> PLATFORMS = Set.of(
            "linkedin", "instagram", "x", "facebook", "threads", "tiktok", "youtube");
    private static final Pattern CONTACT = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern URGENCY = Pattern.compile(
            "(?i)\\b(act now|limited time only|don't miss out|instant profits?)\\b");
    private static long minuteStart = System.nanoTime();
    private static int requests = 0;

    private static synchronized void limit() {
        long now = System.nanoTime();
        if (now - minuteStart > 60_000_000_000L) {
            minuteStart = now;
            requests = 0;
        }
        if (++requests > 60) throw new IllegalArgumentException(
                "Demo validation capacity reached. Please retry in a minute.");
    }

    public static Map<String, Object> inspect(Map<String, Object> input) {
        limit();
        String content = string(input.get("content"), "content", 4000);
        String platform = string(input.get("platform"), "platform", 24)
                .toLowerCase(Locale.ROOT);
        String altText = string(input.get("altText"), "altText", 500);
        boolean hasMedia = Boolean.TRUE.equals(input.get("hasMedia"));
        if (!PLATFORMS.contains(platform)) {
            throw new IllegalArgumentException("Select a supported platform.");
        }
        String forRules = platform.equals("x") ? "X"
                : platform.substring(0, 1).toUpperCase(Locale.ROOT) + platform.substring(1);
        List<Map<String, Object>> findings = new ArrayList<>(
                PreflightRules.inspect(content, forRules, altText, hasMedia));
        if (platform.equals("instagram") && content.codePointCount(0, content.length()) > 2200) {
            add(findings, "PLATFORM-INSTAGRAM-LENGTH", "Caption exceeds 2,200 characters",
                    "High", "Instagram", "The caption is longer than the typical feed-caption limit.",
                    "Create a shorter Instagram-specific variant.");
        }
        if (CONTACT.matcher(content).find()) {
            add(findings, "PRIVACY-EMAIL", "Potential personal contact information",
                    "Medium", "All platforms", "An email address appears in public-facing copy. Check consent and purpose.",
                    "Confirm the address is approved for public use or remove it.");
        }
        if (URGENCY.matcher(content).find()) {
            add(findings, "CONTENT-URGENCY", "High-pressure language",
                    "Medium", "All platforms", "This content uses urgency language that may need brand and compliance review.",
                    "Use accurate, time-bounded language and avoid undue pressure.");
        }
        Matcher urlMatcher = URL.matcher(content);
        int links = 0;
        while (urlMatcher.find()) links++;
        if (links > 2) {
            add(findings, "CONTENT-LINKS", "Multiple external links",
                    "Low", "All platforms", "Many links may reduce readability and increase review effort.",
                    "Use one primary approved destination where practical.");
        }
        int hashtags = 0;
        for (String token : content.split("\\s+")) if (token.startsWith("#") && token.length() > 1) hashtags++;
        if (hashtags > 12) {
            add(findings, "CONTENT-HASHTAGS", "Heavy hashtag usage",
                    "Low", "All platforms", "A large hashtag block may make the message harder to read.",
                    "Keep only relevant campaign hashtags.");
        }
        boolean blocked = PreflightRules.blocking(findings);
        String verdict = blocked ? "BLOCKED"
                : findings.isEmpty() ? "READY_FOR_HUMAN_REVIEW" : "REVIEW_RECOMMENDED";
        int critical = 0, high = 0, medium = 0;
        List<Map<String, Object>> recommendations = new ArrayList<>();
        for (Map<String, Object> finding : findings) {
            String severity = String.valueOf(finding.get("severity"));
            if ("Critical".equals(severity)) critical++;
            if ("High".equals(severity)) high++;
            if ("Medium".equals(severity)) medium++;
            recommendations.add(Map.of(
                    "ruleId", finding.get("id"),
                    "action", finding.get("fix"),
                    "severity", severity));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", verdict);
        result.put("checkedAt", Instant.now().toString());
        result.put("platform", platform);
        result.put("policyVersion", PreflightRules.POLICY_VERSION);
        result.put("fingerprint", PostFlightService.sha256(platform + "|" + content
                + "|" + altText + "|" + hasMedia));
        result.put("characterCount", content.codePointCount(0, content.length()));
        result.put("wordCount", content.isBlank() ? 0 : content.trim().split("\\s+").length);
        result.put("score", PreflightRules.score(findings));
        result.put("findings", findings);
        result.put("recommendations", recommendations);
        result.put("summary", Map.of("critical", critical, "high", high,
                "medium", medium, "total", findings.size()));
        result.put("persisted", false);
        result.put("publisherEnabled", false);
        result.put("disclaimer", "Heuristic preview only, not a legal, regulatory or platform certification. "
                + "No content was saved, approved or published.");
        return result;
    }

    private static String string(Object value, String field, int max) {
        if (value == null) return "";
        if (!(value instanceof String text) || text.length() > max) {
            throw new IllegalArgumentException(field + " must be text up to " + max + " characters.");
        }
        return text;
    }

    private static void add(List<Map<String, Object>> findings, String id, String title,
                            String severity, String channel, String detail, String fix) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("id", id);
        issue.put("title", title);
        issue.put("severity", severity);
        issue.put("channel", channel);
        issue.put("detail", detail);
        issue.put("match", "Heuristic signal");
        issue.put("fix", fix);
        findings.add(issue);
    }
}
