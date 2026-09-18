import sys, os, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_live_scenarios import MinecraftRconClient

DEF_DIR = os.path.join("run", "world", "storynpcs", "definitions")
DIAL_FILE = os.path.join(DEF_DIR, "dialogues", "adv_dialogue.yaml")
NPC_FILE = os.path.join(DEF_DIR, "npcs", "adv_npc.yaml")

results = []
def check(name, ok, detail=""):
    results.append((name, ok))
    status = "PASS" if ok else "FAIL"
    print(f"[{status}] {name}" + (f" -- {detail}" if detail else ""))

print("=== Connecting to Live Server via RCON ===")
rcon = MinecraftRconClient()
# Give up to 45s for server to finish booting
connected = False
for i in range(45):
    try:
        rcon.connect(timeout=2.0)
        connected = True
        break
    except Exception:
        time.sleep(1.0)

if not connected:
    print("FATAL: Could not connect to RCON server.")
    sys.exit(1)

print("=== Connected successfully! Starting Aggressive Live Tests ===\n")

# ==============================================================================
# SECTION 1: Dialogue Scaffolding & Live Graph Persistence (UX-9)
# ==============================================================================
print("--- Section 1: Dialogue Scaffolding & Persistence ---")
resp = rcon.command("storynpcs dialogue create storynpcs:adv_dialogue Adversarial Dialogue")
check("dialogue create reports success", "Created dialogue 'Adversarial Dialogue'" in resp, resp.strip()[:120])
check("dialogue create mentions saved YAML path", "dialogues/adv_dialogue.yaml" in resp)
time.sleep(0.3)
check("dialogue YAML file exists on disk", os.path.exists(DIAL_FILE))

resp = rcon.command("storynpcs dialogue list")
check("dialogue appears in dialogue list", "adv_dialogue" in resp)

resp = rcon.command("storynpcs dialogue info storynpcs:adv_dialogue")
check("dialogue info shows title", "Adversarial Dialogue" in resp)
check("dialogue info shows entry node 'start'", "Entry Node: start" in resp)

resp = rcon.command("storynpcs dialogue create storynpcs:adv_dialogue Duplicate")
check("duplicate dialogue create refused", "already exists" in resp)

# ==============================================================================
# SECTION 2: NPC Scaffolding & Live In-Game Linking (UX-7, UX-9)
# ==============================================================================
print("\n--- Section 2: NPC Scaffolding & Live Linking ---")
resp = rcon.command("storynpcs npc create storynpcs:adv_npc Adversarial NPC")
check("npc create reports success", "Created NPC 'Adversarial NPC'" in resp)
time.sleep(0.3)
check("npc YAML file exists on disk", os.path.exists(NPC_FILE))

# Link dialogue
resp = rcon.command("storynpcs npc set dialogue storynpcs:adv_npc storynpcs:adv_dialogue")
check("assign dialogue reports success", "Assigned dialogue 'storynpcs:adv_dialogue'" in resp)
time.sleep(0.3)
# Read NPC YAML file to confirm persistence
with open(NPC_FILE, "r") as f:
    npc_yaml_content = f.read()
check("NPC YAML contains persisted dialogueId", "dialogueId" in npc_yaml_content and "adv_dialogue" in npc_yaml_content)

# Dialogue info should now report referencing NPC
resp = rcon.command("storynpcs dialogue info storynpcs:adv_dialogue")
check("dialogue info reports referencing NPC", "adv_npc" in resp)

# Attempt assigning non-existent dialogue
resp = rcon.command("storynpcs npc set dialogue storynpcs:adv_npc storynpcs:non_existent_dialogue")
check("assigning missing dialogue refused", "not found" in resp.lower())

# Link faction
resp = rcon.command("storynpcs npc set faction storynpcs:adv_npc storynpcs:town_guard")
check("assign faction reports success", "Assigned faction 'storynpcs:town_guard'" in resp)
time.sleep(0.3)
with open(NPC_FILE, "r") as f:
    npc_yaml_content = f.read()
check("NPC YAML contains persisted factionId", "factionId" in npc_yaml_content and "town_guard" in npc_yaml_content)

# Attempt assigning non-existent faction
resp = rcon.command("storynpcs npc set faction storynpcs:adv_npc storynpcs:fake_faction")
check("assigning missing faction refused", "not found" in resp.lower())

# NPC info shows both
resp = rcon.command("storynpcs npc info storynpcs:adv_npc")
check("npc info shows assigned dialogue and faction", "adv_dialogue" in resp and "town_guard" in resp)

# ==============================================================================
# SECTION 3: Quest & Faction Full Inspections (UX-10)
# ==============================================================================
print("\n--- Section 3: Quest & Faction Inspections ---")
resp = rcon.command("storynpcs quest list")
check("quest list returns loaded quests", "Quests" in resp)

# Check bundled quest bounty_goblins
resp = rcon.command("storynpcs quest info storynpcs:bounty_goblins")
check("quest info shows title", "Bounty: Forest Goblins" in resp or "bounty_goblins" in resp)
check("quest info shows objectives", "Objectives" in resp)
check("quest info shows rewards", "Rewards" in resp)

resp = rcon.command("storynpcs quest info storynpcs:missing_quest")
check("missing quest handled cleanly", "not found" in resp.lower())

resp = rcon.command("storynpcs faction list")
check("faction list returns loaded factions", "Factions" in resp)

resp = rcon.command("storynpcs faction info storynpcs:town_guard")
check("faction info shows default points", "Default Points" in resp)
check("faction info shows standing thresholds", "Standing Thresholds" in resp)

resp = rcon.command("storynpcs faction info storynpcs:missing_faction")
check("missing faction handled cleanly", "not found" in resp.lower())

# ==============================================================================
# SECTION 4: Dialogue Deletion Safeguards (UX-11, VULN-57)
# ==============================================================================
print("\n--- Section 4: Deletion Safeguards & File Cleanup ---")
# Deleting a dialogue that is referenced by adv_npc should warn about the reference
resp = rcon.command("storynpcs dialogue delete storynpcs:adv_dialogue")
check("dialogue delete warns about referencing NPC", "adv_npc" in resp or "referenced" in resp.lower())
time.sleep(0.3)
check("dialogue file deleted from disk", not os.path.exists(DIAL_FILE))

resp = rcon.command("storynpcs dialogue list")
check("deleted dialogue gone from list", "adv_dialogue" not in resp)

# Delete NPC
resp = rcon.command("storynpcs npc delete storynpcs:adv_npc")
check("npc delete succeeds", "Deleted" in resp or "deleted" in resp)
time.sleep(0.3)
check("NPC file deleted from disk", not os.path.exists(NPC_FILE))

resp = rcon.command("storynpcs npc list")
check("deleted NPC gone from list", "adv_npc" not in resp)

# ==============================================================================
# SECTION 5: Adversarial Inputs & Fuzzing (Zero-Crash Guarantee)
# ==============================================================================
print("\n--- Section 5: Adversarial Inputs & Fuzzing ---")
# Test path traversal injection in create
resp = rcon.command("storynpcs npc create storynpcs:../../malicious Path Traversal")
check("path traversal in npc create handled safely", "Exception" not in resp and "Error" not in resp or "rejected" in resp or "Invalid" in resp)

# Test SQL/command injection payload in names
resp = rcon.command("storynpcs npc create storynpcs:fuzz_1 ' OR '1'='1; DROP TABLE npcs;--")
check("injection in display name handled safely", "Exception" not in resp)
# Clean it up
rcon.command("storynpcs npc delete storynpcs:fuzz_1")

# Test weird characters
resp = rcon.command("storynpcs dialogue create storynpcs:fuzz_dial <>?|:*!@#$%^&*")
check("weird characters in title handled safely", "Exception" not in resp)
rcon.command("storynpcs dialogue delete storynpcs:fuzz_dial")

# Test extreme numbers in faction adjust
resp = rcon.command("storynpcs faction adjust storynpcs:town_guard 999999999")
check("large faction adjust handled safely", "Exception" not in resp)

resp = rcon.command("storynpcs faction adjust storynpcs:town_guard -999999999")
check("negative faction adjust handled safely", "Exception" not in resp)

# Test me command from console
resp = rcon.command("storynpcs me")
check("storynpcs me from console handled gracefully", "Exception" not in resp and "player" in resp.lower())

# Final reload check to ensure world definitions are completely consistent
resp = rcon.command("storynpcs reload")
check("final reload succeeds cleanly", "reloaded successfully" in resp.lower())

# Summary
passed = sum(1 for _, ok in results if ok)
total = len(results)
print(f"\n==============================================")
print(f"AGGRESSIVE LIVE TEST SUMMARY: {passed}/{total} PASSED")
print(f"==============================================")
rcon.close()
sys.exit(0 if passed == total else 1)
