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
import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.lwjgl.opengl.GL11;

public class EmojiSuggestionHelper {

    private static final int MAX_SUGGESTIONS = 10;
    private static final int ROW_HEIGHT = 12;
    private static final int MIN_QUERY_LENGTH = 2;

    public interface TextInput {

        String getText();

        int getCursorPosition();

        boolean setText(String text);

        void setCursorPosition(int position);

        int getX();

        int getY();

        int getHeight();

        int getVisibleWidth();

        int getScrollOffset();

        boolean hasBackground();

        int getPopupX(int preferredX, int popupWidth, int screenWidth);

        int getPopupY(int preferredY, int popupHeight, int screenHeight);
    }

    private static class GuiTextFieldInput implements TextInput {

        private final GuiTextField inputField;

        GuiTextFieldInput(GuiTextField inputField) {
            this.inputField = inputField;
        }

        @Override
        public String getText() {
            return inputField.getText();
        }

        @Override
        public int getCursorPosition() {
            return inputField.getCursorPosition();
        }

        @Override
        public boolean setText(String text) {
            inputField.setText(text);
            return true;
        }

        @Override
        public void setCursorPosition(int position) {
            inputField.setCursorPosition(position);
        }

        @Override
        public int getX() {
            return inputField.xPosition;
        }

        @Override
        public int getY() {
            return inputField.yPosition;
        }

        @Override
        public int getHeight() {
            return inputField.height;
        }

        @Override
        public int getVisibleWidth() {
            return inputField.getWidth();
        }

        @Override
        public int getScrollOffset() {
            return ((AccessorGuiTextField) inputField).getLineScrollOffset();
        }

        @Override
        public boolean hasBackground() {
            return inputField.getEnableBackgroundDrawing();
        }

        @Override
        public int getPopupX(int preferredX, int popupWidth, int screenWidth) {
            return preferredX;
        }

        @Override
        public int getPopupY(int preferredY, int popupHeight, int screenHeight) {
            return preferredY;
        }
    }

    private final TextInput input;
    private final FontRenderer font;
    private final boolean appendTrailingSpace;
    private final boolean renderAbove;
    private final int screenWidth;
    private final int screenHeight;

    private List<Emoji> suggestions = new ArrayList<>();
    private int selectedIndex = 0;
    private String lastInputText = "";
    private int lastCursorPosition = -1;
    private String lastInsertedText;
    private int colonStart = -1; // position of the ':' that started the query
    private boolean active;

    public EmojiSuggestionHelper(GuiTextField inputField, FontRenderer font) {
        this(inputField, font, true, true, 0, 0);
    }

    public EmojiSuggestionHelper(GuiTextField inputField, FontRenderer font, boolean appendTrailingSpace,
        boolean renderAbove, int screenWidth, int screenHeight) {
        this(new GuiTextFieldInput(inputField), font, appendTrailingSpace, renderAbove, screenWidth, screenHeight);
    }

    public EmojiSuggestionHelper(TextInput input, FontRenderer font, boolean appendTrailingSpace, boolean renderAbove,
        int screenWidth, int screenHeight) {
        this.input = input;
        this.font = font;
        this.appendTrailingSpace = appendTrailingSpace;
        this.renderAbove = renderAbove;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    public void update() {
        String text = input.getText();
        int cursor = Math.max(0, Math.min(input.getCursorPosition(), text.length()));
        if (text.equals(lastInputText) && cursor == lastCursorPosition) return;
        lastInputText = text;
        lastCursorPosition = cursor;

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

        // Find matching emoji names using EMOJI_BY_SHORT_NAME (includes all emojis sharing a name)
        var seen = new java.util.HashSet<Emoji>();
        for (var entry : ClientEmojiHandler.EMOJI_BY_SHORT_NAME.entrySet()) {
            String key = entry.getKey(); // :name:
            String inner = key.substring(1, key.length() - 1);
            if (inner.contains("/")) continue;
            String lowered = inner.toLowerCase();
            if (!lowered.contains(partial)) continue;
            for (Emoji emoji : entry.getValue()) {
                if (seen.contains(emoji)) continue;
                seen.add(emoji);
                suggestions.add(emoji);
            }
        }

        // Prefix hits first, then earlier substring hits, then shorter names.
        suggestions.sort((a, b) -> {
            String aName = a.getShorterString()
                .toLowerCase();
            String bName = b.getShorterString()
                .toLowerCase();
            boolean aPrefix = aName.startsWith(":" + partial);
            boolean bPrefix = bName.startsWith(":" + partial);
            if (aPrefix != bPrefix) return aPrefix ? -1 : 1;

            int aIndex = aName.indexOf(partial);
            int bIndex = bName.indexOf(partial);
            if (aIndex != bIndex) return Integer.compare(aIndex, bIndex);

            int aLength = a.getShorterString()
                .length();
            int bLength = b.getShorterString()
                .length();
            if (aLength != bLength) return Integer.compare(aLength, bLength);

            return a.getShorterString()
                .compareToIgnoreCase(b.getShorterString());
        });

        if (suggestions.size() > MAX_SUGGESTIONS) {
            suggestions = new ArrayList<>(suggestions.subList(0, MAX_SUGGESTIONS));
        }

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

        int popupW = getPopupWidth();
        int popupX = getPopupX(colonStart, scrollOffset, visibleText, popupW);
        int popupH = count * ROW_HEIGHT;
        int popupY = getPopupY(popupH);

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

            // Name + source label on collision
            int color = selected ? 0xFFFFFF00 : 0xFFAAAAAA;
            EmojiRenderer.bypass = true;
            String label = emoji.getShorterString();
            // Show source if another emoji shares the same short name
            if (hasNameCollision(emoji)) {
                label += " \u00a77(" + emoji.category + ")";
            }
            font.drawStringWithShadow(label, popupX + 12, rowY + 2, color);
            EmojiRenderer.bypass = false;
        }

        // Draw ghost text after the cursor
        if (selectedIndex < suggestions.size()) {
            String full = suggestions.get(selectedIndex)
                .getShorterString();
            String text = input.getText();
            int cursor = Math.max(0, Math.min(input.getCursorPosition(), text.length()));
            String typed = ":" + text.substring(colonStart + 1, cursor);
            if (full.toLowerCase()
                .startsWith(typed.toLowerCase())) {
                String ghost = full.substring(typed.length());
                int cursorX = getRenderedTextX(cursor, scrollOffset, visibleText);
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
        int popupW = getPopupWidth();
        int popupX = getPopupX(colonStart, scrollOffset, visibleText, popupW);
        int popupH = count * ROW_HEIGHT;
        int popupY = getPopupY(popupH);

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
        lastInsertedText = null;
        if (selectedIndex >= suggestions.size()) return;

        Emoji emoji = suggestions.get(selectedIndex);
        String text = input.getText();
        int cursor = Math.max(0, Math.min(input.getCursorPosition(), text.length()));

        // Replace from colonStart to cursor with the emoji insert text
        String before = text.substring(0, colonStart);
        String after = text.substring(cursor);
        String emojiText = EmoteClientHandler.getInsertTextForEmoji(emoji);
        String insert = appendTrailingSpace ? emojiText + " " : emojiText;

        if (!input.setText(before + insert + after)) {
            dismiss();
            return;
        }
        input.setCursorPosition(before.length() + insert.length());
        lastInsertedText = emojiText;

        dismiss();
    }

    public String consumeInsertText() {
        String inserted = lastInsertedText;
        lastInsertedText = null;
        return inserted;
    }

    public void dismiss() {
        active = false;
        suggestions.clear();
        colonStart = -1;
    }

    private int getScrollOffset() {
        return input.getScrollOffset();
    }

    private String getVisibleText(int scrollOffset) {
        String text = input.getText();
        int safeOffset = Math.max(0, Math.min(scrollOffset, text.length()));
        return font.trimStringToWidth(text.substring(safeOffset), input.getVisibleWidth());
    }

    private int getTextRenderX() {
        return input.hasBackground() ? input.getX() + 4 : input.getX();
    }

    private int getTextRenderY() {
        return input.hasBackground() ? input.getY() + (input.getHeight() - 8) / 2 : input.getY();
    }

    private int getPopupWidth() {
        EmojiRenderer.bypass = true;
        int popupW = 0;
        for (Emoji e : suggestions) {
            String label = e.getShorterString();
            if (hasNameCollision(e)) label += " (" + e.category + ")";
            int w = font.getStringWidth(label) + 16;
            if (w > popupW) popupW = w;
        }
        EmojiRenderer.bypass = false;
        return Math.max(popupW, 60);
    }

    private int getPopupX(int textIndex, int scrollOffset, String visibleText, int popupW) {
        int popupX = getRenderedTextX(textIndex, scrollOffset, visibleText);
        popupX = input.getPopupX(popupX, popupW, screenWidth);
        if (screenWidth <= 0) {
            return popupX;
        }
        return Math.max(2, Math.min(popupX, screenWidth - popupW - 2));
    }

    private int getPopupY(int popupH) {
        int popupY = renderAbove ? input.getY() - popupH - 2 : input.getY() + input.getHeight() + 2;
        if (screenHeight <= 0) {
            return popupY;
        }
        if (popupY < 2) {
            popupY = input.getY() + input.getHeight() + 2;
        }
        if (popupY + popupH > screenHeight - 2) {
            popupY = input.getY() - popupH - 2;
        }
        popupY = input.getPopupY(popupY, popupH, screenHeight);
        return Math.max(2, Math.min(popupY, screenHeight - popupH - 2));
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

    private boolean hasNameCollision(Emoji emoji) {
        String shortKey = emoji.getShorterString();
        var all = ClientEmojiHandler.EMOJI_BY_SHORT_NAME.get(shortKey);
        return all != null && all.size() > 1;
    }
}
