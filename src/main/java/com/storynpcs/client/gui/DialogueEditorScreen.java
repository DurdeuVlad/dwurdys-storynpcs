package com.storynpcs.client.gui;

import com.storynpcs.domain.common.DiagnosticError;
import com.storynpcs.domain.dialogue.DialogueAction;
import com.storynpcs.domain.dialogue.DialogueCondition;
import com.storynpcs.editor.DialogueEditorScreenModel;
import com.storynpcs.editor.PayloadBoundRequestId;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.editor.DialogueGraphLayout;
import com.storynpcs.editor.VisualEdge;
import com.storynpcs.editor.VisualNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class DialogueEditorScreen extends Screen {

    private static final int INSPECTOR_W = 200;
    private static final int INSPECTOR_MAX_H = 240;
    /** Smallest inspector height that still clears every widget pair (issue #20). */
    private static final int INSPECTOR_MIN_H = 154;
    /** Second toolbar row drops the inspector start below both rows. */
    private static final int TOOLBAR_H = 62;
    private static final int INSPECTOR_Y = TOOLBAR_H + 6;
    /** How long a delete button stays in its "Confirm?" state before reverting. */
    private static final long DELETE_CONFIRM_MS = 4000;

    // ── Palette (issue #209) ────────────────────────────────────────────────
    // Chrome surfaces (toolbar, inspector, panes, status) consume UiTheme
    // tokens. The graph canvas is the documented bespoke surface — its colors
    // live here as named constants so the distinction stays grep-able.
    private static final int CANVAS_BG             = UiTheme.FIELD_BG;
    private static final int GRID_LINE             = 0x1AFFFFFF;
    private static final int NODE_BODY             = withAlpha(UiTheme.SURFACE_BG, 0xEE);
    private static final int NODE_BODY_SELECTED    = withAlpha(UiTheme.SURFACE_RAISED, 0xEE);
    private static final int NODE_BORDER           = UiTheme.TEXT_DISABLED;
    private static final int NODE_BORDER_ENTRY     = 0xFF22C55E;
    private static final int CANVAS_SELECTED       = 0xFFFACC15;
    private static final int EDGE_NORMAL           = UiTheme.SUCCESS;
    private static final int EDGE_CYCLIC           = 0xFFFFA500;
    private static final int EDGE_LABEL_BG         = 0xCC000000;
    private static final int EDGE_LABEL_BG_SELECTED = 0xCC3B2A00;
    // Chrome fills whose translucency differs from the token's baked alpha.
    private static final int TOOLBAR_BG            = withAlpha(UiTheme.SURFACE_HEADER, 0xDD);
    private static final int INSPECTOR_BG          = withAlpha(UiTheme.SURFACE_BG, 0xEE);
    private static final int PANE_BG               = withAlpha(UiTheme.FIELD_BG, 0xF0);

    /** Replaces a token's alpha channel so surfaces can stay translucent. */
    private static int withAlpha(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }


    private final DialogueEditorScreenModel model;
    private boolean isPanning = false;
    private double lastMouseX;
    private double lastMouseY;

    /** Node-text editor inside the inspector — visible only while a node is selected. */
    private MultiLineEditBox nodeTextBox;
    private EditBox speakerBox;
    private EditBox soundBox;
    private EditBox nodeIdBox;
    private EditBox nodeTextKeyBox;
    private EditBox nodeXBox;
    private EditBox nodeYBox;
    /** Inline warning under the sound field — null when the id is blank or resolves. */
    private String soundWarning;
    /** Guards programmatic setValue during selection sync so it doesn't mark the graph dirty. */
    private boolean syncingInspector;

    private Button deleteNodeButton;
    private Button deleteEdgeButton;
    private Button setEntryButton;
    private EditBox questActionBox;
    /** Dialogue-inspector field (shown when nothing is selected): localization title key. */
    private EditBox dialogueTitleKeyBox;
    /** Edge inspector widgets (P5-3): option text, once-only, localization key, target, condition/action tables. */
    private EditBox edgeTextBox;
    private Button edgeOnceOnlyButton;
    private EditBox edgeTextKeyBox;
    private EditBox edgeTargetBox;
    private EditBox condTypeBox;
    private EditBox condTargetBox;
    private EditBox condOperatorBox;
    private EditBox condValueBox;
    private EditBox actTypeBox;
    private EditBox actTargetBox;
    private EditBox actValueBox;
    private Button condAddButton;
    private Button condRemoveButton;
    private Button actAddButton;
    private Button actRemoveButton;
    private int selectedConditionIndex = -1;
    private int selectedActionIndex = -1;
    /** Edge inspector page: 0 fields, 1 conditions, 2 actions. */
    private int edgePage;
    private Button edgePageFieldsButton;
    private Button edgePageCondsButton;
    private Button edgePageActsButton;
    /** Inline warning under the quest field — format errors only; unknown quests are rejected at save. */
    private String questWarning;

    private EditBox searchBox;
    private boolean previewOpen;
    private boolean diagnosticsOpen;
    private List<DiagnosticError> diagnostics = List.of();
    /** Y-coordinate → node id for clickable diagnostics rows (rebuilt each render). */
    private final List<int[]> diagRowHitY = new java.util.ArrayList<>();
    private final List<String> diagRowHitNode = new java.util.ArrayList<>();
    /** "node"/"edge" while a delete is armed for confirmation, else null. */
    private String deleteArmed;
    private long deleteArmUntil;
    private final long[] expectedRevision;
    private final PayloadBoundRequestId requestIds;

    public DialogueEditorScreen(DialogueEditorScreenModel model) {
        this(model, new long[]{0L}, new PayloadBoundRequestId());
    }

    public DialogueEditorScreen(DialogueEditorScreenModel model, long[] expectedRevision,
                                PayloadBoundRequestId requestIds) {
        super(Component.literal("Dialogue Editor: " + model.getTitle()));
        this.model = model;
        this.expectedRevision = expectedRevision;
        this.requestIds = requestIds;
    }

    public DialogueEditorScreenModel getModel() {
        return model;
    }

    public void onSaveResult(java.util.UUID requestId, boolean success, String message, long revision) {
        if (!requestIds.matchesCurrent(requestId)) return;
        model.onSaveResult(success, message);
        // The response revision is authoritative on success AND on rejection
        // (the server echoes the current token) — never guess with ++.
        expectedRevision[0] = Math.max(0L, revision);
        requestIds.acknowledge(requestId);
    }

    @Override
    protected void init() {
        super.init();

        // Top Toolbar
        this.addRenderableWidget(Button.builder(Component.literal("+ Node"), b -> {
            double cx = DialogueGraphLayout.screenToCanvasX(width / 2.0, model.getEditorState().getPanX(), model.getEditorState().getZoom());
            double cy = DialogueGraphLayout.screenToCanvasY(height / 2.0, model.getEditorState().getPanY(), model.getEditorState().getZoom());
            model.addNode(null, "New node content", cx - 80, cy - 40);
            syncInspectorWidgets();
        }).bounds(10, 10, 60, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Zoom +"), b -> {
            model.getEditorState().zoomIn();
        }).bounds(75, 10, 55, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Zoom -"), b -> {
            model.getEditorState().zoomOut();
        }).bounds(135, 10, 55, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
            model.getEditorState().resetView();
        }).bounds(195, 10, 50, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
            model.save();
        }).bounds(width - 130, 10, 55, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> {
            this.onClose();
        }).bounds(width - 70, 10, 60, 20).build());

        // ── Second toolbar row (P5-3): search, validate, preview, history, IO ──
        int row2 = 36;
        searchBox = new EditBox(this.font, 10, row2, 120, 16, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search nodes/edges"));
        searchBox.setMaxLength(80);
        searchBox.setResponder(v -> {
            model.search(v);
            if (!model.searchMatches().isEmpty()) {
                model.nextSearchMatch(true);
                centerOnSelection();
                syncInspectorWidgets();
            }
        });
        this.addRenderableWidget(searchBox);

        this.addRenderableWidget(Button.builder(Component.literal("Match+"), b -> {
            model.nextSearchMatch(true); centerOnSelection(); syncInspectorWidgets();
        }).bounds(134, row2 - 1, 48, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Match-"), b -> {
            model.nextSearchMatch(false); centerOnSelection(); syncInspectorWidgets();
        }).bounds(184, row2 - 1, 48, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal("Validate"), b -> {
            diagnostics = model.validate();
            diagnosticsOpen = !diagnostics.isEmpty();
            model.setStatusMessage(diagnostics.isEmpty()
                    ? "Validation passed — no diagnostics"
                    : diagnostics.size() + " diagnostic(s) — see pane");
        }).bounds(236, row2 - 1, 56, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal(previewOpen ? "Preview ▾" : "Preview ▸"), b -> {
            previewOpen = !previewOpen;
        }).bounds(296, row2 - 1, 60, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal("Undo"), b -> {
            model.undo(); syncInspectorWidgets();
        }).bounds(360, row2 - 1, 44, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Redo"), b -> {
            model.redo(); syncInspectorWidgets();
        }).bounds(406, row2 - 1, 44, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal("Export"), b -> {
            String json = model.exportJson();
            this.minecraft.keyboardHandler.setClipboard(json);
            model.setStatusMessage("Exported " + json.length() + " chars to clipboard");
        }).bounds(454, row2 - 1, 48, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Import"), b -> {
            model.importJson(this.minecraft.keyboardHandler.getClipboard());
            syncInspectorWidgets();
        }).bounds(504, row2 - 1, 48, 18).build());

        // Inspector text box — created hidden; shown/populated by syncInspectorWidgets().
        // Edits commit live on every keystroke (auto-commit model: selection changes
        // can never silently drop text because the model already holds it).
        int panelX = width - INSPECTOR_W - 10;
        int panelH = inspectorHeight();

        // Speaker + sound — single-line fields, live-commit like the text box
        speakerBox = new EditBox(this.font, panelX + 58, INSPECTOR_Y + 52,
                INSPECTOR_W - 68, 14, Component.literal("Speaker"));
        speakerBox.setMaxLength(60);
        speakerBox.setHint(Component.literal("blank = NPC name"));
        speakerBox.setResponder(v -> {
            if (!syncingInspector) model.updateSelectedNodeSpeaker(v);
        });
        speakerBox.visible = false;
        this.addRenderableWidget(speakerBox);

        soundBox = new EditBox(this.font, panelX + 58, INSPECTOR_Y + 68,
                INSPECTOR_W - 68, 14, Component.literal("Sound"));
        soundBox.setMaxLength(160);
        soundBox.setHint(Component.literal("sound event id"));
        soundBox.setResponder(v -> {
            if (!syncingInspector) {
                model.updateSelectedNodeSound(v);
                soundWarning = soundWarningFor(v);
            }
        });
        soundBox.visible = false;
        this.addRenderableWidget(soundBox);

        nodeTextBox = new MultiLineEditBox(this.font, panelX + 8, INSPECTOR_Y + 112,
                INSPECTOR_W - 16, Math.max(10, panelH - 144),
                Component.literal("Node text…"), Component.literal("Node text"));
        nodeTextBox.setCharacterLimit(2000);
        nodeTextBox.setValueListener(v -> {
            if (!syncingInspector) {
                model.updateSelectedNodeText(v);
            }
        });
        nodeTextBox.visible = false;
        this.addRenderableWidget(nodeTextBox);

        // Delete controls — two-click confirm (the button re-arms to "Confirm?" for a
        // short window) on top of the existing save-gate, so a misclick can't
        // silently drop a node or edge.
        deleteNodeButton = this.addRenderableWidget(Button.builder(
                Component.literal("Delete Node"), b -> onDeleteNodePressed())
                .bounds(panelX + 8, INSPECTOR_Y + panelH - 28, 90, 20).build());
        deleteNodeButton.visible = false;

        setEntryButton = this.addRenderableWidget(Button.builder(
                Component.literal("Set Entry"), b -> {
                    String sel = model.getEditorState().getSelectedNodeId();
                    if (sel != null) {
                        model.setAsEntryNode(sel);
                        syncInspectorWidgets();
                    }
                }).bounds(panelX + 102, INSPECTOR_Y + panelH - 28, 90, 20).build());
        setEntryButton.visible = false;

        deleteEdgeButton = this.addRenderableWidget(Button.builder(
                Component.literal("Delete Edge"), b -> onDeleteEdgePressed())
                .bounds(panelX + 8, INSPECTOR_Y + panelH - 28, 90, 20).build());
        deleteEdgeButton.visible = false;

        // Edge actions — START_QUEST target. Blank clears the action; unknown
        // quest ids are rejected by the server's save validation with a message.
        questActionBox = new EditBox(this.font, panelX + 10, INSPECTOR_Y + 92,
                INSPECTOR_W - 20, 14, Component.literal("Quest id"));
        questActionBox.setMaxLength(160);
        questActionBox.setHint(Component.literal("namespace:quest_id (blank = none)"));
        questActionBox.setResponder(v -> {
            if (!syncingInspector) {
                model.setSelectedEdgeStartQuest(v);
                questWarning = questWarningFor(v);
            }
        });
        questActionBox.visible = false;
        this.addRenderableWidget(questActionBox);

        // Page tabs sit above every edge-inspector page.
        edgePageFieldsButton = this.addRenderableWidget(Button.builder(
                Component.literal("Fields"), b -> { edgePage = 0; syncInspectorWidgets(); })
                .bounds(panelX + 8, INSPECTOR_Y + 24, 58, 14).build());
        edgePageCondsButton = this.addRenderableWidget(Button.builder(
                Component.literal("Conds"), b -> { edgePage = 1; syncInspectorWidgets(); })
                .bounds(panelX + 68, INSPECTOR_Y + 24, 56, 14).build());
        edgePageActsButton = this.addRenderableWidget(Button.builder(
                Component.literal("Acts"), b -> { edgePage = 2; syncInspectorWidgets(); })
                .bounds(panelX + 126, INSPECTOR_Y + 24, 56, 14).build());
        edgePageFieldsButton.visible = false;
        edgePageCondsButton.visible = false;
        edgePageActsButton.visible = false;

        // ── Node inspector additions (P5-3): id rename + localization key ──
        nodeIdBox = new EditBox(this.font, panelX + 34, INSPECTOR_Y + 22,
                INSPECTOR_W - 44, 14, Component.literal("Node id"));
        nodeIdBox.setMaxLength(64);
        nodeIdBox.setResponder(v -> { if (!syncingInspector) model.renameSelectedNode(v); });
        nodeIdBox.visible = false;
        this.addRenderableWidget(nodeIdBox);

        nodeTextKeyBox = new EditBox(this.font, panelX + 40, INSPECTOR_Y + 88,
                INSPECTOR_W - 50, 14, Component.literal("Text key"));
        nodeTextKeyBox.setMaxLength(160);
        nodeTextKeyBox.setHint(Component.literal("localization key"));
        nodeTextKeyBox.setResponder(v -> { if (!syncingInspector) model.updateSelectedNodeTextKey(v); });
        nodeTextKeyBox.visible = false;
        this.addRenderableWidget(nodeTextKeyBox);

        // Editable canvas position — replaces the old read-only Pos line.
        nodeXBox = new EditBox(this.font, panelX + 34, INSPECTOR_Y + 38,
                70, 14, Component.literal("x"));
        nodeXBox.setMaxLength(10);
        nodeXBox.setHint(Component.literal("x"));
        nodeXBox.setResponder(v -> applyNodePosition());
        nodeXBox.visible = false;
        this.addRenderableWidget(nodeXBox);

        nodeYBox = new EditBox(this.font, panelX + 116, INSPECTOR_Y + 38,
                70, 14, Component.literal("y"));
        nodeYBox.setMaxLength(10);
        nodeYBox.setHint(Component.literal("y"));
        nodeYBox.setResponder(v -> applyNodePosition());
        nodeYBox.visible = false;
        this.addRenderableWidget(nodeYBox);

        // ── Edge inspector additions (P5-3): paged sections so every field is
        // reachable at the minimum 240px-high viewport. Page tabs at +24; the
        // fields/conditions/actions content occupies +40..+118 of the panel. ──
        edgeTextBox = new EditBox(this.font, panelX + 10, INSPECTOR_Y + 44,
                INSPECTOR_W - 20, 14, Component.literal("Option text"));
        edgeTextBox.setMaxLength(400);
        edgeTextBox.setResponder(v -> { if (!syncingInspector) model.updateSelectedEdgeText(v); });
        edgeTextBox.visible = false;
        this.addRenderableWidget(edgeTextBox);

        edgeOnceOnlyButton = this.addRenderableWidget(Button.builder(
                Component.literal("Once-only: off"), b -> {
                    model.setSelectedEdgeOnceOnly(!model.getEditorState().getSelectedEdge().isOnceOnly());
                    syncInspectorWidgets();
                }).bounds(panelX + 10, INSPECTOR_Y + 60, 90, 14).build());
        edgeOnceOnlyButton.visible = false;

        edgeTextKeyBox = new EditBox(this.font, panelX + 104, INSPECTOR_Y + 60,
                INSPECTOR_W - 114, 14, Component.literal("Text key"));
        edgeTextKeyBox.setMaxLength(160);
        edgeTextKeyBox.setHint(Component.literal("key"));
        edgeTextKeyBox.setResponder(v -> { if (!syncingInspector) model.updateSelectedEdgeTextKey(v); });
        edgeTextKeyBox.visible = false;
        this.addRenderableWidget(edgeTextKeyBox);

        edgeTargetBox = new EditBox(this.font, panelX + 10, INSPECTOR_Y + 76,
                INSPECTOR_W - 20, 14, Component.literal("Target node"));
        edgeTargetBox.setMaxLength(64);
        edgeTargetBox.setHint(Component.literal("target node id"));
        edgeTargetBox.setResponder(v -> { if (!syncingInspector) model.retargetSelectedEdge(v); });
        edgeTargetBox.visible = false;
        this.addRenderableWidget(edgeTargetBox);

        // Row editors sit on their own pages: type+target share one line, then
        // op/value (conditions) or value+buttons (actions) on the next. Compact
        // "+"/"−" buttons leave room on the second line at 140px min width.
        condTypeBox = smallBox(panelX + 8, INSPECTOR_Y + 88, 96, "type", 40,
                v -> applyConditionEdit());
        condTargetBox = smallBox(panelX + 108, INSPECTOR_Y + 88, INSPECTOR_W - 116, "target", 160,
                v -> applyConditionEdit());
        condOperatorBox = smallBox(panelX + 8, INSPECTOR_Y + 104, 40, "op", 8,
                v -> applyConditionEdit());
        condValueBox = smallBox(panelX + 52, INSPECTOR_Y + 104, INSPECTOR_W - 140, "value", 160,
                v -> applyConditionEdit());
        condAddButton = this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            DialogueCondition fresh = new DialogueCondition(
                    DialogueCondition.Type.QUEST_STATUS, "storynpcs:quest", "==", "IN_PROGRESS");
            if (model.getEditorState().getSelectedEdge() != null) {
                model.addSelectedEdgeCondition(fresh);
                selectedConditionIndex = model.getSelectedEdgeConditions().size() - 1;
            } else {
                model.addAvailabilityCondition(fresh);
                selectedConditionIndex = model.getAvailability().size() - 1;
            }
            syncInspectorWidgets();
        }).bounds(panelX + INSPECTOR_W - 78, INSPECTOR_Y + 104, 20, 14).build());
        condRemoveButton = this.addRenderableWidget(Button.builder(Component.literal("−"), b -> {
            if (model.getEditorState().getSelectedEdge() != null) {
                model.removeSelectedEdgeCondition(selectedConditionIndex);
            } else {
                model.removeAvailabilityCondition(selectedConditionIndex);
            }
            selectedConditionIndex = -1;
            syncInspectorWidgets();
        }).bounds(panelX + INSPECTOR_W - 56, INSPECTOR_Y + 104, 20, 14).build());

        actTypeBox = smallBox(panelX + 8, INSPECTOR_Y + 88, 96, "type", 40,
                v -> applyActionEdit());
        actTargetBox = smallBox(panelX + 108, INSPECTOR_Y + 88, INSPECTOR_W - 116, "target", 160,
                v -> applyActionEdit());
        actValueBox = smallBox(panelX + 8, INSPECTOR_Y + 104, INSPECTOR_W - 96, "value", 160,
                v -> applyActionEdit());
        actAddButton = this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            model.addSelectedEdgeAction(new DialogueAction(
                    DialogueAction.Type.CLOSE_DIALOGUE, "", ""));
            selectedActionIndex = model.getSelectedEdgeActions().size() - 1;
            syncInspectorWidgets();
        }).bounds(panelX + INSPECTOR_W - 78, INSPECTOR_Y + 104, 20, 14).build());
        actRemoveButton = this.addRenderableWidget(Button.builder(Component.literal("−"), b -> {
            model.removeSelectedEdgeAction(selectedActionIndex);
            selectedActionIndex = -1;
            syncInspectorWidgets();
        }).bounds(panelX + INSPECTOR_W - 56, INSPECTOR_Y + 104, 20, 14).build());

        // Dialogue-level inspector (nothing selected): title localization key.
        // Availability conditions reuse the cond* row widgets — the same page
        // layout serves both the edge inspector's conditions page and this one.
        dialogueTitleKeyBox = new EditBox(this.font, panelX + 52, INSPECTOR_Y + 44,
                INSPECTOR_W - 62, 14, Component.literal("Title key"));
        dialogueTitleKeyBox.setMaxLength(160);
        dialogueTitleKeyBox.setHint(Component.literal("title localization key"));
        dialogueTitleKeyBox.setResponder(v -> { if (!syncingInspector) model.setTitleKey(v); });
        dialogueTitleKeyBox.visible = false;
        this.addRenderableWidget(dialogueTitleKeyBox);

        syncInspectorWidgets();
    }

    private EditBox smallBox(int x, int y, int w, String hint, int maxLen,
                             java.util.function.Consumer<String> responder) {
        EditBox box = new EditBox(this.font, x, y, w, 14, Component.literal(hint));
        box.setMaxLength(maxLen);
        box.setHint(Component.literal(hint));
        box.setResponder(v -> { if (!syncingInspector) responder.accept(v); });
        box.visible = false;
        this.addRenderableWidget(box);
        return box;
    }

    /** Applies the condition-row fields back onto the selected condition index —
     *  edge conditions when an edge is selected, dialogue availability otherwise. */
    private void applyConditionEdit() {
        VisualEdge edge = model.getEditorState().getSelectedEdge();
        boolean dialogueInspector = model.getEditorState().getSelectedNodeId() == null && edge == null;
        if ((!dialogueInspector && edge == null) || selectedConditionIndex < 0) return;
        DialogueCondition.Type type = parseConditionType(condTypeBox.getValue());
        if (type == null) return;
        DialogueCondition updated = new DialogueCondition(
                type, condTargetBox.getValue(), condOperatorBox.getValue(), condValueBox.getValue());
        if (dialogueInspector) {
            model.updateAvailabilityCondition(selectedConditionIndex, updated);
        } else {
            model.updateSelectedEdgeCondition(selectedConditionIndex, updated);
        }
    }

    private void applyActionEdit() {
        VisualEdge edge = model.getEditorState().getSelectedEdge();
        if (edge == null || selectedActionIndex < 0) return;
        DialogueAction.Type type = parseActionType(actTypeBox.getValue());
        if (type == null) return;
        model.updateSelectedEdgeAction(selectedActionIndex, new DialogueAction(
                type, actTargetBox.getValue(), actValueBox.getValue()));
    }

    private static DialogueCondition.Type parseConditionType(String raw) {
        try {
            return DialogueCondition.Type.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static DialogueAction.Type parseActionType(String raw) {
        try {
            return DialogueAction.Type.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void centerOnSelection() {
        model.centerOnSelection(width - (insideInspectorVisible() ? INSPECTOR_W + 20 : 0), height);
    }

    private void applyNodePosition() {
        if (syncingInspector) return;
        try {
            double x = Double.parseDouble(nodeXBox.getValue().trim());
            double y = Double.parseDouble(nodeYBox.getValue().trim());
            model.updateSelectedNodePosition(x, y);
        } catch (NumberFormatException ignored) {
            // Incomplete numeric input — leave the node where it is
        }
    }

    private boolean insideInspectorVisible() {
        return model.getEditorState().getSelectedNodeId() != null
                || model.getEditorState().getSelectedEdge() != null;
    }

    /** Shows the inspector widgets for the current selection (node, edge, or none). */
    private void syncInspectorWidgets() {
        disarmDelete();
        String selectedId = model.getEditorState().getSelectedNodeId();
        VisualNode node = selectedId != null ? model.getLayout().getNodes().get(selectedId) : null;
        VisualEdge edge = model.getEditorState().getSelectedEdge();

        if (nodeTextBox != null) {
            nodeTextBox.visible = node != null;
            speakerBox.visible = node != null;
            soundBox.visible = node != null;
            nodeIdBox.visible = node != null;
            nodeTextKeyBox.visible = node != null;
            nodeXBox.visible = node != null;
            nodeYBox.visible = node != null;
            if (node == null) {
                nodeTextBox.setFocused(false);
                speakerBox.setFocused(false);
                soundBox.setFocused(false);
                nodeIdBox.setFocused(false);
                nodeTextKeyBox.setFocused(false);
                nodeXBox.setFocused(false);
                nodeYBox.setFocused(false);
                soundWarning = null;
            } else {
                syncingInspector = true;
                try {
                    nodeTextBox.setValue(node.getText() != null ? node.getText() : "");
                    speakerBox.setValue(node.getSpeaker() != null ? node.getSpeaker() : "");
                    soundBox.setValue(node.getSound() != null ? node.getSound() : "");
                    nodeIdBox.setValue(node.getId() != null ? node.getId() : "");
                    nodeTextKeyBox.setValue(node.getTextKey() != null ? node.getTextKey() : "");
                    nodeXBox.setValue(String.valueOf((int) node.getX()));
                    nodeYBox.setValue(String.valueOf((int) node.getY()));
                } finally {
                    syncingInspector = false;
                }
                // Surface a warning even for pre-existing YAML data, not just new edits
                soundWarning = soundWarningFor(node.getSound());
            }
        }
        if (questActionBox != null) {
            boolean edgeInspector = node == null && edge != null;
            boolean dialogueInspector = node == null && edge == null;
            edgePageFieldsButton.visible = edgeInspector;
            edgePageCondsButton.visible = edgeInspector;
            edgePageActsButton.visible = edgeInspector;
            boolean fieldsPage = edgeInspector && edgePage == 0;
            boolean condsPage = (edgeInspector && edgePage == 1) || dialogueInspector;
            boolean actsPage = edgeInspector && edgePage == 2;
            questActionBox.visible = fieldsPage;
            edgeTextBox.visible = fieldsPage;
            edgeOnceOnlyButton.visible = fieldsPage;
            edgeTextKeyBox.visible = fieldsPage;
            edgeTargetBox.visible = fieldsPage;
            condTypeBox.visible = condsPage;
            condTargetBox.visible = condsPage;
            condOperatorBox.visible = condsPage;
            condValueBox.visible = condsPage;
            condAddButton.visible = condsPage;
            condRemoveButton.visible = condsPage && selectedConditionIndex >= 0;
            actTypeBox.visible = actsPage;
            actTargetBox.visible = actsPage;
            actValueBox.visible = actsPage;
            actAddButton.visible = actsPage;
            actRemoveButton.visible = actsPage && selectedActionIndex >= 0;
            dialogueTitleKeyBox.visible = dialogueInspector;
            if (dialogueInspector) {
                // The same condition widgets serve the dialogue page, sitting
                // lower to clear the title-key field.
                int dTop = INSPECTOR_Y + 112;
                int dBottom = INSPECTOR_Y + 128;
                condTypeBox.setY(dTop); condTargetBox.setY(dTop);
                condOperatorBox.setY(dBottom); condValueBox.setY(dBottom);
                condAddButton.setY(dBottom); condRemoveButton.setY(dBottom);
                syncingInspector = true;
                try {
                    dialogueTitleKeyBox.setValue(model.getTitleKey());
                    var conds = model.getAvailability();
                    if (selectedConditionIndex >= conds.size()) {
                        selectedConditionIndex = conds.isEmpty() ? -1 : 0;
                    }
                    if (selectedConditionIndex >= 0) {
                        DialogueCondition c = conds.get(selectedConditionIndex);
                        condTypeBox.setValue(c.getType().name());
                        condTargetBox.setValue(c.getTarget());
                        condOperatorBox.setValue(c.getOperator());
                        condValueBox.setValue(c.getValue());
                    } else {
                        condTypeBox.setValue(""); condTargetBox.setValue("");
                        condOperatorBox.setValue(""); condValueBox.setValue("");
                    }
                } finally {
                    syncingInspector = false;
                }
                selectedActionIndex = -1;
                questWarning = null;
                return;
            }
            if (edge == null) {
                questActionBox.setFocused(false);
                questWarning = null;
                selectedConditionIndex = -1;
                selectedActionIndex = -1;
            } else {
                // Edge pages use the compact row-editor positions.
                int eTop = INSPECTOR_Y + 88;
                int eBottom = INSPECTOR_Y + 104;
                condTypeBox.setY(eTop); condTargetBox.setY(eTop);
                condOperatorBox.setY(eBottom); condValueBox.setY(eBottom);
                condAddButton.setY(eBottom); condRemoveButton.setY(eBottom);
                syncingInspector = true;
                try {
                    questActionBox.setValue(model.getSelectedEdgeStartQuest());
                    edgeTextBox.setValue(edge.getText() != null ? edge.getText() : "");
                    edgeTextKeyBox.setValue(edge.getTextKey() != null ? edge.getTextKey() : "");
                    edgeTargetBox.setValue(edge.getTargetNodeId() != null ? edge.getTargetNodeId() : "");
                    edgeOnceOnlyButton.setMessage(Component.literal(
                            "Once-only: " + (edge.isOnceOnly() ? "on" : "off")));
                    var conds = model.getSelectedEdgeConditions();
                    if (selectedConditionIndex >= conds.size()) selectedConditionIndex = conds.isEmpty() ? -1 : 0;
                    if (selectedConditionIndex >= 0) {
                        DialogueCondition c = conds.get(selectedConditionIndex);
                        condTypeBox.setValue(c.getType().name());
                        condTargetBox.setValue(c.getTarget());
                        condOperatorBox.setValue(c.getOperator());
                        condValueBox.setValue(c.getValue());
                    } else {
                        condTypeBox.setValue(""); condTargetBox.setValue("");
                        condOperatorBox.setValue(""); condValueBox.setValue("");
                    }
                    var acts = model.getSelectedEdgeActions();
                    if (selectedActionIndex >= acts.size()) selectedActionIndex = acts.isEmpty() ? -1 : 0;
                    if (selectedActionIndex >= 0) {
                        DialogueAction a = acts.get(selectedActionIndex);
                        actTypeBox.setValue(a.getType().name());
                        actTargetBox.setValue(a.getTarget());
                        actValueBox.setValue(a.getValue());
                    } else {
                        actTypeBox.setValue(""); actTargetBox.setValue(""); actValueBox.setValue("");
                    }
                } finally {
                    syncingInspector = false;
                }
                questWarning = questWarningFor(model.getSelectedEdgeStartQuest());
            }
        }
        if (deleteNodeButton != null) {
            deleteNodeButton.visible = node != null;
            setEntryButton.visible = node != null;
            setEntryButton.active = node != null && !node.isEntryNode();
            deleteEdgeButton.visible = node == null && edge != null;
        }
    }

    /** Panel height adapts to the window — on short screens the buttons must stay reachable. */
    private int inspectorHeight() {
        return Math.min(INSPECTOR_MAX_H, Math.max(INSPECTOR_MIN_H, height - INSPECTOR_Y - 16));
    }

    /** Inspector is always on the right edge (dialogue inspector when nothing
     *  is selected) — canvas clicks there must never reach the canvas. */
    private boolean insideInspector(double mouseX, double mouseY) {
        int panelX = width - INSPECTOR_W - 10;
        return mouseX >= panelX && mouseX <= panelX + INSPECTOR_W
                && mouseY >= INSPECTOR_Y && mouseY <= INSPECTOR_Y + inspectorHeight();
    }

    private void onDeleteNodePressed() {
        String sel = model.getEditorState().getSelectedNodeId();
        if (sel == null) return;
        VisualNode node = model.getLayout().getNodes().get(sel);
        if (node != null && node.isEntryNode()) {
            model.setStatusMessage("Cannot delete the entry node — set another node as entry first");
            return;
        }
        if (armedDelete("node")) {
            disarmDelete();
            model.removeSelectedNode();
            syncInspectorWidgets();
        } else {
            armDelete("node");
        }
    }

    private void onDeleteEdgePressed() {
        if (model.getEditorState().getSelectedEdge() == null) return;
        if (armedDelete("edge")) {
            disarmDelete();
            model.removeSelectedEdge();
            syncInspectorWidgets();
        } else {
            armDelete("edge");
        }
    }

    private boolean armedDelete(String what) {
        return what.equals(deleteArmed) && System.currentTimeMillis() < deleteArmUntil;
    }

    private void armDelete(String what) {
        deleteArmed = what;
        deleteArmUntil = System.currentTimeMillis() + DELETE_CONFIRM_MS;
        if (deleteNodeButton != null) {
            deleteNodeButton.setMessage(Component.literal("node".equals(what) ? "Confirm?" : "Delete Node"));
            deleteEdgeButton.setMessage(Component.literal("edge".equals(what) ? "Confirm?" : "Delete Edge"));
        }
    }

    private void disarmDelete() {
        deleteArmed = null;
        if (deleteNodeButton != null) {
            deleteNodeButton.setMessage(Component.literal("Delete Node"));
            deleteEdgeButton.setMessage(Component.literal("Delete Edge"));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen#render runs the menu-blur post-process over whatever is
        // already in the framebuffer — background first, canvas next, widgets
        // last, or the whole graph canvas is blurred while buttons stay
        // sharp (#197).
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        // Dark background canvas
        graphics.fill(0, 0, width, height, CANVAS_BG);

        double panX = model.getEditorState().getPanX();
        double panY = model.getEditorState().getPanY();
        double zoom = model.getEditorState().getZoom();

        // Render Canvas Grid
        renderGrid(graphics, panX, panY, zoom);

        // Render Edges
        for (VisualEdge edge : model.getLayout().getEdges()) {
            VisualNode src = model.getLayout().getNodes().get(edge.getSourceNodeId());
            VisualNode dst = model.getLayout().getNodes().get(edge.getTargetNodeId());
            if (src != null && dst != null) {
                int x1 = (int) DialogueGraphLayout.canvasToScreenX(src.getCenterX(), panX, zoom);
                int y1 = (int) DialogueGraphLayout.canvasToScreenY(src.getCenterY(), panY, zoom);
                int x2 = (int) DialogueGraphLayout.canvasToScreenX(dst.getCenterX(), panX, zoom);
                int y2 = (int) DialogueGraphLayout.canvasToScreenY(dst.getCenterY(), panY, zoom);

                int color = edge.isCyclic() ? EDGE_CYCLIC : EDGE_NORMAL;
                graphics.fill(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2) + 2, Math.max(y1, y2) + 2, color);

                // Draw edge label
                int midX = (x1 + x2) / 2;
                int midY = (y1 + y2) / 2;
                boolean edgeSelected = edge == model.getEditorState().getSelectedEdge();
                graphics.fill(midX - 25, midY - 6, midX + 25, midY + 6,
                        edgeSelected ? EDGE_LABEL_BG_SELECTED : EDGE_LABEL_BG);
                if (edgeSelected) {
                    graphics.renderOutline(midX - 25, midY - 6, 50, 12, CANVAS_SELECTED);
                }
                graphics.drawString(this.font, edge.getText(), midX - 20, midY - 4, color, false);
            }
        }

        // Render Nodes
        for (VisualNode node : model.getLayout().getNodes().values()) {
            int sx = (int) DialogueGraphLayout.canvasToScreenX(node.getX(), panX, zoom);
            int sy = (int) DialogueGraphLayout.canvasToScreenY(node.getY(), panY, zoom);
            int sw = (int) (node.getWidth() * zoom);
            int sh = (int) (node.getHeight() * zoom);

            boolean isSelected = node.getId().equals(model.getEditorState().getSelectedNodeId());
            int borderColor = isSelected ? CANVAS_SELECTED
                    : (node.isEntryNode() ? NODE_BORDER_ENTRY : NODE_BORDER);
            int bodyColor = isSelected ? NODE_BODY_SELECTED : NODE_BODY;

            graphics.fill(sx, sy, sx + sw, sy + sh, bodyColor);
            graphics.renderOutline(sx, sy, sw, sh, borderColor);

            // Node header and preview
            String titleStr = (node.isEntryNode() ? "[ENTRY] " : "") + node.getId();
            graphics.drawString(this.font, titleStr, sx + 6, sy + 6, borderColor, false);
            if (sh > 30) {
                graphics.drawWordWrap(this.font, Component.literal(node.getText()), sx + 6, sy + 20, Math.max(sw - 12, 10), UiTheme.TEXT);
            }
        }

        // Render Top Bar (two rows)
        graphics.fill(0, 0, width, TOOLBAR_H, TOOLBAR_BG);
        graphics.renderOutline(0, 0, width, TOOLBAR_H, UiTheme.BORDER);
        // Title sits in the gap between the two button groups — cap it so it
        // can never render underneath the Save/Close buttons on narrow windows.
        int titleMaxW = Math.max(0, width - 130 - 6 - 250);
        if (titleMaxW > 10) {
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(
                            String.format("Dialogue: %s (%s)", model.getDialogueId(), model.getTitle()), titleMaxW),
                    250, 16, UiTheme.TEXT, false);
        }

        // Status bar — capped so a long message can't slide under the inspector panel
        if (!model.getStatusMessage().isEmpty()) {
            boolean inspectorOpen = model.getEditorState().getSelectedNodeId() != null
                    || model.getEditorState().getSelectedEdge() != null;
            int statusMaxW = inspectorOpen ? width - INSPECTOR_W - 30 : width - 16;
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(model.getStatusMessage(), Math.max(60, statusMaxW)),
                    10, height - 20, UiTheme.TEXT_MUTED, false);
        }

        // Inspector panel for selected node
        renderInspector(graphics);

        // Render widgets directly — super.render() would re-run the blur
        // background pass and smear the canvas drawn above (#197).
        for (var renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }

        // Overlay panes draw above widgets
        renderPane(graphics);
    }

    private void renderGrid(GuiGraphics graphics, double panX, double panY, double zoom) {
        double gridSize = 40.0 * zoom;
        if (gridSize < 10) return;

        double startX = panX % gridSize;
        double startY = panY % gridSize;

        for (double x = startX; x < width; x += gridSize) {
            graphics.fill((int) x, 0, (int) x + 1, height, GRID_LINE);
        }
        for (double y = startY; y < height; y += gridSize) {
            graphics.fill(0, (int) y, width, (int) y + 1, GRID_LINE);
        }
    }

    private void renderInspector(GuiGraphics graphics) {
        if (deleteArmed != null && System.currentTimeMillis() > deleteArmUntil) {
            disarmDelete();
        }
        String selectedId = model.getEditorState().getSelectedNodeId();
        VisualEdge selEdge = model.getEditorState().getSelectedEdge();

        int panelX = width - INSPECTOR_W - 10;
        int panelY = INSPECTOR_Y;

        graphics.fill(panelX, panelY, panelX + INSPECTOR_W, panelY + inspectorHeight(), INSPECTOR_BG);
        graphics.renderOutline(panelX, panelY, INSPECTOR_W, inspectorHeight(), UiTheme.ACCENT);

        if (selectedId == null && selEdge == null) {
            // Dialogue-level inspector — title key + availability conditions.
            graphics.drawString(this.font, "Dialogue Inspector", panelX + 10, panelY + 10, UiTheme.ACCENT, false);
            graphics.drawString(this.font, "title key:", panelX + 10, panelY + 48, UiTheme.TEXT_DISABLED, false);
            graphics.drawString(this.font, "Availability (all must hold):", panelX + 10, panelY + 60,
                    UiTheme.TEXT_MUTED, false);
            List<DialogueCondition> conds = model.getAvailability();
            int rowY = panelY + 66;
            for (int i = 0; i < Math.min(conds.size(), 4); i++) {
                DialogueCondition c = conds.get(i);
                boolean sel = i == selectedConditionIndex;
                if (sel) {
                    graphics.fill(panelX + 6, rowY - 1, panelX + INSPECTOR_W - 6, rowY + 10, UiTheme.ROW_SELECTED);
                }
                String row = c.getType().name() + " " + c.getTarget() + " " + c.getOperator()
                        + " " + c.getValue();
                graphics.drawString(this.font,
                        this.font.plainSubstrByWidth((i + 1) + ". " + row, INSPECTOR_W - 24),
                        panelX + 10, rowY, sel ? UiTheme.ACCENT : UiTheme.TEXT, false);
                rowY += 11;
            }
            if (conds.isEmpty()) {
                graphics.drawString(this.font, "none — + adds a row", panelX + 10, rowY, UiTheme.TEXT_DISABLED, false);
            } else if (conds.size() > 4) {
                graphics.drawString(this.font, "+" + (conds.size() - 4) + " more",
                        panelX + 10, rowY, UiTheme.TEXT_DISABLED, false);
            }
            return;
        }

        if (selEdge != null) {
            graphics.drawString(this.font, "Edge Inspector", panelX + 10, panelY + 10, UiTheme.ACCENT, false);
            if (edgePage == 0) {
                graphics.drawString(this.font, "Option text / flags:", panelX + 10, panelY + 40,
                        UiTheme.TEXT_MUTED, false);
                graphics.drawString(this.font, "Quest (START_QUEST):", panelX + 10, panelY + 98,
                        UiTheme.TEXT_MUTED, false);
                if (questWarning != null) {
                    graphics.drawString(this.font, questWarning, panelX + 10, panelY + 116, UiTheme.WARNING, false);
                }
            } else {
                String label = edgePage == 1 ? "Conditions (all must hold):" : "Actions (run on choice):";
                graphics.drawString(this.font, label, panelX + 10, panelY + 40, UiTheme.TEXT_MUTED, false);
                renderEdgeRows(graphics, selEdge, panelX, panelY);
            }
            return;
        }

        VisualNode node = model.getLayout().getNodes().get(selectedId);
        if (node == null) return;

        graphics.drawString(this.font, "Node Inspector", panelX + 10, panelY + 10, UiTheme.ACCENT, false);
        graphics.drawString(this.font, "id:", panelX + 10, panelY + 26, UiTheme.TEXT_DISABLED, false);
        graphics.drawString(this.font, "x:", panelX + 10, panelY + 42, UiTheme.TEXT_DISABLED, false);
        graphics.drawString(this.font, "y:", panelX + 106, panelY + 42, UiTheme.TEXT_DISABLED, false);
        if (node.isEntryNode()) {
            graphics.drawString(this.font, "entry", panelX + INSPECTOR_W - 34, panelY + 26,
                    UiTheme.SUCCESS, false);
        }
        graphics.drawString(this.font, "Speaker:", panelX + 10, panelY + 56, UiTheme.TEXT_MUTED, false);
        graphics.drawString(this.font, "Sound:", panelX + 10, panelY + 72, UiTheme.TEXT_MUTED, false);
        if (soundWarning != null) {
            graphics.drawString(this.font, soundWarning, panelX + 10, panelY + 84, UiTheme.WARNING, false);
        }
        graphics.drawString(this.font, "key:", panelX + 10, panelY + 92, UiTheme.TEXT_DISABLED, false);
        graphics.drawString(this.font, "Text:", panelX + 10, panelY + 106, UiTheme.TEXT_MUTED, false);
    }

    /** Renders the condition/action row list for the active edge-inspector page. */
    private void renderEdgeRows(GuiGraphics graphics, VisualEdge edge, int panelX, int panelY) {
        List<String> rows = new java.util.ArrayList<>();
        if (edgePage == 1) {
            for (DialogueCondition c : edge.getConditions()) {
                rows.add(c.getType().name() + " " + c.getTarget() + " " + c.getOperator() + " " + c.getValue());
            }
        } else {
            for (DialogueAction a : edge.getActions()) {
                rows.add(a.getType().name() + " " + a.getTarget() + " " + a.getValue());
            }
        }
        int selected = edgePage == 1 ? selectedConditionIndex : selectedActionIndex;
        int rowY = panelY + 46;
        for (int i = 0; i < Math.min(rows.size(), 4); i++) {
            boolean sel = i == selected;
            if (sel) {
                graphics.fill(panelX + 6, rowY - 1, panelX + INSPECTOR_W - 6, rowY + 10, UiTheme.ROW_SELECTED);
            }
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth((i + 1) + ". " + rows.get(i), INSPECTOR_W - 24),
                    panelX + 10, rowY, sel ? UiTheme.ACCENT : UiTheme.TEXT, false);
            rowY += 11;
        }
        if (rows.size() > 4) {
            graphics.drawString(this.font, "+" + (rows.size() - 4) + " more",
                    panelX + 10, rowY, UiTheme.TEXT_DISABLED, false);
        }
        if (rows.isEmpty()) {
            graphics.drawString(this.font, "none — + adds a row", panelX + 10, rowY, UiTheme.TEXT_DISABLED, false);
        }
    }

    /**
     * Overlay panes for validation diagnostics and the authoring preview.
     * Diagnostics rows are clickable — a row selects the node it references.
     */
    private void renderPane(GuiGraphics graphics) {
        if (!diagnosticsOpen && !previewOpen) return;
        int paneW = Math.min(360, width - 40);
        int paneH = Math.min(260, height - 80);
        int x = (width - paneW) / 2;
        int y = (height - paneH) / 2;
        graphics.fill(x - 2, y - 2, x + paneW + 2, y + paneH + 2, UiTheme.ACCENT);
        graphics.fill(x, y, x + paneW, y + paneH, PANE_BG);
        diagRowHitY.clear();
        diagRowHitNode.clear();
        if (diagnosticsOpen) {
            graphics.drawString(this.font, "Diagnostics (click a row to select; Esc closes)",
                    x + 8, y + 8, UiTheme.ACCENT, false);
            int rowY = y + 24;
            if (diagnostics.isEmpty()) {
                graphics.drawString(this.font, "No problems detected.", x + 8, rowY, UiTheme.SUCCESS, false);
            }
            for (DiagnosticError d : diagnostics) {
                if (rowY > y + paneH - 14) break;
                String nodeId = DialogueEditorScreenModel.referencedNodeId(d);
                String line = (nodeId != null ? nodeId + ": " : "")
                        + "[" + d.code() + "] " + d.message();
                graphics.drawString(this.font, this.font.plainSubstrByWidth(line, paneW - 16),
                        x + 8, rowY, d.severity() == DiagnosticError.Severity.ERROR
                                ? UiTheme.DANGER : UiTheme.WARNING, false);
                diagRowHitY.add(new int[]{rowY - 2, rowY + 9});
                diagRowHitNode.add(nodeId);
                rowY += 11;
            }
        } else {
            graphics.drawString(this.font, "Preview (entry-first walkthrough; Esc closes)",
                    x + 8, y + 8, UiTheme.ACCENT, false);
            var prev = model.preview();
            int rowY = y + 24;
            if (prev.lines().isEmpty()) {
                graphics.drawString(this.font, prev.valid()
                                ? "Empty graph or no entry node."
                                : "Validation failed — see Diagnostics.",
                        x + 8, rowY, UiTheme.TEXT_MUTED, false);
            }
            for (var line : prev.lines()) {
                if (rowY > y + paneH - 14) break;
                String head = (line.entry() ? "> " : "  ") + line.nodeId()
                        + (line.speaker() != null && !line.speaker().isBlank()
                                ? " (" + line.speaker() + ")" : "")
                        + ": " + line.text();
                graphics.drawString(this.font, this.font.plainSubstrByWidth(head, paneW - 16),
                        x + 8, rowY, line.entry() ? UiTheme.SUCCESS : UiTheme.TEXT, false);
                rowY += 11;
                for (String opt : line.options()) {
                    if (rowY > y + paneH - 14) break;
                    graphics.drawString(this.font,
                            this.font.plainSubstrByWidth("    - " + opt, paneW - 20),
                            x + 12, rowY, UiTheme.TEXT_MUTED, false);
                    rowY += 11;
                }
            }
        }
    }

    /** Pane bounds used by both rendering and click hit-testing. */
    private int[] paneBounds() {
        int paneW = Math.min(360, width - 40);
        int paneH = Math.min(260, height - 80);
        return new int[]{(width - paneW) / 2, (height - paneH) / 2, paneW, paneH};
    }

    /**
     * Inline sound-id validation: format via ResourceLocation, existence via the
     * client-side sound registry. A warning is shown but the value still commits —
     * custom datapack/pack sounds may legitimately not be in the vanilla registry.
     */
    private String soundWarningFor(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (ResourceLocation.tryParse(raw) == null) return "! not a valid id (namespace:path)";
        if (!BuiltInRegistries.SOUND_EVENT.containsKey(ResourceLocation.parse(raw))) {
            return "! unknown sound event — won't play";
        }
        return null;
    }

    /**
     * Quest ids aren't resolvable client-side (quests live in server-side YAML),
     * so this only checks id format. An unknown-but-wellformed id is rejected by
     * the server's save validation and surfaces in the status bar.
     */
    private String questWarningFor(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (ResourceLocation.tryParse(raw) == null) return "! not a valid id (namespace:path)";
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // An open pane owns the click space: rows select, inside consumes, outside closes.
        if (diagnosticsOpen || previewOpen) {
            int[] pb = paneBounds();
            boolean insidePane = mouseX >= pb[0] && mouseX <= pb[0] + pb[2]
                    && mouseY >= pb[1] && mouseY <= pb[1] + pb[3];
            if (insidePane && diagnosticsOpen) {
                for (int i = 0; i < diagRowHitY.size(); i++) {
                    int[] band = diagRowHitY.get(i);
                    String nodeId = diagRowHitNode.get(i);
                    if (nodeId != null && mouseY >= band[0] && mouseY <= band[1]) {
                        model.getEditorState().setSelectedNodeId(nodeId);
                        centerOnSelection();
                        syncInspectorWidgets();
                    }
                }
            }
            if (!insidePane) {
                diagnosticsOpen = false;
                previewOpen = false;
            }
            return true;
        }
        // Inspector row clicks (condition/action lists) select the row. The
        // dialogue inspector's availability list starts lower than the edge's.
        VisualEdge selEdge = model.getEditorState().getSelectedEdge();
        boolean dialogueRows = model.getEditorState().getSelectedNodeId() == null && selEdge == null;
        if ((dialogueRows || (selEdge != null && edgePage > 0)) && insideInspector(mouseX, mouseY)) {
            int panelX = width - INSPECTOR_W - 10;
            double listTop = dialogueRows ? 66 : 44;
            double relY = mouseY - INSPECTOR_Y;
            if (mouseX >= panelX + 6 && mouseX <= panelX + INSPECTOR_W - 6
                    && relY >= listTop && relY < listTop + 4 * 11) {
                int idx = (int) ((relY - listTop) / 11);
                int size = dialogueRows ? model.getAvailability().size()
                        : edgePage == 1 ? model.getSelectedEdgeConditions().size()
                        : model.getSelectedEdgeActions().size();
                if (idx < size) {
                    if (dialogueRows || edgePage == 1) {
                        selectedConditionIndex = idx;
                    } else {
                        selectedActionIndex = idx;
                    }
                    syncInspectorWidgets();
                    return true;
                }
            }
        }
        // Inspector clicks must reach its widgets, not the canvas — otherwise they would deselect
        if (mouseY > TOOLBAR_H && !insideInspector(mouseX, mouseY)) {
            VisualNode hit = model.getEditorState().findNodeAtScreen(mouseX, mouseY);
            if (hit != null) {
                if (model.getEditorState().getConnectingSourceNodeId() != null) {
                    model.completeConnectingEdge(hit.getId(), "Option");
                } else {
                    model.getEditorState().setSelectedNodeId(hit.getId());
                    syncInspectorWidgets();
                    model.getEditorState().startDragNode(hit.getId(), mouseX, mouseY);
                }
                return true;
            } else {
                if (button == 1 || button == 2) {
                    isPanning = true;
                    lastMouseX = mouseX;
                    lastMouseY = mouseY;
                    return true;
                } else if (button == 0) {
                    // Node missed — try the edge label before treating this as empty canvas
                    VisualEdge edgeHit = model.getEditorState().findEdgeAtScreen(mouseX, mouseY);
                    if (edgeHit != null) {
                        model.getEditorState().setSelectedEdge(edgeHit);
                        syncInspectorWidgets();
                        return true;
                    }
                    model.getEditorState().setSelectedNodeId(null);
                    syncInspectorWidgets();
                    model.cancelConnectingEdge();
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (model.getEditorState().isDragging()) {
            model.getEditorState().dragNode(mouseX, mouseY);
            return true;
        } else if (isPanning) {
            model.getEditorState().pan(mouseX - lastMouseX, mouseY - lastMouseY);
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (model.getEditorState().isDragging()) {
            model.getEditorState().endDrag();
            return true;
        }
        if (isPanning) {
            isPanning = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY > 0) {
            model.getEditorState().zoomIn();
        } else if (scrollY < 0) {
            model.getEditorState().zoomOut();
        }
        return true;
    }

    /**
     * Keyboard surface (P5-3): Ctrl+Z/Y undo/redo, Tab/Shift-Tab cycle selection,
     * Ctrl+F search focus, Delete removes the selection (armed-confirm applies),
     * Ctrl+E exports, Esc closes an open pane before falling through to close.
     * Text widgets keep their normal editing keys — shortcuts only fire when no
     * editor field is focused.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean textFocused = getFocused() instanceof EditBox
                || getFocused() instanceof MultiLineEditBox;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && (diagnosticsOpen || previewOpen)) {
            diagnosticsOpen = false;
            previewOpen = false;
            return true;
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_F) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return true;
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_E && !textFocused) {
            String json = model.exportJson();
            this.minecraft.keyboardHandler.setClipboard(json);
            model.setStatusMessage("Exported " + json.length() + " chars to clipboard");
            return true;
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_Z && !textFocused) {
            if (Screen.hasShiftDown()) model.redo(); else model.undo();
            syncInspectorWidgets();
            return true;
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_Y && !textFocused) {
            model.redo();
            syncInspectorWidgets();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && !textFocused) {
            model.cycleSelection(!Screen.hasShiftDown());
            syncInspectorWidgets();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER && searchBox != null && getFocused() == searchBox) {
            model.nextSearchMatch(!Screen.hasShiftDown());
            centerOnSelection();
            syncInspectorWidgets();
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) && !textFocused) {
            if (model.getEditorState().getSelectedNodeId() != null) {
                onDeleteNodePressed();
                return true;
            }
            if (model.getEditorState().getSelectedEdge() != null) {
                onDeleteEdgePressed();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
