package com.storynpcs.client.gui;

import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.quest.Quest;
import com.storynpcs.domain.quest.QuestObjective;
import com.storynpcs.domain.quest.QuestReward;
import com.storynpcs.editor.QuestEditorScreenModel;
import com.storynpcs.editor.QuestEditorScreenModel.Mode;
import com.storynpcs.editor.QuestEditorScreenModel.RowKind;
import com.storynpcs.network.ServerboundQuestDeletePayload;
import com.storynpcs.network.ServerboundQuestSavePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * In-game quest authoring surface (issue #24). LIST mode browses the synced
 * registry; EDIT mode mirrors the /storynpcs quest command surface — fields,
 * cycle buttons, and objective/reward rows — and routes every mutation through
 * {@link QuestEditorScreenModel} + the same server save/delete packets the
 * commands use.
 */
public class QuestEditorScreen extends Screen {

    private static final int ROW_H = 11;
    private static final int COLOR_OK = 0xFF4ADE80;
    private static final int COLOR_ERR = 0xFFF87171;
    private static final int COLOR_WARN = 0xFFEAB308;
    private static final int COLOR_LABEL = 0xFFA1A1AA;

    private final QuestEditorScreenModel model = new QuestEditorScreenModel();

    // LIST widgets
    private EditBox newIdField;
    private EditBox newTitleField;

    // EDIT widgets
    private EditBox idField;
    private EditBox titleField;
    private EditBox categoryField;
    private EditBox descField;
    private Button repeatButton;

    // Row-editor modal widgets
    private Button rowTypeButton;
    private EditBox rowTargetField;
    private EditBox rowCountField;
    private String rowType;

    private int rowScroll;

    public QuestEditorScreen(List<Quest> quests, String selectId) {
        super(Component.literal("Quest Editor"));
        model.loadQuests(quests);
        if (selectId != null && !selectId.isBlank()) {
            try {
                model.beginEdit(NamespacedId.of(selectId));
            } catch (Exception e) {
                model.setStatus("Malformed quest id: " + selectId, true);
            }
        }
    }

    public QuestEditorScreenModel getModel() { return model; }

    public void onSaveResult(boolean success, String message, List<Quest> refreshed) {
        model.onSaveResult(success, message, refreshed);
        // refresh the edit-fields if the server replaced our working copy view
        if (this.minecraft != null) rebuildWidgets();
    }

    @Override
    protected void init() {
        super.init();
        rowType = null;
        if (model.getMode() == Mode.LIST) {
            initListWidgets();
        } else if (model.getRowKind() != RowKind.NONE) {
            initRowEditorWidgets();
        } else {
            initEditWidgets();
        }
    }

    private void initListWidgets() {
        int bottom = this.height - 30;
        newIdField = new EditBox(this.font, 12, bottom, 170, 16, Component.literal("Quest id"));
        newIdField.setHint(Component.literal("storynpcs:quest_id"));
        newIdField.setMaxLength(64);
        addRenderableWidget(newIdField);

        newTitleField = new EditBox(this.font, 188, bottom, 130, 16, Component.literal("Title"));
        newTitleField.setHint(Component.literal("Title (optional)"));
        newTitleField.setMaxLength(64);
        addRenderableWidget(newTitleField);

        addRenderableWidget(Button.builder(Component.literal("§a+ New"), b -> {
            String raw = newIdField.getValue().trim();
            NamespacedId id;
            try {
                id = NamespacedId.of(raw);
            } catch (Exception e) {
                model.setStatus("Malformed quest id: '" + raw + "'", true);
                return;
            }
            if (model.beginNew(id, newTitleField.getValue().trim())) {
                rebuildWidgets();
            }
        }).bounds(322, bottom, 46, 16).build());

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(this.width - 50, 4, 42, 14).build());
    }

    private void initEditWidgets() {
        Quest q = model.getEditing();
        int y = 22;

        if (model.isEditingNew()) {
            idField = new EditBox(this.font, 60, y, 150, 14, Component.literal("Quest id"));
            idField.setValue(q.getId() != null ? q.getId().toString() : "");
            idField.setMaxLength(64);
            idField.setResponder(v -> model.setQuestId(v));
            addRenderableWidget(idField);
        }
        y += 18;

        titleField = new EditBox(this.font, 42, y, 160, 14, Component.literal("Title"));
        titleField.setValue(q.getTitle() != null ? q.getTitle() : "");
        titleField.setMaxLength(80);
        titleField.setResponder(v -> model.setTitle(v));
        addRenderableWidget(titleField);

        repeatButton = Button.builder(Component.literal("Repeat: " + (q.getRepeatType() != null ? q.getRepeatType() : Quest.RepeatType.ONCE)), b -> {
            Quest.RepeatType next = model.cycleRepeatType();
            repeatButton.setMessage(Component.literal("Repeat: " + next));
        }).bounds(206, y, 92, 14).build();
        addRenderableWidget(repeatButton);
        y += 18;

        categoryField = new EditBox(this.font, 60, y, 108, 14, Component.literal("Category"));
        categoryField.setValue(q.getCategory() != null ? q.getCategory() : "");
        categoryField.setMaxLength(48);
        categoryField.setResponder(v -> model.setCategory(v));
        addRenderableWidget(categoryField);

        descField = new EditBox(this.font, 172, y, this.width - 184, 14, Component.literal("Description"));
        descField.setValue(q.getDescription() != null ? q.getDescription() : "");
        descField.setMaxLength(256);
        descField.setResponder(v -> model.setDescription(v));
        addRenderableWidget(descField);

        int footer = this.height - 20;
        addRenderableWidget(Button.builder(Component.literal("§aSave"), b -> save())
                .bounds(12, footer, 70, 16).build());
        addRenderableWidget(Button.builder(Component.literal(model.isDeleteArmed() ? "§cSure?" : "§cDelete"), b -> {
            if (model.confirmDeleteClick()) {
                PacketDistributor.sendToServer(new ServerboundQuestDeletePayload(q.getId().toString()));
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

    private void initRowEditorWidgets() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        // Resolve initial type/target/count BEFORE building widgets
        Quest q = model.getEditing();
        String preTarget = "";
        String preCount = "1";
        if (model.getRowKind() == RowKind.OBJECTIVE) {
            if (model.getRowIndex() >= 0 && model.getRowIndex() < q.getObjectives().size()) {
                QuestObjective o = q.getObjectives().get(model.getRowIndex());
                rowType = o.getType().name();
                preTarget = o.getTarget() != null ? o.getTarget() : "";
                preCount = String.valueOf(o.getRequiredCount());
            } else {
                rowType = QuestObjective.Type.KILL_ENTITY.name();
            }
        } else {
            if (model.getRowIndex() >= 0 && model.getRowIndex() < q.getRewards().size()) {
                QuestReward r = q.getRewards().get(model.getRowIndex());
                rowType = r.getType().name();
                preTarget = r.getTarget() != null ? r.getTarget() : "";
                preCount = String.valueOf(r.getAmount());
            } else {
                rowType = QuestReward.Type.ITEM.name();
            }
        }

        rowTypeButton = Button.builder(Component.literal("Type: " + rowType), b -> {
            rowType = nextType(rowType, model.getRowKind());
            rowTypeButton.setMessage(Component.literal("Type: " + rowType));
        }).bounds(cx - 80, cy - 30, 160, 16).build();
        addRenderableWidget(rowTypeButton);

        rowTargetField = new EditBox(this.font, cx - 80, cy - 10, 160, 14, Component.literal("Target"));
        rowTargetField.setMaxLength(128);
        rowTargetField.setValue(preTarget);
        addRenderableWidget(rowTargetField);

        rowCountField = new EditBox(this.font, cx - 80, cy + 8, 60, 14, Component.literal("Count"));
        rowCountField.setMaxLength(6);
        rowCountField.setValue(preCount);
        addRenderableWidget(rowCountField);

        addRenderableWidget(Button.builder(Component.literal("§aOK"), b -> {
            int count;
            try {
                count = Math.max(1, Integer.parseInt(rowCountField.getValue().trim()));
            } catch (Exception e) {
                count = 1;
            }
            model.applyRowEdit(rowType, rowTargetField.getValue(), count);
            rebuildWidgets();
        }).bounds(cx - 80, cy + 26, 76, 16).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            model.cancelRowEdit();
            rebuildWidgets();
        }).bounds(cx + 4, cy + 26, 76, 16).build());
    }

    private static String nextType(String current, RowKind kind) {
        String[] vals = kind == RowKind.OBJECTIVE
                ? java.util.Arrays.stream(QuestObjective.Type.values()).map(Enum::name).toArray(String[]::new)
                : java.util.Arrays.stream(QuestReward.Type.values()).map(Enum::name).toArray(String[]::new);
        int i = 0;
        for (int j = 0; j < vals.length; j++) if (vals[j].equals(current)) { i = j; break; }
        return vals[(i + 1) % vals.length];
    }

    private void save() {
        String err = model.validateForSave();
        if (err != null) {
            model.setStatus(err, true);
            return;
        }
        model.setStatus("Saving...", false);
        PacketDistributor.sendToServer(new ServerboundQuestSavePayload(model.saveJson()));
    }

    // ---------- rendering ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, this.width, this.height, 0xE0101014);
        g.renderOutline(0, 0, this.width, this.height, 0xFF3F3F46);

        if (model.getMode() == Mode.LIST) {
            renderList(g, mouseX, mouseY);
        } else {
            renderEdit(g, mouseX, mouseY);
        }
        super.render(g, mouseX, mouseY, partial);

        if (model.getRowKind() != RowKind.NONE) {
            renderRowEditorOverlay(g);
        }
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, "§6StoryNPCs — Quests", 12, 8, 0xFFFFFFFF);
        List<Quest> quests = model.getQuests();
        int top = 22;
        int bottom = this.height - 36;
        int maxRows = Math.max(1, (bottom - top) / ROW_H);
        model.setListScroll(Math.min(model.getListScroll(), Math.max(0, quests.size() - maxRows)));

        if (quests.isEmpty()) {
            g.drawString(this.font, "§7No quests defined yet — create one below.", 12, top + 4, COLOR_LABEL);
        }
        g.enableScissor(0, top, this.width, bottom);
        for (int i = 0; i < maxRows && i + model.getListScroll() < quests.size(); i++) {
            Quest q = quests.get(i + model.getListScroll());
            int ry = top + i * ROW_H;
            boolean hover = mouseX >= 4 && mouseX <= this.width - 4 && mouseY >= ry && mouseY < ry + ROW_H;
            if (hover) {
                g.fill(4, ry, this.width - 4, ry + ROW_H, 0x33FFFFFF);
            }
            String line = "§e" + q.getId() + " §7— §f" + (q.getTitle() != null ? q.getTitle() : "");
            g.drawString(this.font, this.font.plainSubstrByWidth(line, this.width - 16), 10, ry + 2,
                    hover ? 0xFFFFFFFF : 0xFFD4D4D8);
        }
        g.disableScissor();
        if (quests.size() > maxRows) {
            g.drawString(this.font, "§7(scroll — " + quests.size() + " quests)", 12, bottom + 2, COLOR_LABEL);
        }
        g.drawString(this.font, "§7New quest id:", 12, bottom - 10, COLOR_LABEL);
        drawStatus(g, this.height - 12);
    }

    private void renderEdit(GuiGraphics g, int mouseX, int mouseY) {
        Quest q = model.getEditing();
        g.drawString(this.font, "§6Quest Editor: §e" + (model.isEditingNew() ? "(new)" : q.getId()), 12, 8, 0xFFFFFFFF);

        int y = 22;
        if (model.isEditingNew()) {
            g.drawString(this.font, "§7ID", 12, y + 3, COLOR_LABEL);
            y += 18;
        }
        g.drawString(this.font, "§7Title", 12, y + 3, COLOR_LABEL);
        y += 18;
        g.drawString(this.font, "§7Category", 12, y + 3, COLOR_LABEL);
        y += 20;

        int footer = this.height - 20;
        int rowsTop = y;
        int rowsBottom = footer - 14;

        // Objectives + rewards inside one scissored scroll region
        List<QuestObjective> objs = q.getObjectives();
        List<QuestReward> rews = q.getRewards();
        int totalRows = 1 + objs.size() + 1 + 1 + 1 + rews.size() + 1; // headers + rows + add-rows + spacer
        int maxVisible = Math.max(1, (rowsBottom - rowsTop) / ROW_H);
        rowScroll = Math.max(0, Math.min(rowScroll, Math.max(0, totalRows - maxVisible)));

        g.enableScissor(0, rowsTop, this.width, rowsBottom);
        int ry = rowsTop - rowScroll * ROW_H;

        g.drawString(this.font, "§bObjectives (" + objs.size() + ") §7— click a row to edit", 10, ry + 2, 0xFFFFFFFF);
        ry += ROW_H;
        for (int i = 0; i < objs.size(); i++) {
            ry = renderRow(g, "§f" + objs.get(i).getType() + " §7" + objs.get(i).getTarget()
                    + " §ex" + objs.get(i).getRequiredCount(), ry, mouseX, mouseY);
        }
        ry = renderRow(g, "§a+ add objective", ry, mouseX, mouseY);
        ry += ROW_H / 2;
        g.drawString(this.font, "§dRewards (" + rews.size() + ") §7— click a row to edit", 10, ry + 2, 0xFFFFFFFF);
        ry += ROW_H;
        for (int i = 0; i < rews.size(); i++) {
            ry = renderRow(g, "§f" + rews.get(i).getType() + " §7" + rews.get(i).getTarget()
                    + " §ex" + rews.get(i).getAmount(), ry, mouseX, mouseY);
        }
        ry = renderRow(g, "§a+ add reward", ry, mouseX, mouseY);
        g.disableScissor();

        if (totalRows > maxVisible) {
            g.drawString(this.font, "§7(scroll for more)", 12, footer - 11, COLOR_LABEL);
        }
        drawStatus(g, footer - 10);
    }

    private int renderRow(GuiGraphics g, String text, int ry, int mouseX, int mouseY) {
        boolean hover = mouseX >= 4 && mouseX <= this.width - 20 && mouseY >= ry && mouseY < ry + ROW_H;
        if (hover && ry >= 0) {
            g.fill(4, ry, this.width - 20, ry + ROW_H, 0x33FFFFFF);
        }
        g.drawString(this.font, this.font.plainSubstrByWidth(text, this.width - 60), 12, ry + 2,
                hover ? 0xFFFFFFFF : 0xFFD4D4D8);
        g.drawString(this.font, "§c×", this.width - 26, ry + 2, 0xFFF87171);
        return ry + ROW_H;
    }

    private void renderRowEditorOverlay(GuiGraphics g) {
        int cx = this.width / 2;
        int cy = this.height / 2;
        g.fill(cx - 95, cy - 42, cx + 95, cy + 48, 0xFF18181B);
        g.renderOutline(cx - 95, cy - 42, 190, 90, 0xFF3F3F46);
        g.drawString(this.font, model.getRowKind() == RowKind.OBJECTIVE ? "§bEdit Objective" : "§dEdit Reward",
                cx - 85, cy - 36, 0xFFFFFFFF);
    }

    private void drawStatus(GuiGraphics g, int y) {
        if (!model.getStatusMessage().isEmpty()) {
            String msg = this.font.plainSubstrByWidth(model.getStatusMessage(), this.width - 20);
            g.drawString(this.font, msg, 12, y, model.isStatusError() ? COLOR_ERR : COLOR_OK);
        }
    }

    // ---------- input ----------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (model.getRowKind() != RowKind.NONE) {
            return super.mouseClicked(mx, my, button); // modal owns clicks
        }
        if (model.getMode() == Mode.LIST) {
            return listClick(mx, my) || super.mouseClicked(mx, my, button);
        }
        return editClick(mx, my) || super.mouseClicked(mx, my, button);
    }

    private boolean listClick(double mx, double my) {
        List<Quest> quests = model.getQuests();
        int top = 22;
        int bottom = this.height - 36;
        int maxRows = Math.max(1, (bottom - top) / ROW_H);
        if (my < top || my >= bottom) return false;
        int idx = (int) ((my - top) / ROW_H) + model.getListScroll();
        if (idx < quests.size() && idx < model.getListScroll() + maxRows) {
            model.beginEdit(quests.get(idx).getId());
            rebuildWidgets();
            return true;
        }
        return false;
    }

    private boolean editClick(double mx, double my) {
        Quest q = model.getEditing();
        int y = 22 + (model.isEditingNew() ? 18 : 0) + 18 + 20;
        int footer = this.height - 20;
        int rowsTop = y;
        int rowsBottom = footer - 14;
        if (my < rowsTop || my >= rowsBottom) return false;

        List<QuestObjective> objs = q.getObjectives();
        List<QuestReward> rews = q.getRewards();
        int ry = rowsTop - rowScroll * ROW_H;
        ry += ROW_H; // objectives header

        for (int i = 0; i < objs.size(); i++) {
            if (my >= ry && my < ry + ROW_H) {
                if (mx >= this.width - 32) {
                    model.removeObjectiveRow(i);
                } else {
                    model.beginRowEdit(RowKind.OBJECTIVE, i);
                }
                rebuildWidgets();
                return true;
            }
            ry += ROW_H;
        }
        if (my >= ry && my < ry + ROW_H) { // + add objective row
            model.beginRowEdit(RowKind.OBJECTIVE, -1);
            rebuildWidgets();
            return true;
        }
        ry += ROW_H;
        ry += ROW_H / 2;
        ry += ROW_H; // rewards header
        for (int i = 0; i < rews.size(); i++) {
            if (my >= ry && my < ry + ROW_H) {
                if (mx >= this.width - 32) {
                    model.removeRewardRow(i);
                } else {
                    model.beginRowEdit(RowKind.REWARD, i);
                }
                rebuildWidgets();
                return true;
            }
            ry += ROW_H;
        }
        if (my >= ry && my < ry + ROW_H) { // + add reward row
            model.beginRowEdit(RowKind.REWARD, -1);
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
        rowScroll = Math.max(0, rowScroll - (int) Math.signum(dy));
        return true;
    }

    @Override
    public boolean isPauseScreen() { return true; }
}
