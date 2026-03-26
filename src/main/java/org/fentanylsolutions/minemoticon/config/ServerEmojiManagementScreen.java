package org.fentanylsolutions.minemoticon.config;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.MathHelper;

import org.fentanylsolutions.minemoticon.ServerCapabilities;
import org.fentanylsolutions.minemoticon.api.EmojiFromRemote;
import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.fentanylsolutions.minemoticon.network.ServerEmojiManagerClient;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.fentanylsolutions.minemoticon.server.PersistentEmoteStore.OwnerEmojiEntry;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

public class ServerEmojiManagementScreen extends GuiScreen {

    private static final int BTN_DELETE_ALL = 100;
    private static final int BTN_REFRESH = 101;
    private static final int BTN_BACK = 102;

    private static final int PANEL_MARGIN = 18;
    private static final int HEADER_TITLE_Y = 12;
    private static final int HEADER_STATS_Y = 28;
    private static final int QUOTA_BAR_Y = 40;
    private static final int LIST_TOP = 56;
    private static final int LIST_BOTTOM = 34;
    private static final int LIST_SCROLLBAR_W = 4;
    private static final int CARD_HEIGHT = 42;
    private static final int CARD_GAP = 6;
    private static final int LIST_SCROLL_STEP = 18;
    private static final float LIST_SCROLL_LERP = 0.35F;

    private final GuiScreen parent;
    private final List<OwnerEmojiEntry> entries = new ArrayList<>();
    private final Map<String, EmojiFromRemote> previews = new HashMap<>();

    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int scrollbarX;
    private int maxScroll;
    private int scroll;
    private float scrollVisual;
    private boolean draggingScrollbar;
    private int scrollbarDragOffset;
    private long usedBytes;
    private long quotaBytes;
    private String statusMessage;
    private String tooltipText;
    private int tooltipX;
    private int tooltipY;
    private long lastRevision = Long.MIN_VALUE;

    public ServerEmojiManagementScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();

        listX = PANEL_MARGIN;
        listY = LIST_TOP;
        listW = width - PANEL_MARGIN * 2 - LIST_SCROLLBAR_W - 4;
        listH = height - listY - LIST_BOTTOM;
        scrollbarX = listX + listW + 4;

        int bottomY = height - 24;
        int gap = 4;
        int btnW = Math.min(120, (width - PANEL_MARGIN * 2 - gap * 2) / 3);
        int totalW = btnW * 3 + gap * 2;
        int startX = (width - totalW) / 2;
        buttonList.add(new GuiButton(BTN_DELETE_ALL, startX, bottomY, btnW, 20, "Delete All"));
        buttonList.add(new GuiButton(BTN_REFRESH, startX + btnW + gap, bottomY, btnW, 20, "\u21BB Refresh"));
        buttonList.add(new GuiButton(BTN_BACK, startX + (btnW + gap) * 2, bottomY, btnW, 20, "Back"));

        ServerEmojiManagerClient.requestList();
        applySnapshot(ServerEmojiManagerClient.snapshot());
        snapScroll();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        applySnapshot(ServerEmojiManagerClient.snapshot());
        if (Math.abs(scrollVisual - scroll) < 0.5F) {
            scrollVisual = scroll;
        } else {
            scrollVisual += (scroll - scrollVisual) * LIST_SCROLL_LERP;
        }
    }

    @Override
    public void onGuiClosed() {
        for (EmojiFromRemote preview : previews.values()) {
            preview.destroy();
        }
        previews.clear();
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_DELETE_ALL) {
            ServerEmojiManagerClient.clearMine();
            return;
        }
        if (button.id == BTN_REFRESH) {
            ServerEmojiManagerClient.requestList();
            return;
        }
        if (button.id == BTN_BACK) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0) {
            if (isInsideScrollbar(mouseX, mouseY)) {
                beginScrollbarDrag(mouseY);
                return;
            }

            if (isInsideList(mouseX, mouseY)) {
                OwnerEmojiEntry entry = findEntryAt(mouseX, mouseY);
                if (entry != null && isInsideDeleteButton(entry, mouseX, mouseY)) {
                    ServerEmojiManagerClient.delete(entry.checksum);
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton == 0 && draggingScrollbar) {
            updateScrollbarDrag(mouseY);
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        if (state != -1) {
            draggingScrollbar = false;
        }
        super.mouseMovedOrUp(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) {
            mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta == 0) {
            return;
        }

        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (isInsideList(mouseX, mouseY)) {
            scroll = clamp(scroll - Integer.signum(delta) * LIST_SCROLL_STEP, 0, maxScroll);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        tooltipText = null;

        drawCenteredString(fontRendererObj, "Manage Server Emoji Cache", width / 2, HEADER_TITLE_Y, 0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            ServerCapabilities.hasServerMod() ? formatQuotaText() : "Server does not advertise Minemoticon support",
            width / 2,
            HEADER_STATS_Y,
            0xB8B8B8);

        drawQuotaBar();
        drawListBackground();
        renderEntries(mouseX, mouseY);
        renderScrollbar(mouseX, mouseY);
        renderStatus();

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (tooltipText != null) {
            renderTooltip(tooltipX, tooltipY, tooltipText);
        }
    }

    private void applySnapshot(ServerEmojiManagerClient.Snapshot snapshot) {
        if (snapshot.revision == lastRevision) {
            return;
        }

        lastRevision = snapshot.revision;
        entries.clear();
        entries.addAll(snapshot.entries);
        usedBytes = snapshot.usedBytes;
        quotaBytes = snapshot.quotaBytes;
        statusMessage = snapshot.statusMessage;
        recomputeMaxScroll();
    }

    private void drawQuotaBar() {
        int barW = Math.min(260, width - PANEL_MARGIN * 2);
        int barX = (width - barW) / 2;
        int barY = QUOTA_BAR_Y;
        Gui.drawRect(barX, barY, barX + barW, barY + 8, 0x40202020);
        Gui.drawRect(barX + 1, barY + 1, barX + barW - 1, barY + 7, 0x90000000);
        if (quotaBytes > 0L) {
            float fillRatio = Math.min(1.0F, (float) usedBytes / (float) quotaBytes);
            int fillW = Math.max(0, Math.round((barW - 2) * fillRatio));
            int fillColor = fillRatio > 0.9F ? 0xFFE06464 : fillRatio > 0.7F ? 0xFFE0BC64 : 0xFF78D88A;
            Gui.drawRect(barX + 1, barY + 1, barX + 1 + fillW, barY + 7, fillColor);
        }
    }

    private void drawListBackground() {
        Gui.drawRect(listX - 2, listY - 2, listX + listW + LIST_SCROLLBAR_W + 6, listY + listH + 2, 0x80202020);
        Gui.drawRect(listX - 1, listY - 1, listX + listW + LIST_SCROLLBAR_W + 5, listY + listH + 1, 0xA0000000);
    }

    private void renderEntries(int mouseX, int mouseY) {
        enableScissor(listX, listY, listW + 1, listH);
        for (int i = 0; i < entries.size(); i++) {
            OwnerEmojiEntry entry = entries.get(i);
            int y = listY + i * (CARD_HEIGHT + CARD_GAP) - getRenderedScroll();
            if (y + CARD_HEIGHT < listY || y > listY + listH) {
                continue;
            }

            drawEntry(entry, y, mouseX, mouseY);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void drawEntry(OwnerEmojiEntry entry, int y, int mouseX, int mouseY) {
        int x = listX;
        int w = listW;
        Gui.drawRect(x, y, x + w, y + CARD_HEIGHT, 0x48151515);
        drawOutline(x, y, w, CARD_HEIGHT, 0x60505050);

        EmojiFromRemote preview = previewFor(entry);
        int previewX = x + 8;
        int previewY = y + 14;
        if (preview != null) {
            EmojiRenderer.renderQuad(preview, previewX, previewY);
        } else {
            Gui.drawRect(previewX, previewY, previewX + 10, previewY + 10, 0x50303030);
            drawOutline(previewX, previewY, 10, 10, 0x70505050);
        }

        int textX = x + 24;
        String title = entry.name == null || entry.name.isEmpty() ? entry.checksum : entry.name;
        fontRendererObj.drawStringWithShadow(title, textX, y + 8, 0xFFFFFF);
        fontRendererObj.drawString(
            trimToWidth(
                entry.namespace == null || entry.namespace.isEmpty() ? shortChecksum(entry.checksum)
                    : entry.namespace + "  " + shortChecksum(entry.checksum),
                w - 120),
            textX,
            y + 22,
            0xB8B8B8);

        String sizeText = formatBytes(entry.sizeBytes);
        int sizeW = fontRendererObj.getStringWidth(sizeText);
        fontRendererObj.drawString(sizeText, x + w - 22 - sizeW, y + 9, 0xD8D8D8);

        int deleteX = x + w - 18;
        boolean hoveredDelete = mouseX >= deleteX && mouseX <= deleteX + 12 && mouseY >= y + 6 && mouseY <= y + 18;
        Gui.drawRect(deleteX, y + 6, deleteX + 12, y + 18, hoveredDelete ? 0x80A04040 : 0x60404040);
        drawOutline(deleteX, y + 6, 12, 12, hoveredDelete ? 0xFFFF8080 : 0xFFB0B0B0);
        fontRendererObj.drawString("x", deleteX + 4, y + 8, 0xFFFFFF);

        if (hoveredDelete) {
            tooltipText = "Delete " + title;
            tooltipX = mouseX;
            tooltipY = mouseY;
        }
    }

    private EmojiFromRemote previewFor(OwnerEmojiEntry entry) {
        File cacheFile = EmoteClientHandler.findCachedEmoteFile(entry.checksum);
        if (cacheFile == null || !cacheFile.isFile()) {
            EmoteClientHandler.queueDownloadRequest(entry.checksum);
            return null;
        }

        return previews.computeIfAbsent(
            entry.checksum,
            checksum -> new EmojiFromRemote(
                entry.name != null && !entry.name.isEmpty() ? entry.name : checksum,
                checksum,
                cacheFile,
                "",
                false));
    }

    private void renderScrollbar(int mouseX, int mouseY) {
        if (maxScroll <= 0) {
            return;
        }

        Gui.drawRect(scrollbarX, listY, scrollbarX + LIST_SCROLLBAR_W, listY + listH, 0x30FFFFFF);
        int thumbH = getScrollbarThumbHeight();
        int travel = listH - thumbH;
        int thumbY = listY + (travel <= 0 ? 0 : (int) ((float) getRenderedScroll() / maxScroll * travel));
        boolean hovered = mouseX >= scrollbarX - 2 && mouseX <= scrollbarX + LIST_SCROLLBAR_W + 2
            && mouseY >= listY
            && mouseY <= listY + listH;
        Gui.drawRect(
            scrollbarX,
            thumbY,
            scrollbarX + LIST_SCROLLBAR_W,
            thumbY + thumbH,
            hovered ? 0xA0FFFFFF : 0x70FFFFFF);
    }

    private void renderStatus() {
        if (statusMessage == null || statusMessage.isEmpty()) {
            return;
        }
        drawCenteredString(fontRendererObj, statusMessage, width / 2, height - 36, 0xFFB8D8E8);
    }

    private void renderTooltip(int mouseX, int mouseY, String text) {
        int textWidth = fontRendererObj.getStringWidth(text);
        int x = mouseX + 12;
        int y = mouseY - 6;
        if (x + textWidth + 6 > width) {
            x = mouseX - textWidth - 8;
        }
        Gui.drawRect(x - 3, y - 3, x + textWidth + 3, y + 11, 0xE0000000);
        Gui.drawRect(x - 3, y - 3, x + textWidth + 3, y - 2, 0x50FF8080);
        fontRendererObj.drawStringWithShadow(text, x, y, 0xFFFFFF);
    }

    private void enableScissor(int x, int y, int w, int h) {
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int scale = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * scale, (height - y - h) * scale, w * scale, h * scale);
    }

    private void drawOutline(int x, int y, int w, int h, int color) {
        Gui.drawRect(x, y, x + w, y + 1, color);
        Gui.drawRect(x, y + h - 1, x + w, y + h, color);
        if (h > 2) {
            Gui.drawRect(x, y + 1, x + 1, y + h - 1, color);
            Gui.drawRect(x + w - 1, y + 1, x + w, y + h - 1, color);
        }
    }

    private OwnerEmojiEntry findEntryAt(int mouseX, int mouseY) {
        if (!isInsideList(mouseX, mouseY)) {
            return null;
        }
        for (int i = 0; i < entries.size(); i++) {
            int y = listY + i * (CARD_HEIGHT + CARD_GAP) - getRenderedScroll();
            if (mouseX >= listX && mouseX <= listX + listW && mouseY >= y && mouseY <= y + CARD_HEIGHT) {
                return entries.get(i);
            }
        }
        return null;
    }

    private boolean isInsideDeleteButton(OwnerEmojiEntry entry, int mouseX, int mouseY) {
        int index = entries.indexOf(entry);
        if (index < 0) {
            return false;
        }
        int y = listY + index * (CARD_HEIGHT + CARD_GAP) - getRenderedScroll();
        int deleteX = listX + listW - 18;
        return mouseX >= deleteX && mouseX <= deleteX + 12 && mouseY >= y + 6 && mouseY <= y + 18;
    }

    private boolean isInsideList(int mouseX, int mouseY) {
        return mouseX >= listX && mouseX < listX + listW + LIST_SCROLLBAR_W + 6
            && mouseY >= listY
            && mouseY < listY + listH;
    }

    private boolean isInsideScrollbar(int mouseX, int mouseY) {
        return maxScroll > 0 && mouseX >= scrollbarX - 2
            && mouseX <= scrollbarX + LIST_SCROLLBAR_W + 2
            && mouseY >= listY
            && mouseY <= listY + listH;
    }

    private void beginScrollbarDrag(int mouseY) {
        draggingScrollbar = true;
        int thumbY = getScrollbarThumbY();
        int thumbH = getScrollbarThumbHeight();
        scrollbarDragOffset = mouseY >= thumbY && mouseY <= thumbY + thumbH ? mouseY - thumbY : thumbH / 2;
        updateScrollbarDrag(mouseY);
    }

    private void updateScrollbarDrag(int mouseY) {
        int thumbH = getScrollbarThumbHeight();
        int travel = listH - thumbH;
        if (travel <= 0 || maxScroll <= 0) {
            scroll = 0;
            snapScroll();
            return;
        }

        int thumbY = clamp(mouseY - scrollbarDragOffset, listY, listY + travel);
        float ratio = (float) (thumbY - listY) / (float) travel;
        scroll = clamp(Math.round(ratio * maxScroll), 0, maxScroll);
        snapScroll();
    }

    private int getScrollbarThumbHeight() {
        if (maxScroll <= 0) {
            return listH;
        }
        float viewRatio = (float) listH / (float) (listH + maxScroll);
        return Math.max(12, (int) (listH * viewRatio));
    }

    private int getScrollbarThumbY() {
        int thumbH = getScrollbarThumbHeight();
        int travel = listH - thumbH;
        return listY + (travel <= 0 ? 0 : (int) ((float) getRenderedScroll() / maxScroll * travel));
    }

    private void recomputeMaxScroll() {
        int contentHeight = entries.isEmpty() ? 0
            : entries.size() * CARD_HEIGHT + Math.max(0, entries.size() - 1) * CARD_GAP;
        maxScroll = Math.max(0, contentHeight - listH);
        scroll = clamp(scroll, 0, maxScroll);
        scrollVisual = clamp(Math.round(scrollVisual), 0, maxScroll);
    }

    private void snapScroll() {
        scrollVisual = scroll;
    }

    private int getRenderedScroll() {
        return Math.round(scrollVisual);
    }

    private String formatQuotaText() {
        if (quotaBytes <= 0L) {
            return entries.size() + " stored emoji, unlimited quota";
        }
        return entries.size() + " stored emoji, " + formatBytes(usedBytes) + " / " + formatBytes(quotaBytes);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private String shortChecksum(String checksum) {
        return checksum.length() <= 10 ? checksum : checksum.substring(0, 10);
    }

    private String trimToWidth(String text, int maxWidth) {
        if (fontRendererObj.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int ellipsisW = fontRendererObj.getStringWidth(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (fontRendererObj.getStringWidth(builder.toString() + text.charAt(i)) + ellipsisW > maxWidth) {
                break;
            }
            builder.append(text.charAt(i));
        }
        return builder.append(ellipsis)
            .toString();
    }

    private static int clamp(int value, int min, int max) {
        return MathHelper.clamp_int(value, min, max);
    }
}
