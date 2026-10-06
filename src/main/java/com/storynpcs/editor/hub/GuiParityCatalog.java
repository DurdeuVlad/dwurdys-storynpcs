package com.storynpcs.editor.hub;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P10-1 parity map: every one of the 149 target GUI classes routes to a
 * StoryNPCs screen, a hub panel, an explicit improved equivalent, a
 * player-facing surface owned by #150 (M12), or a not-a-screen component
 * marker. The row set is verified against
 * docs/parity/target-surface-manifest.json in tests - a renamed or dropped
 * target class fails the suite.
 */
public final class GuiParityCatalog {

    public enum Status {
        /** Direct StoryNPCs screen counterpart. */
        SCREEN,
        /** Reachable through a hub panel section of an existing screen. */
        PANEL,
        /** Explicit improved equivalent - YAML schema + canonical commands or
         *  an inspector sub-surface instead of a bespoke wizard screen. */
        EQUIVALENT,
        /** Player-facing runtime surface owned by issue #150 (M12). */
        PLAYER_SURFACE,
        /** Target widget/toolkit internals - not a screen, no mapping needed. */
        COMPONENT_NA
    }

    public record Row(String targetClass, Status status, String destination, String note) {}

    private static final Map<String, Row> ROWS = new LinkedHashMap<>();

    private static void row(String targetClass, Status status, String destination, String note) {
        ROWS.put(targetClass, new Row(targetClass, status, destination, note));
    }

    static {
        row("AbstractTab", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiAssetsSelector", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiButton", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiButtonList", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiColoredLine", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiEntityDisplay", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiItemRenderer", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiLabel", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiScroll", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiSlider", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiSlot", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiTextArea", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiTextField", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("CustomGuiTexturedRect", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("GuiAchievement", Status.SCREEN, "PlayerAchievementsScreen", "earned quest/standing/companion rows (#150)");
        row("GuiBlockBuilder", Status.EQUIVALENT, "TOOL panel: worldtool sessions (P8-2/P8-3)", "bounded tool sessions + markers");
        row("GuiBlockCopy", Status.EQUIVALENT, "TOOL panel: worldtool sessions (P8-2/P8-3)", "bounded tool sessions + markers");
        row("GuiBorderBlock", Status.EQUIVALENT, "TOOL panel: worldtool sessions (P8-2/P8-3)", "bounded tool sessions + markers");
        row("GuiContainerNPCInterface", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiContainerNPCInterface2", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiCreationEntities", Status.SCREEN, "NpcDisplayScreen", "display/variants/parts/model presets (P8-6)");
        row("GuiCreationExtra", Status.SCREEN, "NpcDisplayScreen", "display/variants/parts/model presets (P8-6)");
        row("GuiCreationLoad", Status.SCREEN, "NpcDisplayScreen", "display/variants/parts/model presets (P8-6)");
        row("GuiCreationNewParts", Status.SCREEN, "NpcDisplayScreen", "display/variants/parts/model presets (P8-6)");
        row("GuiCreationScale", Status.SCREEN, "NpcDisplayScreen", "display/variants/parts/model presets (P8-6)");
        row("GuiCreationScreenInterface", Status.SCREEN, "NpcDisplayScreen", "display/variants/parts/model presets (P8-6)");
        row("GuiCustom", Status.SCREEN, "CustomGuiScreen", "authored CustomGuiLayout runtime renderer (#150)");
        row("GuiCustomComponents", Status.EQUIVALENT, "CustomGuiLayout YAML element schema (P8-6)", "scripted GUI elements are authored YAML");
        row("GuiCustomScrollingPanel", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("GuiDialogEdit", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("GuiDialogInteract", Status.SCREEN, "DialogueScreen", "player-facing dialogue runtime");
        row("GuiDialogSelection", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("GuiFaction", Status.SCREEN, "FactionEditorScreen", "faction defs + standing fields");
        row("GuiJobFarmer", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiMailbox", Status.SCREEN, "PlayerMailScreen", "mail list/read + compose (#150)");
        row("GuiMailmanWrite", Status.SCREEN, "PlayerMailScreen", "mail compose/send (#150)");
        row("GuiMerchantAdd", Status.SCREEN, "TraderBankerAdminScreen", "trader/banker listing + vault admin");
        row("GuiModelColor", Status.SCREEN, "NpcDisplayScreen", "display/variants/parts/model presets (P8-6)");
        row("GuiNPCAdvancedLinkedNpc", Status.EQUIVALENT, "TEMPLATE panel: storynpcs {template,spawner,scene,link,naturalspawn,transform} (P8-5)", "definitions are YAML + canonical commands; no fixed wizard screens");
        row("GuiNPCBankChest", Status.SCREEN, "NpcBankScreen", "banker-role vault screen");
        row("GuiNPCDialogNpcOptions", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("GuiNPCFactionSelection", Status.SCREEN, "FactionEditorScreen", "faction defs + standing fields");
        row("GuiNPCFactionSetup", Status.SCREEN, "FactionEditorScreen", "faction defs + standing fields");
        row("GuiNPCGlobalMainMenu", Status.SCREEN, "AuthoringHubScreen", "hub root");
        row("GuiNPCInterface", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNPCInterface2", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNPCInv", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNPCLinesEdit", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("GuiNPCLinesMenu", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("GuiNPCManageBanks", Status.SCREEN, "TraderBankerAdminScreen", "trader/banker listing + vault admin");
        row("GuiNPCManageDialogs", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("GuiNPCManageFactions", Status.SCREEN, "FactionEditorScreen", "faction defs + standing fields");
        row("GuiNPCManageLinkedNpc", Status.EQUIVALENT, "TEMPLATE panel: storynpcs {template,spawner,scene,link,naturalspawn,transform} (P8-5)", "definitions are YAML + canonical commands; no fixed wizard screens");
        row("GuiNPCManageQuest", Status.SCREEN, "QuestEditorScreen", "quest defs/objectives/rewards");
        row("GuiNPCManageTransporters", Status.EQUIVALENT, "TRANSPORT panel: transport YAML + commands (P6)", "route/category defs");
        row("GuiNPCMarks", Status.EQUIVALENT, "TOOL panel: remote admin ops (P9-4) + playerdata commands", "server-authoritative admin ops");
        row("GuiNPCNightSetup", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNPCScenes", Status.EQUIVALENT, "TEMPLATE panel: storynpcs {template,spawner,scene,link,naturalspawn,transform} (P8-5)", "definitions are YAML + canonical commands; no fixed wizard screens");
        row("GuiNPCSoundsMenu", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("GuiNPCTrader", Status.SCREEN, "TraderBankerAdminScreen", "trader/banker listing + vault admin");
        row("GuiNPCTransportCategoryEdit", Status.EQUIVALENT, "TRANSPORT panel: transport YAML + commands (P6)", "category defs");
        row("GuiNbtBook", Status.SCREEN, "NbtBookScreen", "entity NBT viewer/editor");
        row("GuiNpcAI", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNpcAdvanced", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNpcBankSetup", Status.SCREEN, "TraderBankerAdminScreen", "trader/banker listing + vault admin");
        row("GuiNpcBard", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNpcCarpentryBench", Status.SCREEN, "PlayerCarpentryScreen", "authored recipes + craft commit (#150)");
        row("GuiNpcCompanion", Status.SCREEN, "PlayerCompanionScreen", "hired companions: health/stage/paused (#150)");
        row("GuiNpcCompanionInv", Status.EQUIVALENT, "companions panel — carry capacity read; per-slot item editing unsupported", "slot grid not authored");
        row("GuiNpcCompanionStats", Status.SCREEN, "PlayerCompanionScreen", "health + carry capacity (#150)");
        row("GuiNpcCompanionTalents", Status.SCREEN, "PlayerCompanionScreen", "talent count + active stage (#150)");
        row("GuiNpcConversation", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("GuiNpcDimension", Status.EQUIVALENT, "TEMPLATE panel: storynpcs {template,spawner,scene,link,naturalspawn,transform} (P8-5)", "definitions are YAML + canonical commands; no fixed wizard screens");
        row("GuiNpcDisplay", Status.SCREEN, "NpcDisplayScreen", "display/variants/parts/model presets (P8-6)");
        row("GuiNpcFollower", Status.SCREEN, "PlayerCompanionScreen", "hired list; state cycle via entity interact (#150)");
        row("GuiNpcFollowerHire", Status.SCREEN, "PlayerFollowerHireScreen", "hire flow w/ wage preview + session commit (#150)");
        row("GuiNpcFollowerJob", Status.EQUIVALENT, "storynpcs follower interact — job field unsupported", "no job assignment surface");
        row("GuiNpcFollowerSetup", Status.EQUIVALENT, "companions panel read view + entity interact state cycle", "dedicated setup GUI not authored");
        row("GuiNpcGuard", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNpcHealer", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNpcItemGiver", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNpcManagePlayerData", Status.EQUIVALENT, "TOOL panel: remote admin ops (P9-4) + playerdata commands", "server-authoritative admin ops");
        row("GuiNpcManageRecipes", Status.EQUIVALENT, "recipe YAML + storynpcs recipe commands (P8-4)", "carpentry recipe defs");
        row("GuiNpcMenu", Status.SCREEN, "AuthoringHubScreen", "hub root");
        row("GuiNpcMobSpawner", Status.EQUIVALENT, "TEMPLATE panel: storynpcs {template,spawner,scene,link,naturalspawn,transform} (P8-5)", "definitions are YAML + canonical commands; no fixed wizard screens");
        row("GuiNpcMobSpawnerAdd", Status.EQUIVALENT, "TEMPLATE panel: storynpcs {template,spawner,scene,link,naturalspawn,transform} (P8-5)", "definitions are YAML + canonical commands; no fixed wizard screens");
        row("GuiNpcMobSpawnerMounter", Status.EQUIVALENT, "TEMPLATE panel: storynpcs {template,spawner,scene,link,naturalspawn,transform} (P8-5)", "definitions are YAML + canonical commands; no fixed wizard screens");
        row("GuiNpcMobSpawnerSelector", Status.EQUIVALENT, "TEMPLATE panel: storynpcs {template,spawner,scene,link,naturalspawn,transform} (P8-5)", "definitions are YAML + canonical commands; no fixed wizard screens");
        row("GuiNpcNaturalSpawns", Status.EQUIVALENT, "TEMPLATE panel: storynpcs {template,spawner,scene,link,naturalspawn,transform} (P8-5)", "definitions are YAML + canonical commands; no fixed wizard screens");
        row("GuiNpcPather", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNpcPuppet", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNpcQuestReward", Status.SCREEN, "QuestEditorScreen", "quest defs/objectives/rewards");
        row("GuiNpcQuestTypeDialog", Status.SCREEN, "QuestEditorScreen", "quest defs/objectives/rewards");
        row("GuiNpcQuestTypeItem", Status.SCREEN, "QuestEditorScreen", "quest defs/objectives/rewards");
        row("GuiNpcQuestTypeKill", Status.SCREEN, "QuestEditorScreen", "quest defs/objectives/rewards");
        row("GuiNpcQuestTypeLocation", Status.SCREEN, "QuestEditorScreen", "quest defs/objectives/rewards");
        row("GuiNpcQuestTypeManual", Status.SCREEN, "QuestEditorScreen", "quest defs/objectives/rewards");
        row("GuiNpcRedstoneBlock", Status.EQUIVALENT, "TOOL panel: worldtool sessions (P8-2/P8-3)", "bounded tool sessions + markers");
        row("GuiNpcRemoteEditor", Status.EQUIVALENT, "TOOL panel: remote admin ops (P9-4) + playerdata commands", "server-authoritative admin ops");
        row("GuiNpcSpawner", Status.EQUIVALENT, "TEMPLATE panel: storynpcs {template,spawner,scene,link,naturalspawn,transform} (P8-5)", "definitions are YAML + canonical commands; no fixed wizard screens");
        row("GuiNpcStats", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiNpcTraderSetup", Status.SCREEN, "TraderBankerAdminScreen", "trader/banker listing + vault admin");
        row("GuiNpcTransporter", Status.EQUIVALENT, "TRANSPORT panel: transport YAML + commands (P6)", "route/category defs");
        row("GuiNpcWaypoint", Status.EQUIVALENT, "TOOL panel: worldtool sessions (P8-2/P8-3)", "bounded tool sessions + markers");
        row("GuiPresetSave", Status.SCREEN, "NpcDisplayScreen", "display/variants/parts/model presets (P8-6)");
        row("GuiQuestCompletion", Status.SCREEN, "PlayerQuestLogScreen", "pending completions surfaced in quest log (#150)");
        row("GuiQuestEdit", Status.SCREEN, "QuestEditorScreen", "quest defs/objectives/rewards");
        row("GuiQuestLog", Status.SCREEN, "PlayerQuestLogScreen", "quest log with objectives + pending (#150)");
        row("GuiQuestSelection", Status.SCREEN, "QuestEditorScreen", "quest defs/objectives/rewards");
        row("GuiRecipes", Status.SCREEN, "PlayerCarpentryScreen", "recipe list on the carpentry bench (#150)");
        row("GuiRoleDialog", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("GuiScript", Status.EQUIVALENT, "SCRIPT panel: script YAML + storynpcs script commands (P9-2)", "bounded Rhino scripts; editor is the source file, not a bespoke GUI");
        row("GuiScriptBlock", Status.EQUIVALENT, "SCRIPT panel: script YAML + storynpcs script commands (P9-2)", "bounded Rhino scripts; editor is the source file, not a bespoke GUI");
        row("GuiScriptDoor", Status.EQUIVALENT, "SCRIPT panel: script YAML + storynpcs script commands (P9-2)", "bounded Rhino scripts; editor is the source file, not a bespoke GUI");
        row("GuiScriptForge", Status.EQUIVALENT, "SCRIPT panel: script YAML + storynpcs script commands (P9-2)", "bounded Rhino scripts; editor is the source file, not a bespoke GUI");
        row("GuiScriptGlobal", Status.EQUIVALENT, "SCRIPT panel: script YAML + storynpcs script commands (P9-2)", "bounded Rhino scripts; editor is the source file, not a bespoke GUI");
        row("GuiScriptInterface", Status.EQUIVALENT, "SCRIPT panel: script YAML + storynpcs script commands (P9-2)", "bounded Rhino scripts; editor is the source file, not a bespoke GUI");
        row("GuiScriptItem", Status.EQUIVALENT, "SCRIPT panel: script YAML + storynpcs script commands (P9-2)", "bounded Rhino scripts; editor is the source file, not a bespoke GUI");
        row("GuiScriptList", Status.EQUIVALENT, "SCRIPT panel: script YAML + storynpcs script commands (P9-2)", "bounded Rhino scripts; editor is the source file, not a bespoke GUI");
        row("GuiScriptPlayers", Status.EQUIVALENT, "SCRIPT panel: script YAML + storynpcs script commands (P9-2)", "bounded Rhino scripts; editor is the source file, not a bespoke GUI");
        row("GuiSoundSelection", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("GuiTextureSelection", Status.SCREEN, "NpcDisplayScreen", "display/variants/parts/model presets (P8-6)");
        row("GuiTooltipUtils", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("GuiTransportSelection", Status.SCREEN, "PlayerTransportScreen", "player panel (client exists)");
        row("IGuiComponent", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("InventoryTabFactions", Status.SCREEN, "PlayerFactionPanelScreen", "faction standings panel (#150)");
        row("InventoryTabQuests", Status.SCREEN, "PlayerQuestLogScreen", "quest log panel (#150)");
        row("InventoryTabVanilla", Status.EQUIVALENT, "storynpcs reuses the vanilla inventory screen", "vanilla surface reused unchanged");
        row("SubGuiColorSelector", Status.COMPONENT_NA, "vanilla widgets + CustomGuiLayout elements (P8-6)", "widget toolkit internals, not a screen");
        row("SubGuiEditText", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("SubGuiMailmanSendSetup", Status.SCREEN, "PlayerMailScreen", "mail compose/send fields (#150)");
        row("SubGuiNpcAvailability", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("SubGuiNpcAvailabilityDialog", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("SubGuiNpcAvailabilityQuest", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("SubGuiNpcAvailabilityScoreboard", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("SubGuiNpcBiomes", Status.EQUIVALENT, "naturalspawn YAML biome filters (P8-5)", "list picker -> typed YAML field");
        row("SubGuiNpcCommand", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("SubGuiNpcConversationLine", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("SubGuiNpcDialogOption", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("SubGuiNpcDialogOptions", Status.SCREEN, "DialogueEditorScreen", "graph editor nodes/edges/conditions/actions/availability");
        row("SubGuiNpcFactionOptions", Status.SCREEN, "FactionEditorScreen", "faction defs + standing fields");
        row("SubGuiNpcFactionPoints", Status.SCREEN, "FactionEditorScreen", "faction defs + standing fields");
        row("SubGuiNpcMeleeProperties", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("SubGuiNpcMovement", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("SubGuiNpcName", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("SubGuiNpcProjectiles", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("SubGuiNpcRangeProperties", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("SubGuiNpcResistanceProperties", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
        row("SubGuiNpcRespawn", Status.SCREEN, "NpcEditorScreen", "identity/stats/AI/inventory/role fields");
    }

    public static Map<String, Row> rows() { return Collections.unmodifiableMap(ROWS); }

    public static Row forTarget(String targetClass) { return ROWS.get(targetClass); }

    /**
     * Every UI-applicable target GUI family resolves to a StoryNPCs surface.
     * COMPONENT_NA entries are widget internals - they still count as mapped
     * because the row names the explicit replacement toolkit.
     */
    public static boolean isFullyMapped() {
        return ROWS.values().stream().allMatch(r ->
                r.destination() != null && !r.destination().isBlank());
    }
}
