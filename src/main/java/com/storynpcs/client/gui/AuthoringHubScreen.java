package com.storynpcs.client.gui;

import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.client.ui.widgets.ScrollState;
import com.storynpcs.client.ui.widgets.SelectableList;
import com.storynpcs.client.ui.widgets.SelectionModel;
import com.storynpcs.editor.hub.AuthoringHub;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Unified authoring hub (P10-1): the searchable, paginated panel list that
 * routes creators to every parity domain. Panels open via their declared
 * routes — editor screens arrive as server payloads after the matching
 * command; YAML/tool surfaces give the exact command or family path.
 * Server-authoritative: the hub never fabricates editor state client-side.
 *
 * <p>Migrated onto the shared chrome (#207): framed surface instead of an
 * opaque full-screen fill, the list is a {@link SelectableList} keyed by the
 * {@link AuthoringHub.Panel} enum, and feedback echoes ride the footer line.
 * Up/Down/Tab cycle the page, Enter dispatches the first command route,
 * PageUp/PageDown flip pages — same keyboard contract as before.
 */
public class AuthoringHubScreen extends UiScreen {

    private static final int LIST_W = 150;
    private static final int PAGE_ROW_H = UiTheme.BUTTON_H + 4;

    private final AuthoringHub hub = new AuthoringHub();
    private final SelectionModel<AuthoringHub.Panel> selection = new SelectionModel<>();
    private final ScrollState scroll = new ScrollState();
    private EditBox searchBox;
    private SelectableList<AuthoringHub.Panel, AuthoringHub.Panel> panelList;
    private List<AuthoringHub.Panel> filtered = List.of(AuthoringHub.Panel.values());
    private int page;
    private String lastQuery = "";
    private boolean searchHadFocus;

    public AuthoringHubScreen() {
        super(Component.literal("StoryNPCs — Authoring Hub"));
    }

    @Override
    protected void initContent() {
        searchBox = new EditBox(this.font, contentLeft(), contentTop(), LIST_W,
                UiTheme.BUTTON_H, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search panels..."));
        // Seed before the responder attaches — init() rebuilds (resize) must
        // not silently reset a live filter.
        searchBox.setValue(lastQuery);
        if (searchHadFocus) {
            searchBox.setFocused(true);
            this.setFocused(searchBox);
        }
        searchBox.setResponder(v -> {
            searchHadFocus = true;
            lastQuery = v;
            page = 0;
            refilter();
        });
        addRenderableWidget(searchBox);

        int listTop = contentTop() + UiTheme.BUTTON_H + UiTheme.PAD_M;
        int listBottom = contentBottom() - PAGE_ROW_H;
        panelList = new SelectableList<>(contentLeft(), listTop, LIST_W,
                Math.max(1, listBottom - listTop), UiTheme.ROW_H,
                p -> p, p -> Component.literal(p.name().replace('_', ' ')),
                selection, scroll);
        panelList.setOnSelect(p -> hub.open(p, null));
        panelList.setOnActivate(p -> dispatchSelected());
        addRenderableWidget(panelList);

        // Page controls under the list column. Rebuild on flip so the
        // disabled-state affordance tracks the bounds.
        int navY = contentBottom() - UiTheme.BUTTON_H;
        Button prevBtn = Button.builder(Component.literal("<"), b -> gotoPage(page - 1))
                .bounds(contentLeft(), navY, 20, UiTheme.BUTTON_H).build();
        prevBtn.active = page > 0;
        addRenderableWidget(prevBtn);
        Button nextBtn = Button.builder(Component.literal(">"), b -> gotoPage(page + 1))
                .bounds(contentLeft() + 24, navY, 20, UiTheme.BUTTON_H).build();
        nextBtn.active = page < pageCount() - 1;
        addRenderableWidget(nextBtn);

        addFooterAction(Component.literal("Close"), b -> onClose());
        setStatus(Component.literal("Enter opens the first command route — arrows cycle.")
                .withColor(UiTheme.TEXT_MUTED));
        refilter();
    }

    private void refilter() {
        filtered = AuthoringHub.searchPanels(searchBox != null ? searchBox.getValue() : "");
        int pc = pageCount();
        if (page >= pc) page = Math.max(0, pc - 1);
        if (!filtered.contains(hub.state().panel()) && !filtered.isEmpty()) {
            hub.open(filtered.get(0), null);
        }
        refreshRows();
    }

    private void gotoPage(int target) {
        if (target == page || target < 0 || target >= pageCount()) return;
        page = target;
        rebuildWidgets();
    }

    private void refreshRows() {
        List<AuthoringHub.Panel> visible = AuthoringHub.page(filtered, page, rowsPerPage());
        panelList.setRows(visible);
        selection.select(hub.state().panel());
    }

    private int rowsPerPage() {
        int fit = (contentBottom() - contentTop() - UiTheme.BUTTON_H - UiTheme.PAD_M - PAGE_ROW_H)
                / UiTheme.ROW_H;
        return Math.max(1, Math.min(fit, 12));
    }

    private int pageCount() {
        return Math.max(1, AuthoringHub.pageCount(filtered.size(), rowsPerPage()));
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Page indicator under the list, beside the < > buttons.
        g.drawString(this.font,
                "page " + (page + 1) + "/" + pageCount() + "  (" + filtered.size() + " panels)",
                contentLeft() + 50, contentBottom() - UiTheme.BUTTON_H + 3, UiTheme.TEXT_MUTED);

        // Detail pane — routes for the selected panel.
        int detailX = contentLeft() + LIST_W + UiTheme.PAD_L;
        int detailW = Math.max(40, contentRight() - detailX);
        if (filtered.isEmpty()) {
            renderEmpty(g, "No panels match the search.");
            return;
        }
        AuthoringHub.Panel sel = hub.state().panel();
        int dy = contentTop() + UiTheme.BUTTON_H + UiTheme.PAD_M;
        g.drawString(this.font, "§b" + sel.name().replace('_', ' '), detailX, dy, UiTheme.ACCENT);
        dy += 12;
        g.drawString(this.font,
                this.font.plainSubstrByWidth(AuthoringHub.preview(sel), detailW),
                detailX, dy, UiTheme.TEXT_MUTED);
        dy += 16;
        for (AuthoringHub.Route route : AuthoringHub.routesFor(sel)) {
            if (dy > contentBottom() - 24) break;
            g.drawString(this.font,
                    this.font.plainSubstrByWidth("• " + route.description(), detailW),
                    detailX, dy, UiTheme.TEXT);
            g.drawString(this.font,
                    this.font.plainSubstrByWidth("   " + route.kind() + ": " + route.openPath(),
                            detailW),
                    detailX, dy + 9, UiTheme.TEXT_MUTED);
            dy += 22;
        }
        // Diagnostics — schema-path + repair-hint errors reported to the hub.
        for (AuthoringHub.FieldError err : hub.errors()) {
            if (dy > contentBottom() - 12) break;
            g.drawString(this.font,
                    this.font.plainSubstrByWidth(
                            "⚠ " + err.schemaPath() + ": " + err.message()
                                    + " — " + err.repairHint(),
                            detailW),
                    detailX, dy, UiTheme.DANGER);
            dy += 11;
        }
    }

    /** Up/Down/Tab cycle the filtered list; Enter dispatches; PgUp/PgDn flip pages. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() == searchBox && keyCode != GLFW.GLFW_KEY_ENTER
                && keyCode != GLFW.GLFW_KEY_KP_ENTER
                && keyCode != GLFW.GLFW_KEY_DOWN && keyCode != GLFW.GLFW_KEY_UP
                && keyCode != GLFW.GLFW_KEY_PAGE_DOWN && keyCode != GLFW.GLFW_KEY_PAGE_UP) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_TAB) {
            moveSelection(hub.cyclePanel(filtered, true));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            moveSelection(hub.cyclePanel(filtered, false));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            gotoPage(page + 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            gotoPage(page - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            dispatchSelected();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void moveSelection(AuthoringHub.Panel panel) {
        hub.open(panel, null);
        // Keep the selection visible — flip the page the panel lives on.
        int idx = filtered.indexOf(panel);
        if (idx >= 0) {
            int targetPage = idx / rowsPerPage();
            if (targetPage != page) {
                page = targetPage;
                refreshRows();
            }
        }
        selection.select(panel);
    }

    private void dispatchSelected() {
        for (AuthoringHub.Route route : AuthoringHub.routesFor(hub.state().panel())) {
            // Only dispatchable commands send — required-arg placeholders
            // (<...>) and union hints (a|b) are descriptive text. Trailing
            // optional segments ([id]) are stripped: the bare form is valid.
            if (route.kind() == AuthoringHub.Route.Kind.COMMAND
                    && !route.openPath().contains("<") && !route.openPath().contains("|")) {
                String cmd = route.openPath();
                int opt = cmd.indexOf('[');
                if (opt >= 0) cmd = cmd.substring(0, opt).trim();
                if (this.minecraft != null && this.minecraft.player != null) {
                    // sendCommand expects the full root command without '/'.
                    this.minecraft.player.connection.sendCommand(cmd);
                    echo(Component.literal("Sent: /" + cmd).withColor(UiTheme.ACCENT), 3000);
                }
                return;
            }
        }
        echo(Component.literal("No direct command — follow the route hint.")
                .withColor(UiTheme.TEXT_MUTED), 3000);
    }

    @Override
    public boolean isPauseScreen() { return true; }
}
