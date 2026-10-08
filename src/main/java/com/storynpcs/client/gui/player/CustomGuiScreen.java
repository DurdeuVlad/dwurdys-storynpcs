package com.storynpcs.client.gui.player;

import com.storynpcs.client.gui.model.CustomGuiScreenModel;
import com.storynpcs.client.ui.UiScreen;
import com.storynpcs.client.ui.UiTheme;
import com.storynpcs.creator.gui.CustomGuiLayout;
import com.storynpcs.network.ServerboundCustomGuiActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime renderer for authored {@link CustomGuiLayout}s (issue #150,
 * GuiCustom* parity). Renders the layout's flattened element tree inside the
 * shared {@link UiScreen} chrome: the layout id is the title, the authored
 * extent is centered in the content band and scissored to it, and Close lives
 * in the footer (issue #203).
 *
 * <p>Element semantics are unchanged: panels and textures draw bounded fills,
 * labels/text keys draw text, buttons post session-bound commits, and inputs
 * feed the commit payload. Element types outside the interactive vocabulary
 * render as labelled placeholders — never silently dropped, never executed.
 */
public class CustomGuiScreen extends UiScreen {

    /** Authored panel fill — translucent dark so sibling panels blend rather
     *  than occlude, tinted to the FIELD_BG tone. */
    private static final int PANEL_FILL = 0x6610131A;
    /** Unsupported-element placeholder fill — warning-tinted so it stays
     *  visibly distinct from authored panels. */
    private static final int UNSUPPORTED_FILL = 0x22FBBF24;

    private final CustomGuiLayout layout;
    private final CustomGuiScreenModel model;
    private final UUID sessionId;
    private final Map<String, EditBox> inputBoxes = new LinkedHashMap<>();
    private int originX;
    private int originY;

    public CustomGuiScreen(CustomGuiLayout layout, UUID sessionId) {
        super(Component.literal(
                layout != null && layout.getId() != null ? layout.getId().getPath() : "Custom GUI"));
        this.layout = layout;
        this.model = new CustomGuiScreenModel(layout);
        this.sessionId = sessionId != null ? sessionId : new UUID(0L, 0L);
    }

    @Override
    protected void initContent() {
        // Authored coordinates are relative to the layout extent; center the
        // extent inside the content band (top-left anchored when it overflows).
        int[] extent = model.extent();
        originX = contentLeft() + Math.max(0, (contentWidth() - extent[0]) / 2);
        originY = contentTop() + Math.max(0, (contentBottom() - contentTop() - extent[1]) / 2);
        inputBoxes.clear();
        int clipped = 0;
        for (var e : model.entries()) {
            int w = Math.max(20, e.width());
            int h = Math.max(12, e.height());
            switch (e.type()) {
                case "button" -> {
                    int[] r = bandClamp(originX + e.absX(), originY + e.absY(),
                            Math.min(w, 200), Math.min(h, 24));
                    if (r == null) { clipped++; break; }
                    addRenderableWidget(Button.builder(
                                    resolveText(e), b -> commit(e.path()))
                            .bounds(r[0], r[1], r[2], r[3]).build());
                }
                case "input" -> {
                    int[] r = bandClamp(originX + e.absX(), originY + e.absY(),
                            Math.min(w, 220), 14);
                    if (r == null) { clipped++; break; }
                    var box = new EditBox(this.font, r[0], r[1], r[2], r[3],
                            resolveText(e));
                    box.setMaxLength(128);
                    box.setResponder(v -> model.setInputValue(e.path(), v));
                    inputBoxes.put(e.path(), box);
                    addRenderableWidget(box);
                }
                default -> { }
            }
        }
        setStatus(Component.literal(Math.max(0, model.entries().size() - 1) + " element(s)"
                + (clipped > 0 ? ", " + clipped + " clipped" : "")));
        addFooterAction(Component.literal("Close"), b -> onClose());
    }

    /**
     * Intersects an authored rect with the content band — widgets are
     * scissored for drawing but vanilla hit-tests use widget bounds, so the
     * only honest containment is to clamp the widget itself. Fully outside
     * returns null (the element is skipped, counted as clipped).
     */
    private int[] bandClamp(int x, int y, int w, int h) {
        int cx = Math.max(x, contentLeft());
        int cy = Math.max(y, contentTop());
        int cr = Math.min(x + w, contentRight());
        int cb = Math.min(y + h, contentBottom());
        if (cr <= cx || cb <= cy) return null;
        return new int[]{cx, cy, Math.max(1, cr - cx), Math.max(1, cb - cy)};
    }

    private Component resolveText(CustomGuiScreenModel.RenderEntry e) {
        if (e.textKey() != null && !e.textKey().isBlank()) {
            return Component.translatable(e.textKey());
        }
        return Component.literal(e.name() == null ? "" : e.name());
    }

    /** Commit a button press: element path + collected input values. */
    private void commit(String elementPath) {
        String inputsJson;
        try {
            inputsJson = com.storynpcs.domain.role.RoleSerde.toJson(model.inputValues());
        } catch (Exception e) {
            inputsJson = "{}";
        }
        PacketDistributor.sendToServer(new ServerboundCustomGuiActionPayload(
                sessionId, java.util.UUID.randomUUID(),
                layout.getId() == null ? "" : layout.getId().toString(),
                elementPath, inputsJson == null ? "{}" : inputsJson));
    }

    @Override
    protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Authored elements stay inside the content band — an oversized
        // element may clip but can never paint over the header or footer.
        graphics.enableScissor(contentLeft(), contentTop(), contentRight(), contentBottom());
        for (var e : model.entries()) {
            int x = originX + e.absX();
            int y = originY + e.absY();
            int w = Math.max(1, e.width());
            int h = Math.max(1, e.height());
            switch (e.type()) {
                case "panel" -> graphics.fill(x, y, x + w, y + h, PANEL_FILL);
                case "texture" -> {
                    // Authored texture refs aren't resolved client-side — draw
                    // a bordered placeholder so the authored footprint is real.
                    graphics.fill(x, y, x + w, y + h, UiTheme.ROW_HOVER);
                    graphics.fill(x, y, x + w, y + 1, UiTheme.BORDER);
                }
                case "label" -> graphics.drawString(this.font, resolveText(e), x, y, UiTheme.TEXT);
                case "button", "input" -> { } // real widgets render themselves
                default -> {
                    // Unsupported authored element — honest placeholder.
                    graphics.fill(x, y, x + w, y + Math.max(10, h), UNSUPPORTED_FILL);
                    graphics.drawString(this.font, "§8[" + e.type() + "] " + e.name(),
                            x + 2, y + 2, UiTheme.TEXT_MUTED);
                }
            }
        }
        graphics.disableScissor();
    }

    @Override
    public void onClose() {
        // Notify the server so the session dies with the screen — a reopened
        // GUI gets a fresh token and stale commits fail closed.
        String id = layout.getId() == null ? "" : layout.getId().toString();
        if (!id.isEmpty()) {
            PacketDistributor.sendToServer(
                    new com.storynpcs.network.ServerboundPanelActionPayload(
                            sessionId, java.util.UUID.randomUUID(),
                            "custom_gui:" + id, "close", ""));
        }
        super.onClose();
    }

    /** Headless test hook: the flattened model this screen renders. */
    public CustomGuiScreenModel model() {
        return model;
    }
}
