package org.fentanylsolutions.minemoticon.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.api.Emoji;
import org.fentanylsolutions.minemoticon.api.RenderableEmoji;
import org.fentanylsolutions.minemoticon.mixins.early.minecraft.AccessorGuiTextField;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.lwjgl.opengl.GL11;

public class EmojiSuggestionHelper {

    private static final int MAX_SUGGESTIONS = 10;
    private static final int ROW_HEIGHT = 12;
    private static final int MIN_QUERY_LENGTH = 2;

    private final GuiTextField inputField;
    private final FontRenderer font;

    private List<Emoji> suggestions = new ArrayList<>();
    private int selectedIndex = 0;
    private String lastInputText = "";
    private int colonStart = -1; // position of the ':' that started the query
    private boolean active;

    public EmojiSuggestionHelper(GuiTextField inputField, FontRenderer font) {
        this.inputField = inputField;
        this.font = font;
    }

    public void update() {
        String text = inputField.getText();
        if (text.equals(lastInputText)) return;
        lastInputText = text;

        int cursor = inputField.getCursorPosition();
        colonStart = -1;
        active = false;
        suggestions.clear();
        selectedIndex = 0;

        // Search backwards from cursor for an unmatched ':'
        for (int i = cursor - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ':') {
                colonStart = i;
                break;
            }
            if (c == ' ') break; // stop at space
        }

        if (colonStart < 0) return;

        String partial = text.substring(colonStart + 1, cursor)
            .toLowerCase();
        if (partial.length() < MIN_QUERY_LENGTH) return;

        // Find matching emoji names
        for (var entry : ClientEmojiHandler.EMOJI_LOOKUP.entrySet()) {
            String key = entry.getKey(); // :name:
            String name = key.substring(1, key.length() - 1); // strip colons
            if (name.toLowerCase()
                .startsWith(partial)) {
                suggestions.add(entry.getValue());
                if (suggestions.size() >= MAX_SUGGESTIONS) break;
            }
        }

        // Sort shorter names first (more relevant)
        suggestions.sort(
            (a, b) -> a.getShorterString()
                .length()
                - b.getShorterString()
                    .length());

        active = !suggestions.isEmpty();
        if (active) selectedIndex = 0;
    }

    public boolean isActive() {
        return active;
    }

    public void render(int mouseX, int mouseY) {
        if (!active || suggestions.isEmpty()) return;

        int count = suggestions.size();
        int scrollOffset = getScrollOffset();
        String visibleText = getVisibleText(scrollOffset);
        int textY = getTextRenderY();
        EmojiRenderer.bypass = true;
        int popupW = 0;
        for (Emoji e : suggestions) {
            int w = font.getStringWidth(e.getShorterString()) + 16;
            if (w > popupW) popupW = w;
        }
        popupW = Math.max(popupW, 60);
        EmojiRenderer.bypass = false;

        // Position above the input field, aligned with the colon
        int popupX = getRenderedTextX(colonStart, scrollOffset, visibleText);
        int popupH = count * ROW_HEIGHT;
        int popupY = inputField.yPosition - popupH - 2;

        // Background
        Gui.drawRect(popupX - 2, popupY - 2, popupX + popupW + 2, popupY + popupH, 0xE0000000);

        // Rows
        for (int i = 0; i < count; i++) {
            Emoji emoji = suggestions.get(i);
            int rowY = popupY + i * ROW_HEIGHT;
            boolean selected = i == selectedIndex;
            boolean hovered = mouseX >= popupX - 2 && mouseX < popupX + popupW + 2
                && mouseY >= rowY
                && mouseY < rowY + ROW_HEIGHT;

            if (selected) {
                Gui.drawRect(popupX - 2, rowY, popupX + popupW + 2, rowY + ROW_HEIGHT, 0x40FFFFFF);
            }

            // Emoji icon
            if (emoji instanceof RenderableEmoji t) {
                EmojiRenderer.renderQuad(t, popupX, rowY + 1);
                GL11.glColor4f(1, 1, 1, 1);
            }

            // Name
            int color = selected ? 0xFFFFFF00 : 0xFFAAAAAA;
            EmojiRenderer.bypass = true;
            font.drawStringWithShadow(emoji.getShorterString(), popupX + 12, rowY + 2, color);
            EmojiRenderer.bypass = false;
        }

        // Draw ghost text after the cursor
        if (selectedIndex < suggestions.size()) {
            String full = suggestions.get(selectedIndex)
                .getShorterString();
            String typed = ":" + inputField.getText()
                .substring(colonStart + 1, inputField.getCursorPosition());
            if (full.toLowerCase()
                .startsWith(typed.toLowerCase())) {
                String ghost = full.substring(typed.length());
                int cursorX = getRenderedTextX(inputField.getCursorPosition(), scrollOffset, visibleText);
                if (cursorX >= 0) {
                    EmojiRenderer.bypass = true;
                    font.drawString(ghost, cursorX, textY, 0x808080);
                    EmojiRenderer.bypass = false;
                }
            }
        }
    }

    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!active || suggestions.isEmpty()) return false;

        int count = suggestions.size();
        int scrollOffset = getScrollOffset();
        String visibleText = getVisibleText(scrollOffset);
        EmojiRenderer.bypass = true;
        int popupW = 0;
        for (Emoji e : suggestions) {
            int w = font.getStringWidth(e.getShorterString()) + 16;
            if (w > popupW) popupW = w;
        }
        popupW = Math.max(popupW, 60);
        EmojiRenderer.bypass = false;
        int popupX = getRenderedTextX(colonStart, scrollOffset, visibleText);
        int popupH = count * ROW_HEIGHT;
        int popupY = inputField.yPosition - popupH - 2;

        if (mouseX >= popupX - 2 && mouseX < popupX + popupW + 2 && mouseY >= popupY - 2 && mouseY < popupY + popupH) {
            int idx = (mouseY - popupY) / ROW_HEIGHT;
            if (idx >= 0 && idx < count) {
                selectedIndex = idx;
                applySuggestion();
                return true;
            }
        }

        // Click outside dismisses
        dismiss();
        return false;
    }

    public boolean keyTyped(char c, int keyCode) {
        if (!active) return false;

        switch (keyCode) {
            case 200: // Up
                selectedIndex = (selectedIndex - 1 + suggestions.size()) % suggestions.size();
                return true;
            case 208: // Down
                selectedIndex = (selectedIndex + 1) % suggestions.size();
                return true;
            case 15: // Tab
            case 28:
            case 156: // Enter
                applySuggestion();
                return true;
            case 1: // ESC
                dismiss();
                return true;
        }

        return false;
    }

    private void applySuggestion() {
        if (selectedIndex >= suggestions.size()) return;

        Emoji emoji = suggestions.get(selectedIndex);
        String text = inputField.getText();
        int cursor = inputField.getCursorPosition();

        // Replace from colonStart to cursor with the emoji insert text
        String before = text.substring(0, colonStart);
        String after = text.substring(cursor);
        String insert = emoji.getInsertText() + " ";

        inputField.setText(before + insert + after);
        inputField.setCursorPosition(before.length() + insert.length());

        dismiss();
    }

    private void dismiss() {
        active = false;
        suggestions.clear();
        colonStart = -1;
    }

    private int getScrollOffset() {
        return ((AccessorGuiTextField) inputField).getLineScrollOffset();
    }

    private String getVisibleText(int scrollOffset) {
        int safeOffset = Math.max(
            0,
            Math.min(
                scrollOffset,
                inputField.getText()
                    .length()));
        return font.trimStringToWidth(
            inputField.getText()
                .substring(safeOffset),
            inputField.getWidth());
    }

    private int getTextRenderX() {
        return inputField.getEnableBackgroundDrawing() ? inputField.xPosition + 4 : inputField.xPosition;
    }

    private int getTextRenderY() {
        return inputField.getEnableBackgroundDrawing() ? inputField.yPosition + (inputField.height - 8) / 2
            : inputField.yPosition;
    }

    private int getRenderedTextX(int textIndex, int scrollOffset, String visibleText) {
        int relativeIndex = textIndex - scrollOffset;
        int textX = getTextRenderX();

        if (relativeIndex < 0) {
            return textX;
        }

        if (relativeIndex > visibleText.length()) {
            return -1;
        }

        return textX + font.getStringWidth(visibleText.substring(0, relativeIndex));
    }
}
