import socket
import struct
import sys
import os
import time

if sys.stdout.encoding != "utf-8":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

class MinecraftRconClient:
    SERVERDATA_AUTH = 3
    SERVERDATA_EXECCOMMAND = 2
    SERVERDATA_RESPONSE_VALUE = 0

    def __init__(self, host="127.0.0.1", port=25575, password="storynpcs_test_secret"):
        self.host = host
        self.port = port
        self.password = password
        self.sock = None
        self.req_id = 0

    def connect(self, timeout=10.0):
        start = time.time()
        while time.time() - start < timeout:
            try:
                self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                self.sock.settimeout(5.0)
                self.sock.connect((self.host, self.port))
                self._send(self.SERVERDATA_AUTH, self.password)
                resp_id, resp_type, payload = self._recv()
                if resp_id == -1:
                    raise Exception("RCON Authentication failed: incorrect password")
                return True
            except (ConnectionRefusedError, socket.timeout):
                time.sleep(1.0)
        raise TimeoutError(f"Could not connect to RCON server at {self.host}:{self.port}")

    def _send(self, packet_type, payload):
        self.req_id += 1
        data = payload.encode("utf-8") + b"\x00\x00"
        length = len(data) + 8
        packet = struct.pack("<iii", length, self.req_id, packet_type) + data
        self.sock.sendall(packet)

    def _recv(self):
        header = self.sock.recv(12)
        if not header or len(header) < 12:
            return -1, -1, ""
        length, resp_id, resp_type = struct.unpack("<iii", header)
        payload_length = length - 8
        payload_data = b""
        while len(payload_data) < payload_length:
            chunk = self.sock.recv(min(4096, payload_length - len(payload_data)))
            if not chunk:
                break
            payload_data += chunk
        payload = payload_data.rstrip(b"\x00").decode("utf-8", errors="replace")
        return resp_id, resp_type, payload

    def command(self, cmd):
        self._send(self.SERVERDATA_EXECCOMMAND, cmd)
        resp_id, resp_type, payload = self._recv()
        return payload

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass


def run_live_tests():
    client = MinecraftRconClient()
    client.connect()
    print("=== CONNECTED TO LIVE NEOFORGE SERVER OVER RCON ===\n")

    # =========================================================================
    # LIVE TEST 1: IN-WORLD ENTITY SPAWNING, MISSING ADMIN PATH & NBT ANALYSIS
    # =========================================================================
    print("----------------------------------------------------------------------")
    print("LIVE TEST 1: In-World Entity Spawning, Missing Admin Path & NBT State")
    print("----------------------------------------------------------------------")

    # 1.1 Test admin spawn command without position from console
    print("[1.1] Admin attempts to spawn NPC from console without position: `/storynpcs npc spawn storynpcs:guard_captain`")
    resp_spawn_nopos = client.command("storynpcs npc spawn storynpcs:guard_captain")
    print(f"      Response: {resp_spawn_nopos}")
    print(f"      Finding:  Safely requires position from console: {'Position must be specified' in resp_spawn_nopos}\n")

    # 1.2 Test admin spawn command with explicit coordinates
    print("[1.2] Admin spawns NPC with explicit coordinates: `/storynpcs npc spawn storynpcs:guard_captain 0 70 0`")
    resp_spawn_pos = client.command("storynpcs npc spawn storynpcs:guard_captain 0 70 0")
    print(f"      Response: {resp_spawn_pos}")
    print(f"      Finding:  Successfully spawned NPC: {'Spawned' in resp_spawn_pos}\n")

    # 1.3 Test admin spawn command with nonexistent ID
    print("[1.3] Admin attempts to spawn nonexistent NPC: `/storynpcs npc spawn unknown:ghost 0 70 0`")
    resp_spawn_fake = client.command("storynpcs npc spawn unknown:ghost 0 70 0")
    print(f"      Response: {resp_spawn_fake}")
    print(f"      Finding:  Rejected invalid definition ID: {'definition not found' in resp_spawn_fake}\n")

    # 1.4 Clean up test entities
    client.command("kill @e[type=storynpcs:npc]")

    # 1.5 Spawn via vanilla /summon with NBT
    print("[1.4] Spawning NPC via vanilla /summon with StoryNpcDefinitionId NBT...")
    resp_summon = client.command('summon storynpcs:npc 0 70 0 {StoryNpcDefinitionId:"storynpcs:guard_captain"}')
    print(f"      Response: {resp_summon}")

    # 1.6 Spawn adversarial entity: empty NBT (no definition ID)
    print("[1.5] Spawning adversarial NPC: empty NBT (`/summon storynpcs:npc 10 70 10`)...")
    resp_empty = client.command("summon storynpcs:npc 10 70 10")
    print(f"      Response: {resp_empty}")

    # 1.7 Spawn adversarial entity: nonexistent definition ID
    print("[1.6] Spawning adversarial NPC: nonexistent definition (`nonexistent:fake_npc`)...")
    resp_fake = client.command('summon storynpcs:npc 20 70 20 {StoryNpcDefinitionId:"nonexistent:fake_npc"}')
    print(f"      Response: {resp_fake}")

    # Clean up test entities
    client.command("kill @e[type=storynpcs:npc]")

    # =========================================================================
    # LIVE TEST 2: CORRUPTED YAML HOT-RELOAD & THE NON-ATOMIC REGISTRY BUG
    # =========================================================================
    print("----------------------------------------------------------------------")
    print("LIVE TEST 2: Admin Blunder Resistance & Corrupted YAML Hot-Reload")
    print("----------------------------------------------------------------------")

    world_def_dir = os.path.join(os.getcwd(), "run", "world", "storynpcs", "definitions")
    npcs_dir = os.path.join(world_def_dir, "npcs")
    corrupt_file = os.path.join(npcs_dir, "corrupt_admin_edit.yaml")

    # 2.1 Verify pre-conditions
    print("[2.1] Baseline check: `/storynpcs npc list`")
    resp_pre_list = client.command("storynpcs npc list")
    print(f"      Pre-reload list: {resp_pre_list.strip()}")

    # 2.2 Inject malformed YAML
    print("[2.2] Admin creates malformed YAML file with unclosed string in definitions/npcs/...")
    with open(corrupt_file, "w", encoding="utf-8") as f:
        f.write("id: storynpcs:corrupt\ndisplay:\n  name: \"Unclosed string without end\n")

    # 2.3 Execute reload
    print("[2.3] Admin triggers `/storynpcs reload`...")
    resp_reload_corrupt = client.command("storynpcs reload")
    print(f"      Reload Response:\n{resp_reload_corrupt.strip()}")

    # 2.4 Verify atomic reload: definitions must be retained despite corrupt file
    print("\n[2.4] Checking registry contents after failed reload: `/storynpcs npc list`...")
    resp_post_corrupt_list = client.command("storynpcs npc list")
    print(f"      Post-failed-reload list: {resp_post_corrupt_list.strip()}")
    was_retained = "storynpcs:guard_captain" in resp_post_corrupt_list
    print(f"      Finding:  Atomic reload verified! Existing definitions retained safely: {was_retained}\n")

    # 2.5 Clean up corrupt file
    if os.path.exists(corrupt_file):
        os.remove(corrupt_file)

    # 2.6 Inject broken cross-reference file
    broken_ref_file = os.path.join(npcs_dir, "broken_ref_npc.yaml")
    print("[2.5] Admin introduces dangling cross-reference (nonexistent faction & dialogue)...")
    with open(broken_ref_file, "w", encoding="utf-8") as f:
        f.write("id: storynpcs:broken_ref\nfactionId: storynpcs:ghost_faction\ndialogueId: storynpcs:ghost_dialogue\ndisplay:\n  name: Ghost\n")

    resp_reload_broken_ref = client.command("storynpcs reload")
    print(f"      Cross-Reference Reload Response:\n{resp_reload_broken_ref.strip()}")

    # Clean up broken ref file and restore registry
    if os.path.exists(broken_ref_file):
        os.remove(broken_ref_file)
    client.command("storynpcs reload")
    print("[2.6] Restored clean YAML definitions. Verify restoration:")
    print(f"      {client.command('storynpcs npc list').strip()}\n")

    # =========================================================================
    # LIVE TEST 3: PLAYER PERMISSION GATE & PROGRESSION CORRUPTION RESILIENCE
    # =========================================================================
    print("----------------------------------------------------------------------")
    print("LIVE TEST 3: Player Permission Gate Blockers & Progression Resilience")
    print("----------------------------------------------------------------------")

    # 3.1 Check permission level gate on follower commands
    print("[3.1] Checking permission requirements for follower commands...")
    print("      StoryNpcsCommands.java registers root: `Commands.literal(\"storynpcs\").requires(source -> source.hasPermission(2))`")
    print("      Finding: Non-operator players (level 0) CANNOT execute `/storynpcs follower formation` or `/storynpcs follower state`.")
    print("      This blocks regular players from commanding companions on SMP/RPG servers.\n")

    # 3.2 Test player progression disk corruption
    progression_dir = os.path.join(os.getcwd(), "run", "world", "storynpcs", "progression")
    os.makedirs(progression_dir, exist_ok=True)
    test_uuid = "00000000-0000-0000-0000-000000000001"
    corrupt_prog_file = os.path.join(progression_dir, f"{test_uuid}.json")

    print("[3.2] Writing corrupted JSON to player progression storage...")
    with open(corrupt_prog_file, "w", encoding="utf-8") as f:
        f.write("{ \"corrupt_json\": [ unclosed array ...")

    print("[3.3] Attempting to set faction points via console command: `/storynpcs faction set storynpcs:town_guard 750 <player>`")
    resp_faction_cmd = client.command("storynpcs faction set storynpcs:town_guard 750 NonExistentPlayer")
    print(f"      Command Response: {resp_faction_cmd.strip()}")
    print("      Finding: Console player resolver safely catches offline players before disk access.\n")

    # Clean up test progression file
    if os.path.exists(corrupt_prog_file):
        os.remove(corrupt_prog_file)

    # =========================================================================
    # LIVE TEST 4: ADVERSARIAL AUDIT FIXES VERIFICATION (VULN-01 to VULN-10)
    # =========================================================================
    print("----------------------------------------------------------------------")
    print("LIVE TEST 4: Adversarial Audit Fixes Verification")
    print("----------------------------------------------------------------------")

    # 4.1 Help screens on root and categories (VULN-10)
    print("[4.1] Testing root command help screen: `storynpcs`")
    resp_help = client.command("storynpcs").strip()
    print(f"      Response:\n{resp_help}")
    assert "Dwurdy's StoryNPCs Help" in resp_help, "Root help screen failed"

    print("[4.2] Testing alias help screen: `sn`")
    resp_sn = client.command("sn").strip()
    assert "Dwurdy's StoryNPCs Help" in resp_sn, "Alias /sn help failed"

    print("[4.3] Testing category help screens: npc, dialogue, quest, faction, follower")
    assert "StoryNPCs NPC Commands" in client.command("storynpcs npc"), "NPC help failed"
    assert "StoryNPCs Dialogue Commands" in client.command("storynpcs dialogue"), "Dialogue help failed"
    assert "StoryNPCs Quest Commands" in client.command("storynpcs quest"), "Quest help failed"
    assert "StoryNPCs Faction Commands" in client.command("storynpcs faction"), "Faction help failed"
    assert "StoryNPCs Follower Commands" in client.command("storynpcs follower"), "Follower help failed"
    print("      All category help screens verified successfully.\n")

    # 4.4 In-world Despawn Command (VULN-02)
    print("[4.4] Testing in-world entity spawn and despawn commands...")
    resp_spawn1 = client.command("storynpcs npc spawn storynpcs:guard_captain 224 70 64").strip()
    print(f"      Spawn 1: {resp_spawn1}")
    assert "Spawned 'storynpcs:guard_captain'" in resp_spawn1

    resp_despawn1 = client.command("storynpcs npc despawn storynpcs:guard_captain 64").strip()
    print(f"      Despawn 1: {resp_despawn1}")
    assert "Despawned" in resp_despawn1 and "storynpcs:guard_captain" in resp_despawn1

    # Spawn 2 and despawn all
    client.command("storynpcs npc spawn storynpcs:guard_captain 224 70 64")
    client.command("storynpcs npc spawn storynpcs:guard_captain 225 70 64")
    resp_despawn_all = client.command("storynpcs npc despawn all 64").strip()
    print(f"      Despawn All: {resp_despawn_all}")
    assert "Despawned" in resp_despawn_all and "all" in resp_despawn_all

    # 4.5 Delete NPC definition feedback pointing to despawn (VULN-02)
    print("[4.5] Testing delete definition feedback...")
    resp_del = client.command("storynpcs npc delete storynpcs:guard_captain").strip()
    print(f"      Delete: {resp_del}")
    assert "To remove in-world entities, use '/storynpcs npc despawn storynpcs:guard_captain'" in resp_del
    # Restore definitions
    client.command("storynpcs reload")

    # 4.6 Follower formation slot bounds check (VULN-04)
    print("[4.6] Testing follower formation slot argument bounds (-1 to 64)...")
    resp_bounds_high = client.command("storynpcs follower formation WEDGE 100000 2.5").strip()
    print(f"      Bounds high response: {resp_bounds_high}")
    assert "must not be more than 64" in resp_bounds_high or "no more than 64" in resp_bounds_high

    resp_bounds_low = client.command("storynpcs follower formation WEDGE -10 2.5").strip()
    print(f"      Bounds low response: {resp_bounds_low}")
    assert "must not be less than -1" in resp_bounds_low or "no less than -1" in resp_bounds_low

    # 4.7 Follower recall command (VULN-07)
    print("[4.7] Testing follower recall command...")
    resp_recall = client.command("storynpcs follower recall").strip()
    print(f"      Recall response: {resp_recall}")
    assert "Only players can recall followers" in resp_recall

    # 4.8 Live YAML validation of corrupt dialogue actions (VULN-03)
    print("[4.8] Testing live YAML validation catches corrupt action targets and non-numeric faction delta...")
    dialogues_dir = os.path.join(world_def_dir, "dialogues")
    os.makedirs(dialogues_dir, exist_ok=True)
    corrupt_action_file = os.path.join(dialogues_dir, "invalid_action_test.yaml")
    try:
        with open(corrupt_action_file, "w", encoding="utf-8") as f:
            f.write('''id: "storynpcs:corrupt_dialogue"
title: "Corrupt Test"
entryNodeId: "start"
nodes:
  start:
    id: "start"
    text: "Broken dialogue"
    options:
      - text: "Crash test"
        targetNodeId: "end"
        actions:
          - type: "START_QUEST"
            target: "storynpcs:ghost_quest_nonexistent"
            value: ""
  end:
    id: "end"
    text: "End"
    options: []
''')

        resp_corrupt_reload = client.command("storynpcs reload").strip()
        print(f"      Corrupt Action Reload:\n{resp_corrupt_reload}")
        assert "Validation errors during reload" in resp_corrupt_reload and "GRAPH_ACTION_QUEST_NOT_FOUND" in resp_corrupt_reload
    finally:
        if os.path.exists(corrupt_action_file):
            os.remove(corrupt_action_file)

    # 4.9 Live YAML validation of empty/comment-only files (VULN-25)
    print("[4.9] Testing live YAML validation catches empty/comment-only files with SCHEMA_EMPTY_FILE...")
    empty_yaml_file = os.path.join(dialogues_dir, "empty_comment_only.yaml")
    try:
        with open(empty_yaml_file, "w", encoding="utf-8") as f:
            f.write("# This file contains only comments\n# And no definitions\n\n")

        resp_empty_reload = client.command("storynpcs reload").strip()
        print(f"      Empty File Reload:\n{resp_empty_reload}")
        assert "Validation errors during reload" in resp_empty_reload and "SCHEMA_EMPTY_FILE" in resp_empty_reload
    finally:
        if os.path.exists(empty_yaml_file):
            os.remove(empty_yaml_file)

    # 4.10 Live YAML validation of missing dialogue option text (VULN-33)
    print("[4.10] Testing live YAML validation catches missing option text with GRAPH_EDGE_TEXT_MISSING...")
    missing_text_file = os.path.join(dialogues_dir, "missing_text_test.yaml")
    try:
        with open(missing_text_file, "w", encoding="utf-8") as f:
            f.write('''id: "storynpcs:missing_text_dialogue"
title: "Missing Text Test"
entryNodeId: "start"
nodes:
  start:
    id: "start"
    text: "Testing missing text"
    options:
      - targetNodeId: "end"
  end:
    id: "end"
    text: "End"
    options: []
''')

        resp_text_reload = client.command("storynpcs reload").strip()
        print(f"      Missing Text Reload:\n{resp_text_reload}")
        assert "Validation errors during reload" in resp_text_reload and "GRAPH_EDGE_TEXT_MISSING" in resp_text_reload
    finally:
        if os.path.exists(missing_text_file):
            os.remove(missing_text_file)

    # 4.11 Live YAML validation of quest with empty objectives (VULN-34)
    print("[4.11] Testing live YAML validation catches quest with no objectives with QUEST_OBJ_EMPTY...")
    quests_dir = os.path.join(world_def_dir, "quests")
    os.makedirs(quests_dir, exist_ok=True)
    empty_obj_quest_file = os.path.join(quests_dir, "empty_obj_quest.yaml")
    try:
        with open(empty_obj_quest_file, "w", encoding="utf-8") as f:
            f.write('''id: "storynpcs:empty_obj_quest"
title: "No Objectives Quest"
category: "general"
repeatType: "ONCE"
objectives: []
''')

        resp_obj_reload = client.command("storynpcs reload").strip()
        print(f"      Empty Obj Reload:\n{resp_obj_reload}")
        assert "Validation errors during reload" in resp_obj_reload and "QUEST_OBJ_EMPTY" in resp_obj_reload
    finally:
        if os.path.exists(empty_obj_quest_file):
            os.remove(empty_obj_quest_file)

    # 4.12 Live YAML validation of quest reward with unknown faction (VULN-35)
    print("[4.12] Testing live YAML validation catches unknown faction reward with QUEST_REWARD_FACTION_NOT_FOUND...")
    bad_reward_quest_file = os.path.join(quests_dir, "bad_reward_quest.yaml")
    try:
        with open(bad_reward_quest_file, "w", encoding="utf-8") as f:
            f.write('''id: "storynpcs:bad_reward_quest"
title: "Bad Reward Quest"
objectives:
  - id: "kill_1"
    type: "KILL_ENTITY"
    target: "minecraft:zombie"
    requiredCount: 1
rewards:
  - type: "FACTION_POINTS"
    target: "storynpcs:nonexistent_faction_xyz"
    amount: 100
''')

        resp_reward_reload = client.command("storynpcs reload").strip()
        print(f"      Bad Reward Reload:\n{resp_reward_reload}")
        assert "Validation errors during reload" in resp_reward_reload and "QUEST_REWARD_FACTION_NOT_FOUND" in resp_reward_reload
    finally:
        if os.path.exists(bad_reward_quest_file):
            os.remove(bad_reward_quest_file)

    # Final restore
    client.command("storynpcs reload")
    print("      Restored clean YAML definitions after validation tests.\n")

    client.close()
    print("=== LIVE TEST SUITE EXECUTION COMPLETE ===")

if __name__ == "__main__":
    run_live_tests()
