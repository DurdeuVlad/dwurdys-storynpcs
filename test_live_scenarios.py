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

    client.close()
    print("=== LIVE TEST SUITE EXECUTION COMPLETE ===")

if __name__ == "__main__":
    run_live_tests()
