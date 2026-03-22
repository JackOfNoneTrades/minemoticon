package org.fentanylsolutions.minemoticon.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.EmojiConfig;
import org.fentanylsolutions.minemoticon.api.Emoji;
import org.fentanylsolutions.minemoticon.api.EmojiFromTwitmoji;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.lwjgl.opengl.GL11;

public class EmojiPickerGui {

    private static final int GRID_COLS = 9;
    private static final int VISIBLE_ROWS = 8;
    private static final int CELL_SIZE = 12;
    private static final int PADDING = 3;
    private static final int SEARCH_HEIGHT = 14;
    private static final int INFO_HEIGHT = 12;

    private final GuiTextField chatInput;
    private final FontRenderer font;
    private final GuiTextField searchField;

    private boolean open;
    private int scrollOffset;
    private Emoji hoveredEmoji;
    private EmojiFromTwitmoji buttonEmoji;
    private List<Object> filteredLines = new ArrayList<>();

    // Layout positions
    private final int panelX, panelY, panelW, panelH;
    private final int buttonX, buttonY;
    private final int gridX, gridY;

    public EmojiPickerGui(GuiTextField chatInput, FontRenderer font, int screenWidth, int screenHeight) {
        this.chatInput = chatInput;
        this.font = font;

        panelW = GRID_COLS * CELL_SIZE + PADDING * 2;
        panelH = SEARCH_HEIGHT + VISIBLE_ROWS * CELL_SIZE + INFO_HEIGHT + PADDING * 3;
        panelX = screenWidth - panelW - 2;
        panelY = screenHeight - 14 - panelH;

        gridX = panelX + PADDING;
        gridY = panelY + PADDING + SEARCH_HEIGHT + PADDING;

        buttonX = screenWidth - CELL_SIZE - 2;
        buttonY = screenHeight - CELL_SIZE - 1;

        // Resolve button emoji from config
        var lookup = ClientEmojiHandler.EMOJI_LOOKUP.get(":" + EmojiConfig.pickerButtonEmoji + ":");
        if (lookup instanceof EmojiFromTwitmoji t) buttonEmoji = t;

        searchField = new GuiTextField(
            font,
            panelX + PADDING + 2,
            panelY + PADDING + 2,
            panelW - PADDING * 2 - 4,
            SEARCH_HEIGHT - 4);
        searchField.setMaxStringLength(50);
    }

    public boolean isOpen() {
        return open;
    }

    public void toggle() {
        open = !open;
        if (!open) {
            searchField.setFocused(false);
            searchField.setText("");
            filteredLines.clear();
            scrollOffset = 0;
        }
    }

    public void tick() {
        if (open) searchField.updateCursorCounter();
    }

    public void render(int mouseX, int mouseY) {
        // Button
        if (buttonEmoji != null) {
            EmojiRenderer.renderQuad(buttonEmoji, buttonX, buttonY);
            GL11.glColor4f(1, 1, 1, 1);
        }

        if (!open) return;

        hoveredEmoji = null;

        // Panel background
        Gui.drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0xD0000000);

        // Search field
        Gui.drawRect(
            panelX + PADDING - 1,
            panelY + PADDING - 1,
            panelX + panelW - PADDING + 1,
            panelY + PADDING + SEARCH_HEIGHT - 1,
            0xFF333333);
        searchField.drawTextBox();

        // Grid
        var lines = getVisibleLines();
        for (int row = 0; row < VISIBLE_ROWS && row < lines.size(); row++) {
            var line = lines.get(row);
            int y = gridY + row * CELL_SIZE;

            if (line instanceof String category) {
                var trimmed = font.trimStringToWidth(category, panelW - PADDING * 2);
                font.drawStringWithShadow(trimmed, gridX + 1, y + 2, 0x969696);
            } else if (line instanceof Emoji[]emojis) {
                for (int col = 0; col < GRID_COLS; col++) {
                    if (emojis[col] == null) continue;
                    int x = gridX + col * CELL_SIZE;

                    if (mouseX >= x && mouseX < x + CELL_SIZE && mouseY >= y && mouseY < y + CELL_SIZE) {
                        Gui.drawRect(x, y, x + CELL_SIZE, y + CELL_SIZE, 0x40FFFFFF);
                        hoveredEmoji = emojis[col];
                    }

                    if (emojis[col] instanceof EmojiFromTwitmoji t) {
                        EmojiRenderer.renderQuad(t, x + 1, y + 1);
                    }
                }
                GL11.glColor4f(1, 1, 1, 1);
            }
        }

        // Info area
        int infoY = panelY + panelH - INFO_HEIGHT;
        Gui.drawRect(panelX, infoY, panelX + panelW, panelY + panelH, 0x80000000);
        if (hoveredEmoji != null) {
            var name = hoveredEmoji.getShorterString();
            font.drawStringWithShadow(name, panelX + PADDING, infoY + 2, 0xCCCCCC);
        }
    }

    // Returns the emoji insert text if one was clicked, null otherwise.
    public String mouseClicked(int mouseX, int mouseY, int button) {
        // Toggle button
        if (mouseX >= buttonX && mouseX < buttonX + CELL_SIZE && mouseY >= buttonY && mouseY < buttonY + CELL_SIZE) {
            toggle();
            return null;
        }

        if (!open) return null;

        // Search field focus
        searchField.mouseClicked(mouseX, mouseY, button);

        // Emoji grid click
        if (mouseX >= gridX && mouseX < gridX + GRID_COLS * CELL_SIZE
            && mouseY >= gridY
            && mouseY < gridY + VISIBLE_ROWS * CELL_SIZE) {
            var lines = getVisibleLines();
            int row = (mouseY - gridY) / CELL_SIZE;
            int col = (mouseX - gridX) / CELL_SIZE;
            if (row < lines.size() && lines.get(row) instanceof Emoji[]emojis) {
                if (col < GRID_COLS && emojis[col] != null) {
                    String text = emojis[col].getInsertText();
                    if (EmojiConfig.closePickerOnSelect) toggle();
                    return text;
                }
            }
        }

        return null;
    }

    // Returns true if the click was inside the panel (should be consumed).
    public boolean isInsidePanel(int mouseX, int mouseY) {
        if (mouseX >= buttonX && mouseX < buttonX + CELL_SIZE && mouseY >= buttonY && mouseY < buttonY + CELL_SIZE)
            return true;
        if (!open) return false;
        return mouseX >= panelX && mouseX < panelX + panelW && mouseY >= panelY && mouseY < panelY + panelH;
    }

    public boolean keyTyped(char c, int keyCode) {
        if (!open) return false;
        if (keyCode == 1) { // ESC
            toggle();
            return true;
        }
        if (searchField.textboxKeyTyped(c, keyCode)) {
            updateFilter();
            return true;
        }
        return false;
    }

    public boolean handleScroll(int mouseX, int mouseY, int delta) {
        if (!open) return false;
        if (mouseX < panelX || mouseX >= panelX + panelW || mouseY < panelY || mouseY >= panelY + panelH) return false;

        scrollOffset -= delta;
        var allLines = getCurrentLines();
        int maxScroll = Math.max(0, allLines.size() - VISIBLE_ROWS);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        return true;
    }

    private void updateFilter() {
        String query = searchField.getText()
            .toLowerCase();
        filteredLines.clear();
        scrollOffset = 0;
        if (query.isEmpty()) return;

        var matching = ClientEmojiHandler.EMOJI_LIST.stream()
            .filter(
                e -> e.strings.stream()
                    .anyMatch(
                        s -> s.toLowerCase()
                            .contains(query)))
            .collect(Collectors.toList());

        for (int i = 0; i < matching.size(); i += GRID_COLS) {
            var row = new Emoji[GRID_COLS];
            for (int j = 0; j < GRID_COLS && i + j < matching.size(); j++) {
                row[j] = matching.get(i + j);
            }
            filteredLines.add(row);
        }
    }

    private List<Object> getCurrentLines() {
        return searchField.getText()
            .isEmpty() ? ClientEmojiHandler.PICKER_LINES : filteredLines;
    }

    private List<Object> getVisibleLines() {
        var all = getCurrentLines();
        int start = Math.max(0, Math.min(scrollOffset, Math.max(0, all.size() - VISIBLE_ROWS)));
        int end = Math.min(start + VISIBLE_ROWS, all.size());
        if (start >= end) return new ArrayList<>();
        return all.subList(start, end);
    }
}
