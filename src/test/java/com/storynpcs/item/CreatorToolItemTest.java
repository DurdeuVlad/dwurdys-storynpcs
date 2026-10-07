package com.storynpcs.item;

import net.minecraft.world.item.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #197 / #195: {@code StoryNpcEntity#mobInteract} must return PASS while a
 * creator tool is held so the interact falls through to
 * {@code Item#interactLivingEntity} (wand editor, dialogue-wand graph editor,
 * cloner, pather, mounter, teleporter, remover, soulstone, NBT book). Item
 * instances cannot be constructed in a plain JUnit JVM (Item.Properties pulls
 * in NeoForge's loading context — see {@link WandTooltipTest}), so the
 * contract is pinned at the type level: every creator tool implements the
 * {@link CreatorToolItem} marker, ordinary items do not, and the entity's
 * mobInteract consults the marker before it can consume the interact.
 */
class CreatorToolItemTest {

    private static final List<Class<? extends Item>> CREATOR_TOOLS = List.of(
            NpcWandItem.class,
            NpcDialogueWandItem.class,
            NpcClonerItem.class,
            NpcPathItem.class,
            NpcMounterItem.class,
            NpcTeleporterItem.class,
            NpcRemoverItem.class,
            NpcSoulStoneItem.class,
            NbtBookItem.class);

    @Test
    @DisplayName("Every creator tool implements the CreatorToolItem marker")
    void testToolClassesAreMarked() {
        for (Class<? extends Item> tool : CREATOR_TOOLS) {
            assertTrue(CreatorToolItem.class.isAssignableFrom(tool),
                    tool.getSimpleName() + " must implement CreatorToolItem so "
                            + "mobInteract can PASS the interact through to interactLivingEntity");
        }
    }

    @Test
    @DisplayName("Ordinary items are not classified as creator tools")
    void testPlainItemsAreNotMarked() {
        assertFalse(CreatorToolItem.class.isAssignableFrom(Item.class),
                "a plain Item must not short-circuit NPC interaction");
        // Canonical held items players right-click NPCs with must keep the
        // normal dialogue/trade/nothing-to-say paths.
        assertFalse(CreatorToolItem.class.isAssignableFrom(net.minecraft.world.item.NameTagItem.class));
        assertFalse(CreatorToolItem.class.isAssignableFrom(net.minecraft.world.item.BlockItem.class));
    }

    @Test
    @DisplayName("mobInteract consults the creator-tool marker before consuming the interact")
    void testEntityPassesToolsThroughBeforeClientSuccess() throws Exception {
        Path source = Path.of("src/main/java/com/storynpcs/entity/StoryNpcEntity.java");
        assertTrue(Files.exists(source), "entity source must exist: " + source);
        String text = Files.readString(source);

        int method = text.indexOf("InteractionResult mobInteract");
        assertTrue(method >= 0, "mobInteract must exist");

        // The creator-tool PASS check must appear inside mobInteract, after
        // the hand filter, and before the client-side SUCCESS that would
        // otherwise consume the interact before the item sees it. The check
        // must be the positive form `if (...isCreatorTool(...)` — an inverted
        // `!isCreatorTool` would silently block every tool, so pin the exact
        // statement shape rather than the bare call site.
        int check = text.indexOf("if (com.storynpcs.item.CreatorToolItem.isCreatorTool(", method);
        assertTrue(check > method,
                "mobInteract must return PASS for CreatorToolItem so interactLivingEntity fires");

        // VULN-12: the alive/spectator/line-of-sight gate must run before the
        // tool PASS, or tools could reach through walls.
        int losGate = text.indexOf("!this.hasLineOfSight(player)", method);
        assertTrue(losGate > method && losGate < check,
                "the VULN-12 line-of-sight gate must precede the creator-tool PASS");

        int clientSideSuccess = text.indexOf("isClientSide", method);
        assertTrue(clientSideSuccess > 0 && check < clientSideSuccess,
                "the creator-tool PASS must precede the client-side SUCCESS");
    }
}
