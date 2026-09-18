import sys, os, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_live_scenarios import MinecraftRconClient

DEF_DIR = os.path.join("run", "world", "storynpcs", "definitions")
NPC_FILE = os.path.join(DEF_DIR, "npcs", "live_test_npc.yaml")
QUEST_FILE = os.path.join(DEF_DIR, "quests", "broken_live.yaml")

results = []
def check(name, ok, detail=""):
    results.append((name, ok))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))

rcon = MinecraftRconClient()
rcon.connect()
print("=== RCON connected ===\n")

# --- UX-7: npc create from console -------------------------------------------
resp = rcon.command("storynpcs npc create storynpcs:live_test_npc Live Test NPC")
check("create from console reports success", "Created NPC 'Live Test NPC'" in resp, resp.strip()[:120])
check("create mentions saved YAML path", "npcs/live_test_npc.yaml" in resp)
time.sleep(0.3)
check("YAML file written to disk", os.path.exists(NPC_FILE))

resp = rcon.command("storynpcs npc list")
check("created NPC appears in list", "live_test_npc" in resp)

resp = rcon.command("storynpcs npc create storynpcs:live_test_npc Duplicate")
check("duplicate create refused", "already exists" in resp)

resp = rcon.command("storynpcs npc info storynpcs:live_test_npc")
check("info shows display name", "Live Test NPC" in resp)

# --- UX-6: reload diagnostics -------------------------------------------------
# Drop a broken quest: empty objectives -> QUEST_OBJ_EMPTY
with open(QUEST_FILE, "w") as f:
    f.write('id: "storynpcs:broken_live"\ntitle: "Broken"\nobjectives: []\n')

resp = rcon.command("storynpcs reload")
check("reload failure surfaces error to caller", "error" in resp.lower(), resp.strip()[:160])
check("diagnostic carries the validation code", "QUEST_OBJ_EMPTY" in resp, resp.strip()[:200])
check("previous definitions retained message", "retained" in resp)

os.remove(QUEST_FILE)
resp = rcon.command("storynpcs reload")
check("reload succeeds after fix", "reloaded successfully" in resp.lower(), resp.strip()[:120])

# --- UX-5: /storynpcs me from console (graceful player-only path) -------------
resp = rcon.command("storynpcs me")
check("me from console handled gracefully", resp.strip() != "" and "Exception" not in resp, resp.strip()[:120])

# --- cleanup ------------------------------------------------------------------
resp = rcon.command("storynpcs npc delete storynpcs:live_test_npc")
check("delete succeeds", "Deleted" in resp or "deleted" in resp)
time.sleep(0.3)
check("YAML file removed from disk (VULN-57)", not os.path.exists(NPC_FILE))

resp = rcon.command("storynpcs npc list")
check("deleted NPC gone from list", "live_test_npc" not in resp)

print(f"\n=== {sum(1 for _, ok in results if ok)}/{len(results)} checks passed ===")
sys.exit(0 if all(ok for _, ok in results) else 1)
