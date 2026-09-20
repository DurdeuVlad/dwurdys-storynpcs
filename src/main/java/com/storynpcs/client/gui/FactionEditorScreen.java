package com.storynpcs.client.gui;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.faction.Faction;
import com.storynpcs.editor.FactionEditorScreenModel;
import com.storynpcs.editor.FactionEditorScreenModel.Mode;
import com.storynpcs.network.ServerboundFactionDeletePayload;
import com.storynpcs.network.ServerboundFactionSavePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * In-game faction authoring surface (issue #25). LIST mode browses the synced
 * registry; EDIT mode mirrors the /storynpcs faction command surface — name,
 * defaultPoints, hostileThreshold, friendlyThreshold — routed through
 * {@link FactionEditorScreenModel} and the same service methods as the commands.
 */
public class FactionEditorScreen extends Screen {

    private static final int ROW_H = 11;
    private static final int COLOR_OK = 0xFF4ADE80;
    private static final int COLOR_ERR = 0xFFF87171;
    private static final int COLOR_LABEL = 0xFFA1A1AA;

    private final FactionEditorScreenModel model = new FactionEditorScreenModel();

    // LIST widgets
    private EditBox newIdField;
    private EditBox newNameField;

    // EDIT widgets
    private EditBox idField;
    private EditBox nameField;
    private EditBox defaultPointsField;
    private EditBox hostileField;
    private EditBox friendlyField;

    public FactionEditorScreen(List<Faction> factions, String selectId) {
        super(Component.literal("Faction Editor"));
        model.loadFactions(factions);
        if (selectId != null && !selectId.isBlank()) {
            try {
                model.beginEdit(NamespacedId.of(selectId));
            } catch (Exception e) {
                model.setStatus("Malformed faction id: " + selectId, true);
            }
        }
    }

    public FactionEditorScreenModel getModel() { return model; }

    public void onSaveResult(boolean success, String message, List<Faction> refreshed) {
        model.onSaveResult(success, message, refreshed);
        if (this.minecraft != null) rebuildWidgets();
    }

    @Override
    protected void init() {
        super.init();
        if (model.getMode() == Mode.LIST) {
            initListWidgets();
        } else {
            initEditWidgets();
        }
    }

    private void initListWidgets() {
        int bottom = this.height - 30;
        newIdField = new EditBox(this.font, 12, bottom, 170, 16, Component.literal("Faction id"));
        newIdField.setHint(Component.literal("storynpcs:faction_id"));
        newIdField.setMaxLength(64);
        addRenderableWidget(newIdField);

        newNameField = new EditBox(this.font, 188, bottom, 130, 16, Component.literal("Name"));
        newNameField.setHint(Component.literal("Name (optional)"));
        newNameField.setMaxLength(64);
        addRenderableWidget(newNameField);

        addRenderableWidget(Button.builder(Component.literal("§a+ New"), b -> {
            String raw = newIdField.getValue().trim();
            NamespacedId id;
            try {
                id = NamespacedId.of(raw);
            } catch (Exception e) {
                model.setStatus("Malformed faction id: '" + raw + "'", true);
                return;
            }
            if (model.beginNew(id, newNameField.getValue().trim())) {
                rebuildWidgets();
            }
        }).bounds(322, bottom, 46, 16).build());

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(this.width - 50, 4, 42, 14).build());
    }

    private void initEditWidgets() {
        Faction f = model.getEditing();
        int y = 22;

        if (model.isEditingNew()) {
            idField = new EditBox(this.font, 60, y, 170, 14, Component.literal("Faction id"));
            idField.setValue(f.getId() != null ? f.getId().toString() : "");
            idField.setMaxLength(64);
            idField.setResponder(v -> model.setFactionId(v));
            addRenderableWidget(idField);
            y += 18;
        }

        nameField = new EditBox(this.font, 60, y, 170, 14, Component.literal("Name"));
        nameField.setValue(f.getName() != null ? f.getName() : "");
        nameField.setMaxLength(64);
        nameField.setResponder(v -> model.setName(v));
        addRenderableWidget(nameField);
        y += 22;

        defaultPointsField = intField(96, y, f.getDefaultPoints(), v -> model.setDefaultPoints(v));
        hostileField = intField(96, y + 20, f.getHostileThreshold(), v -> model.setHostileThreshold(v));
        friendlyField = intField(96, y + 40, f.getFriendlyThreshold(), v -> model.setFriendlyThreshold(v));

        int footer = this.height - 20;
        addRenderableWidget(Button.builder(Component.literal("§aSave"), b -> save())
                .bounds(12, footer, 70, 16).build());
        addRenderableWidget(Button.builder(Component.literal(model.isDeleteArmed() ? "§cSure?" : "§cDelete"), b -> {
            if (model.confirmDeleteClick()) {
                PacketDistributor.sendToServer(new ServerboundFactionDeletePayload(f.getId().toString()));
                model.setStatus("Deleting...", false);
            } else {
                rebuildWidgets();
            }
        }).bounds(88, footer, 52, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
            model.backToList();
            rebuildWidgets();
        }).bounds(146, footer, 46, 16).build());
    }

    private EditBox intField(int x, int y, int initial, java.util.function.IntConsumer apply) {
        EditBox box = new EditBox(this.font, x, y, 70, 14, Component.literal("value"));
        box.setValue(String.valueOf(initial));
        box.setMaxLength(10);
        box.setResponder(v -> {
            try {
                apply.accept(Integer.parseInt(v.trim()));
            } catch (Exception ignored) { }
        });
        addRenderableWidget(box);
        return box;
    }

    private void save() {
        String err = model.validateForSave();
        if (err != null) {
            model.setStatus(err, true);
            return;
        }
        model.setStatus("Saving...", false);
        PacketDistributor.sendToServer(new ServerboundFactionSavePayload(model.saveJson()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, this.width, this.height, 0xE0101014);
        g.renderOutline(0, 0, this.width, this.height, 0xFF3F3F46);

        if (model.getMode() == Mode.LIST) {
            renderList(g, mouseX, mouseY);
        } else {
            renderEdit(g);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, "§6StoryNPCs — Factions", 12, 8, 0xFFFFFFFF);
        List<Faction> factions = model.getFactions();
        int top = 22;
        int bottom = this.height - 36;
        int maxRows = Math.max(1, (bottom - top) / ROW_H);
        model.setListScroll(Math.min(model.getListScroll(), Math.max(0, factions.size() - maxRows)));

        if (factions.isEmpty()) {
            g.drawString(this.font, "§7No factions defined yet — create one below.", 12, top + 4, COLOR_LABEL);
        }
        g.enableScissor(0, top, this.width, bottom);
        for (int i = 0; i < maxRows && i + model.getListScroll() < factions.size(); i++) {
            Faction f = factions.get(i + model.getListScroll());
            int ry = top + i * ROW_H;
            boolean hover = mouseX >= 4 && mouseX <= this.width - 4 && mouseY >= ry && mouseY < ry + ROW_H;
            if (hover) {
                g.fill(4, ry, this.width - 4, ry + ROW_H, 0x33FFFFFF);
            }
            String line = "§e" + f.getId() + " §7— §f" + (f.getName() != null ? f.getName() : "")
                    + " §7(hostile<" + f.getHostileThreshold() + " friendly>=" + f.getFriendlyThreshold() + ")";
            g.drawString(this.font, this.font.plainSubstrByWidth(line, this.width - 16), 10, ry + 2,
                    hover ? 0xFFFFFFFF : 0xFFD4D4D8);
        }
        g.disableScissor();
        if (factions.size() > maxRows) {
            g.drawString(this.font, "§7(scroll — " + factions.size() + " factions)", 12, bottom + 2, COLOR_LABEL);
        }
        g.drawString(this.font, "§7New faction id:", 12, bottom - 10, COLOR_LABEL);
        drawStatus(g, this.height - 12);
    }

    private void renderEdit(GuiGraphics g) {
        Faction f = model.getEditing();
        g.drawString(this.font, "§6Faction Editor: §e" + (model.isEditingNew() ? "(new)" : f.getId()), 12, 8, 0xFFFFFFFF);

        int y = 22;
        if (model.isEditingNew()) {
            g.drawString(this.font, "§7ID", 12, y + 3, COLOR_LABEL);
            y += 18;
        }
        g.drawString(this.font, "§7Name", 12, y + 3, COLOR_LABEL);
        y += 22;
        g.drawString(this.font, "§7Default Points", 12, y + 3, COLOR_LABEL);
        g.drawString(this.font, "§7Hostile below", 12, y + 23, COLOR_LABEL);
        g.drawString(this.font, "§7Friendly at/above", 12, y + 43, COLOR_LABEL);
        g.drawString(this.font, "§8New players start at Default; below Hostile → attacked on sight;", 12, y + 62, 0xFF71717A);
        g.drawString(this.font, "§8at/above Friendly → allied perks.", 12, y + 72, 0xFF71717A);

        int footer = this.height - 20;
        drawStatus(g, footer - 10);
    }

    private void drawStatus(GuiGraphics g, int y) {
        if (!model.getStatusMessage().isEmpty()) {
            String msg = this.font.plainSubstrByWidth(model.getStatusMessage(), this.width - 20);
            g.drawString(this.font, msg, 12, y, model.isStatusError() ? COLOR_ERR : COLOR_OK);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (model.getMode() == Mode.LIST) {
            return listClick(mx, my) || super.mouseClicked(mx, my, button);
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean listClick(double mx, double my) {
        List<Faction> factions = model.getFactions();
        int top = 22;
        int bottom = this.height - 36;
        int maxRows = Math.max(1, (bottom - top) / ROW_H);
        if (my < top || my >= bottom) return false;
        int idx = (int) ((my - top) / ROW_H) + model.getListScroll();
        if (idx < factions.size() && idx < model.getListScroll() + maxRows) {
            model.beginEdit(factions.get(idx).getId());
            rebuildWidgets();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (model.getMode() == Mode.LIST) {
            model.setListScroll(model.getListScroll() - (int) Math.signum(dy));
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean isPauseScreen() { return true; }
}
