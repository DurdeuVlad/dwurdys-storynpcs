package com.storynpcs.item;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wand tooltips exist so a first-time admin can discover what each authoring tool does
 * without reading docs. Items cannot be constructed in a plain JUnit JVM (Item.Properties
 * pulls in NeoForge's loading context), so these tests pin the contract differently:
 * each wand must override appendHoverText, every "item.storynpcs.*.tooltip.*" key it
 * references must exist in en_us.json, and every translation must stay tooltip-length.
 */
class WandTooltipTest {

    private static final Pattern TOOLTIP_KEY = Pattern.compile("\"(item\\.storynpcs\\.[a-z_]+\\.tooltip\\.\\d+)\"");

    private static final Map<Class<? extends Item>, String> WANDS = new LinkedHashMap<>();

    static {
        WANDS.put(NpcWandItem.class, "NpcWandItem.java");
        WANDS.put(NpcClonerItem.class, "NpcClonerItem.java");
        WANDS.put(NpcPathItem.class, "NpcPathItem.java");
        WANDS.put(NpcMounterItem.class, "NpcMounterItem.java");
        WANDS.put(NpcDialogueWandItem.class, "NpcDialogueWandItem.java");
    }

    private static JsonObject langFile() {
        try (InputStream in = WandTooltipTest.class.getClassLoader()
                .getResourceAsStream("assets/storynpcs/lang/en_us.json")) {
            assertNotNull(in, "en_us.json must be on the classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            fail("Could not read en_us.json: " + e.getMessage());
            return new JsonObject();
        }
    }

    private static List<String> tooltipKeysInSource(String fileName) throws Exception {
        Path source = Path.of("src/main/java/com/storynpcs/item", fileName);
        assertTrue(Files.exists(source), "item source must exist: " + source);
        String text = Files.readString(source, StandardCharsets.UTF_8);
        Matcher m = TOOLTIP_KEY.matcher(text);
        return m.results().map(r -> r.group(1)).toList();
    }

    @Test
    @DisplayName("Every authoring wand overrides appendHoverText")
    void testWandsOverrideAppendHoverText() throws Exception {
        for (Class<? extends Item> wand : WANDS.keySet()) {
            wand.getDeclaredMethod("appendHoverText",
                    ItemStack.class, Item.TooltipContext.class, List.class, TooltipFlag.class);
        }
    }

    @Test
    @DisplayName("Every tooltip key referenced by a wand is translated in en_us.json")
    void testTooltipKeysTranslatedInEnUs() throws Exception {
        JsonObject lang = langFile();
        for (Map.Entry<Class<? extends Item>, String> entry : WANDS.entrySet()) {
            List<String> keys = tooltipKeysInSource(entry.getValue());
            assertFalse(keys.isEmpty(),
                    entry.getKey().getSimpleName() + " must reference at least one item.storynpcs.*.tooltip.* key");
            for (String key : keys) {
                assertTrue(lang.has(key),
                        entry.getKey().getSimpleName() + " emits '" + key + "' but en_us.json has no translation");
                String value = lang.get(key).getAsString();
                assertFalse(value.isBlank(), "translation for " + key + " must not be blank");
                assertTrue(value.length() <= 90,
                        "translation for " + key + " too long for narrow tooltips: " + value.length() + " chars");
            }
        }
    }

    @Test
    @DisplayName("Tooltip translations use translatable components, not literals")
    void testTooltipsAreTranslatableNotLiteral() throws Exception {
        // appendHoverText bodies must build translatable components — a literal
        // Component.literal(...) inside the method means untranslatable text shipped.
        for (Map.Entry<Class<? extends Item>, String> entry : WANDS.entrySet()) {
            Path source = Path.of("src/main/java/com/storynpcs/item", entry.getValue());
            String text = Files.readString(source, StandardCharsets.UTF_8);
            int methodStart = text.indexOf("appendHoverText");
            assertTrue(methodStart >= 0, entry.getKey().getSimpleName() + " must define appendHoverText");
            String body = text.substring(methodStart, Math.min(text.length(), methodStart + 1500));
            assertTrue(body.contains("Component.translatable"),
                    entry.getKey().getSimpleName() + " tooltip must use Component.translatable");
        }
    }
}
