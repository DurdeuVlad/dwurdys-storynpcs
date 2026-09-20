package com.storynpcs.editor;

public class GraphEditorState {

    private final DialogueGraphLayout layout;
    private double panX = 0;
    private double panY = 0;
    private double zoom = 1.0;

    private String selectedNodeId;
    private VisualEdge selectedEdge;
    private String connectingSourceNodeId;

    private String draggingNodeId;
    private double dragStartMouseX;
    private double dragStartMouseY;
    private double dragStartNodeX;
    private double dragStartNodeY;

    public GraphEditorState(DialogueGraphLayout layout) {
        this.layout = layout != null ? layout : new DialogueGraphLayout();
    }

    public DialogueGraphLayout getLayout() { return layout; }

    public double getPanX() { return panX; }
    public double getPanY() { return panY; }
    public double getZoom() { return zoom; }

    public void pan(double dx, double dy) {
        this.panX += dx;
        this.panY += dy;
    }

    public void setZoom(double newZoom) {
        this.zoom = Math.max(0.25, Math.min(3.0, newZoom));
    }

    public void zoomIn() {
        setZoom(zoom * 1.15);
    }

    public void zoomOut() {
        setZoom(zoom / 1.15);
    }

    public void resetView() {
        this.panX = 0;
        this.panY = 0;
        this.zoom = 1.0;
    }

    public String getSelectedNodeId() { return selectedNodeId; }
    public void setSelectedNodeId(String selectedNodeId) {
        this.selectedNodeId = selectedNodeId;
        this.selectedEdge = null;
    }

    public VisualEdge getSelectedEdge() { return selectedEdge; }
    public void setSelectedEdge(VisualEdge selectedEdge) {
        this.selectedEdge = selectedEdge;
        this.selectedNodeId = null;
    }

    public String getConnectingSourceNodeId() { return connectingSourceNodeId; }
    public void setConnectingSourceNodeId(String connectingSourceNodeId) {
        this.connectingSourceNodeId = connectingSourceNodeId;
    }

    /**
     * Hit-tests the edge label — the renderer draws a ~50x12 label box at the
     * midpoint of each edge's center-to-center segment; a click inside it selects
     * that edge.
     */
    public VisualEdge findEdgeAtScreen(double screenX, double screenY) {
        for (VisualEdge edge : layout.getEdges()) {
            VisualNode src = layout.getNodes().get(edge.getSourceNodeId());
            VisualNode dst = layout.getNodes().get(edge.getTargetNodeId());
            if (src == null || dst == null) continue;
            double midX = (DialogueGraphLayout.canvasToScreenX(src.getCenterX(), panX, zoom)
                    + DialogueGraphLayout.canvasToScreenX(dst.getCenterX(), panX, zoom)) / 2.0;
            double midY = (DialogueGraphLayout.canvasToScreenY(src.getCenterY(), panY, zoom)
                    + DialogueGraphLayout.canvasToScreenY(dst.getCenterY(), panY, zoom)) / 2.0;
            if (Math.abs(screenX - midX) <= 25 && Math.abs(screenY - midY) <= 6) {
                return edge;
            }
        }
        return null;
    }

    public VisualNode findNodeAtScreen(double screenX, double screenY) {
        double canvasX = DialogueGraphLayout.screenToCanvasX(screenX, panX, zoom);
        double canvasY = DialogueGraphLayout.screenToCanvasY(screenY, panY, zoom);

        for (VisualNode node : layout.getNodes().values()) {
            if (node.contains(canvasX, canvasY)) {
                return node;
            }
        }
        return null;
    }

    public void startDragNode(String nodeId, double screenX, double screenY) {
        VisualNode node = layout.getNodes().get(nodeId);
        if (node != null) {
            this.draggingNodeId = nodeId;
            this.dragStartMouseX = screenX;
            this.dragStartMouseY = screenY;
            this.dragStartNodeX = node.getX();
            this.dragStartNodeY = node.getY();
        }
    }

    public void dragNode(double screenX, double screenY) {
        if (draggingNodeId != null) {
            VisualNode node = layout.getNodes().get(draggingNodeId);
            if (node != null) {
                double deltaX = (screenX - dragStartMouseX) / zoom;
                double deltaY = (screenY - dragStartMouseY) / zoom;
                node.setX(dragStartNodeX + deltaX);
                node.setY(dragStartNodeY + deltaY);
            }
        }
    }

    public void endDrag() {
        this.draggingNodeId = null;
    }

    public boolean isDragging() {
        return draggingNodeId != null;
    }

    public void connectEdge(String targetNodeId, String choiceText) {
        if (connectingSourceNodeId != null && targetNodeId != null && !connectingSourceNodeId.equals(targetNodeId)) {
            VisualEdge edge = new VisualEdge(connectingSourceNodeId, targetNodeId, choiceText);
            layout.addEdge(edge);
        }
        this.connectingSourceNodeId = null;
    }
}