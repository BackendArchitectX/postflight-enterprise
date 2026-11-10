#!/usr/bin/env python3
"""Contract tests for PostFlight operational workflows, run ONLY on disposable local storage."""
import json
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:10004").rstrip("/")


def call(method, path, payload=None):
    headers = {"Accept": "application/json"}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(BASE + path, method=method, headers=headers, data=data)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.load(resp)
    except urllib.error.HTTPError as err:
        try:
            body = json.loads(err.read().decode("utf-8"))
        except ValueError:
            body = {}
        return err.code, body


before_code, before = call("GET", "/api/incidents")
assert before_code == 200 and isinstance(before, list)

status, invalid = call("POST", "/api/incidents", {"title": "Bad incident", "severity": "Extreme"})
assert status == 400 and "severity" in invalid["error"].lower()

status, invalid = call("POST", "/api/incidents", {"title": " "})
assert status == 400

status, created = call("POST", "/api/incidents", {
    "title": "QA: Unapproved financial performance claim",
    "description": "Test incident created in isolated temporary storage.",
    "severity": "High",
    "owner": "QA Compliance",
})
assert status == 201, (status, created)
inc_id = created["id"]
assert inc_id.startswith("PF-INC-")
assert created["status"] == "Open"
assert created["severity"] == "High"
assert created["escalationLevel"] == 0

status, incident_list = call("GET", "/api/incidents")
assert status == 200
assert len(incident_list) == len(before) + 1
assert incident_list[0]["id"] == inc_id

status, invalid = call("POST", "/api/incidents/" + inc_id + "/escalate", {"reason": ""})
assert status == 400

status, escalated = call("POST", "/api/incidents/" + inc_id + "/escalate", {
    "reason": "Escalate unsubstantiated return promise for human review",
    "assignedTo": "Legal reviewer",
})
assert status == 200, (status, escalated)
assert escalated["status"] == "Investigating"
assert escalated["owner"] == "Legal reviewer"
assert escalated["escalationLevel"] == 1
assert len(escalated["escalations"]) == 1
assert escalated["escalations"][0]["reason"].startswith("Escalate")

status, escalated2 = call("POST", "/api/incidents/" + inc_id + "/escalate", {
    "reason": "Second escalation after review timeout", "assignedTo": "Compliance director"
})
assert status == 200 and escalated2["escalationLevel"] == 2
assert len(escalated2["escalations"]) == 2

status, audit = call("GET", "/api/audit")
assert status == 200 and audit["chainValid"] is True
events = [x for x in audit["events"] if x.get("entityId") == inc_id]
assert any(x["action"] == "INCIDENT_CREATED" for x in events)
assert sum(x["action"] == "INCIDENT_ESCALATED" for x in events) == 2

status, templates = call("GET", "/api/workflows/templates")
assert status == 200 and isinstance(templates, list)
assert {x["id"] for x in templates} == {"regulated", "standard", "emergency"}
assert len({tuple(s["name"] for s in x["stages"]) for x in templates}) == 3
assert all(x["version"] == "1.0" for x in templates)
assert all(s["slaHours"] > 0 and s["owner"] for x in templates for s in x["stages"])

status, closed = call("PATCH", "/api/incidents/" + inc_id, {"status": "Resolved"})
assert status == 200 and closed["status"] == "Resolved"

status, rejected = call("POST", "/api/incidents/" + inc_id + "/escalate", {
    "reason": "Should be rejected", "assignedTo": "Nobody"
})
assert status == 409 and "resolved" in rejected["error"].lower()

status, integration = call("GET", "/api/integrations")
assert status == 200 and integration["zernio"]["publishingEnabled"] is False

print("PASS - incidents: validation, creation, persistence, escalation, ownership and audit")
print("PASS - workflow templates: 3 distinct versioned server-defined paths")
print("PASS - resolved incident escalation blocked; Zernio publishing unchanged")
