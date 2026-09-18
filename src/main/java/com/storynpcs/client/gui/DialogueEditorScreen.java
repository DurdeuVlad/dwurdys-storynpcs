package com.storynpcs.client.gui;

import com.storynpcs.editor.DialogueEditorScreenModel;
import com.storynpcs.editor.DialogueGraphLayout;
import com.storynpcs.editor.VisualEdge;
import com.storynpcs.editor.VisualNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class DialogueEditorScreen extends Screen {

    private final DialogueEditorScreenModel model;
    private boolean isPanning = false;
    private double lastMouseX;
    private double lastMouseY;

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
                graphics.fill(midX - 25, midY - 6, midX + 25, midY + 6, 0xCC000000);
                graphics.drawString(this.font, edge.getText(), midX - 20, midY - 4, color, false);
            }
        }

        // Render Nodes
        for (VisualNode node : model.getLayout().getNodes().values()) {
            int sx = (int) DialogueGraphLayout.canvasToScreenX(node.getX(), panX, zoom);
            int sy = (int) DialogueGraphLayout.canvasToScreenY(node.getY(), panX, zoom);
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
        String selectedId = model.getEditorState().getSelectedNodeId();
        if (selectedId == null) return;

        VisualNode node = model.getLayout().getNodes().get(selectedId);
        if (node == null) return;

        int panelW = 200;
        int panelX = width - panelW - 10;
        int panelY = 50;
        int panelH = 160;

        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE0F172A);
        graphics.renderOutline(panelX, panelY, panelW, panelH, 0xFF38BDF8);

        graphics.drawString(this.font, "Node Inspector", panelX + 10, panelY + 10, 0xFF38BDF8, false);
        graphics.drawString(this.font, "ID: " + node.getId(), panelX + 10, panelY + 28, 0xFFE2E8F0, false);
        graphics.drawString(this.font, "Pos: (" + (int)node.getX() + ", " + (int)node.getY() + ")", panelX + 10, panelY + 44, 0xFF94A3B8, false);
        graphics.drawString(this.font, "Entry: " + node.isEntryNode(), panelX + 10, panelY + 60, 0xFF94A3B8, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseY > 40) {
            VisualNode hit = model.getEditorState().findNodeAtScreen(mouseX, mouseY);
            if (hit != null) {
                if (model.getEditorState().getConnectingSourceNodeId() != null) {
                    model.completeConnectingEdge(hit.getId(), "Option");
                } else {
                    model.getEditorState().setSelectedNodeId(hit.getId());
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
                    model.getEditorState().setSelectedNodeId(null);
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