package com.storynpcs.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.storynpcs.migration.FieldMappingRegistry.Action;
import com.storynpcs.migration.FieldMappingRegistry.FieldMapping;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

/**
 * #194: CustomNPCs text-export (SNBT clone file) → StoryNPCs {@code npc}
 * document translator.
 *
 * <p>Real CustomNPCs "Unofficial" NeoForge exports write clone/dialog/quest
 * files as pretty-printed SNBT ({@code "Key": 0b}-style NBT text). This
 * translator maps a verified field subset from the committed sample corpus
 * ({@code src/test/resources/fixtures/customnpcs/}) into a normalized
 * StoryNPCs YAML document. Every observed source field is reported: mapped
 * fields carry a migration note; unmapped fields are reported as UNSUPPORTED
 * rather than silently dropped.</p>
 *
 * <p>Scope: clone (NPC) documents only. CNPC {@code dialogs/}, {@code quests/},
 * and binary {@code *.dat} stores are explicitly re-scoped — their families
 * quarantine in the importer until their mappings are defined.</p>
 */
public final class CnpcTextExportTranslator {

    /** Per-field translation outcome for the import report. */
    public record Translation(String yaml, List<FieldMapping> mappings) {}

    /** Transient vanilla entity state that must never become definition content. */
    private static final Set<String> TRANSIENT = Set.of(
            "AbsorptionAmount", "Air", "Age", "Bred", "Brain", "CanPickUpLoot",
            "DeathTime", "FallDistance", "FallFlying", "Fire", "ForgeCaps",
            "ForgeData", "NeoForgeData", "ForcedAge", "Health", "HurtByTimestamp",
            "HurtTime", "InLove", "Invulnerable", "KilledTime", "LeftHanded",
            "Motion", "NoGravity", "OnGround", "PersistenceRequired", "PortalCooldown",
            "Pos", "Rotation", "SleepingX", "SleepingY", "SleepingZ", "UUID",
            "Attributes", "HandItems", "ArmorItems", "ArmorDropChances",
            "HandDropChances", "DropChances", "DropChance", "LootMode",
            "MovingPos", "MovingState", "MoveState", "SpawnCycle");

    /** Recognized CNPC surfaces intentionally re-scoped for a later mapping pass. */
    private static final Map<String, String> RESCOPED = Map.ofEntries(
            Map.entry("TraderSold", "trade stock — item stack mapping not yet defined"),
            Map.entry("TraderCurrency", "trade currency — item stack mapping not yet defined"),
            Map.entry("TraderMarket", "trade market link — not yet defined"),
            Map.entry("TraderIgnoreDamage", "trade compare rule — not yet defined"),
            Map.entry("TraderIgnoreNBT", "trade compare rule — not yet defined"),
            Map.entry("NPCDialogOptions", "dialogue links — CNPC dialog corpus mapping not yet defined"),
            Map.entry("Role", "CNPC role id — role model differs; explicit mapping required"),
            Map.entry("RoleDialog", "role dialogue link — not yet defined"),
            Map.entry("RoleOptions", "role options — not yet defined"),
            Map.entry("RoleOptionTexts", "role options — not yet defined"),
            Map.entry("RoleQuestId", "role quest link — not yet defined"),
            Map.entry("NpcJob", "CNPC job id — job model differs; explicit mapping required"),
            Map.entry("Scripts", "CNPC script hooks — StoryNPCs scripts are namespaced ids"),
            Map.entry("ScriptEnabled", "CNPC script hooks — not yet defined"),
            Map.entry("ScriptLanguage", "CNPC script hooks — not yet defined"));

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private CnpcTextExportTranslator() {}

    /**
     * Translate one CNPC clone SNBT document into a StoryNPCs {@code npc} YAML
     * document. Never throws on unexpected content — unmapped fields are
     * reported as UNSUPPORTED mappings.
     */
    public static Translation translateClone(String snbt) throws CommandSyntaxException {
        CompoundTag tag = TagParser.parseTag(snbt == null ? "" : snbt);
        if (tag == null || tag.isEmpty()) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS
                    .dispatcherParseException().create("empty clone document");
        }
        List<FieldMapping> mappings = new ArrayList<>();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", 1);

        String name = tag.getString("Name");
        String slug = slugify(name);
        doc.put("id", "cnpc:" + slug);

        Map<String, Object> display = new LinkedHashMap<>();
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Object> melee = new LinkedHashMap<>();

        for (String key : tag.getAllKeys()) {
            switch (key) {
                case "Name" -> {
                    display.put("name", name);
                    mapped(mappings, key, "display.name");
                }
                case "Title" -> {
                    display.put("title", tag.getString(key));
                    mapped(mappings, key, "display.title");
                }
                case "Texture" -> {
                    display.put("skinTexture", tag.getString(key));
                    mapped(mappings, key, "display.skinTexture");
                }
                case "SkinUrl" -> {
                    if (tag.getBoolean("UsingSkinUrl") && !tag.getString(key).isBlank()) {
                        display.put("skinUrl", tag.getString(key));
                        display.put("skinSource", "URL");
                        mapped(mappings, key, "display.skinUrl + display.skinSource=URL");
                    } else {
                        unsupported(mappings, key, "empty/unused skin URL");
                    }
                }
                case "UsingSkinUrl" -> unsupported(mappings, key,
                        "consumed with SkinUrl when active");
                case "ShowName" -> {
                    display.put("showNameMode", tag.getInt(key));
                    mapped(mappings, key, "display.showNameMode");
                }
                case "MaxHealth" -> {
                    stats.put("maxHealth", tag.getFloat(key));
                    mapped(mappings, key, "stats.maxHealth");
                }
                case "MoveSpeed" -> {
                    // CNPC speed scale is 0–10+ (5 ≈ vanilla walk); StoryNPCs
                    // stores the MC attribute scale (0–5). Normalized by /20
                    // so CNPC 5 → 0.25, matching the vanilla default.
                    stats.put("movementSpeed",
                            Math.min(tag.getFloat(key) / 20.0f, 5.0f));
                    mapped(mappings, key,
                            "stats.movementSpeed — CNPC scale normalized /20 (5 → 0.25)");
                }
                case "AttackStrenght" -> {
                    stats.put("attackDamage", tag.getFloat(key));
                    mapped(mappings, key, "stats.attackDamage — CNPC key spelling preserved");
                }
                case "AttackRange" -> {
                    melee.put("attackRange", tag.getFloat(key));
                    mapped(mappings, key, "stats.melee.attackRange");
                }
                case "AttackSpeed" -> {
                    melee.put("attackDelayTicks", tag.getInt(key));
                    mapped(mappings, key, "stats.melee.attackDelayTicks — CNPC tick interval");
                }
                case "AggroRange" -> {
                    stats.put("aggroRange", tag.getInt(key));
                    mapped(mappings, key, "stats.aggroRange");
                }
                case "HealthRegen" -> {
                    stats.put("healthRegenPerSecond", tag.getFloat(key));
                    mapped(mappings, key, "stats.healthRegenPerSecond — CNPC rate preserved raw");
                }
                case "CombatRegen" -> {
                    stats.put("combatRegenPerSecond", tag.getFloat(key));
                    mapped(mappings, key, "stats.combatRegenPerSecond — CNPC rate preserved raw");
                }
                case "RespawnTime" -> {
                    stats.put("respawnTimeSeconds", tag.getInt(key));
                    mapped(mappings, key, "stats.respawnTimeSeconds — CNPC units preserved raw");
                }
                case "FactionID" -> {
                    doc.put("factionId", "cnpc:faction_" + tag.getInt(key));
                    mapped(mappings, key, "factionId — numeric CNPC faction id namespaced");
                }
                default -> {
                    if (TRANSIENT.contains(key)) {
                        unsupported(mappings, key, "transient entity runtime state — never imported");
                    } else if (RESCOPED.containsKey(key)) {
                        unsupported(mappings, key, RESCOPED.get(key));
                    } else {
                        unsupported(mappings, key,
                                "no verified StoryNPCs equivalent — reported, not dropped");
                    }
                }
            }
        }
        doc.put("display", display);
        if (!melee.isEmpty()) stats.put("melee", melee);
        doc.put("stats", stats);
        try {
            return new Translation(YAML.writeValueAsString(doc), List.copyOf(mappings));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("YAML emission failed", e);
        }
    }

    private static void mapped(List<FieldMapping> mappings, String cnpcKey, String target) {
        mappings.add(new FieldMapping("cnpc:" + cnpcKey, Action.MIGRATED,
                "translates to " + target, "#194/cnpc-clone"));
    }

    private static void unsupported(List<FieldMapping> mappings, String cnpcKey, String note) {
        mappings.add(new FieldMapping("cnpc:" + cnpcKey, Action.UNSUPPORTED, note,
                "#194/cnpc-clone"));
    }

    private static String slugify(String name) {
        String slug = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
        return slug.isBlank() ? "unnamed_clone" : slug;
    }
}
