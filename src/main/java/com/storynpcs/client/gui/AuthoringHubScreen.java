package com.storynpcs.client.gui;

import com.storynpcs.editor.hub.AuthoringHub;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Unified authoring hub (P10-1): the searchable, paginated panel list that
 * routes creators to every parity domain. Panels open via their declared
 * routes — editor screens arrive as server payloads after the matching
 * command; YAML/tool surfaces give the exact command or family path.
 * Server-authoritative: the hub never fabricates editor state client-side.
 */
public class AuthoringHubScreen extends Screen {

    private static final int ROW_H = 14;
    private static final int LIST_X = 10;
    private static final int LIST_Y = 56;
    private static final int DETAIL_X = 170;

    private final AuthoringHub hub = new AuthoringHub();
    private EditBox searchBox;
    private List<AuthoringHub.Panel> filtered = List.of(AuthoringHub.Panel.values());
    private int page;
    private String statusLine = "";

    public AuthoringHubScreen() {
        super(Component.literal("StoryNPCs Authoring Hub"));
    }

    @Override
    protected void init() {
        super.init();
        searchBox = new EditBox(this.font, LIST_X, 10, 140, 16, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search panels..."));
        searchBox.setResponder(v -> { page = 0; refilter(); });
        this.addRenderableWidget(searchBox);

        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            if (page > 0) { page--; }
        }).bounds(LIST_X, height - 24, 20, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            if (page < pageCount() - 1) { page++; }
        }).bounds(LIST_X + 24, height - 24, 20, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(width - 60, height - 24, 54, 16).build());
        refilter();
    }

    private void refilter() {
        filtered = AuthoringHub.searchPanels(searchBox != null ? searchBox.getValue() : "");
        int pc = pageCount();
        if (page >= pc) page = Math.max(0, pc - 1);
        if (!filtered.contains(hub.state().panel()) && !filtered.isEmpty()) {
            hub.open(filtered.get(0), null);
        }
    }

    private int rowsPerPage() {
        int fit = (height - LIST_Y - 34) / ROW_H;
        return Math.max(1, Math.min(fit, 12));
    }

    private int pageCount() {
        return Math.max(1, AuthoringHub.pageCount(filtered.size(), rowsPerPage()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Screen#render runs the menu-blur post-process over whatever is
        // already in the framebuffer — background first, custom content next,
        // widgets last, or the hub lists are blurred while buttons stay
        // sharp (#197).
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, width, height, 0xFF101418);
        graphics.drawString(this.font, "StoryNPCs — Authoring Hub", LIST_X, 32, 0xFF38BDF8, false);

        int perPage = rowsPerPage();
        List<AuthoringHub.Panel> visible = AuthoringHub.page(filtered, page, perPage);
        int y = LIST_Y;
        for (AuthoringHub.Panel p : visible) {
            boolean sel = p == hub.state().panel();
            if (sel) {
                graphics.fill(LIST_X - 2, y - 1, LIST_X + 148, y + 11, 0x3338BDF8);
            }
            graphics.drawString(this.font, (sel ? "> " : "  ")
                            + p.name().replace('_', ' '),
                    LIST_X, y + 2, sel ? 0xFF38BDF8 : 0xFFCBD5E1, false);
            y += ROW_H;
        }
        graphics.drawString(this.font,
                "page " + (page + 1) + "/" + pageCount() + "  (" + filtered.size() + " panels)",
                LIST_X + 50, height - 20, 0xFF64748B, false);

        // Detail pane — routes for the selected panel
        AuthoringHub.Panel sel = hub.state().panel();
        graphics.drawString(this.font, sel.name().replace('_', ' '), DETAIL_X, LIST_Y, 0xFF38BDF8, false);
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(AuthoringHub.preview(sel), width - DETAIL_X - 12),
                DETAIL_X, LIST_Y + 11, 0xFF94A3B8, false);
        int dy = LIST_Y + 26;
        for (AuthoringHub.Route route : AuthoringHub.routesFor(sel)) {
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth("• " + route.description(),
                            width - DETAIL_X - 12),
                    DETAIL_X, dy, 0xFFE2E8F0, false);
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth("   " + route.kind() + ": " + route.openPath(),
                            width - DETAIL_X - 12),
                    DETAIL_X, dy + 10, 0xFF94A3B8, false);
            dy += 24;
            if (dy > height - 50) break;
        }
        // Diagnostics — schema-path + repair-hint errors reported to the hub.
        for (AuthoringHub.FieldError err : hub.errors()) {
            if (dy > height - 40) break;
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(
                            "⚠ " + err.schemaPath() + ": " + err.message()
                                    + " — " + err.repairHint(),
                            width - DETAIL_X - 12),
                    DETAIL_X, dy, 0xFFFCA5A5, false);
            dy += 12;
        }
        if (!statusLine.isEmpty()) {
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(statusLine, width - 16),
                    DETAIL_X, height - 20, 0xFFFBBF24, false);
        }
        for (var renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int perPage = rowsPerPage();
        List<AuthoringHub.Panel> visible = AuthoringHub.page(filtered, page, perPage);
        if (mouseX >= LIST_X && mouseX <= LIST_X + 148 && mouseY >= LIST_Y) {
            int idx = (int) ((mouseY - LIST_Y) / ROW_H);
            if (idx >= 0 && idx < visible.size()) {
                hub.open(visible.get(idx), null);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Up/down cycle the filtered list; Enter dispatches the first command route. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() == searchBox && keyCode != GLFW.GLFW_KEY_ENTER
                && keyCode != GLFW.GLFW_KEY_DOWN && keyCode != GLFW.GLFW_KEY_UP) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_TAB) {
            select(hub.cyclePanel(filtered, true));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            select(hub.cyclePanel(filtered, false));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            if (page < pageCount() - 1) page++;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            if (page > 0) page--;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            dispatchSelected();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void select(AuthoringHub.Panel panel) {
        hub.open(panel, null);
        // Keep the selection visible on the current page.
        int idx = filtered.indexOf(panel);
        if (idx >= 0) page = idx / rowsPerPage();
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
                    statusLine = "Sent: /" + cmd;
                }
                return;
            }
        }
        statusLine = "No direct command — follow the route hint.";
    }
}
