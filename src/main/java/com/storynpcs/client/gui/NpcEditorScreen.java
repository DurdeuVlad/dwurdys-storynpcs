package com.storynpcs.client.gui;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.npc.NpcAi;
import com.storynpcs.domain.npc.NpcDefinition;
import com.storynpcs.domain.npc.NpcDefinitionSerde;
import com.storynpcs.domain.npc.TacticalStance;
import com.storynpcs.network.ServerboundNpcSavePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public class NpcEditorScreen extends Screen {

    private final NpcDefinition definition;
    private EditBox nameField;
    private EditBox titleField;
    private EditBox skinField;
    private EditBox factionField;
    private EditBox healthField;
    private EditBox damageField;
    private EditBox speedField;
    private EditBox rangeField;

    private Button movementButton;
    private Button stanceButton;
    private NpcAi.MovementType currentMovement;
    private TacticalStance currentStance;

    private String statusMessage = "";
    private int statusColor = 0xFF4ADE80;

    public NpcEditorScreen(NpcDefinition definition) {
        super(Component.literal("NPC Editor: " + (definition.getId() != null ? definition.getId().toString() : "Unknown")));
        this.definition = definition;
        this.currentMovement = definition.getAi() != null ? definition.getAi().getMovementType() : NpcAi.MovementType.STANDING;
        this.currentStance = definition.getAi() != null ? definition.getAi().getTacticalStance() : TacticalStance.GUARD;
    }

    public NpcDefinition getDefinition() {
        return definition;
    }

    public void onSaveResult(boolean success, String message) {
        this.statusMessage = message;
        this.statusColor = success ? 0xFF4ADE80 : 0xFFF87171;
    }

    @Override
    protected void init() {
        super.init();

        int panelWidth = Math.min(400, this.width - 8);
        int panelHeight = Math.min(245, this.height - 8);
        int startX = (this.width - panelWidth) / 2;
        int startY = Math.max(4, (this.height - panelHeight) / 2);

        int leftX = startX + 12;
        int rightX = startX + 210;
        int colWidth = Math.min(178, panelWidth - 222);

        // Left Column: Identity & Appearance
        nameField = new EditBox(this.font, leftX, startY + 34, colWidth, 16, Component.literal("Name"));
        nameField.setValue(definition.getDisplay() != null ? definition.getDisplay().getName() : "StoryNPC");
        nameField.setMaxLength(64);
        this.addRenderableWidget(nameField);

        titleField = new EditBox(this.font, leftX, startY + 62, colWidth, 16, Component.literal("Title"));
        titleField.setValue(definition.getDisplay() != null && definition.getDisplay().getTitle() != null ? definition.getDisplay().getTitle() : "");
        titleField.setMaxLength(64);
        this.addRenderableWidget(titleField);

        skinField = new EditBox(this.font, leftX, startY + 90, colWidth, 16, Component.literal("Skin"));
        skinField.setValue(definition.getDisplay() != null && definition.getDisplay().getSkinTexture() != null ? definition.getDisplay().getSkinTexture() : "minecraft:textures/entity/player/wide/steve.png");
        skinField.setMaxLength(128);
        this.addRenderableWidget(skinField);

        // Skin presets row
        int presetW = 42;
        this.addRenderableWidget(Button.builder(Component.literal("Steve"), b -> skinField.setValue("minecraft:textures/entity/player/wide/steve.png"))
                .bounds(leftX, startY + 108, presetW, 14).build());
        this.addRenderableWidget(Button.builder(Component.literal("Alex"), b -> skinField.setValue("minecraft:textures/entity/player/wide/alex.png"))
                .bounds(leftX + 45, startY + 108, presetW, 14).build());
        this.addRenderableWidget(Button.builder(Component.literal("Guard"), b -> skinField.setValue("storynpcs:textures/entity/guard.png"))
                .bounds(leftX + 90, startY + 108, presetW, 14).build());
        this.addRenderableWidget(Button.builder(Component.literal("Villager"), b -> skinField.setValue("minecraft:textures/entity/villager/villager.png"))
                .bounds(leftX + 135, startY + 108, presetW, 14).build());

        factionField = new EditBox(this.font, leftX, startY + 134, colWidth, 16, Component.literal("Faction"));
        factionField.setValue(definition.getFactionId() != null ? definition.getFactionId().toString() : "");
        factionField.setMaxLength(64);
        this.addRenderableWidget(factionField);

        // Right Column: Stats & AI
        healthField = new EditBox(this.font, rightX, startY + 34, 85, 16, Component.literal("Health"));
        healthField.setValue(String.format("%.1f", definition.getStats() != null ? definition.getStats().getMaxHealth() : 20.0));
        this.addRenderableWidget(healthField);

        damageField = new EditBox(this.font, rightX + 93, startY + 34, 85, 16, Component.literal("Damage"));
        damageField.setValue(String.format("%.1f", definition.getStats() != null ? definition.getStats().getAttackDamage() : 5.0));
        this.addRenderableWidget(damageField);

        speedField = new EditBox(this.font, rightX, startY + 62, 85, 16, Component.literal("Speed"));
        speedField.setValue(String.format("%.2f", definition.getStats() != null ? definition.getStats().getMovementSpeed() : 0.25));
        this.addRenderableWidget(speedField);

        rangeField = new EditBox(this.font, rightX + 93, startY + 62, 85, 16, Component.literal("Range"));
        rangeField.setValue(String.valueOf(definition.getAi() != null ? definition.getAi().getWalkingRange() : 10));
        this.addRenderableWidget(rangeField);

        // Movement button (cycles STANDING -> WANDERING -> PATHING)
        movementButton = Button.builder(Component.literal("Move: " + currentMovement.name()), b -> {
            NpcAi.MovementType[] vals = NpcAi.MovementType.values();
            currentMovement = vals[(currentMovement.ordinal() + 1) % vals.length];
            movementButton.setMessage(Component.literal("Move: " + currentMovement.name()));
        }).bounds(rightX, startY + 90, colWidth, 16).build();
        this.addRenderableWidget(movementButton);

        // Stance button (cycles GUARD -> PASSIVE -> NEUTRAL -> AGGRESSIVE -> EVASIVE)
        stanceButton = Button.builder(Component.literal("Stance: " + currentStance.name()), b -> {
            TacticalStance[] vals = TacticalStance.values();
            currentStance = vals[(currentStance.ordinal() + 1) % vals.length];
            stanceButton.setMessage(Component.literal("Stance: " + currentStance.name()));
        }).bounds(rightX, startY + 108, colWidth, 16).build();
        this.addRenderableWidget(stanceButton);

        // Dialogue editor shortcut button
        String dialogueId = definition.getDialogueId() != null ? definition.getDialogueId().toString() : "";
        String dialogueBtnText = dialogueId.isEmpty() ? "§e+ Create Dialogue Graph" : "§aEdit Dialogue: §f" + dialogueId;
        this.addRenderableWidget(Button.builder(Component.literal(dialogueBtnText), b -> {
            saveCurrentState();
            if (Minecraft.getInstance().player != null) {
                if (dialogueId.isEmpty()) {
                    String scaffoldId = (definition.getId() != null ? definition.getId().getPath() : "npc") + "_dialogue";
                    Minecraft.getInstance().player.connection.sendCommand("storynpcs dialogue create " + scaffoldId);
                } else {
                    Minecraft.getInstance().player.connection.sendCommand("storynpcs dialogue edit " + dialogueId);
                }
            }
        }).bounds(rightX, startY + 134, colWidth, 16).build());

        // Behavior rules sub-screen (issue #26)
        int ruleCount = definition.getRules() != null ? definition.getRules().size() : 0;
        this.addRenderableWidget(Button.builder(
                Component.literal("§bRules (" + ruleCount + ")"), b -> {
                    saveCurrentState();
                    Minecraft.getInstance().setScreen(new NpcRulesScreen(definition));
                }).bounds(rightX, startY + 154, colWidth, 14).build());

        // Bottom Action Bar — anchored to the panel bottom so it stays inside
        // the frame when the panel shrinks on short windows
        int actionY = startY + panelHeight - 52;
        this.addRenderableWidget(Button.builder(Component.literal("§aSave Changes"), b -> {
            saveCurrentState();
            statusMessage = "Saving...";
            statusColor = 0xFFEAB308;
            PacketDistributor.sendToServer(new ServerboundNpcSavePayload(
                    definition.getId().toString(),
                    NpcDefinitionSerde.toJson(definition)
            ));
        }).bounds(startX + 12, actionY, 140, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal("§cDespawn NPC"), b -> {
            if (Minecraft.getInstance().player != null && definition.getId() != null) {
                Minecraft.getInstance().player.connection.sendCommand("storynpcs npc despawn " + definition.getId());
                this.onClose();
            }
        }).bounds(startX + 160, actionY, 110, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> {
            this.onClose();
        }).bounds(startX + 280, actionY, 108, 18).build());
    }

    private void saveCurrentState() {
        if (definition.getDisplay() == null) definition.setDisplay(new com.storynpcs.domain.npc.NpcDisplay());
        if (definition.getStats() == null) definition.setStats(new com.storynpcs.domain.npc.NpcStats());
        if (definition.getAi() == null) definition.setAi(new com.storynpcs.domain.npc.NpcAi());

        definition.getDisplay().setName(nameField.getValue().trim());
        definition.getDisplay().setTitle(titleField.getValue().trim());
        definition.getDisplay().setSkinTexture(skinField.getValue().trim());

        String fac = factionField.getValue().trim();
        if (!fac.isEmpty()) {
            try {
                definition.setFactionId(NamespacedId.of(fac));
            } catch (Exception ignored) {}
        } else {
            definition.setFactionId(null);
        }

        try {
            definition.getStats().setMaxHealth(Math.max(1.0, Double.parseDouble(healthField.getValue().trim())));
        } catch (Exception ignored) {}

        try {
            definition.getStats().setAttackDamage(Math.max(0.0, Double.parseDouble(damageField.getValue().trim())));
        } catch (Exception ignored) {}

        try {
            definition.getStats().setMovementSpeed(Math.max(0.01, Double.parseDouble(speedField.getValue().trim())));
        } catch (Exception ignored) {}

        try {
            definition.getAi().setWalkingRange(Math.max(0, Integer.parseInt(rangeField.getValue().trim())));
        } catch (Exception ignored) {}

        definition.getAi().setMovementType(currentMovement);
        definition.getAi().setTacticalStance(currentStance);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int panelWidth = Math.min(400, this.width - 8);
        int panelHeight = Math.min(245, this.height - 8);
        int startX = (this.width - panelWidth) / 2;
        int startY = Math.max(4, (this.height - panelHeight) / 2);

        // Dark background and border
        graphics.fill(startX, startY, startX + panelWidth, startY + panelHeight, 0xF0121216);
        graphics.renderOutline(startX, startY, panelWidth, panelHeight, 0xFF3F3F46);

        // Header bar
        graphics.fill(startX, startY, startX + panelWidth, startY + 22, 0xFF18181B);
        graphics.drawString(this.font, "§6StoryNPCs — NPC Editor: §e" + (definition.getId() != null ? definition.getId() : "New"), startX + 12, startY + 7, 0xFFFFFFFF);

        // Labels Left Column
        graphics.drawString(this.font, "§7Display Name", startX + 12, startY + 26, 0xFFA1A1AA);
        graphics.drawString(this.font, "§7Title / Role", startX + 12, startY + 54, 0xFFA1A1AA);
        graphics.drawString(this.font, "§7Skin Texture Path", startX + 12, startY + 82, 0xFFA1A1AA);
        graphics.drawString(this.font, "§7Faction ID", startX + 12, startY + 126, 0xFFA1A1AA);

        // Labels Right Column
        graphics.drawString(this.font, "§7Health / Damage", startX + 210, startY + 26, 0xFFA1A1AA);
        graphics.drawString(this.font, "§7Speed / Range", startX + 210, startY + 54, 0xFFA1A1AA);
        graphics.drawString(this.font, "§7AI Movement & Stance", startX + 210, startY + 82, 0xFFA1A1AA);
        graphics.drawString(this.font, "§7Dialogue Graph", startX + 210, startY + 126, 0xFFA1A1AA);

        // Status message — inside the panel, below the action row
        if (!statusMessage.isEmpty()) {
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(statusMessage, panelWidth - 24),
                    startX + 12, startY + panelHeight - 14, statusColor);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
