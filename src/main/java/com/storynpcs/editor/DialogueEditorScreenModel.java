package com.storynpcs.editor;

import com.storynpcs.domain.common.DiagnosticError;
import com.storynpcs.domain.common.NamespacedId;
import com.storynpcs.domain.common.ValidationResult;
import com.storynpcs.domain.dialogue.DialogueAction;
import com.storynpcs.domain.dialogue.DialogueCondition;
import com.storynpcs.domain.dialogue.DialogueGraph;
import com.storynpcs.domain.dialogue.DialogueGraphSerde;
import com.storynpcs.domain.dialogue.DialogueGraphValidator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public class DialogueEditorScreenModel {

    private static final int MAX_HISTORY = 64;
    /** Preview walkthrough depth cap — a malformed/self-looping graph can't run away. */
    private static final int MAX_PREVIEW_NODES = 64;

    private final NamespacedId dialogueId;
    private String title;
    private final GraphEditorState editorState;
    private final Consumer<DialogueGraph> onSaveCallback;

    private String statusMessage = "";
    private boolean unsavedChanges = false;

    private final Deque<Snapshot> undoStack = new ArrayDeque<>();
    private final Deque<Snapshot> redoStack = new ArrayDeque<>();
    private String searchQuery = "";
    private int searchMatchIndex = -1;

    public DialogueEditorScreenModel(DialogueGraph graph, Consumer<DialogueGraph> onSaveCallback) {
        this.dialogueId = graph != null ? graph.getId() : NamespacedId.of("storynpcs:new_dialogue");
        this.title = graph != null ? graph.getTitle() : "New Dialogue";
        this.onSaveCallback = onSaveCallback;

        DialogueGraphLayout layout = graph != null
                ? DialogueGraphLayout.fromDialogueGraph(graph)
                : new DialogueGraphLayout();
        this.editorState = new GraphEditorState(layout);
    }

    public NamespacedId getDialogueId() { return dialogueId; }
    public String getTitle() { return title; }
    public GraphEditorState getEditorState() { return editorState; }
    public DialogueGraphLayout getLayout() { return editorState.getLayout(); }
    public String getStatusMessage() { return statusMessage; }
    public boolean hasUnsavedChanges() { return unsavedChanges; }

    // ── undo/redo ────────────────────────────────────────────────────────────

    private record Snapshot(DialogueGraphLayout layout, String selectedNodeId, int selectedEdgeIndex) {}

    private String lastUndoKey;

    /**
     * Pushes a pre-mutation snapshot. {@code coalesceKey} collapses repeated
     * calls (keystroke-frequency field edits) into one undo step — a different
     * key or any non-coalesced mutation starts a new step.
     */
    private void pushUndo(String coalesceKey) {
        if (coalesceKey != null && coalesceKey.equals(lastUndoKey)) {
            return;
        }
        lastUndoKey = coalesceKey;
        undoStack.push(new Snapshot(getLayout().deepCopy(),
                editorState.getSelectedNodeId(), editorState.getSelectedEdgeIndex()));
        while (undoStack.size() > MAX_HISTORY) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    private void pushUndo() {
        pushUndo(null);
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        redoStack.push(new Snapshot(getLayout().deepCopy(),
                editorState.getSelectedNodeId(), editorState.getSelectedEdgeIndex()));
        restore(undoStack.pop());
        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        undoStack.push(new Snapshot(getLayout().deepCopy(),
                editorState.getSelectedNodeId(), editorState.getSelectedEdgeIndex()));
        restore(redoStack.pop());
        return true;
    }

    private void restore(Snapshot snapshot) {
        editorState.replaceLayout(snapshot.layout().deepCopy());
        lastUndoKey = null;
        if (snapshot.selectedNodeId() != null
                && getLayout().getNodes().containsKey(snapshot.selectedNodeId())) {
            editorState.setSelectedNodeId(snapshot.selectedNodeId());
        } else {
            editorState.selectEdgeIndex(snapshot.selectedEdgeIndex());
        }
        unsavedChanges = true;
        statusMessage = "Restored earlier state";
    }

    public void setTitle(String title) {
        pushUndo();
        this.title = title != null ? title : "";
        unsavedChanges = true;
    }

    /** Graph-level localization key for the title (blank = literal title). */
    public String getTitleKey() { return getLayout().getTitleKey(); }
    public void setTitleKey(String titleKey) {
        pushUndo("titleKey");
        getLayout().setTitleKey(titleKey);
        unsavedChanges = true;
    }

    // ── search ───────────────────────────────────────────────────────────────

    /** One search hit — node id for node hits, edge index for edge hits. */
    public record SearchMatch(boolean edge, String nodeId, int edgeIndex, String detail) {}

    /**
     * Case-insensitive substring search across every authored field: node
     * id/text/speaker/sound/textKey, edge option text/textKey/target, and
     * condition/action payloads. Deterministic order — layout order.
     */
    private List<SearchMatch> lastSearchMatches = List.of();

    public List<SearchMatch> search(String query) {
        searchQuery = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        searchMatchIndex = -1;
        List<SearchMatch> matches = new ArrayList<>();
        if (searchQuery.isEmpty()) {
            return matches;
        }
        for (VisualNode node : getLayout().getNodes().values()) {
            if (contains(node.getId(), searchQuery) || contains(node.getText(), searchQuery)
                    || contains(node.getSpeaker(), searchQuery) || contains(node.getSound(), searchQuery)
                    || contains(node.getTextKey(), searchQuery)) {
                matches.add(new SearchMatch(false, node.getId(), -1, node.getText()));
            }
        }
        List<VisualEdge> edges = getLayout().getEdges();
        for (int i = 0; i < edges.size(); i++) {
            VisualEdge edge = edges.get(i);
            StringBuilder hay = new StringBuilder()
                    .append(edge.getText()).append(' ').append(edge.getTextKey())
                    .append(' ').append(edge.getSourceNodeId())
                    .append(' ').append(edge.getTargetNodeId());
            for (DialogueCondition c : edge.getConditions()) {
                hay.append(' ').append(c.getType()).append(' ').append(c.getTarget())
                        .append(' ').append(c.getOperator()).append(' ').append(c.getValue());
            }
            for (DialogueAction a : edge.getActions()) {
                hay.append(' ').append(a.getType()).append(' ').append(a.getTarget())
                        .append(' ').append(a.getValue());
            }
            if (hay.toString().toLowerCase(Locale.ROOT).contains(searchQuery)) {
                matches.add(new SearchMatch(true, null, i,
                        edge.getSourceNodeId() + " -> " + edge.getTargetNodeId()));
            }
        }
        lastSearchMatches = List.copyOf(matches);
        return matches;
    }

    /** The search hits from the most recent {@link #search(String)} call. */
    public List<SearchMatch> searchMatches() {
        return lastSearchMatches;
    }

    /** Cycles to the next/previous search hit, selecting and marking dirty-free. */
    public boolean nextSearchMatch(boolean forward) {
        List<SearchMatch> matches = lastSearchMatches;
        if (matches.isEmpty()) {
            statusMessage = searchQuery.isEmpty() ? "No search query" : "No matches for '" + searchQuery + "'";
            return false;
        }
        searchMatchIndex = (searchMatchIndex + (forward ? 1 : -1) + matches.size()) % matches.size();
        SearchMatch match = matches.get(searchMatchIndex);
        if (match.edge()) {
            editorState.selectEdgeIndex(match.edgeIndex());
        } else {
            editorState.setSelectedNodeId(match.nodeId());
        }
        statusMessage = "Match " + (searchMatchIndex + 1) + "/" + matches.size()
                + (match.edge() ? " (edge " : " (node ") + match.detail() + ")";
        return true;
    }

    private static boolean contains(String field, String query) {
        return field != null && field.toLowerCase(Locale.ROOT).contains(query);
    }

    public VisualNode addNode(String id, String text, double canvasX, double canvasY) {
        if (id == null || id.trim().isEmpty()) {
            id = "node_" + UUID.randomUUID().toString().substring(0, 6);
        }
        VisualNode node = new VisualNode(id, text, canvasX, canvasY);
        pushUndo();
        getLayout().addNode(node);
        if (getLayout().getEntryNodeId() == null) {
            getLayout().setEntryNodeId(id);
        }
        editorState.setSelectedNodeId(id);
        unsavedChanges = true;
        statusMessage = "Added node: " + id;
        return node;
    }

    /**
     * Removes the selected node and every edge touching it. Refuses to delete the
     * entry node — silently reassigning it would surprise the author, so the caller
     * must move the entry flag first. Returns false (with a status message) in that case.
     */
    public boolean removeSelectedNode() {
        String selectedId = editorState.getSelectedNodeId();
        if (selectedId == null) {
            return false;
        }
        VisualNode node = getLayout().getNodes().get(selectedId);
        if (node == null) {
            return false;
        }
        if (node.isEntryNode()) {
            statusMessage = "Cannot delete the entry node — set another node as entry first";
            return false;
        }
        pushUndo();
        getLayout().removeNode(selectedId);
        editorState.setSelectedNodeId(null);
        unsavedChanges = true;
        statusMessage = "Removed node: " + selectedId;
        return true;
    }

    /** Removes the selected edge only — both endpoint nodes are untouched. */
    public boolean removeSelectedEdge() {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge == null) {
            return false;
        }
        pushUndo();
        getLayout().removeEdge(edge);
        editorState.setSelectedEdge(null);
        unsavedChanges = true;
        statusMessage = "Removed edge " + edge.getSourceNodeId() + " -> " + edge.getTargetNodeId();
        return true;
    }

    public void updateSelectedNodeText(String newText) {
        String selectedId = editorState.getSelectedNodeId();
        if (selectedId != null) {
            VisualNode node = getLayout().getNodes().get(selectedId);
            if (node != null) {
                pushUndo("nodeText:" + selectedId);
                node.setText(newText != null ? newText : "");
                unsavedChanges = true;
            }
        }
    }

    /** Canvas position edit from the inspector (x,y are layout state). */
    public boolean updateSelectedNodePosition(double x, double y) {
        String selectedId = editorState.getSelectedNodeId();
        VisualNode node = selectedId != null ? getLayout().getNodes().get(selectedId) : null;
        if (node == null) return false;
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isInfinite(x) || Double.isInfinite(y)) {
            return false;
        }
        pushUndo("nodePos:" + selectedId);
        node.setX(x);
        node.setY(y);
        unsavedChanges = true;
        return true;
    }

    /** Node localization key (blank = literal text). Coalesced per node. */
    public void updateSelectedNodeTextKey(String textKey) {
        String selectedId = editorState.getSelectedNodeId();
        VisualNode node = selectedId != null ? getLayout().getNodes().get(selectedId) : null;
        if (node != null) {
            pushUndo("nodeTextKey:" + selectedId);
            node.setTextKey(textKey);
            unsavedChanges = true;
        }
    }

    /**
     * Renames the selected node, retargeting every edge endpoint and the entry
     * flag. Blank/duplicate ids are rejected with a status message — renaming
     * must never orphan edges.
     */
    public boolean renameSelectedNode(String newId) {
        String selectedId = editorState.getSelectedNodeId();
        VisualNode node = selectedId != null ? getLayout().getNodes().get(selectedId) : null;
        String trimmed = newId != null ? newId.trim() : "";
        if (node == null) return false;
        if (trimmed.isEmpty()) {
            statusMessage = "Node id cannot be blank";
            return false;
        }
        if (trimmed.equals(selectedId)) return true;
        if (getLayout().getNodes().containsKey(trimmed)) {
            statusMessage = "A node named '" + trimmed + "' already exists";
            return false;
        }
        pushUndo();
        getLayout().renameNodeId(selectedId, trimmed);
        for (VisualEdge edge : getLayout().getEdges()) {
            if (edge.getSourceNodeId().equals(selectedId)) edge.setSourceNodeId(trimmed);
            if (edge.getTargetNodeId().equals(selectedId)) edge.setTargetNodeId(trimmed);
        }
        if (Objects.equals(getLayout().getEntryNodeId(), selectedId)) {
            getLayout().setEntryNodeId(trimmed);
        }
        getLayout().recalculateCycles();
        editorState.setSelectedNodeId(trimmed);
        unsavedChanges = true;
        statusMessage = "Renamed node " + selectedId + " -> " + trimmed;
        return true;
    }

    /** Quest id targeted by the selected edge's START_QUEST action, or "" if it has none. */
    public String getSelectedEdgeStartQuest() {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge == null) return "";
        for (DialogueAction a : edge.getActions()) {
            if (a.getType() == DialogueAction.Type.START_QUEST) {
                return a.getTarget() != null ? a.getTarget() : "";
            }
        }
        return "";
    }

    /**
     * Sets (or clears, when blank) the selected edge's START_QUEST quest target.
     * An existing action is replaced in place so its position among the edge's
     * other actions — and its value payload — is preserved.
     */
    public void setSelectedEdgeStartQuest(String questId) {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge == null) return;
        String q = questId != null ? questId.trim() : "";
        pushUndo("edgeQuest:" + edgeIndexKey(edge));
        List<DialogueAction> actions = edge.getActions();
        int idx = -1;
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).getType() == DialogueAction.Type.START_QUEST) { idx = i; break; }
        }
        if (q.isEmpty()) {
            if (idx >= 0) actions.remove(idx);
        } else if (idx >= 0) {
            actions.set(idx, new DialogueAction(DialogueAction.Type.START_QUEST, q, actions.get(idx).getValue()));
        } else {
            actions.add(0, new DialogueAction(DialogueAction.Type.START_QUEST, q, ""));
        }
        unsavedChanges = true;
    }

    /** Per-node speaker override; blank restores the NPC-name/title fallback at runtime. */
    public void updateSelectedNodeSpeaker(String speaker) {
        String selectedId = editorState.getSelectedNodeId();
        if (selectedId != null) {
            VisualNode node = getLayout().getNodes().get(selectedId);
            if (node != null) {
                pushUndo("nodeSpeaker:" + selectedId);
                node.setSpeaker(speaker != null ? speaker : "");
                unsavedChanges = true;
            }
        }
    }

    /** Per-node sound event id; blank means no sound. Validation feedback is a UI concern (see screen). */
    public void updateSelectedNodeSound(String sound) {
        String selectedId = editorState.getSelectedNodeId();
        if (selectedId != null) {
            VisualNode node = getLayout().getNodes().get(selectedId);
            if (node != null) {
                pushUndo("nodeSound:" + selectedId);
                node.setSound(sound != null ? sound.trim() : "");
                unsavedChanges = true;
            }
        }
    }

    public void setAsEntryNode(String nodeId) {
        if (getLayout().getNodes().containsKey(nodeId)) {
            pushUndo();
            getLayout().setEntryNodeId(nodeId);
            unsavedChanges = true;
            statusMessage = "Set entry node to: " + nodeId;
        }
    }

    public void startConnectingEdge(String sourceNodeId) {
        editorState.setConnectingSourceNodeId(sourceNodeId);
        statusMessage = "Click target node to connect edge...";
    }

    public void completeConnectingEdge(String targetNodeId, String choiceText) {
        if (editorState.getConnectingSourceNodeId() != null) {
            String src = editorState.getConnectingSourceNodeId();
            pushUndo();
            editorState.connectEdge(targetNodeId, choiceText != null && !choiceText.isEmpty() ? choiceText : "Continue");
            unsavedChanges = true;
            statusMessage = String.format("Connected %s -> %s", src, targetNodeId);
        }
    }

    public void cancelConnectingEdge() {
        editorState.setConnectingSourceNodeId(null);
        statusMessage = "Edge connection canceled";
    }

    // ── edge property editing (P5-3) ─────────────────────────────────────────

    private int edgeIndexKey(VisualEdge edge) {
        return getLayout().getEdges().indexOf(edge);
    }

    /** Option text of the selected edge — the player-facing choice label. */
    public void updateSelectedEdgeText(String text) {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge != null) {
            pushUndo("edgeText:" + edgeIndexKey(edge));
            edge.setText(text);
            unsavedChanges = true;
        }
    }

    public void setSelectedEdgeOnceOnly(boolean onceOnly) {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge != null) {
            pushUndo();
            edge.setOnceOnly(onceOnly);
            unsavedChanges = true;
        }
    }

    /** Option localization key (blank = literal text). */
    public void updateSelectedEdgeTextKey(String textKey) {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge != null) {
            pushUndo("edgeTextKey:" + edgeIndexKey(edge));
            edge.setTextKey(textKey);
            unsavedChanges = true;
        }
    }

    /** Re-targets the selected edge to a different existing node. */
    public boolean retargetSelectedEdge(String targetNodeId) {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge == null) return false;
        String t = targetNodeId != null ? targetNodeId.trim() : "";
        if (!getLayout().getNodes().containsKey(t)) {
            statusMessage = "No node '" + t + "' to target";
            return false;
        }
        pushUndo();
        edge.setTargetNodeId(t);
        getLayout().recalculateCycles();
        unsavedChanges = true;
        return true;
    }

    /** Edge conditions — the edge only renders when all evaluate true. */
    public List<DialogueCondition> getSelectedEdgeConditions() {
        VisualEdge edge = editorState.getSelectedEdge();
        return edge != null ? edge.getConditions() : List.of();
    }

    public void addSelectedEdgeCondition(DialogueCondition condition) {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge == null || condition == null) return;
        pushUndo();
        edge.getConditions().add(condition);
        unsavedChanges = true;
    }

    public void updateSelectedEdgeCondition(int index, DialogueCondition condition) {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge == null || condition == null
                || index < 0 || index >= edge.getConditions().size()) return;
        pushUndo();
        edge.getConditions().set(index, condition);
        unsavedChanges = true;
    }

    public void removeSelectedEdgeCondition(int index) {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge == null || index < 0 || index >= edge.getConditions().size()) return;
        pushUndo();
        edge.getConditions().remove(index);
        unsavedChanges = true;
    }

    /** Edge actions — fired once when the option is chosen (choice tokens gate replay). */
    public List<DialogueAction> getSelectedEdgeActions() {
        VisualEdge edge = editorState.getSelectedEdge();
        return edge != null ? edge.getActions() : List.of();
    }

    public void addSelectedEdgeAction(DialogueAction action) {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge == null || action == null) return;
        pushUndo();
        edge.getActions().add(action);
        unsavedChanges = true;
    }

    public void updateSelectedEdgeAction(int index, DialogueAction action) {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge == null || action == null
                || index < 0 || index >= edge.getActions().size()) return;
        pushUndo();
        edge.getActions().set(index, action);
        unsavedChanges = true;
    }

    public void removeSelectedEdgeAction(int index) {
        VisualEdge edge = editorState.getSelectedEdge();
        if (edge == null || index < 0 || index >= edge.getActions().size()) return;
        pushUndo();
        edge.getActions().remove(index);
        unsavedChanges = true;
    }

    // ── graph-level availability (Dialog.availability parity) ────────────────

    public List<DialogueCondition> getAvailability() {
        return getLayout().getAvailability();
    }

    public void addAvailabilityCondition(DialogueCondition condition) {
        if (condition == null) return;
        pushUndo();
        getLayout().getAvailability().add(condition);
        unsavedChanges = true;
    }

    public void updateAvailabilityCondition(int index, DialogueCondition condition) {
        if (condition == null || index < 0 || index >= getLayout().getAvailability().size()) return;
        pushUndo();
        getLayout().getAvailability().set(index, condition);
        unsavedChanges = true;
    }

    public void removeAvailabilityCondition(int index) {
        if (index < 0 || index >= getLayout().getAvailability().size()) return;
        pushUndo();
        getLayout().getAvailability().remove(index);
        unsavedChanges = true;
    }

    // ── validation + preview (P5-3) ──────────────────────────────────────────

    /**
     * Runs the same {@link DialogueGraphValidator} the server save path uses —
     * the preview/validation pane is never a weaker local re-check.
     */
    public List<DiagnosticError> validate() {
        return new DialogueGraphValidator().validate(exportGraph()).getDiagnostics();
    }

    /**
     * One walkthrough line: node id, speaker, first line of text, and each
     * outgoing option annotated with its gate/action summary.
     */
    public record PreviewLine(String nodeId, String speaker, String text,
                              boolean entry, List<String> options) {}
    public record PreviewResult(boolean valid, List<PreviewLine> lines,
                                List<DiagnosticError> diagnostics) {}

    /**
     * Authoring preview: validates through the server validator, then walks
     * breadth-first from the entry node — bounded at {@link #MAX_PREVIEW_NODES}
     * so cyclic graphs terminate — listing each node's options with their
     * conditions/actions summarized. Conditions are NOT evaluated (no player
     * context exists); gated options are marked so authors see the gate.
     */
    public PreviewResult preview() {
        List<DiagnosticError> diagnostics = validate();
        boolean valid = diagnostics.stream().noneMatch(
                d -> d.severity() == DiagnosticError.Severity.ERROR);
        List<PreviewLine> lines = new ArrayList<>();
        String entry = getLayout().getEntryNodeId();
        if (entry == null) {
            return new PreviewResult(valid, lines, diagnostics);
        }
        Deque<String> queue = new ArrayDeque<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        queue.add(entry);
        while (!queue.isEmpty() && lines.size() < MAX_PREVIEW_NODES) {
            String id = queue.poll();
            if (!visited.add(id)) continue;
            VisualNode node = getLayout().getNodes().get(id);
            if (node == null) continue;
            List<String> options = new ArrayList<>();
            for (VisualEdge edge : getLayout().getEdges()) {
                if (edge.getSourceNodeId().equals(id)) {
                    StringBuilder opt = new StringBuilder(edge.getText());
                    if (!edge.getConditions().isEmpty()) {
                        opt.append(" [if ").append(edge.getConditions().size()).append(" cond]");
                    }
                    if (!edge.getActions().isEmpty()) {
                        opt.append(" [").append(edge.getActions().size()).append(" action]");
                    }
                    if (edge.isOnceOnly()) opt.append(" [once]");
                    opt.append(" -> ").append(edge.getTargetNodeId());
                    options.add(opt.toString());
                    if (!visited.contains(edge.getTargetNodeId())) {
                        queue.add(edge.getTargetNodeId());
                    }
                }
            }
            lines.add(new PreviewLine(id, node.getSpeaker(), node.getText(),
                    id.equals(entry), options));
        }
        return new PreviewResult(valid, lines, diagnostics);
    }

    /**
     * Extracts the node id a diagnostic points at, if the message embeds the
     * validator's {@code node 'id'} marker — used for click-to-select in the
     * diagnostics pane.
     */
    public static String referencedNodeId(DiagnosticError diagnostic) {
        if (diagnostic == null || diagnostic.message() == null) return null;
        var matcher = java.util.regex.Pattern.compile("node '([^']+)'")
                .matcher(diagnostic.message());
        return matcher.find() ? matcher.group(1) : null;
    }

    // ── import/export + keyboard selection cycling (P5-3) ────────────────────

    /** Full-fidelity JSON export through the same serde the loader uses. */
    public String exportJson() {
        return DialogueGraphSerde.toJson(exportGraph());
    }

    /**
     * Imports a JSON graph document. Parse failures are reported in the status
     * line and leave the working graph untouched; a successful import is one
     * undoable step.
     */
    public boolean importJson(String json) {
        var parsed = DialogueGraphSerde.fromJson(json);
        if (parsed.isEmpty()) {
            statusMessage = "Import failed: document is not a dialogue graph";
            return false;
        }
        DialogueGraph graph = parsed.get();
        pushUndo();
        editorState.replaceLayout(DialogueGraphLayout.fromDialogueGraph(graph));
        this.title = graph.getTitle() != null ? graph.getTitle() : this.title;
        lastUndoKey = null;
        unsavedChanges = true;
        statusMessage = "Imported " + graph.getNodes().size() + " nodes";
        return true;
    }

    /**
     * Keyboard selection cycling (P5-3): Tab/Shift-Tab walk every selectable —
     * nodes in layout order first, then edges — wrapping at both ends.
     */
    public void cycleSelection(boolean forward) {
        List<String> nodeIds = new ArrayList<>(getLayout().getNodes().keySet());
        List<VisualEdge> edges = getLayout().getEdges();
        int total = nodeIds.size() + edges.size();
        if (total == 0) return;
        int current;
        String selNode = editorState.getSelectedNodeId();
        VisualEdge selEdge = editorState.getSelectedEdge();
        if (selNode != null) {
            current = nodeIds.indexOf(selNode);
        } else if (selEdge != null) {
            current = nodeIds.size() + edges.indexOf(selEdge);
        } else {
            current = forward ? -1 : 0;
        }
        int next = (current + (forward ? 1 : -1) + total) % total;
        if (next < nodeIds.size()) {
            editorState.setSelectedNodeId(nodeIds.get(next));
        } else {
            editorState.setSelectedEdge(edges.get(next - nodeIds.size()));
        }
    }

    /** Centers the view on the current selection for the given viewport size. */
    public void centerOnSelection(double viewportWidth, double viewportHeight) {
        String sel = editorState.getSelectedNodeId();
        if (sel != null) {
            editorState.centerOnNode(sel, viewportWidth, viewportHeight);
        }
    }

    public DialogueGraph exportGraph() {
        return getLayout().toDialogueGraph(dialogueId, title);
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage != null ? statusMessage : "";
    }

    /**
     * Sends the exported graph to the save callback (the server, in production).
     * The status reflects that a save was *requested* — the real outcome arrives
     * via {@link #onSaveResult} once the server validates and persists.
     */
    public void save() {
        DialogueGraph graph = exportGraph();
        if (onSaveCallback != null) {
            onSaveCallback.accept(graph);
            statusMessage = "Save sent — awaiting server confirmation...";
        } else {
            statusMessage = "Cannot save: no server connection.";
        }
    }

    /**
     * Applies the server's verdict on a save request. Only a successful save
     * clears the unsaved-changes flag; a rejection keeps it so nothing is lost.
     */
    public void onSaveResult(boolean success, String message) {
        statusMessage = message != null ? message : (success ? "Saved." : "Save rejected.");
        if (success) {
            unsavedChanges = false;
        }
    }
}