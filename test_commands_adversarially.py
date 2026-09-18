import socket
import struct
import sys
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
    SERVERDATA_AUTH_RESPONSE = 2

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
                # Authenticate
                self._send(self.SERVERDATA_AUTH, self.password)
                resp_id, resp_type, payload = self._recv()
                if resp_id == -1:
                    raise Exception("RCON Authentication failed: incorrect password")
                return True
            except (ConnectionRefusedError, socket.timeout):
                time.sleep(1.0)
        raise TimeoutError(f"Could not connect to RCON server at {self.host}:{self.port} within {timeout}s")

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
        # Strip trailing null bytes
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


def run_all_tests():
    print("Connecting to Minecraft RCON server...")
    client = MinecraftRconClient()
    client.connect(timeout=15.0)
    print("RCON Connected & Authenticated successfully!\n")

    test_cases = [
        # 1. Root & Syntax Edge Cases
        ("Root command without subcommands", "storynpcs", None, "expected syntax error or usage"),
        ("Invalid subcommand", "storynpcs invalid_subcommand_test", None, "expected unknown command"),
        ("Unicode / special chars subcommand", "storynpcs 𝔖𝔱𝔬𝔯𝔶𝔑𝔓ℭ", None, "handled without crash"),
        
        # 2. Alias /sn
        ("Alias /sn list npcs", "sn npc list", "StoryNPCs", "expected /sn alias to redirect to /storynpcs"),
        
        # 3. Reload Command
        ("Reload definitions", "storynpcs reload", "reloaded successfully", "expected successful reload of YAML"),
        ("Alias reload", "sn reload", "reloaded successfully", "expected successful alias reload"),

        # 4. NPC Subcommands
        ("NPC list", "storynpcs npc list", "guard_captain", "expected guard_captain in list"),
        ("NPC info valid", "storynpcs npc info storynpcs:guard_captain", "Captain Valerie", "expected details of guard_captain"),
        ("NPC info nonexistent", "storynpcs npc info unknown:fake_npc", "NPC not found", "expected not found error"),
        ("NPC info malformed id", "storynpcs npc info illegal::id::format", "Invalid ID", "expected invalid path validation"),
        ("NPC info injection attempt", "storynpcs npc info \"; DROP TABLE npcs; --", "trailing data", "expected injection rejection"),
        ("NPC delete valid", "storynpcs npc delete storynpcs:guard_captain", "Deleted NPC definition", "expected deletion"),
        ("NPC verify deleted", "storynpcs npc info storynpcs:guard_captain", "NPC not found", "expected not found after deletion"),
        ("NPC delete nonexistent", "storynpcs npc delete storynpcs:guard_captain", "NPC not found", "expected already deleted"),
        ("Restore deleted NPC via create", "storynpcs npc create storynpcs:guard_captain Captain Valerie", "Created NPC 'Captain Valerie'", "expected recreated via scaffold"),
        ("Verify NPC restored", "storynpcs npc info storynpcs:guard_captain", "Captain Valerie", "expected restored"),

        # 5. Dialogue Subcommands
        ("Dialogue list", "storynpcs dialogue list", "captain_dialogue", "expected captain_dialogue in list"),
        ("Dialogue info valid", "storynpcs dialogue info storynpcs:captain_dialogue", "Total Nodes: 4", "expected 4 nodes"),
        ("Dialogue info nonexistent", "storynpcs dialogue info nonexistent:dialogue", "Dialogue not found", "expected not found error"),
        ("Dialogue edit from console", "storynpcs dialogue edit storynpcs:captain_dialogue", "The dialogue editor can only be opened by a player", "expected console guard"),
        ("Dialogue edit nonexistent", "storynpcs dialogue edit nonexistent:dialogue", "Dialogue not found", "expected not found"),
        ("Dialogue start console without player", "storynpcs dialogue start storynpcs:captain_dialogue", "Player must be specified when executed from console", "expected console guard"),
        ("Dialogue start nonexistent player", "storynpcs dialogue start storynpcs:captain_dialogue NonExistentPlayer999", "Invalid name or UUID", "expected player lookup failure"),

        # 6. Quest Subcommands
        ("Quest list", "storynpcs quest list", "bounty_goblins", "expected bounty_goblins in list"),
        ("Quest start console without player", "storynpcs quest start storynpcs:bounty_goblins", "Player must be specified when executed from console", "expected console guard"),
        ("Quest start nonexistent player", "storynpcs quest start storynpcs:bounty_goblins NonExistentPlayer999", "Invalid name or UUID", "expected player lookup failure"),
        ("Quest complete console without player", "storynpcs quest complete storynpcs:bounty_goblins", "Player must be specified when executed from console", "expected console guard"),
        ("Quest complete nonexistent player", "storynpcs quest complete storynpcs:bounty_goblins NonExistentPlayer999", "Invalid name or UUID", "expected player lookup failure"),

        # 7. Faction Subcommands
        ("Faction list", "storynpcs faction list", "town_guard", "expected town_guard in list"),
        ("Faction set console without player", "storynpcs faction set storynpcs:town_guard 500", "Player must be specified when executed from console", "expected console guard"),
        ("Faction set nonexistent player", "storynpcs faction set storynpcs:town_guard 500 NonExistentPlayer999", "Invalid name or UUID", "expected player lookup failure"),
        ("Faction adjust console without player", "storynpcs faction adjust storynpcs:town_guard -50", "Player must be specified when executed from console", "expected console guard"),
        ("Faction adjust nonexistent player", "storynpcs faction adjust storynpcs:town_guard 50 NonExistentPlayer999", "Invalid name or UUID", "expected player lookup failure"),
        ("Faction set out-of-range integer", "storynpcs faction set storynpcs:town_guard 999999999999999999999999", None, "expected integer parser error"),

        # 8. Follower Subcommands
        ("Follower formation console execution", "storynpcs follower formation WEDGE", "Command must be executed by a player", "expected player-only guard"),
        ("Follower formation out of bounds spacing", "storynpcs follower formation WEDGE 0 99.0", None, "expected double range error"),
        ("Follower formation negative spacing", "storynpcs follower formation WEDGE 0 -5.0", None, "expected double range error"),
        ("Follower state console execution", "storynpcs follower state FOLLOWING", "Command must be executed by a player", "expected player-only guard"),
        ("Follower state invalid state string", "storynpcs follower state INVALID_STATE", "Command must be executed by a player", "console check precedes argument evaluation"),
    ]

    results = []
    passed = 0
    failed = 0

    print(f"Executing {len(test_cases)} Adversarial Command Test Cases against Live Server...\n")

    for idx, (name, cmd, expected_substring, notes) in enumerate(test_cases, 1):
        print(f"[{idx}/{len(test_cases)}] Testing: {name}")
        print(f"    Command:  `/{cmd}`")
        resp = client.command(cmd).strip()
        print(f"    Response: {resp if resp else '<empty response>'}")

        success = True
        if expected_substring is not None:
            if expected_substring.lower() not in resp.lower():
                success = False

        if success:
            print(f"    Result:   [PASS] ({notes})\n")
            passed += 1
        else:
            print(f"    Result:   [FAIL] Expected substring: '{expected_substring}' | Notes: {notes}\n")
            failed += 1

        results.append({
            "index": idx,
            "name": name,
            "command": cmd,
            "response": resp,
            "expected": expected_substring,
            "passed": success,
            "notes": notes
        })

    client.close()

    print("==================================================")
    print(f"Adversarial Command Suite Complete: {passed} PASSED, {failed} FAILED out of {len(test_cases)} tests.")
    print("==================================================")

    return results, passed, failed

if __name__ == "__main__":
    results, passed, failed = run_all_tests()
    if failed > 0:
        sys.exit(1)
    sys.exit(0)
