package com.postflight.local;

import com.postflight.core.*;
import java.nio.file.*;
import java.util.*;

/** Local-only tenant-data tests; test principals stand in for already-verified Clerk identities. */
public final class WorkspaceDraftSecurityCheck {
    private static int passed = 0;
    private static void check(boolean good, String what) {
        if (!good) throw new AssertionError(what);
        passed++;
    }
    private static void reject(int status, Runnable call) {
        try { call.run(); throw new AssertionError("Expected HTTP " + status); }
        catch (WorkspaceRegistry.Failure e) {
            check(e.status == status, "Wrong HTTP failure code: " + e.status);
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("postflight-draft-security-");
        try {
            WorkspaceRegistry members = new WorkspaceRegistry(dir.resolve("members.json"));
            WorkspaceDraftStore drafts = new WorkspaceDraftStore(dir.resolve("drafts"));
            var owner = new WorkspaceRegistry.Principal("user_AAAAAAAAAAAAAAAA", "owner");
            var outsider = new WorkspaceRegistry.Principal("user_BBBBBBBBBBBBBBBB", "outsider");
            var reader = new WorkspaceRegistry.Principal("user_CCCCCCCCCCCCCCCC", "viewer");
            String wsA = (String)members.create(owner, Map.of("name", "A")).get("id");
            String wsB = (String)members.create(outsider, Map.of("name", "B")).get("id");
            // Gateway role checks: unauthorized workspaces are deliberately hidden.
            reject(404, () -> members.workspace(outsider, wsA));
            reject(404, () -> members.workspace(owner, wsB));
            var invite = members.invite(owner, wsA, Map.of("targetSub", reader.sub(), "role", "VIEWER"));
            members.accept(reader, Map.of("invitationId", invite.get("id"), "inviteToken", invite.get("inviteToken")));
            check("VIEWER".equals(members.workspace(reader, wsA).get("yourRole")), "Viewer role missing");
            check(!List.of("OWNER", "ADMIN").contains(members.workspace(reader, wsA).get("yourRole")),
                "Viewer must be denied mutations by gateway");
            var a = drafts.create(wsA, owner.sub(), Map.of("title", "Release A", "body", "Hello A"));
            var b = drafts.create(wsB, outsider.sub(), Map.of("title", "Release B", "body", "Hello B"));
            check(drafts.list(wsA).size() == 1, "Tenant A draft leak");
            check(drafts.list(wsB).size() == 1, "Tenant B draft leak");
            reject(404, () -> drafts.get(wsB, (String)a.get("id")));
            reject(404, () -> drafts.get(wsA, (String)b.get("id")));
            reject(409, () -> drafts.update(wsA, (String)a.get("id"), owner.sub(),
                Map.of("title", "Changed", "body", "New", "version", 2)));
            var updated = drafts.update(wsA, (String)a.get("id"), owner.sub(),
                Map.of("title", "Changed", "body", "New", "version", 1));
            check(((Number)updated.get("version")).intValue() == 2, "Version not incremented");
            check("Release B".equals(drafts.get(wsB, (String)b.get("id")).get("title")), "Tenant B overwritten");
            reject(400, () -> drafts.create(wsA, owner.sub(), Map.of("title", "  ")));
            reject(400, () -> drafts.create(wsA, owner.sub(), Map.of("title", "x".repeat(161))));
            reject(400, () -> drafts.create(wsA, owner.sub(), Map.of("title", "t", "body", "x".repeat(20001))));
            var restarted = new WorkspaceDraftStore(dir.resolve("drafts"));
            check(restarted.list(wsA).size() == 1, "Draft persistence failed");
            check("Changed".equals(restarted.get(wsA, (String)a.get("id")).get("title")), "Draft revision not durable");
            check(!Files.exists(dir.resolve("postflight-data.json")), "Demo store should remain untouched");
            System.out.println("PASS - " + passed + " draft persistence, isolation and validation assertions");
        } finally {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }
}
