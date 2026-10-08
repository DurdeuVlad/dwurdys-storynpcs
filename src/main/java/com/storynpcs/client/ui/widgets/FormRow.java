package com.storynpcs.client.ui.widgets;

import com.storynpcs.client.ui.UiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Label + {@link EditBox} + inline validation error (issue #199): the standard
 * form unit so every screen stops hand-rolling label/field/error geometry.
 * The owning screen adds {@link #editBox()} via {@code addRenderableWidget}
 * (which renders and focuses it) and calls {@link #render} for the label and
 * error line only — {@link #render} intentionally does not draw the EditBox,
 * or it would be double-drawn per frame. Do not call
 * {@code editBox().setResponder}: it would detach per-edit validation; use
 * {@link #setValidator} instead.
 *
 * <p>Validation runs on every edit and on {@link #validate()}; the current
 * error is exposed via {@link #error()} so a Save action can refuse to send.
 * Geometry: {@code ROW_H} tall row = 8px label line + field + 8px error line
 * is laid out by the caller through {@link #layout}.
 */
public final class FormRow {

    private final Font font;
    private final Component label;
    private final EditBox editBox;
    private FieldValidator validator = value -> null;
    private String error;
    private int x, y, width, labelWidth;

    public FormRow(Font font, Component label, Component placeholder) {
        this.font = font;
        this.label = label;
        this.editBox = new EditBox(font, 0, 0, 0, UiTheme.BUTTON_H, placeholder);
        this.editBox.setResponder(value -> validate());
    }

    public EditBox editBox() { return editBox; }
    public String value() { return editBox.getValue(); }
    public void setValue(String value) { editBox.setValue(value); validate(); }

    public void setValidator(FieldValidator validator) {
        this.validator = validator == null ? v -> null : validator;
        validate();
    }

    /** @return the current validation error, or null when valid. */
    public String error() { return error; }

    /** Re-runs the validator against the live field value. */
    public boolean validate() {
        error = validator.validate(editBox.getValue());
        return error == null;
    }

    /**
     * Positions the row: label at (x, y) with {@code labelWidth}, the field
     * filling the remaining width, the error line directly beneath the field.
     * Returns the y where the next row can start — the error line is part of
     * the row's occupied space, so stacked rows never overlap it.
     */
    public int layout(int x, int y, int width, int labelWidth) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.labelWidth = labelWidth;
        int fx = x + labelWidth + UiTheme.PAD_S;
        editBox.setX(fx);
        editBox.setY(y - 2);
        editBox.setWidth(Math.max(20, x + width - fx));
        // setValue before layout ran with width 0, which clamps the scroll
        // offset (displayPos) to the text end — the seeded value would render
        // as blank. Re-clamping with the real width restores the scroll.
        editBox.moveCursorToEnd(false);
        // field bottom + 9px error line + small gap
        return editBox.getY() + editBox.getHeight() + font.lineHeight + 1
                + UiTheme.PAD_XS;
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, label, x, y + 2,
                error != null ? UiTheme.DANGER : UiTheme.TEXT_MUTED);
        if (error != null) {
            graphics.drawString(font, Component.literal(error),
                    editBox.getX(), editBox.getY() + editBox.getHeight() + 1, UiTheme.DANGER);
        }
    }
}
