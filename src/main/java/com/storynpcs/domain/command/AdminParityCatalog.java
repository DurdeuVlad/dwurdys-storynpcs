package com.storynpcs.domain.command;

import java.util.List;

/**
 * Remote/admin/config parity mapping (issue #86 — P9-4). Every target surface
 * named by the acceptance criteria — {@code SPacketRemote*},
 * {@code GuiNpcRemoteEditor}, {@code SPacketPlayerData*},
 * {@code GuiNpcManagePlayerData}, {@code PacketConfigFont},
 * {@code GuiAchievement}, {@code SPacketMenu*} — carries an explicit
 * {@code SUPPORTED} equivalent or a documented {@code INTENTIONAL_DEVIATION}.
 *
 * <p>Transport boundary: the wire packets and client GUIs themselves are
 * owned by P1-3 (packet surface) and P10-1 (screens). What P9-4 delivers is
 * the server-authoritative behavior each surface drives — session/permission
 * proof, self-vs-admin scoping, unloaded-target guards, atomic config
 * persistence, and audit records — plus the command/API adapters that reach
 * it today.
 */
public final class AdminParityCatalog {

    private static final List<CommandParityEntry> ENTRIES = List.of(
            new CommandParityEntry("target.packets.0131", "SPacketRemoteFreeze",
                    "freeze/unfreeze a remote NPC entity",
                    CommandParityStatus.SUPPORTED, "/storynpcs remote freeze <entity_uuid>",
                    "Toggles no-AI on the loaded entity; unloaded or wrong-world targets fail REMOTE_TARGET_UNLOADED."),
            new CommandParityEntry("target.packets.0132", "SPacketRemoteMenuOpen",
                    "open the remote NPC editor menu",
                    CommandParityStatus.INTENTIONAL_DEVIATION, null,
                    "The remote editor menu is a client-render surface — owned by P10-1 screens. The server ops it would drive are implemented separately."),
            new CommandParityEntry("target.packets.0133", "SPacketRemoteNpcDelete",
                    "delete a remote NPC entity",
                    CommandParityStatus.SUPPORTED, "/storynpcs remote delete <entity_uuid>",
                    "Discards the loaded entity with audit; packet transport is P1-3 scope."),
            new CommandParityEntry("target.packets.0134", "SPacketRemoteNpcReset",
                    "reset a remote NPC entity",
                    CommandParityStatus.SUPPORTED, "/storynpcs remote reset <entity_uuid>",
                    "Respawns the loaded entity at its start position with audit."),
            new CommandParityEntry("target.packets.0135", "SPacketRemoteNpcsGet",
                    "list remote NPC entities near the operator",
                    CommandParityStatus.SUPPORTED, "/storynpcs remote list [radius]",
                    "Bounded-radius loaded-entity listing; target radius is fixed, StoryNPCs bounds it explicitly."),
            new CommandParityEntry("target.packets.0136", "SPacketRemoteNpcTp",
                    "teleport a remote NPC entity to the operator",
                    CommandParityStatus.SUPPORTED, "/storynpcs remote tp <entity_uuid>",
                    "Teleports a loaded entity in the operator's dimension only — cross-dimension/unloaded targets are refused."),
            new CommandParityEntry("target.gui.0017", "GuiNpcRemoteEditor",
                    "remote NPC editor screen",
                    CommandParityStatus.INTENTIONAL_DEVIATION, null,
                    "Client screen — P10-1 owner. Server-side remote ops (freeze/reset/tp/delete/list) are implemented and audited."),
            new CommandParityEntry("target.packets.0108", "SPacketPlayerDataGet",
                    "read stored player data",
                    CommandParityStatus.SUPPORTED, "/storynpcs playerdata read [player]",
                    "Detached PlayerDataSummary through adminReadPlayerData — SELF scope for own data, ADMIN (perm 2) for cross-player."),
            new CommandParityEntry("target.packets.0109", "SPacketPlayerDataRemove",
                    "remove stored player data",
                    CommandParityStatus.SUPPORTED, "/storynpcs playerdata clear [player]",
                    "Canonical adminClearPlayerData deletes the durable progression record — self-scoped or perm-2 admin, idempotent, audited."),
            new CommandParityEntry("target.gui.0070", "GuiNpcManagePlayerData",
                    "player-data management screen",
                    CommandParityStatus.INTENTIONAL_DEVIATION, null,
                    "Client screen — P10-1 owner. The player-data read/clear ops it drives are implemented server-side."),
            new CommandParityEntry("target.packets.0004", "PacketConfigFont",
                    "send font config to the client",
                    CommandParityStatus.INTENTIONAL_DEVIATION, null,
                    "Font rendering is a client-only surface — P10-1 owner. Server-side config goes through /storynpcs config get|set."),
            new CommandParityEntry("target.packets.0001", "PacketAchievement",
                    "display an achievement toast to the client",
                    CommandParityStatus.INTENTIONAL_DEVIATION, null,
                    "Client toast surface — P10-1 owner. Quest completion events (QuestCompletionEvent) are the server-side semantic."),
            new CommandParityEntry("target.gui.0001", "GuiAchievement",
                    "achievement editor screen",
                    CommandParityStatus.INTENTIONAL_DEVIATION, null,
                    "Client screen — P10-1 owner. No achievement-definition family exists; quest completion is the progression analog."),
            new CommandParityEntry("target.packets.0082", "SPacketMenuClose",
                    "close a custom GUI menu",
                    CommandParityStatus.SUPPORTED, "OverlaySession close (P8-6)",
                    "Overlay sessions close on logout/server-stop and expose a per-session close; the packet transport is P1-3."),
            new CommandParityEntry("target.packets.0083", "SPacketMenuGet",
                    "fetch a custom GUI menu",
                    CommandParityStatus.SUPPORTED, "/storynpcs layout show <layout_id>",
                    "Detached CustomGuiLayout read through the canonical definition registry."),
            new CommandParityEntry("target.packets.0084", "SPacketMenuSave",
                    "save a custom GUI menu",
                    CommandParityStatus.SUPPORTED, "canonical layout save (YAML/API domain)",
                    "CustomGuiLayout writes go through the canonical save/delete ops with revision checks; authored layouts persist to YAML, the API domain facade exposes save to session holders.")
    );

    private AdminParityCatalog() {}

    public static List<CommandParityEntry> all() {
        return ENTRIES;
    }
}
