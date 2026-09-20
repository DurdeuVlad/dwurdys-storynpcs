package com.storynpcs.client.gui;

import com.storynpcs.editor.DialogueEditorScreenModel;
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

public class DialogueEditorScreen extends Screen {

    private static final int INSPECTOR_W = 200;
    private static final int INSPECTOR_MAX_H = 240;
    private static final int INSPECTOR_Y = 50;
    /** How long a delete button stays in its "Confirm?" state before reverting. */
    private static final long DELETE_CONFIRM_MS = 4000;

    private final DialogueEditorScreenModel model;
    private boolean isPanning = false;
    private double lastMouseX;
    private double lastMouseY;

    /** Node-text editor inside the inspector — visible only while a node is selected. */
    private MultiLineEditBox nodeTextBox;
    private EditBox speakerBox;
    private EditBox soundBox;
    /** Inline warning under the sound field — null when the id is blank or resolves. */
    private String soundWarning;
    /** Guards programmatic setValue during selection sync so it doesn't mark the graph dirty. */
    private boolean syncingInspector;

    private Button deleteNodeButton;
    private Button deleteEdgeButton;
    private Button setEntryButton;
    private EditBox questActionBox;
    /** Inline warning under the quest field — format errors only; unknown quests are rejected at save. */
    private String questWarning;
    /** "node"/"edge" while a delete is armed for confirmation, else null. */
    private String deleteArmed;
    private long deleteArmUntil;

    public DialogueEditorScreen(DialogueEditorScreenModel model) {
        super(Component.literal("Dialogue Editor: " + model.getTitle()));
        this.model = model;
    }

    public DialogueEditorScreenModel getModel() {
        return model;
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

        // Inspector text box — created hidden; shown/populated by syncInspectorWidgets().
        // Edits commit live on every keystroke (auto-commit model: selection changes
        // can never silently drop text because the model already holds it).
        int panelX = width - INSPECTOR_W - 10;
        int panelH = inspectorHeight();

        // Speaker + sound — single-line fields, live-commit like the text box
        speakerBox = new EditBox(this.font, panelX + 62, INSPECTOR_Y + 66,
                INSPECTOR_W - 72, 16, Component.literal("Speaker"));
        speakerBox.setMaxLength(60);
        speakerBox.setHint(Component.literal("blank = NPC name"));
        speakerBox.setResponder(v -> {
            if (!syncingInspector) model.updateSelectedNodeSpeaker(v);
        });
        speakerBox.visible = false;
        this.addRenderableWidget(speakerBox);

        soundBox = new EditBox(this.font, panelX + 62, INSPECTOR_Y + 88,
                INSPECTOR_W - 72, 16, Component.literal("Sound"));
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

        nodeTextBox = new MultiLineEditBox(this.font, panelX + 8, INSPECTOR_Y + 126,
                INSPECTOR_W - 16, Math.max(12, panelH - 166),
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
                .bounds(panelX + 8, INSPECTOR_Y + panelH - 30, 90, 20).build());
        deleteNodeButton.visible = false;

        setEntryButton = this.addRenderableWidget(Button.builder(
                Component.literal("Set Entry"), b -> {
                    String sel = model.getEditorState().getSelectedNodeId();
                    if (sel != null) {
                        model.setAsEntryNode(sel);
                        syncInspectorWidgets();
                    }
                }).bounds(panelX + 102, INSPECTOR_Y + panelH - 30, 90, 20).build());
        setEntryButton.visible = false;

        deleteEdgeButton = this.addRenderableWidget(Button.builder(
                Component.literal("Delete Edge"), b -> onDeleteEdgePressed())
                .bounds(panelX + 8, INSPECTOR_Y + 64, 90, 20).build());
        deleteEdgeButton.visible = false;

        // Edge actions — START_QUEST target. Blank clears the action; unknown
        // quest ids are rejected by the server's save validation with a message.
        questActionBox = new EditBox(this.font, panelX + 10, INSPECTOR_Y + 98,
                INSPECTOR_W - 20, 16, Component.literal("Quest id"));
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

        syncInspectorWidgets();
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
            if (node == null) {
                nodeTextBox.setFocused(false);
                speakerBox.setFocused(false);
                soundBox.setFocused(false);
                soundWarning = null;
            } else {
                syncingInspector = true;
                try {
                    nodeTextBox.setValue(node.getText() != null ? node.getText() : "");
                    speakerBox.setValue(node.getSpeaker() != null ? node.getSpeaker() : "");
                    soundBox.setValue(node.getSound() != null ? node.getSound() : "");
                } finally {
                    syncingInspector = false;
                }
                // Surface a warning even for pre-existing YAML data, not just new edits
                soundWarning = soundWarningFor(node.getSound());
            }
        }
        if (questActionBox != null) {
            questActionBox.visible = node == null && edge != null;
            if (edge == null) {
                questActionBox.setFocused(false);
                questWarning = null;
            } else {
                syncingInspector = true;
                try {
                    questActionBox.setValue(model.getSelectedEdgeStartQuest());
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
        return Math.min(INSPECTOR_MAX_H, Math.max(130, height - INSPECTOR_Y - 16));
    }

    /** Inspector occupies the right side while a node OR an edge is selected — canvas clicks there must not deselect. */
    private boolean insideInspector(double mouseX, double mouseY) {
        if (model.getEditorState().getSelectedNodeId() == null
                && model.getEditorState().getSelectedEdge() == null) return false;
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
        // Dark background canvas
        graphics.fill(0, 0, width, height, 0xFF121214);

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

                int color = edge.isCyclic() ? 0xFFFFA500 : 0xFF4ADE80; // Orange if cyclic, green otherwise
                graphics.fill(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2) + 2, Math.max(y1, y2) + 2, color);

                // Draw edge label
                int midX = (x1 + x2) / 2;
                int midY = (y1 + y2) / 2;
                boolean edgeSelected = edge == model.getEditorState().getSelectedEdge();
                graphics.fill(midX - 25, midY - 6, midX + 25, midY + 6,
                        edgeSelected ? 0xCC3B2A00 : 0xCC000000);
                if (edgeSelected) {
                    graphics.renderOutline(midX - 25, midY - 6, 50, 12, 0xFFFACC15);
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
            int borderColor = isSelected ? 0xFFFACC15 : (node.isEntryNode() ? 0xFF22C55E : 0xFF64748B);
            int bodyColor = isSelected ? 0xEE1E293B : 0xEE0F172A;

            graphics.fill(sx, sy, sx + sw, sy + sh, bodyColor);
            graphics.renderOutline(sx, sy, sw, sh, borderColor);

            // Node header and preview
            String titleStr = (node.isEntryNode() ? "[ENTRY] " : "") + node.getId();
            graphics.drawString(this.font, titleStr, sx + 6, sy + 6, borderColor, false);
            if (sh > 30) {
                graphics.drawWordWrap(this.font, Component.literal(node.getText()), sx + 6, sy + 20, Math.max(sw - 12, 10), 0xFFCBD5E1);
            }
        }

        // Render Top Bar
        graphics.fill(0, 0, width, 40, 0xDD0F172A);
        graphics.renderOutline(0, 0, width, 40, 0xFF334155);
        graphics.drawString(this.font, String.format("Dialogue: %s (%s)", model.getDialogueId(), model.getTitle()), 260, 16, 0xFFF8FAFC, false);

        // Status bar
        if (!model.getStatusMessage().isEmpty()) {
            graphics.drawString(this.font, model.getStatusMessage(), 10, height - 20, 0xFF94A3B8, false);
        }

        // Inspector panel for selected node
        renderInspector(graphics);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderGrid(GuiGraphics graphics, double panX, double panY, double zoom) {
        double gridSize = 40.0 * zoom;
        if (gridSize < 10) return;

        double startX = panX % gridSize;
        double startY = panY % gridSize;

        for (double x = startX; x < width; x += gridSize) {
            graphics.fill((int) x, 0, (int) x + 1, height, 0x1AFFFFFF);
        }
        for (double y = startY; y < height; y += gridSize) {
            graphics.fill(0, (int) y, width, (int) y + 1, 0x1AFFFFFF);
        }
    }

    private void renderInspector(GuiGraphics graphics) {
        if (deleteArmed != null && System.currentTimeMillis() > deleteArmUntil) {
            disarmDelete();
        }
        String selectedId = model.getEditorState().getSelectedNodeId();
        VisualEdge selEdge = model.getEditorState().getSelectedEdge();
        if (selectedId == null && selEdge == null) return;

        int panelX = width - INSPECTOR_W - 10;
        int panelY = INSPECTOR_Y;

        graphics.fill(panelX, panelY, panelX + INSPECTOR_W, panelY + inspectorHeight(), 0xEE0F172A);
        graphics.renderOutline(panelX, panelY, INSPECTOR_W, inspectorHeight(), 0xFF38BDF8);

        if (selEdge != null) {
            graphics.drawString(this.font, "Edge Inspector", panelX + 10, panelY + 10, 0xFF38BDF8, false);
            String endpoints = selEdge.getSourceNodeId() + " -> " + selEdge.getTargetNodeId();
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(endpoints, INSPECTOR_W - 20),
                    panelX + 10, panelY + 28, 0xFFE2E8F0, false);
            graphics.drawString(this.font, "Option text:", panelX + 10, panelY + 44, 0xFF94A3B8, false);
            graphics.drawWordWrap(this.font, Component.literal(selEdge.getText()),
                    panelX + 10, panelY + 56, INSPECTOR_W - 20, 0xFFCBD5E1);
            graphics.drawString(this.font, "Actions — START_QUEST:", panelX + 10, panelY + 88, 0xFF94A3B8, false);
            if (questWarning != null) {
                graphics.drawString(this.font, questWarning, panelX + 10, panelY + 118, 0xFFFBBF24, false);
            }
            return;
        }

        VisualNode node = model.getLayout().getNodes().get(selectedId);
        if (node == null) return;

        graphics.drawString(this.font, "Node Inspector", panelX + 10, panelY + 10, 0xFF38BDF8, false);
        graphics.drawString(this.font, "ID: " + node.getId(), panelX + 10, panelY + 28, 0xFFE2E8F0, false);
        graphics.drawString(this.font, "Pos: (" + (int)node.getX() + ", " + (int)node.getY() + ")", panelX + 10, panelY + 44, 0xFF94A3B8, false);
        graphics.drawString(this.font, "Entry: " + node.isEntryNode(), panelX + 10, panelY + 60, 0xFF94A3B8, false);
        graphics.drawString(this.font, "Speaker:", panelX + 10, panelY + 70, 0xFF94A3B8, false);
        graphics.drawString(this.font, "Sound:", panelX + 10, panelY + 92, 0xFF94A3B8, false);
        if (soundWarning != null) {
            graphics.drawString(this.font, soundWarning, panelX + 10, panelY + 107, 0xFFFBBF24, false);
        }
        graphics.drawString(this.font, "Text:", panelX + 10, panelY + 118, 0xFF94A3B8, false);
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
        // Inspector clicks must reach its widgets, not the canvas — otherwise they would deselect
        if (mouseY > 40 && !insideInspector(mouseX, mouseY)) {
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}