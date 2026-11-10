package com.postflight.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Local-only persistent workspace drafts. The gateway authenticates Clerk JWTs and
 * checks workspace membership/roles BEFORE calling this store. Never expose it
 * as a public or unscoped API. Not a multi-node production database.
 */
public final class WorkspaceDraftStore {
    private final Path directory;

    public WorkspaceDraftStore(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
    }

    private static WorkspaceRegistry.Failure bad(int code, String message) {
        return new WorkspaceRegistry.Failure(code, message);
    }

    private Path file(String workspaceId) {
        if (workspaceId == null || !workspaceId.matches("ws_[0-9a-f]{32}"))
            throw bad(404, "Workspace not found");
        Path path = directory.resolve(workspaceId + ".json").normalize();
        if (!path.getParent().equals(directory)) throw bad(404, "Workspace not found");
        return path;
    }

    private static Map<String,Object> obj(Object... pairs) {
        Map<String,Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String)pairs[i], pairs[i + 1]);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> records(Map<String,Object> state) {
        return (List<Map<String,Object>>)(List<?>)Json.array(state.get("drafts"));
    }

    private Map<String,Object> load(String ws) {
        Path path = file(ws);
        if (!Files.isRegularFile(path))
            return obj("schemaVersion", 1, "workspaceId", ws,
                "drafts", new ArrayList<>(), "audit", new ArrayList<>());
        try {
            Map<String,Object> state = Json.object(Json.parse(Files.readString(path, StandardCharsets.UTF_8)));
            if (!ws.equals(state.get("workspaceId")))
                throw bad(500, "Workspace draft storage mismatch");
            Json.array(state.get("drafts"));
            return state;
        } catch (IOException | IllegalArgumentException e) {
            throw bad(500, "Unable to load workspace drafts");
        }
    }

    private void persist(String ws, Map<String,Object> state) {
        Path destination = file(ws);
        Path temp = destination.resolveSibling(destination.getFileName() + ".tmp");
        try {
            Files.writeString(temp, Json.stringify(state), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw bad(500, "Unable to save workspace drafts");
        }
    }

    private static Map<String,Object> copy(Map<String,Object> input) {
        return Json.object(Json.parse(Json.stringify(input)));
    }

    private static String text(Object input, String key, int max, boolean required) {
        if (!(input instanceof String s)) throw bad(400, key + " must be text");
        String value = s.trim();
        if ((required && value.isEmpty()) || value.length() > max)
            throw bad(400, key + " must be " + (required ? "1–" : "0–") + max + " characters");
        return value;
    }

    private static String title(Map<String,Object> body) {
        return text(body.get("title"), "title", 160, true);
    }

    private static String content(Map<String,Object> body) {
        return text(body.getOrDefault("body", ""), "body", 20000, false);
    }

    private static Map<String,Object> find(Map<String,Object> state, String id) {
        if (!id.matches("dft_[0-9a-f]{32}")) throw bad(404, "Draft not found");
        for (Map<String,Object> draft : records(state))
            if (id.equals(draft.get("id"))) return draft;
        throw bad(404, "Draft not found");
    }

    @SuppressWarnings("unchecked")
    private static void audit(Map<String,Object> state, String action, String draftId,
                              String actor, int version) {
        List<Object> history = (List<Object>)state.get("audit");
        history.add(obj("at", Instant.now().toString(), "action", action,
            "draftId", draftId, "actorSub", actor, "version", version));
    }

    public synchronized List<Map<String,Object>> list(String ws) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String,Object> record : records(load(ws))) result.add(copy(record));
        return result;
    }

    public synchronized Map<String,Object> get(String ws, String draftId) {
        return copy(find(load(ws), draftId));
    }

    public synchronized Map<String,Object> create(String ws, String actor,
                                                    Map<String,Object> payload) {
        Map<String,Object> state = load(ws);
        String now = Instant.now().toString();
        String id = "dft_" + UUID.randomUUID().toString().replace("-", "");
        Map<String,Object> draft = obj("id", id, "workspaceId", ws,
            "title", title(payload), "body", content(payload),
            "status", "DRAFT", "version", 1,
            "createdBySub", actor, "updatedBySub", actor,
            "createdAt", now, "updatedAt", now);
        records(state).add(draft);
        audit(state, "DRAFT_CREATED", id, actor, 1);
        persist(ws, state);
        return copy(draft);
    }

    public synchronized Map<String,Object> update(String ws, String draftId,
                   String actor, Map<String,Object> payload) {
        Map<String,Object> state = load(ws);
        Map<String,Object> draft = find(state, draftId);
        Object expected = payload.get("version");
        if (!(expected instanceof Number n) || n.longValue() != n.doubleValue()
                || n.longValue() < 1) throw bad(400, "version must be a positive integer");
        int current = ((Number) draft.get("version")).intValue();
        if (n.longValue() != current) throw bad(409, "Draft changed; reload before saving");
        String nextTitle = title(payload);
        String nextBody = content(payload);
        draft.put("title", nextTitle);
        draft.put("body", nextBody);
        draft.put("version", current + 1);
        draft.put("updatedBySub", actor);
        draft.put("updatedAt", Instant.now().toString());
        audit(state, "DRAFT_UPDATED", draftId, actor, current + 1);
        persist(ws, state);
        return copy(draft);
    }
}
