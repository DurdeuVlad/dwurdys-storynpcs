package com.storynpcs.client.gui;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcAi;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.domain.npc.TacticalStance;
import com.storynpcs.editor.PayloadBoundRequestId;
import com.storynpcs.network.ServerboundNpcSavePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Primary NPC editor (two-column form). Migrated onto the shared chrome
 * (#206): the columns are proportional to the content band instead of a fixed
 * 400px canvas, and Save/Despawn/Close live in the adaptive footer — the
 * crowded bottom action row flagged in D4 is gone.
 */
public class NpcEditorScreen extends UiScreen {

    private static final int LABEL_H = 8;
    private static final int FIELD_PITCH = 26;

    private final NpcDefinition definition;
    private EditBox nameField;
    private EditBox titleField;
    private EditBox skinField;
    private EditBox factionField;
    private EditBox healthField;
    private EditBox damageField;
    private EditBox speedField;
    private EditBox rangeField;

    private NpcAi.MovementType currentMovement;
    private TacticalStance currentStance;

    /** Field text survives rebuilds (window resize re-runs init()); applied
        to the definition only via saveCurrentState on commit/navigation. */
    private final java.util.Map<String, String> draft = new java.util.HashMap<>();
    /** Bottom of the right column — abilities summary hides when it would collide. */
    private int rightColumnBottomY;

    private long expectedRevision;
    private final PayloadBoundRequestId saveRequestId = new PayloadBoundRequestId();

    public NpcEditorScreen(NpcDefinition definition) {
        this(definition, 0L);
    }

    public NpcEditorScreen(NpcDefinition definition, long expectedRevision) {
        super(Component.literal("NPC Editor — "
                + (definition.getId() != null ? definition.getId().toString() : "New")));
        this.definition = definition;
        this.expectedRevision = Math.max(0L, expectedRevision);
        this.currentMovement = definition.getAi() != null ? definition.getAi().getMovementType() : NpcAi.MovementType.STANDING;
        this.currentStance = definition.getAi() != null ? definition.getAi().getTacticalStance() : TacticalStance.GUARD;
    }

    public NpcDefinition getDefinition() {
        return definition;
    }

    public void onSaveResult(UUID requestId, boolean success, String message, long revision) {
        if (!saveRequestId.matchesCurrent(requestId)) return;
        echo(Component.literal(message)
                .withColor(success ? UiTheme.TEXT : UiTheme.DANGER), 4000);
        // The response revision is authoritative on success AND on rejection
        // (the server echoes the current token) — never guess with ++.
        expectedRevision = Math.max(0L, revision);
        saveRequestId.acknowledge(requestId);
    }

    @Override
    protected void initContent() {
        int gap = UiTheme.PAD_L;
        int colW = Math.max(60, (contentWidth() - gap) / 2);
        int leftX = contentLeft();
        int rightX = contentLeft() + colW + gap;

        // ── Left column: identity & appearance ─────────────────────────────
        int y = contentTop();
        nameField = field(leftX, y, colW - 50, "Display Name",
                definition.getDisplay() != null ? definition.getDisplay().getName() : "StoryNPC", 64);

        // #123: server-authoritative name randomize — the server generates
        // from its loaded dictionaries ("*" = any culture) and applies it
        // through the canonical save; the field refreshes on reopen and the
        // generated name echoes in the save-result message.
        addRenderableWidget(Button.builder(Component.literal("🎲"), b -> {
            saveCurrentState();
            String npcId = npcId();
            if (npcId == null) {
                echo(Component.literal("Cannot save — NPC has no id.")
                        .withColor(UiTheme.DANGER), 3000);
                return;
            }
            overrideStatus(Component.literal("Randomizing name...").withColor(UiTheme.ACCENT));
            String submittedJson = NpcDefinitionSerde.toJson(definition);
            UUID requestId = saveRequestId.forPayload(submittedJson);
            PacketDistributor.sendToServer(new ServerboundNpcSavePayload(
                    npcId, submittedJson, expectedRevision, requestId, "*"));
        }).bounds(leftX + colW - 44, y + LABEL_H + 1, 44, UiTheme.BUTTON_H).build());

        y += FIELD_PITCH;
        titleField = field(leftX, y, colW, "Title / Role",
                definition.getDisplay() != null && definition.getDisplay().getTitle() != null
                        ? definition.getDisplay().getTitle() : "", 64);

        y += FIELD_PITCH;
        skinField = field(leftX, y, colW, "Skin Texture Path",
                definition.getDisplay() != null && definition.getDisplay().getSkinTexture() != null
                        ? definition.getDisplay().getSkinTexture()
                        : "minecraft:textures/entity/player/wide/steve.png", 128);

        y += FIELD_PITCH;
        int presetW = Math.max(30, (colW - 3 * UiTheme.PAD_XS) / 4);
        String[][] presets = {
                {"Steve", "minecraft:textures/entity/player/wide/steve.png"},
                {"Alex", "minecraft:textures/entity/player/wide/alex.png"},
                {"Guard", "storynpcs:textures/entity/guard.png"},
                {"Villager", "minecraft:textures/entity/villager/villager.png"},
        };
        for (int i = 0; i < presets.length; i++) {
            final String skin = presets[i][1];
            addRenderableWidget(Button.builder(Component.literal(presets[i][0]),
                    b -> skinField.setValue(skin))
                    .bounds(leftX + i * (presetW + UiTheme.PAD_XS), y, presetW, UiTheme.BUTTON_H).build());
        }

        y += UiTheme.BUTTON_H + UiTheme.PAD_S;
        factionField = field(leftX, y, colW, "Faction ID",
                definition.getFactionId() != null ? definition.getFactionId().toString() : "", 64);

        // ── Right column: stats, AI, navigation ────────────────────────────
        y = contentTop();
        int half = (colW - UiTheme.PAD_S) / 2;
        healthField = field(rightX, y, half, "Health",
                String.format("%.1f", definition.getStats() != null ? definition.getStats().getMaxHealth() : 20.0), 16);
        damageField = field(rightX + half + UiTheme.PAD_S, y, half, "Damage",
                String.format("%.1f", definition.getStats() != null ? definition.getStats().getAttackDamage() : 5.0), 16);

        y += FIELD_PITCH;
        speedField = field(rightX, y, half, "Speed",
                String.format("%.2f", definition.getStats() != null ? definition.getStats().getMovementSpeed() : 0.25), 16);
        rangeField = field(rightX + half + UiTheme.PAD_S, y, half, "Range",
                String.valueOf(definition.getAi() != null ? definition.getAi().getWalkingRange() : 10), 16);

        y += FIELD_PITCH;
        addRenderableWidget(Button.builder(Component.literal("Move: " + currentMovement.name()), b -> {
            NpcAi.MovementType[] vals = NpcAi.MovementType.values();
            currentMovement = vals[(currentMovement.ordinal() + 1) % vals.length];
            b.setMessage(Component.literal("Move: " + currentMovement.name()));
        }).bounds(rightX, y, colW, UiTheme.BUTTON_H).build());

        y += UiTheme.BUTTON_H + UiTheme.PAD_S;
        addRenderableWidget(Button.builder(Component.literal("Stance: " + currentStance.name()), b -> {
            TacticalStance[] vals = TacticalStance.values();
            currentStance = vals[(currentStance.ordinal() + 1) % vals.length];
            b.setMessage(Component.literal("Stance: " + currentStance.name()));
        }).bounds(rightX, y, colW, UiTheme.BUTTON_H).build());

        // Sub-screens — the whole form is applied to the definition first, so
        // navigation never loses un-saved edits.
        y += UiTheme.BUTTON_H + UiTheme.PAD_M;
        addRenderableWidget(Button.builder(Component.literal("§bDisplay & Render"), b -> {
            saveCurrentState();
            Minecraft.getInstance().setScreen(new NpcDisplayScreen(definition, expectedRevision));
        }).bounds(rightX, y, colW, UiTheme.BUTTON_H).build());

        y += UiTheme.BUTTON_H + UiTheme.PAD_XS;
        int ruleCount = definition.getRules() != null ? definition.getRules().size() : 0;
        addRenderableWidget(Button.builder(Component.literal("§bRules (" + ruleCount + ")"), b -> {
            saveCurrentState();
            Minecraft.getInstance().setScreen(new NpcRulesScreen(definition, expectedRevision));
        }).bounds(rightX, y, colW, UiTheme.BUTTON_H).build());

        y += UiTheme.BUTTON_H + UiTheme.PAD_XS;
        addRenderableWidget(Button.builder(Component.literal("§bTrade & Bank"), b -> {
            saveCurrentState();
            Minecraft.getInstance().setScreen(new TraderBankerAdminScreen(definition, expectedRevision));
        }).bounds(rightX, y, colW, UiTheme.BUTTON_H).build());

        // Dialogue editor shortcut
        y += UiTheme.BUTTON_H + UiTheme.PAD_XS;
        String dialogueId = definition.getDialogueId() != null ? definition.getDialogueId().toString() : "";
        String dialogueBtnText = dialogueId.isEmpty()
                ? "§e+ Create Dialogue Graph" : "§aEdit Dialogue: §f" + dialogueId;
        addRenderableWidget(Button.builder(Component.literal(dialogueBtnText), b -> {
            saveCurrentState();
            if (Minecraft.getInstance().player != null) {
                if (dialogueId.isEmpty()) {
                    String scaffoldId = (definition.getId() != null ? definition.getId().getPath() : "npc") + "_dialogue";
                    Minecraft.getInstance().player.connection.sendCommand("storynpcs dialogue create " + scaffoldId);
                } else {
                    Minecraft.getInstance().player.connection.sendCommand("storynpcs dialogue edit " + dialogueId);
                }
            }
        }).bounds(rightX, y, colW, UiTheme.BUTTON_H).build());

        rightColumnBottomY = y + UiTheme.BUTTON_H;

        // ── Footer actions (D4: adaptive right-aligned row, no fixed crowding)
        addFooterAction(Component.literal("Save Changes"), b -> {
            String warning = saveCurrentState();
            String npcId = npcId();
            if (npcId == null) {
                echo(Component.literal("Cannot save — NPC has no id.")
                        .withColor(UiTheme.DANGER), 3000);
                return;
            }
            if (warning != null) {
                echo(Component.literal(warning).withColor(UiTheme.DANGER), 4000);
            } else {
                overrideStatus(Component.literal("Saving...").withColor(UiTheme.ACCENT));
            }
            String submittedJson = NpcDefinitionSerde.toJson(definition);
            UUID requestId = saveRequestId.forPayload(submittedJson);
            PacketDistributor.sendToServer(new ServerboundNpcSavePayload(
                    npcId, submittedJson, expectedRevision, requestId));
        });
        addFooterAction(Component.literal("Despawn NPC"), b -> {
            if (Minecraft.getInstance().player != null && definition.getId() != null) {
                Minecraft.getInstance().player.connection.sendCommand("storynpcs npc despawn " + definition.getId());
                this.onClose();
            }
        });
        addFooterAction(Component.literal("Close"), b -> this.onClose());
    }

    private String npcId() {
        return definition.getId() != null ? definition.getId().toString() : null;
    }

    private EditBox field(int x, int y, int w, String label, String value, int maxLength) {
        // The label renders in renderContent via the row's y — drawn once, not per widget.
        EditBox box = new EditBox(this.font, x, y + LABEL_H + 1, Math.max(20, w),
                UiTheme.BUTTON_H, Component.literal(label));
        box.setMaxLength(maxLength);
        // Draft wins over the definition — resize rebuilds must not lose typing.
        box.setValue(draft.getOrDefault(label, value));
        box.setResponder(v -> draft.put(label, v));
        addRenderableWidget(box);
        return box;
    }

    /** @return a user-facing warning for the first rejected field, or null. */
    private String saveCurrentState() {
        if (definition.getDisplay() == null) definition.setDisplay(new com.storynpcs.domain.npc.NpcDisplay());
        if (definition.getStats() == null) definition.setStats(new com.storynpcs.domain.npc.NpcStats());
        if (definition.getAi() == null) definition.setAi(new com.storynpcs.domain.npc.NpcAi());

        definition.getDisplay().setName(nameField.getValue().trim());
        definition.getDisplay().setTitle(titleField.getValue().trim());
        definition.getDisplay().setSkinTexture(skinField.getValue().trim());

        String warning = null;
        String fac = factionField.getValue().trim();
        if (!fac.isEmpty()) {
            try {
                definition.setFactionId(NamespacedId.of(fac));
            } catch (Exception e) {
                warning = "Ignored invalid Faction ID — kept previous value.";
            }
        } else {
            definition.setFactionId(null);
        }

        try {
            definition.getStats().setMaxHealth(Math.max(1.0, Double.parseDouble(healthField.getValue().trim())));
        } catch (Exception e) {
            if (warning == null) warning = "Ignored invalid Health — kept previous value.";
        }

        try {
            definition.getStats().setAttackDamage(Math.max(0.0, Double.parseDouble(damageField.getValue().trim())));
        } catch (Exception e) {
            if (warning == null) warning = "Ignored invalid Damage — kept previous value.";
        }

        try {
            definition.getStats().setMovementSpeed(Math.max(0.01, Double.parseDouble(speedField.getValue().trim())));
        } catch (Exception e) {
            if (warning == null) warning = "Ignored invalid Speed — kept previous value.";
        }

        try {
            definition.getAi().setWalkingRange(Math.max(0, Integer.parseInt(rangeField.getValue().trim())));
        } catch (Exception e) {
            if (warning == null) warning = "Ignored invalid Range — kept previous value.";
        }

        definition.getAi().setMovementType(currentMovement);
        definition.getAi().setTacticalStance(currentStance);
        return warning;
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int gap = UiTheme.PAD_L;
        int colW = Math.max(60, (contentWidth() - gap) / 2);
        int leftX = contentLeft();
        int rightX = contentLeft() + colW + gap;

        int y = contentTop();
        label(g, leftX, y, "Display Name");
        y += FIELD_PITCH; label(g, leftX, y, "Title / Role");
        y += FIELD_PITCH; label(g, leftX, y, "Skin Texture Path");
        y += FIELD_PITCH + UiTheme.BUTTON_H + UiTheme.PAD_S;
        label(g, leftX, y, "Faction ID");

        y = contentTop();
        label(g, rightX, y, "Health / Damage");
        y += FIELD_PITCH; label(g, rightX, y, "Speed / Range");

        // Authored combat abilities (#147) — read-only summary; editing is
        // via /storynpcs npc ability add|set|remove or the YAML definition.
        var abilities = definition.getAbilities();
        String abilityLine = abilities.isEmpty()
                ? "none — /storynpcs npc ability add"
                : abilities.stream()
                        .map(a -> a.getType() != null ? a.getType().name() : "?")
                        .collect(java.util.stream.Collectors.joining(", "));
        int abilitiesY = contentBottom() - 18;
        // At short windows the summary would collide with the column — drop
        // it rather than overdraw the dialogue button or the footer.
        if (abilitiesY < rightColumnBottomY + UiTheme.PAD_S) {
            return;
        }
        label(g, rightX, abilitiesY, "Abilities");
        g.drawString(this.font, this.font.plainSubstrByWidth("§f" + abilityLine, colW),
                rightX, abilitiesY + LABEL_H + 2, UiTheme.TEXT);
    }

    private void label(GuiGraphics g, int x, int y, String text) {
        g.drawString(this.font, "§7" + text, x, y, UiTheme.TEXT_MUTED);
    }
}
