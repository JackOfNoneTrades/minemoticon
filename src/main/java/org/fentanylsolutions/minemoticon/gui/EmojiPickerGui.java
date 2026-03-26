package org.fentanylsolutions.minemoticon.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.EmojiConfig;
import org.fentanylsolutions.minemoticon.api.Emoji;
import org.fentanylsolutions.minemoticon.api.RenderableEmoji;
import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

public class EmojiPickerGui {

    // Layout
    private static final int COLS = 9;
    private static final int ROWS = 8;
    private static final int CELL = 12;
    private static final int PAD = 3;
    private static final int GAP = 2;
    private static final int SIDEBAR_W = 14;
    private static final int SCROLLBAR_W = 4;
    private static final int SEARCH_H = 14;
    private static final int INFO_H = 14;
    private static final float EASE = 0.2f;
    private static final float SNAP = 0.05f;

    private final FontRenderer font;
    private final GuiTextField searchField;
    private final int screenHeight;

    private boolean open;
    private RenderableEmoji buttonEmoji;
    private Emoji hoveredEmoji;
    private String hoveredCategory;
    private List<Object> filteredLines = new ArrayList<>();

    // Scroll state
    private int scrollOffset;
    private float scrollSmooth;

    // Sidebar state
    private float sidebarHighlight;
    private float sidebarScroll;
    private float sidebarScrollTarget;
    private boolean sidebarManual;

    // Scrollbar drag
    private boolean draggingScrollbar;

    // Keyboard selection
    private int selectedLine = -1;
    private int selectedCol = -1;
    private String pendingInsertText;

    // Config gear
    private RenderableEmoji gearEmoji;
    private boolean openConfig;

    // Panel bounds
    private final int panelX, panelY, panelW, panelH;
    private final int buttonX, buttonY;
    private final int sidebarX, sidebarY;
    private final int gridX, gridY, gridW, gridH;
    private final int scrollbarX;
    private final int infoY;
    private final int gearX, gearY;

    public EmojiPickerGui(GuiTextField chatInput, FontRenderer font, int screenWidth, int screenHeight) {
        this.font = font;
        this.screenHeight = screenHeight;

        gridW = COLS * CELL;
        gridH = ROWS * CELL;

        panelW = PAD + SIDEBAR_W + GAP + gridW + GAP + SCROLLBAR_W + PAD;
        panelH = PAD + SEARCH_H + GAP + gridH + GAP + INFO_H + PAD;
        panelX = screenWidth - panelW - 2;
        panelY = screenHeight - 14 - panelH;

        sidebarX = panelX + PAD;
        sidebarY = panelY + PAD + SEARCH_H + GAP;

        gridX = sidebarX + SIDEBAR_W + GAP;
        gridY = sidebarY;

        scrollbarX = gridX + gridW + GAP;

        infoY = gridY + gridH + GAP;

        buttonX = screenWidth - CELL - 2;
        buttonY = screenHeight - CELL;

        var lookup = ClientEmojiHandler.EMOJI_LOOKUP.get(":" + EmojiConfig.pickerButtonEmoji + ":");
        if (lookup instanceof RenderableEmoji r) buttonEmoji = r;

        var gearLookup = ClientEmojiHandler.EMOJI_LOOKUP.get(":gear:");
        if (gearLookup instanceof RenderableEmoji r) gearEmoji = r;

        gearX = panelX + panelW - PAD - CELL;
        gearY = panelY + PAD;

        searchField = new GuiTextField(
            font,
            panelX + PAD + 2,
            panelY + PAD + 2,
            panelW - PAD * 2 - CELL - GAP - 4,
            SEARCH_H - 4);
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
            scrollSmooth = 0;
            draggingScrollbar = false;
            selectedLine = -1;
            selectedCol = -1;
        }
    }

    public void tick() {
        if (open) searchField.updateCursorCounter();
    }

    // Returns and clears pending text from keyboard selection (Enter key).
    public String consumeInsertText() {
        var text = pendingInsertText;
        pendingInsertText = null;
        return text;
    }

    public boolean shouldOpenConfig() {
        if (openConfig) {
            openConfig = false;
            return true;
        }
        return false;
    }

    // -- Rendering --

    public void render(int mouseX, int mouseY) {
        if (buttonEmoji != null) {
            renderButtonEmoji(mouseX, mouseY);
        }

        if (!open) return;

        hoveredEmoji = null;
        hoveredCategory = null;

        scrollSmooth = ease(scrollSmooth, scrollOffset);

        if (draggingScrollbar) {
            if (!Mouse.isButtonDown(0)) {
                draggingScrollbar = false;
            } else {
                updateScrollFromMouseY(mouseY);
            }
        }

        boolean searching = !searchField.getText()
            .isEmpty();
        if (!searching) {
            String activeCat = getCategoryAtOffset((int) (scrollSmooth + 0.5f));
            int activeIdx = ClientEmojiHandler.CATEGORIES.indexOf(activeCat);
            if (activeIdx >= 0) {
                sidebarHighlight = ease(sidebarHighlight, activeIdx);
                if (!sidebarManual) {
                    int sidebarCapacity = gridH / CELL;
                    sidebarScrollTarget = activeIdx - sidebarCapacity / 2.0f + 0.5f;
                    sidebarScrollTarget = clamp(
                        sidebarScrollTarget,
                        0,
                        Math.max(0, ClientEmojiHandler.CATEGORIES.size() - sidebarCapacity));
                }
            }
            sidebarScroll = ease(sidebarScroll, sidebarScrollTarget);
        }

        Gui.drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0xD0000000);

        Gui.drawRect(
            panelX + PAD - 1,
            panelY + PAD - 1,
            panelX + panelW - PAD - CELL - GAP,
            panelY + PAD + SEARCH_H - 1,
            0xFF333333);
        searchField.drawTextBox();

        // Gear button with light gray border matching GuiTextField style
        // Match the search field's visual box: outer at (x-1,y-1,x+w+1,y+h+1), inner at (x,y,x+w,y+h)
        if (gearEmoji != null) {
            int gfy = panelY + PAD + 2;
            int gfs = SEARCH_H - 4; // square size (10px), matches search field height
            int gfx = gearX + (CELL - gfs) / 2; // center horizontally in the allocated space
            boolean gearHovered = mouseX >= gfx - 1 && mouseX < gfx + gfs + 1
                && mouseY >= gfy - 1
                && mouseY < gfy + gfs + 1;
            Gui.drawRect(gfx - 1, gfy - 1, gfx + gfs + 1, gfy + gfs + 1, 0xFFA0A0A0);
            Gui.drawRect(gfx, gfy, gfx + gfs, gfy + gfs, 0xFF000000);
            if (gearHovered) {
                Gui.drawRect(gfx, gfy, gfx + gfs, gfy + gfs, 0x40FFFFFF);
            }
            var texLoc = gearEmoji.getResourceLocation();
            if (texLoc != null) {
                Minecraft.getMinecraft()
                    .getTextureManager()
                    .bindTexture(texLoc);
                float brightness = gearHovered ? 0.9f : 0.6f;
                GL11.glColor4f(brightness, brightness, brightness, 1.0f);
                float[] guv = gearEmoji.getUV();
                float gu0 = guv != null ? guv[0] : 0, gv0 = guv != null ? guv[1] : 0;
                float gu1 = guv != null ? guv[2] : 1, gv1 = guv != null ? guv[3] : 1;
                float size = gfs - 3;
                float ex = gfx + (gfs - size) / 2.0f;
                float ey = gfy + (gfs - size) / 2.0f;

                Tessellator tessellator = Tessellator.instance;

                tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
                tessellator.addVertexWithUV(ex, ey, 0, gu0, gv0);
                tessellator.addVertexWithUV(ex, ey + size, 0, gu0, gv1);
                tessellator.addVertexWithUV(ex + size, ey, 0, gu1, gv0);
                tessellator.addVertexWithUV(ex + size, ey + size, 0, gu1, gv1);
                tessellator.draw();

                GL11.glColor4f(1, 1, 1, 1);
            }

            if (gearHovered) {
                hoveredCategory = "Config";
            }
        }

        if (!searching) {
            renderSidebar(mouseX, mouseY);
        }

        renderGrid(mouseX, mouseY, searching);
        renderScrollbar(mouseX, mouseY);
        renderInfoBar();

        // Tooltip for hovered category (rendered last, on top of everything)
        if (hoveredCategory != null) {
            renderTooltip(mouseX, mouseY, hoveredCategory);
        }
    }

    private void renderSidebar(int mouseX, int mouseY) {
        var categories = ClientEmojiHandler.CATEGORIES;
        if (categories.isEmpty()) return;

        enableScissor(sidebarX, sidebarY, SIDEBAR_W, gridH);

        for (int i = 0; i < categories.size(); i++) {
            float y = sidebarY + (i - sidebarScroll) * CELL;

            // Active highlight
            float highlightY = sidebarY + (sidebarHighlight - sidebarScroll) * CELL - 1;
            if (i == (int) (sidebarHighlight + 0.5f)) {
                Gui.drawRect(sidebarX, (int) highlightY, sidebarX + SIDEBAR_W, (int) highlightY + CELL, 0x40FFFFFF);
            }

            // Hover
            if (mouseX >= sidebarX && mouseX < sidebarX + SIDEBAR_W
                && mouseY >= (int) y - 1
                && mouseY < (int) y + CELL - 1) {
                Gui.drawRect(sidebarX, (int) y - 1, sidebarX + SIDEBAR_W, (int) y + CELL - 1, 0x20FFFFFF);
                hoveredCategory = categories.get(i);
            }

            String cat = categories.get(i);
            // Use pack icon if available, otherwise first emoji in category
            Emoji icon = ClientEmojiHandler.PACK_CATEGORY_ICONS.get(cat);
            if (icon == null) {
                var emojis = ClientEmojiHandler.EMOJI_MAP.get(cat);
                if (emojis != null && !emojis.isEmpty()) icon = emojis.get(0);
            }
            if (icon instanceof RenderableEmoji r) {
                EmojiRenderer.renderQuad(r, sidebarX + 2, y + 1);
            }
        }
        GL11.glColor4f(1, 1, 1, 1);

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void renderGrid(int mouseX, int mouseY, boolean searching) {
        var allLines = getCurrentLines();
        int xStart = searching ? sidebarX : gridX;
        int areaW = searching ? (gridX - sidebarX + gridW) : gridW;

        int baseRow = (int) scrollSmooth;
        float fracPixel = (scrollSmooth - baseRow) * CELL;

        enableScissor(xStart, gridY, areaW + GAP + SCROLLBAR_W, gridH);

        for (int row = 0; row < ROWS + 1; row++) {
            int lineIdx = baseRow + row;
            if (lineIdx < 0 || lineIdx >= allLines.size()) continue;

            var line = allLines.get(lineIdx);
            float y = gridY + row * CELL - fracPixel;

            if (line instanceof String category) {
                var trimmed = font.trimStringToWidth(category, areaW);
                font.drawStringWithShadow(trimmed, xStart + 1, (int) y + 2, 0x969696);
            } else if (line instanceof Emoji[]emojis) {
                for (int col = 0; col < COLS; col++) {
                    if (emojis[col] == null) continue;
                    int x = xStart + col * CELL;
                    int cellTop = (int) y - 1;

                    // Keyboard selection highlight
                    boolean isSelected = lineIdx == selectedLine && col == selectedCol;
                    if (isSelected) {
                        Gui.drawRect(x, cellTop, x + CELL, cellTop + CELL, 0x60FFFFFF);
                        hoveredEmoji = emojis[col];
                    }

                    // Mouse hover highlight
                    if (mouseX >= x && mouseX < x + CELL
                        && mouseY >= Math.max(cellTop, gridY)
                        && mouseY < Math.min(cellTop + CELL, gridY + gridH)) {
                        if (!isSelected) {
                            Gui.drawRect(x, cellTop, x + CELL, cellTop + CELL, 0x40FFFFFF);
                        }
                        hoveredEmoji = emojis[col];
                    }

                    if (emojis[col] instanceof RenderableEmoji t) {
                        EmojiRenderer.renderQuad(t, x + 1, cellTop + 2);
                    }
                }
                GL11.glColor4f(1, 1, 1, 1);
            }
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void renderScrollbar(int mouseX, int mouseY) {
        var allLines = getCurrentLines();
        int total = allLines.size();
        if (total <= ROWS) return;

        Gui.drawRect(scrollbarX, gridY, scrollbarX + SCROLLBAR_W, gridY + gridH, 0x20FFFFFF);

        float viewRatio = (float) ROWS / total;
        int thumbH = Math.max(8, (int) (gridH * viewRatio));
        float scrollRatio = scrollSmooth / (total - ROWS);
        scrollRatio = clamp(scrollRatio, 0, 1);
        int thumbY = gridY + (int) ((gridH - thumbH) * scrollRatio);

        boolean hovered = mouseX >= scrollbarX - 2 && mouseX < scrollbarX + SCROLLBAR_W + 2
            && mouseY >= gridY
            && mouseY < gridY + gridH;
        int thumbColor = draggingScrollbar ? 0xC0FFFFFF : (hovered ? 0xA0FFFFFF : 0x60FFFFFF);
        Gui.drawRect(scrollbarX, thumbY, scrollbarX + SCROLLBAR_W, thumbY + thumbH, thumbColor);
    }

    private void renderInfoBar() {
        Gui.drawRect(panelX, infoY, panelX + panelW, infoY + INFO_H, 0x80000000);
        if (hoveredEmoji == null) return;

        if (hoveredEmoji instanceof RenderableEmoji t) {
            EmojiRenderer.renderQuad(t, panelX + PAD + 1, infoY + 2);
            GL11.glColor4f(1, 1, 1, 1);
        }

        EmojiRenderer.bypass = true;
        int nameX = panelX + PAD + CELL + 3;
        int maxNameW = panelX + panelW - PAD - nameX;
        String name = hoveredEmoji.getShorterString();
        if (font.getStringWidth(name) > maxNameW) {
            name = font.trimStringToWidth(name, maxNameW - font.getStringWidth("...")) + "...";
        }
        font.drawStringWithShadow(name, nameX, infoY + 3, 0xCCCCCC);
        EmojiRenderer.bypass = false;
    }

    private void renderTooltip(int mouseX, int mouseY, String text) {
        int textW = font.getStringWidth(text);
        // Position to the right of the cursor, clamped to panel bounds
        int tipX = mouseX + 12;
        if (tipX + textW + 2 > panelX + panelW) {
            tipX = mouseX - textW - 8;
        }
        int tipY = mouseY - 4;
        Gui.drawRect(tipX - 2, tipY - 2, tipX + textW + 2, tipY + 12, 0xE0000000);
        Gui.drawRect(tipX - 2, tipY - 2, tipX + textW + 2, tipY - 1, 0x505050FF);
        font.drawStringWithShadow(text, tipX, tipY, 0xFFFFFF);
    }

    private void renderButtonEmoji(int mouseX, int mouseY) {
        var texLoc = buttonEmoji.getResourceLocation();
        if (texLoc == null) return;
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(texLoc);

        boolean hovered = mouseX >= buttonX && mouseX < buttonX + CELL && mouseY >= buttonY && mouseY < buttonY + CELL;
        float brightness = hovered || open ? 0.9f : 0.6f;
        GL11.glColor4f(brightness, brightness, brightness, 1.0f);

        float[] uv = buttonEmoji.getUV();
        float u0 = uv != null ? uv[0] : 0;
        float v0 = uv != null ? uv[1] : 0;
        float u1 = uv != null ? uv[2] : 1;
        float v1 = uv != null ? uv[3] : 1;

        float size = EmojiRenderer.EMOJI_SIZE;
        float top = buttonY - 1.0f;

        Tessellator tessellator = Tessellator.instance;

        tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
        tessellator.addVertexWithUV(buttonX, top, 0, u0, v0);
        tessellator.addVertexWithUV(buttonX, top + size, 0, u0, v1);
        tessellator.addVertexWithUV(buttonX + size, top, 0, u1, v0);
        tessellator.addVertexWithUV(buttonX + size, top + size, 0, u1, v1);
        tessellator.draw();

        GL11.glColor4f(1, 1, 1, 1);
    }

    private void enableScissor(int x, int y, int w, int h) {
        var mc = Minecraft.getMinecraft();
        var sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int scale = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * scale, (screenHeight - y - h) * scale, w * scale, h * scale);
    }

    // -- Input --

    public String mouseClicked(int mouseX, int mouseY, int button) {
        if (mouseX >= buttonX && mouseX < buttonX + CELL && mouseY >= buttonY && mouseY < buttonY + CELL) {
            toggle();
            return null;
        }

        if (!open) return null;

        searchField.mouseClicked(mouseX, mouseY, button);

        // Gear button
        int gfy = panelY + PAD + 2;
        int gfs = SEARCH_H - 4;
        int gfx = gearX + (CELL - gfs) / 2;
        if (mouseX >= gfx - 1 && mouseX < gfx + gfs + 1 && mouseY >= gfy - 1 && mouseY < gfy + gfs + 1) {
            openConfig = true;
            toggle();
            return null;
        }

        if (mouseX >= scrollbarX - 2 && mouseX < scrollbarX + SCROLLBAR_W + 2
            && mouseY >= gridY
            && mouseY < gridY + gridH) {
            draggingScrollbar = true;
            updateScrollFromMouseY(mouseY);
            return null;
        }

        boolean searching = !searchField.getText()
            .isEmpty();

        if (!searching && mouseX >= sidebarX
            && mouseX < sidebarX + SIDEBAR_W
            && mouseY >= sidebarY
            && mouseY < sidebarY + gridH) {
            var categories = ClientEmojiHandler.CATEGORIES;
            int idx = (int) ((mouseY - sidebarY) / CELL + sidebarScroll);
            if (idx >= 0 && idx < categories.size()) {
                var lineIdx = ClientEmojiHandler.CATEGORY_LINE_INDEX.get(categories.get(idx));
                if (lineIdx != null) {
                    scrollOffset = lineIdx;
                    clampScroll();
                }
            }
            return null;
        }

        int xStart = searching ? sidebarX : gridX;
        if (mouseX >= xStart && mouseX < xStart + COLS * CELL && mouseY >= gridY && mouseY < gridY + gridH) {
            var allLines = getCurrentLines();
            int baseRow = (int) scrollSmooth;
            float fracPixel = (scrollSmooth - baseRow) * CELL;

            for (int row = 0; row < ROWS + 1; row++) {
                int lineIdx = baseRow + row;
                if (lineIdx < 0 || lineIdx >= allLines.size()) continue;
                float y = gridY + row * CELL - fracPixel;
                if (mouseY < y - 1 || mouseY >= y + CELL - 1) continue;
                if (!(allLines.get(lineIdx) instanceof Emoji[]emojis)) continue;

                int col = (mouseX - xStart) / CELL;
                if (col >= 0 && col < COLS && emojis[col] != null) {
                    String text = EmoteClientHandler.getInsertTextForEmoji(emojis[col]);
                    if (EmojiConfig.closePickerOnSelect) toggle();
                    return text;
                }
            }
        }

        return null;
    }

    public boolean isInsidePanel(int mouseX, int mouseY) {
        if (mouseX >= buttonX && mouseX < buttonX + CELL && mouseY >= buttonY && mouseY < buttonY + CELL) return true;
        if (!open) return false;
        return mouseX >= panelX && mouseX < panelX + panelW && mouseY >= panelY && mouseY < panelY + panelH;
    }

    public boolean keyTyped(char c, int keyCode) {
        if (!open) return false;
        if (keyCode == 1) {
            toggle();
            return true;
        }

        // Arrow key navigation
        switch (keyCode) {
            case 200:
                moveSelectionVertical(-1);
                return true;
            case 208:
                moveSelectionVertical(1);
                return true;
            case 203:
                moveSelectionHorizontal(-1);
                return true;
            case 205:
                moveSelectionHorizontal(1);
                return true;
            case 28:
            case 156: // Enter
                if (trySelectCurrent()) return true;
                return false; // no selection -- let Enter send the chat message
        }

        EmoteClientHandler.beginInputSuppression();
        boolean handled;
        try {
            handled = searchField.textboxKeyTyped(c, keyCode);
        } finally {
            EmoteClientHandler.endInputSuppression();
        }
        if (handled) {
            updateFilter();
            scrollOffset = 0;
            scrollSmooth = 0;
            selectedLine = -1;
            selectedCol = -1;
            return true;
        }
        return false;
    }

    public boolean handleScroll(int mouseX, int mouseY, int delta) {
        if (!open) return false;
        if (mouseX < panelX || mouseX >= panelX + panelW || mouseY < panelY || mouseY >= panelY + panelH) return false;

        if (mouseX >= sidebarX && mouseX < sidebarX + SIDEBAR_W
            && mouseY >= sidebarY
            && mouseY < sidebarY + gridH
            && searchField.getText()
                .isEmpty()) {
            sidebarManual = true;
            sidebarScrollTarget -= delta;
            int sidebarCapacity = gridH / CELL;
            sidebarScrollTarget = clamp(
                sidebarScrollTarget,
                0,
                Math.max(0, ClientEmojiHandler.CATEGORIES.size() - sidebarCapacity));
            return true;
        }

        sidebarManual = false;
        scrollOffset -= delta;
        clampScroll();
        return true;
    }

    // -- Selection --

    private boolean trySelectCurrent() {
        var lines = getCurrentLines();
        if (selectedLine < 0 || selectedLine >= lines.size()) return false;
        if (!(lines.get(selectedLine) instanceof Emoji[]emojis)) return false;
        if (selectedCol < 0 || selectedCol >= COLS || emojis[selectedCol] == null) return false;
        pendingInsertText = EmoteClientHandler.getInsertTextForEmoji(emojis[selectedCol]);
        if (EmojiConfig.closePickerOnSelect) toggle();
        return true;
    }

    private void moveSelectionVertical(int dir) {
        var lines = getCurrentLines();
        if (lines.isEmpty()) return;

        int start = selectedLine < 0 ? (dir > 0 ? -1 : lines.size()) : selectedLine;
        for (int i = start + dir; i >= 0 && i < lines.size(); i += dir) {
            if (lines.get(i) instanceof Emoji[]emojis) {
                selectedLine = i;
                selectedCol = clampCol(emojis, Math.max(selectedCol, 0));
                ensureSelectionVisible();
                return;
            }
        }
    }

    private void moveSelectionHorizontal(int dir) {
        var lines = getCurrentLines();
        if (selectedLine < 0) {
            moveSelectionVertical(1);
            return;
        }
        if (!(lines.get(selectedLine) instanceof Emoji[]emojis)) return;

        int col = selectedCol + dir;
        // Skip nulls
        while (col >= 0 && col < COLS && emojis[col] == null) col += dir;

        if (col >= 0 && col < COLS) {
            selectedCol = col;
        } else {
            // Wrap to adjacent row
            int prevCol = selectedCol;
            moveSelectionVertical(dir > 0 ? 1 : -1);
            if (selectedLine >= 0 && lines.get(selectedLine) instanceof Emoji[]row) {
                if (dir > 0) {
                    selectedCol = 0;
                    while (selectedCol < COLS && row[selectedCol] == null) selectedCol++;
                } else {
                    selectedCol = COLS - 1;
                    while (selectedCol >= 0 && row[selectedCol] == null) selectedCol--;
                }
                if (selectedCol < 0 || selectedCol >= COLS) selectedCol = prevCol;
            }
        }
    }

    private int clampCol(Emoji[] emojis, int col) {
        col = Math.max(0, Math.min(col, COLS - 1));
        if (emojis[col] != null) return col;
        for (int d = 1; d < COLS; d++) {
            if (col - d >= 0 && emojis[col - d] != null) return col - d;
            if (col + d < COLS && emojis[col + d] != null) return col + d;
        }
        return 0;
    }

    private void ensureSelectionVisible() {
        if (selectedLine < scrollOffset) {
            scrollOffset = selectedLine;
        } else if (selectedLine >= scrollOffset + ROWS) {
            scrollOffset = selectedLine - ROWS + 1;
        }
        clampScroll();
    }

    // -- Helpers --

    private void updateScrollFromMouseY(int mouseY) {
        float ratio = (float) (mouseY - gridY) / gridH;
        ratio = clamp(ratio, 0, 1);
        var allLines = getCurrentLines();
        int maxScroll = Math.max(0, allLines.size() - ROWS);
        scrollOffset = (int) (ratio * maxScroll);
    }

    private void clampScroll() {
        var allLines = getCurrentLines();
        int maxScroll = Math.max(0, allLines.size() - ROWS);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    private void updateFilter() {
        String query = searchField.getText()
            .toLowerCase();
        filteredLines.clear();
        if (query.isEmpty()) return;

        var matching = ClientEmojiHandler.EMOJI_LIST.stream()
            .filter(
                e -> e.strings.stream()
                    .anyMatch(
                        s -> s.toLowerCase()
                            .contains(query)))
            .collect(Collectors.toList());

        for (int i = 0; i < matching.size(); i += COLS) {
            var row = new Emoji[COLS];
            for (int j = 0; j < COLS && i + j < matching.size(); j++) {
                row[j] = matching.get(i + j);
            }
            filteredLines.add(row);
        }
    }

    private String getCategoryAtOffset(int offset) {
        var lines = ClientEmojiHandler.PICKER_LINES;
        for (int i = Math.min(offset, lines.size() - 1); i >= 0; i--) {
            if (lines.get(i) instanceof String s) return s;
        }
        var cats = ClientEmojiHandler.CATEGORIES;
        return cats.isEmpty() ? null : cats.get(0);
    }

    private List<Object> getCurrentLines() {
        return searchField.getText()
            .isEmpty() ? ClientEmojiHandler.PICKER_LINES : filteredLines;
    }

    private static float ease(float current, float target) {
        current += (target - current) * EASE;
        if (Math.abs(current - target) < SNAP) current = target;
        return current;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
