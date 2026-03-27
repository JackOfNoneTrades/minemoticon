package org.fentanylsolutions.minemoticon.config;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.fentlib.util.FileUtil;
import org.fentanylsolutions.fentlib.util.drop.DropListener;
import org.fentanylsolutions.fentlib.util.drop.WindowDropTarget;
import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.EmojiConfig;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.api.DownloadedTexture;
import org.fentanylsolutions.minemoticon.colorfont.ColorFont;
import org.fentanylsolutions.minemoticon.colorfont.VariationAxis;
import org.fentanylsolutions.minemoticon.font.CustomFontSource;
import org.fentanylsolutions.minemoticon.font.FontSource;
import org.fentanylsolutions.minemoticon.font.FontStack;
import org.fentanylsolutions.minemoticon.font.FontVariationConfig;
import org.fentanylsolutions.minemoticon.font.GlyphCache;
import org.fentanylsolutions.minemoticon.font.MinecraftFontSource;
import org.fentanylsolutions.minemoticon.render.EmojiTextureUtil;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

public class FontSelectionScreen extends GuiScreen implements DropListener {

    private static final int BTN_ADD_FONT = 100;
    private static final int BTN_OPEN_FOLDER = 101;
    private static final int BTN_DONE = 102;

    private static final int PANEL_MARGIN = 12;
    private static final int COLUMN_GAP = 8;
    private static final int HEADER_Y = 8;
    private static final int LIST_TOP = 30;
    private static final int LIST_BOTTOM = 28;
    private static final int SCROLLBAR_W = 4;
    private static final int CARD_GAP = 4;
    private static final int CARD_HEIGHT = 42;
    private static final int CARD_PAD = 4;
    private static final int MINI_BTN = 14;
    private static final int STATUS_DURATION_MS = 3500;
    private static final int SCROLL_STEP = 20;
    private static final float SCROLL_LERP = 0.35F;
    private static final String[] SUPPORTED_EXTENSIONS = { "ttf", "otf" };
    private static final Semaphore PREVIEW_LOAD_SEMAPHORE = new Semaphore(1);

    private static final int[] PREVIEW_CODEPOINTS = { 0x1F600, 0x1F60D, 0x1F622, 0x1F525, 0x2764, 0x1F44D, 0x1F680,
        0x2603 };
    private static final int PREVIEW_GLYPH_SIZE = 48;
    private static final int PREVIEW_DISPLAY_SIZE = 12;
    private static final int MAX_PREVIEW_CACHE_ENTRIES = 24;
    private static final long MAX_PREVIEW_CACHE_BYTES = 6L * 1024 * 1024;
    private static final String SETTINGS_GEAR = "\u2699";

    private static final class CachedPreviewStrip {

        final String cacheKey;
        final long fileSize;
        final long lastModified;
        final BufferedImage image;
        final String unsupportedReason;

        CachedPreviewStrip(String cacheKey, long fileSize, long lastModified, BufferedImage image,
            String unsupportedReason) {
            this.cacheKey = cacheKey;
            this.fileSize = fileSize;
            this.lastModified = lastModified;
            this.image = image;
            this.unsupportedReason = unsupportedReason;
        }
    }

    private static final LinkedHashMap<String, CachedPreviewStrip> PREVIEW_STRIP_CACHE = new LinkedHashMap<>(
        MAX_PREVIEW_CACHE_ENTRIES,
        0.75F,
        true);
    private static long previewStripCacheBytes = 0L;

    private final GuiScreen parent;
    private final Map<String, PreviewTexture> previews = new HashMap<>();
    private final List<String> droppedFilePaths = new ArrayList<>();
    private final Map<String, Map<String, Float>> fontVariationOverrides = new LinkedHashMap<>();

    // Two-column model
    private final List<FontEntry> availableEntries = new ArrayList<>();
    private final List<FontEntry> enabledEntries = new ArrayList<>();

    // Left column (available) layout
    private int leftX, leftY, leftW, leftH;
    private int leftScrollbarX, leftMaxScroll, leftScroll;
    private float leftScrollVisual;
    private boolean draggingLeftScrollbar;
    private int leftScrollbarDragOffset;

    // Right column (enabled) layout
    private int rightX, rightY, rightW, rightH;
    private int rightScrollbarX, rightMaxScroll, rightScroll;
    private float rightScrollVisual;
    private boolean draggingRightScrollbar;
    private int rightScrollbarDragOffset;

    private String statusText;
    private int statusColor = 0xFFB0B0B0;
    private long statusUntilMs;
    private volatile boolean dragDropActive;
    private float dragDropSdlX = -1.0F;
    private float dragDropSdlY = -1.0F;
    private boolean dirty;

    public FontSelectionScreen(GuiScreen parent) {
        this.parent = parent;
    }

    public void openFirstSettingsPopupForDevCapture() {
        for (FontEntry entry : enabledEntries) {
            if (entry.hasSettingsButton()) {
                mc.displayGuiScreen(new FontSettingsScreen(this, entry));
                return;
            }
        }
    }

    @Override
    public void initGui() {
        buttonList.clear();

        int usableW = width - PANEL_MARGIN * 2 - COLUMN_GAP;
        leftW = usableW / 2;
        rightW = usableW - leftW;
        leftX = PANEL_MARGIN;
        rightX = leftX + leftW + COLUMN_GAP;
        leftY = LIST_TOP;
        rightY = LIST_TOP;
        leftH = height - LIST_TOP - LIST_BOTTOM;
        rightH = leftH;
        leftScrollbarX = leftX + leftW - SCROLLBAR_W;
        rightScrollbarX = rightX + rightW - SCROLLBAR_W;

        int bottomY = height - 22;
        int gap = 4;
        int btnW = Math.min(120, (width - PANEL_MARGIN * 2 - gap * 2) / 3);
        int totalW = btnW * 3 + gap * 2;
        int startX = (width - totalW) / 2;
        buttonList.add(new GuiButton(BTN_ADD_FONT, startX, bottomY, btnW, 20, "+ Add Font"));
        buttonList.add(new GuiButton(BTN_OPEN_FOLDER, startX + btnW + gap, bottomY, btnW, 20, "Fonts Folder"));
        buttonList.add(new GuiButton(BTN_DONE, startX + (btnW + gap) * 2, bottomY, btnW, 20, "Done"));

        WindowDropTarget.register();
        WindowDropTarget.addListener(this);

        fontVariationOverrides.clear();
        fontVariationOverrides.putAll(FontVariationConfig.parse(EmojiConfig.fontVariationSettings));
        rebuildLists();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        leftScrollVisual = lerpScroll(leftScrollVisual, leftScroll);
        rightScrollVisual = lerpScroll(rightScrollVisual, rightScroll);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        drawCenteredString(fontRendererObj, "Fonts", width / 2, HEADER_Y, 0xFFFFFF);

        // Column headers
        drawCenteredString(fontRendererObj, "Available", leftX + leftW / 2, LIST_TOP - 10, 0xB8B8B8);
        drawCenteredString(
            fontRendererObj,
            "Enabled (top = highest priority)",
            rightX + rightW / 2,
            LIST_TOP - 10,
            0xB8B8B8);

        // Column backgrounds
        drawColumnBg(leftX, leftY, leftW, leftH);
        drawColumnBg(rightX, rightY, rightW, rightH);

        // Render entries
        renderColumn(availableEntries, leftX, leftY, leftW, leftH, Math.round(leftScrollVisual), mouseX, mouseY);
        renderColumn(enabledEntries, rightX, rightY, rightW, rightH, Math.round(rightScrollVisual), mouseX, mouseY);

        // Scrollbars
        renderScrollbar(leftScrollbarX, leftY, leftH, leftMaxScroll, Math.round(leftScrollVisual), mouseX, mouseY);
        renderScrollbar(rightScrollbarX, rightY, rightH, rightMaxScroll, Math.round(rightScrollVisual), mouseX, mouseY);

        renderDragDropOverlay();
        renderStatus();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_ADD_FONT) {
            addFontFromPicker();
        } else if (button.id == BTN_OPEN_FOLDER) {
            FileUtil.openFolder(ClientEmojiHandler.FONTS_DIR);
        } else if (button.id == BTN_DONE) {
            applyAndClose();
        }
    }

    @Override
    public void onGuiClosed() {
        WindowDropTarget.removeListener(this);
        dragDropActive = false;
        dragDropSdlX = -1.0F;
        dragDropSdlY = -1.0F;
        synchronized (droppedFilePaths) {
            droppedFilePaths.clear();
        }
        super.onGuiClosed();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0) {
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }

        // Check scrollbars
        if (isInsideScrollbar(leftScrollbarX, leftY, leftH, leftMaxScroll, mouseX, mouseY)) {
            beginScrollbarDrag(true, mouseY);
            return;
        }
        if (isInsideScrollbar(rightScrollbarX, rightY, rightH, rightMaxScroll, mouseX, mouseY)) {
            beginScrollbarDrag(false, mouseY);
            return;
        }

        // Check available column clicks
        if (isInsideArea(mouseX, mouseY, leftX, leftY, leftW, leftH)) {
            FontEntry entry = findEntry(
                availableEntries,
                leftX,
                leftY,
                leftW,
                leftH,
                Math.round(leftScrollVisual),
                mouseX,
                mouseY);
            if (entry != null) {
                if (entry.isInsideActionButton(mouseX, mouseY)) {
                    enableFont(entry);
                    return;
                }
                if (entry.isInsideRemoveButton(mouseX, mouseY) && entry.canRemove()) {
                    removeFont(entry);
                    return;
                }
            }
            return;
        }

        // Check enabled column clicks
        if (isInsideArea(mouseX, mouseY, rightX, rightY, rightW, rightH)) {
            FontEntry entry = findEntry(
                enabledEntries,
                rightX,
                rightY,
                rightW,
                rightH,
                Math.round(rightScrollVisual),
                mouseX,
                mouseY);
            if (entry != null) {
                if (entry.isInsideSettingsButton(mouseX, mouseY)) {
                    mc.displayGuiScreen(new FontSettingsScreen(this, entry));
                    return;
                }
                if (entry.isInsideUpButton(mouseX, mouseY)) {
                    moveUp(entry);
                    return;
                }
                if (entry.isInsideDownButton(mouseX, mouseY)) {
                    moveDown(entry);
                    return;
                }
                if (entry.isInsideActionButton(mouseX, mouseY)) {
                    disableFont(entry);
                    return;
                }
            }
            return;
        }

        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton == 0) {
            if (draggingLeftScrollbar) {
                updateScrollbarDrag(true, mouseY);
                return;
            }
            if (draggingRightScrollbar) {
                updateScrollbarDrag(false, mouseY);
                return;
            }
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        if (state != -1) {
            draggingLeftScrollbar = false;
            draggingRightScrollbar = false;
        }
        super.mouseMovedOrUp(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) {
            applyAndClose();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int delta = Mouse.getEventDWheel();
        if (delta == 0) return;
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int steps = delta > 0 ? 1 : -1;
        if (isInsideArea(mouseX, mouseY, leftX, leftY, leftW + SCROLLBAR_W + 4, leftH)) {
            leftScroll = clamp(leftScroll - steps * SCROLL_STEP, 0, leftMaxScroll);
        } else if (isInsideArea(mouseX, mouseY, rightX, rightY, rightW + SCROLLBAR_W + 4, rightH)) {
            rightScroll = clamp(rightScroll - steps * SCROLL_STEP, 0, rightMaxScroll);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // --- List management ---

    private void rebuildLists() {
        availableEntries.clear();
        enabledEntries.clear();

        List<FontSource> allSources = ClientEmojiHandler.getAllSources();
        List<String> enabledIds = EmojiConfig.fontStack != null ? Arrays.asList(EmojiConfig.fontStack)
            : Arrays.asList("twemoji", "minecraft");

        Map<String, FontSource> sourceById = new HashMap<>();
        for (FontSource s : allSources) {
            sourceById.put(s.getId(), s);
        }

        // Build enabled list in config order
        for (String id : enabledIds) {
            FontSource source = sourceById.get(id);
            if (source != null) {
                enabledEntries.add(new FontEntry(source, getOrCreatePreview(source), true));
            }
        }

        // Available: everything not in enabled
        for (FontSource source : allSources) {
            if (enabledIds.contains(source.getId())) continue;
            availableEntries.add(new FontEntry(source, getOrCreatePreview(source), false));
        }

        refreshMetrics();
    }

    private void enableFont(FontEntry entry) {
        availableEntries.remove(entry);
        entry.enabled = true;
        enabledEntries.add(0, entry);
        dirty = true;
        refreshMetrics();
    }

    private void disableFont(FontEntry entry) {
        enabledEntries.remove(entry);
        entry.enabled = false;
        availableEntries.add(entry);
        dirty = true;
        refreshMetrics();
    }

    private void moveUp(FontEntry entry) {
        int idx = enabledEntries.indexOf(entry);
        if (idx <= 0) return;
        enabledEntries.remove(idx);
        enabledEntries.add(idx - 1, entry);
        dirty = true;
        refreshMetrics();
    }

    private void moveDown(FontEntry entry) {
        int idx = enabledEntries.indexOf(entry);
        if (idx < 0 || idx >= enabledEntries.size() - 1) return;
        enabledEntries.remove(idx);
        enabledEntries.add(idx + 1, entry);
        dirty = true;
        refreshMetrics();
    }

    private void removeFont(FontEntry entry) {
        if (!entry.canRemove()) return;
        File file = new File(ClientEmojiHandler.FONTS_DIR, entry.source.getId());
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            showStatus("Failed to remove " + entry.source.getDisplayName(), 0xFFFF8080);
            return;
        }
        availableEntries.remove(entry);
        enabledEntries.remove(entry);
        fontVariationOverrides.remove(entry.source.getId());
        invalidatePreview(entry.source.getId());
        dirty = true;
        refreshMetrics();
        showStatus("Removed " + entry.source.getDisplayName(), 0xFF80FF80);
    }

    private void applyAndClose() {
        persistChanges();
        mc.displayGuiScreen(parent);
    }

    private void persistChanges() {
        if (!dirty) {
            return;
        }

        String[] ids = new String[enabledEntries.size()];
        for (int i = 0; i < enabledEntries.size(); i++) {
            ids[i] = enabledEntries.get(i).source.getId();
        }
        EmojiConfig.fontStack = ids;
        pruneVariationOverrides();
        EmojiConfig.fontVariationSettings = FontVariationConfig.encode(fontVariationOverrides);
        try {
            com.gtnewhorizon.gtnhlib.config.ConfigurationManager.save(EmojiConfig.class);
        } catch (Exception e) {
            Minemoticon.LOG.warn("Failed to save font stack config", e);
        }
        ClientEmojiHandler.reloadFontStack();
        dirty = false;
    }

    private void refreshMetrics() {
        leftMaxScroll = Math.max(0, availableEntries.size() * (CARD_HEIGHT + CARD_GAP) - CARD_GAP - leftH);
        rightMaxScroll = Math.max(0, enabledEntries.size() * (CARD_HEIGHT + CARD_GAP) - CARD_GAP - rightH);
        leftScroll = clamp(leftScroll, 0, leftMaxScroll);
        rightScroll = clamp(rightScroll, 0, rightMaxScroll);
    }

    // --- Rendering ---

    private void drawColumnBg(int x, int y, int w, int h) {
        Gui.drawRect(x - 1, y - 1, x + w + 1, y + h + 1, 0x80202020);
        Gui.drawRect(x, y, x + w, y + h, 0xA0000000);
    }

    private void renderColumn(List<FontEntry> entries, int colX, int colY, int colW, int colH, int scroll, int mouseX,
        int mouseY) {
        enableScissor(colX, colY, colW, colH);
        int cardW = colW - SCROLLBAR_W - 4;
        for (int i = 0; i < entries.size(); i++) {
            FontEntry entry = entries.get(i);
            int cardY = colY + i * (CARD_HEIGHT + CARD_GAP) - scroll;
            if (cardY + CARD_HEIGHT < colY || cardY > colY + colH) continue;
            entry.draw(colX + 1, cardY, cardW, mouseX, mouseY);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void renderScrollbar(int sbX, int colY, int colH, int maxScroll, int scroll, int mouseX, int mouseY) {
        if (maxScroll <= 0) return;
        Gui.drawRect(sbX, colY, sbX + SCROLLBAR_W, colY + colH, 0x30FFFFFF);
        float viewRatio = (float) colH / (float) (colH + maxScroll);
        int thumbH = Math.max(12, (int) (colH * viewRatio));
        int travel = colH - thumbH;
        int thumbY = colY + (travel <= 0 ? 0 : (int) ((float) scroll / maxScroll * travel));
        boolean hovered = mouseX >= sbX - 2 && mouseX <= sbX + SCROLLBAR_W + 2
            && mouseY >= colY
            && mouseY <= colY + colH;
        Gui.drawRect(sbX, thumbY, sbX + SCROLLBAR_W, thumbY + thumbH, hovered ? 0xA0FFFFFF : 0x70FFFFFF);
    }

    private void renderStatus() {
        if (statusText == null || System.currentTimeMillis() > statusUntilMs) return;
        drawCenteredString(fontRendererObj, statusText, width / 2, height - 34, statusColor);
    }

    private void renderDragDropOverlay() {
        if (!dragDropActive) return;
        int[] dropZone = getDropZoneBounds();
        int zoneX = dropZone[0];
        int zoneY = dropZone[1];
        int zoneW = dropZone[2];
        int zoneH = dropZone[3];
        boolean hovered = isDropZoneHovered();

        Gui.drawRect(0, 0, width, height, 0x14000000);
        Gui.drawRect(zoneX, zoneY, zoneX + zoneW, zoneY + zoneH, hovered ? 0x58275EA8 : 0x40193458);
        Gui.drawRect(zoneX + 2, zoneY + 2, zoneX + zoneW - 2, zoneY + zoneH - 2, hovered ? 0x320B1120 : 0x24090E16);
        drawOutline(zoneX, zoneY, zoneW, zoneH, hovered ? 0xE08FD8FF : 0xB06DB8E8);
        drawOutline(zoneX + 2, zoneY + 2, zoneW - 4, zoneH - 4, hovered ? 0x607FCBFF : 0x40639BC6);

        drawCenteredString(
            fontRendererObj,
            "Drop font files here",
            zoneX + zoneW / 2,
            zoneY + zoneH / 2 - 14,
            hovered ? 0xFFE8F0F7 : 0xFFDAE4EE);
        drawCenteredString(
            fontRendererObj,
            "TTF and OTF supported",
            zoneX + zoneW / 2,
            zoneY + zoneH / 2 + 8,
            hovered ? 0xFFCBE9FF : 0xFFAACDE3);
    }

    private void renderPreviewStrip(PreviewTexture preview, int x, int y, int displaySize) {
        mc.getTextureManager()
            .bindTexture(preview.location);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        int count = PREVIEW_CODEPOINTS.length;
        float texW = count * PREVIEW_GLYPH_SIZE;
        for (int i = 0; i < count; i++) {
            float u0 = (float) (i * PREVIEW_GLYPH_SIZE) / texW;
            float u1 = (float) ((i + 1) * PREVIEW_GLYPH_SIZE) / texW;
            int dx = x + i * (displaySize + 1);
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
            tessellator.addVertexWithUV(dx, y, 0, u0, 0);
            tessellator.addVertexWithUV(dx, y + displaySize, 0, u0, 1);
            tessellator.addVertexWithUV(dx + displaySize, y, 0, u1, 0);
            tessellator.addVertexWithUV(dx + displaySize, y + displaySize, 0, u1, 1);
            tessellator.draw();
        }
    }

    // --- Scrollbar interaction ---

    private boolean isInsideScrollbar(int sbX, int colY, int colH, int maxScroll, int mouseX, int mouseY) {
        return maxScroll > 0 && mouseX >= sbX - 2
            && mouseX <= sbX + SCROLLBAR_W + 2
            && mouseY >= colY
            && mouseY <= colY + colH;
    }

    private void beginScrollbarDrag(boolean left, int mouseY) {
        if (left) {
            draggingLeftScrollbar = true;
            leftScrollbarDragOffset = computeScrollbarDragOffset(
                leftY,
                leftH,
                leftMaxScroll,
                Math.round(leftScrollVisual),
                mouseY);
        } else {
            draggingRightScrollbar = true;
            rightScrollbarDragOffset = computeScrollbarDragOffset(
                rightY,
                rightH,
                rightMaxScroll,
                Math.round(rightScrollVisual),
                mouseY);
        }
    }

    private void updateScrollbarDrag(boolean left, int mouseY) {
        if (left) {
            leftScroll = computeScrollFromDrag(leftY, leftH, leftMaxScroll, leftScrollbarDragOffset, mouseY);
            leftScrollVisual = leftScroll;
        } else {
            rightScroll = computeScrollFromDrag(rightY, rightH, rightMaxScroll, rightScrollbarDragOffset, mouseY);
            rightScrollVisual = rightScroll;
        }
    }

    private int computeScrollbarDragOffset(int colY, int colH, int maxScroll, int scroll, int mouseY) {
        float viewRatio = (float) colH / (float) (colH + maxScroll);
        int thumbH = Math.max(12, (int) (colH * viewRatio));
        int travel = colH - thumbH;
        int thumbY = colY + (travel <= 0 ? 0 : (int) ((float) scroll / maxScroll * travel));
        if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
            return mouseY - thumbY;
        }
        return thumbH / 2;
    }

    private int computeScrollFromDrag(int colY, int colH, int maxScroll, int dragOffset, int mouseY) {
        float viewRatio = (float) colH / (float) (colH + maxScroll);
        int thumbH = Math.max(12, (int) (colH * viewRatio));
        int travel = colH - thumbH;
        if (travel <= 0 || maxScroll <= 0) return 0;
        int thumbY = clamp(mouseY - dragOffset, colY, colY + travel);
        float ratio = (float) (thumbY - colY) / (float) travel;
        return clamp(Math.round(ratio * maxScroll), 0, maxScroll);
    }

    // --- Entry lookup ---

    private FontEntry findEntry(List<FontEntry> entries, int colX, int colY, int colW, int colH, int scroll, int mouseX,
        int mouseY) {
        if (!isInsideArea(mouseX, mouseY, colX, colY, colW, colH)) return null;
        int cardW = colW - SCROLLBAR_W - 4;
        for (int i = 0; i < entries.size(); i++) {
            int cardY = colY + i * (CARD_HEIGHT + CARD_GAP) - scroll;
            if (mouseX >= colX + 1 && mouseX < colX + 1 + cardW && mouseY >= cardY && mouseY < cardY + CARD_HEIGHT) {
                FontEntry entry = entries.get(i);
                entry.lastDrawX = colX + 1;
                entry.lastDrawY = cardY;
                entry.lastDrawW = cardW;
                return entry;
            }
        }
        return null;
    }

    // --- Preview infrastructure ---

    private PreviewTexture getOrCreatePreview(FontSource source) {
        String key = source.getId();
        PreviewTexture preview = previews.get(key);
        if (preview == null) {
            preview = new PreviewTexture(key);
            previews.put(key, preview);
            mc.getTextureManager()
                .loadTexture(preview.location, preview);
        }
        requestPreviewLoad(source, preview);
        return preview;
    }

    private void requestPreviewLoad(FontSource source, PreviewTexture preview) {
        if (!preview.tryRequestLoad()) return;

        if (source instanceof CustomFontSource) {
            File file = new File(ClientEmojiHandler.FONTS_DIR, source.getId());
            if (file.isFile()) {
                loadFontPreview(source.getId(), file, preview);
            } else {
                preview.setUnsupported("File not found");
            }
            return;
        }

        if (source instanceof MinecraftFontSource) {
            preview.setUnsupported(null);
            return;
        }

        ColorFont font = source.getColorFont();
        if (font != null) {
            loadPreviewFromFont(source.getId(), font, preview);
            return;
        }

        // Custom font from file
        File file = new File(ClientEmojiHandler.FONTS_DIR, source.getId());
        if (file.isFile()) {
            loadFontPreview(source.getId(), file, preview);
        } else {
            preview.setUnsupported("File not found");
        }
    }

    private void loadPreviewFromFont(String id, ColorFont font, PreviewTexture preview) {
        String cacheKey = "_font_" + id;
        CachedPreviewStrip cached = getCachedPreviewStrip(cacheKey, -1L, -1L);
        if (applyCachedPreview(cached, preview)) return;

        DownloadedTexture.submitToPool(() -> {
            boolean permit = false;
            try {
                PREVIEW_LOAD_SEMAPHORE.acquire();
                permit = true;
                BufferedImage strip = buildPreviewStrip(font);
                preview.setImage(strip);
                cachePreviewStrip(new CachedPreviewStrip(cacheKey, -1L, -1L, strip, null));
            } catch (Exception e) {
                String reason = simplifyLoadError(e);
                preview.setUnsupported(reason);
                cachePreviewStrip(new CachedPreviewStrip(cacheKey, -1L, -1L, null, reason));
            } finally {
                if (permit) PREVIEW_LOAD_SEMAPHORE.release();
            }
        });
    }

    private void loadFontPreview(String filename, File file, PreviewTexture preview) {
        Map<String, Float> variationSettings = getVariationSettings(filename);
        String cacheKey = file.getAbsolutePath() + "#" + FontVariationConfig.signature(variationSettings);
        long fileSize = file.length();
        long lastModified = file.lastModified();
        CachedPreviewStrip cached = getCachedPreviewStrip(cacheKey, fileSize, lastModified);
        if (applyCachedPreview(cached, preview)) return;

        DownloadedTexture.submitToPool(() -> {
            boolean permit = false;
            try {
                PREVIEW_LOAD_SEMAPHORE.acquire();
                permit = true;
                byte[] data = Files.readAllBytes(file.toPath());
                ColorFont font = ColorFont.load(new ByteArrayInputStream(data), variationSettings);
                BufferedImage strip = buildPreviewStrip(font);
                preview.setImage(strip);
                cachePreviewStrip(new CachedPreviewStrip(cacheKey, fileSize, lastModified, strip, null));
            } catch (Exception e) {
                String reason = simplifyLoadError(e);
                preview.setUnsupported(reason);
                cachePreviewStrip(new CachedPreviewStrip(cacheKey, fileSize, lastModified, null, reason));
            } finally {
                if (permit) PREVIEW_LOAD_SEMAPHORE.release();
            }
        });
    }

    private BufferedImage buildPreviewStrip(ColorFont font) {
        int count = PREVIEW_CODEPOINTS.length;
        BufferedImage strip = new BufferedImage(
            count * PREVIEW_GLYPH_SIZE,
            PREVIEW_GLYPH_SIZE,
            BufferedImage.TYPE_INT_ARGB);
        var g = strip.createGraphics();
        for (int i = 0; i < count; i++) {
            BufferedImage glyph = font.renderGlyph(PREVIEW_CODEPOINTS[i], PREVIEW_GLYPH_SIZE);
            if (glyph != null) {
                g.drawImage(glyph, i * PREVIEW_GLYPH_SIZE, 0, null);
            }
        }
        g.dispose();
        return strip;
    }

    // --- Font import ---

    private void addFontFromPicker() {
        FileUtil.FilePickerResult result = FileUtil
            .pickFile("Select font file", ClientEmojiHandler.FONTS_DIR, SUPPORTED_EXTENSIONS);

        switch (result.getStatus()) {
            case CANCELLED:
                return;
            case UNAVAILABLE:
            case ERROR:
                showStatus(result.getMessage(), 0xFFFF8080);
                return;
            case SELECTED:
                break;
        }

        FontImportReport report = importFontFiles(Arrays.asList(result.getFile()));
        applyImportReport(report);
    }

    private FontImportReport importFontFiles(List<File> sources) {
        FontImportReport report = new FontImportReport();
        FileUtil.createFolderIfNotExists(ClientEmojiHandler.FONTS_DIR);

        for (File source : sources) {
            if (source == null) continue;
            try {
                String message = validateFontFile(source);
                if (message != null) {
                    report.recordSkipped(message);
                    continue;
                }
                File target = prepareTargetFile(source);
                if (target == null) {
                    report.recordSkipped(source.getName() + " is already in the fonts folder");
                    continue;
                }
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                report.recordAdded(target.getName());
            } catch (IOException e) {
                report.recordSkipped("Failed to add " + source.getName());
            }
        }

        if (report.addedCount > 0) {
            // Reload to pick up new files, then rebuild lists
            ClientEmojiHandler.reloadFontStack();
            rebuildLists();
        }
        return report;
    }

    private void applyImportReport(FontImportReport report) {
        if (report.addedCount <= 0) {
            showStatus(report.firstMessage != null ? report.firstMessage : "No fonts were added", 0xFFFF8080);
            return;
        }
        if (report.addedCount == 1 && report.skippedCount == 0) {
            showStatus("Added " + report.lastAddedName, 0xFF80FF80);
            return;
        }
        StringBuilder message = new StringBuilder("Added ");
        message.append(report.addedCount)
            .append(report.addedCount == 1 ? " font" : " fonts");
        if (report.skippedCount > 0) {
            message.append(" (")
                .append(report.skippedCount)
                .append(" skipped)");
        }
        showStatus(message.toString(), report.skippedCount > 0 ? 0xFFE0D080 : 0xFF80FF80);
    }

    private File prepareTargetFile(File source) throws IOException {
        File fontsDir = ClientEmojiHandler.FONTS_DIR.getCanonicalFile();
        File canonicalSource = source.getCanonicalFile();
        if (canonicalSource.getParentFile() != null && canonicalSource.getParentFile()
            .equals(fontsDir)) {
            return null;
        }
        return uniqueFontFile(fontsDir, sanitizeFontFilename(source.getName()));
    }

    private String validateFontFile(File source) {
        if (source == null || !source.isFile()) return "Selected file is invalid";
        if (!source.canRead()) return "Selected font is not readable";
        if (!hasSupportedExtension(source.getName())) return "Only TTF and OTF fonts are supported";
        try {
            byte[] data = Files.readAllBytes(source.toPath());
            ColorFont.load(new ByteArrayInputStream(data));
            return null;
        } catch (Exception e) {
            return "Unsupported font: " + simplifyLoadError(e);
        }
    }

    // --- Drag and drop ---

    @Override
    public void onDragBegin() {
        if (Minecraft.getMinecraft().currentScreen != this) return;
        dragDropActive = true;
        dragDropSdlX = -1.0F;
        dragDropSdlY = -1.0F;
        synchronized (droppedFilePaths) {
            droppedFilePaths.clear();
        }
    }

    @Override
    public void onDragPosition(float sdlX, float sdlY) {
        dragDropSdlX = sdlX;
        dragDropSdlY = sdlY;
    }

    @Override
    public void onDropFile(String filePath, float sdlX, float sdlY) {
        synchronized (droppedFilePaths) {
            droppedFilePaths.add(filePath);
        }
    }

    @Override
    public void onDragComplete(WindowDropTarget.DropResult result) {
        dragDropActive = false;
        dragDropSdlX = result.getSdlX();
        dragDropSdlY = result.getSdlY();
        if (Minecraft.getMinecraft().currentScreen != this) {
            synchronized (droppedFilePaths) {
                droppedFilePaths.clear();
            }
            return;
        }

        if (!isInsideDropZone(result.getSdlX(), result.getSdlY())) {
            synchronized (droppedFilePaths) {
                droppedFilePaths.clear();
            }
            return;
        }

        List<File> droppedFiles = new ArrayList<>();
        synchronized (droppedFilePaths) {
            for (String filePath : droppedFilePaths) {
                droppedFiles.add(new File(filePath));
            }
            droppedFilePaths.clear();
        }
        if (droppedFiles.isEmpty() && result.isFileDrop()) {
            droppedFiles.add(new File(result.getFilePath()));
        }

        if (droppedFiles.isEmpty()) {
            if (result.isTextDrop()) {
                showStatus("Drop a TTF/OTF file, not text", 0xFFFF8080);
            }
            return;
        }

        applyImportReport(importFontFiles(droppedFiles));
    }

    // --- Utilities ---

    private void enableScissor(int x, int y, int w, int h) {
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int scale = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * scale, (height - y - h) * scale, w * scale, h * scale);
    }

    private void showStatus(String text, int color) {
        if (text == null || text.isEmpty()) return;
        statusText = text;
        statusColor = color;
        statusUntilMs = System.currentTimeMillis() + STATUS_DURATION_MS;
    }

    private static float lerpScroll(float current, int target) {
        if (Math.abs(current - target) < 0.5F) return target;
        return current + (target - current) * SCROLL_LERP;
    }

    private static boolean isInsideArea(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void drawOutline(int x, int y, int w, int h, int color) {
        Gui.drawRect(x, y, x + w, y + 1, color);
        Gui.drawRect(x, y + h - 1, x + w, y + h, color);
        if (h > 2) {
            Gui.drawRect(x, y + 1, x + 1, y + h - 1, color);
            Gui.drawRect(x + w - 1, y + 1, x + w, y + h - 1, color);
        }
    }

    private void drawMiniButton(int x, int y, String label, boolean hovered, int baseColor, int hoverColor) {
        Gui.drawRect(x, y, x + MINI_BTN, y + MINI_BTN, hovered ? hoverColor : baseColor);
        drawOutline(x, y, MINI_BTN, MINI_BTN, hovered ? 0xC0FFFFFF : 0x60FFFFFF);
        drawCenteredString(fontRendererObj, label, x + MINI_BTN / 2, y + 3, 0xFFFFFFFF);
    }

    private static boolean isInside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private Map<String, Float> getVariationSettings(String fontId) {
        return FontVariationConfig.copySettings(fontVariationOverrides.get(fontId));
    }

    private void setVariationSettings(String fontId, Map<String, Float> settings) {
        if (settings == null || settings.isEmpty()) {
            fontVariationOverrides.remove(fontId);
        } else {
            fontVariationOverrides.put(fontId, new LinkedHashMap<>(settings));
        }
        invalidatePreview(fontId);
        dirty = true;
    }

    private void invalidatePreview(String fontId) {
        PreviewTexture preview = previews.get(fontId);
        if (preview != null) {
            preview.reset();
        }
    }

    private void pruneVariationOverrides() {
        List<String> validIds = new ArrayList<>();
        for (FontEntry entry : availableEntries) {
            validIds.add(entry.source.getId());
        }
        for (FontEntry entry : enabledEntries) {
            if (!validIds.contains(entry.source.getId())) {
                validIds.add(entry.source.getId());
            }
        }
        fontVariationOverrides.keySet()
            .removeIf(id -> !validIds.contains(id));
    }

    private int[] getDropZoneBounds() {
        int zoneW = Math.min(width - PANEL_MARGIN * 6, 404);
        int zoneH = 80;
        int zoneX = (width - zoneW) / 2;
        int contentTop = LIST_TOP + 12;
        int contentBottom = height - LIST_BOTTOM - 36;
        int zoneY = contentTop + Math.max(0, (contentBottom - contentTop - zoneH) / 2);
        return new int[] { zoneX, zoneY, zoneW, zoneH };
    }

    private boolean isDropZoneHovered() {
        return dragDropActive && isInsideDropZone(dragDropSdlX, dragDropSdlY);
    }

    private boolean isInsideDropZone(float sdlX, float sdlY) {
        if (sdlX < 0.0F || sdlY < 0.0F) {
            return false;
        }
        float[] guiCoords = WindowDropTarget.sdlToGuiCoords(sdlX, sdlY);
        int[] dropZone = getDropZoneBounds();
        return isInside(
            Math.round(guiCoords[0]),
            Math.round(guiCoords[1]),
            dropZone[0],
            dropZone[1],
            dropZone[2],
            dropZone[3]);
    }

    private static String sanitizeFontFilename(String name) {
        if (name == null || name.trim()
            .isEmpty()) return "font.ttf";
        String safe = name.trim()
            .replaceAll("[\\\\/:*?\"<>|]", "_");
        return safe.isEmpty() ? "font.ttf" : safe;
    }

    private static File uniqueFontFile(File directory, String fileName) {
        String safeName = sanitizeFontFilename(fileName);
        int dot = safeName.lastIndexOf('.');
        String base = dot > 0 ? safeName.substring(0, dot) : safeName;
        String extension = dot > 0 ? safeName.substring(dot) : ".ttf";
        File candidate = new File(directory, safeName);
        int index = 2;
        while (candidate.exists()) {
            candidate = new File(directory, base + "-" + index + extension);
            index++;
        }
        return candidate;
    }

    private static boolean hasSupportedExtension(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith("." + ext)) return true;
        }
        return false;
    }

    private static String simplifyLoadError(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.trim()
            .isEmpty() ? t.getClass()
                .getSimpleName() : msg.trim();
    }

    private static synchronized CachedPreviewStrip getCachedPreviewStrip(String cacheKey, long fileSize,
        long lastModified) {
        CachedPreviewStrip cached = PREVIEW_STRIP_CACHE.get(cacheKey);
        if (cached == null) return null;
        if (cached.fileSize == fileSize && cached.lastModified == lastModified) return cached;
        PREVIEW_STRIP_CACHE.remove(cacheKey);
        previewStripCacheBytes -= estimatePreviewStripBytes(cached);
        return null;
    }

    private static boolean applyCachedPreview(CachedPreviewStrip cached, PreviewTexture preview) {
        if (cached == null) return false;
        if (cached.image != null) {
            preview.setImage(cached.image);
        } else {
            preview.setUnsupported(cached.unsupportedReason);
        }
        return true;
    }

    private static synchronized void cachePreviewStrip(CachedPreviewStrip entry) {
        CachedPreviewStrip previous = PREVIEW_STRIP_CACHE.put(entry.cacheKey, entry);
        if (previous != null) previewStripCacheBytes -= estimatePreviewStripBytes(previous);
        previewStripCacheBytes += estimatePreviewStripBytes(entry);
        Iterator<Map.Entry<String, CachedPreviewStrip>> it = PREVIEW_STRIP_CACHE.entrySet()
            .iterator();
        while ((PREVIEW_STRIP_CACHE.size() > MAX_PREVIEW_CACHE_ENTRIES
            || previewStripCacheBytes > MAX_PREVIEW_CACHE_BYTES) && it.hasNext()) {
            CachedPreviewStrip eldest = it.next()
                .getValue();
            previewStripCacheBytes -= estimatePreviewStripBytes(eldest);
            it.remove();
        }
    }

    private static long estimatePreviewStripBytes(CachedPreviewStrip entry) {
        if (entry == null || entry.image == null) return 0L;
        return (long) entry.image.getWidth() * (long) entry.image.getHeight() * 4L;
    }

    // --- Inner classes ---

    private static final class FontSettingsScreen extends GuiScreen {

        private static final int BTN_SAVE = 1;
        private static final int BTN_RESET = 2;
        private static final int BTN_CANCEL = 3;
        private static final int PANEL_W = 284;
        private static final int PREVIEW_W = PANEL_W - 20;

        private final FontSelectionScreen parent;
        private final FontEntry entry;
        private final String fontId;
        private final String displayName;
        private final List<VariationAxis> axes;
        private final Map<String, Float> values;
        private final float defaultDisplayHeight;
        private final float defaultWidthPercent;
        private final float defaultVerticalOffset;
        private float displayHeight;
        private float widthPercent;
        private float verticalOffset;
        private int draggingAxis = -1;
        private boolean draggingSize;
        private boolean draggingWidth;
        private boolean draggingYOffset;
        private PreviewTexture previewTexture;
        private String previewSignature = "";
        private Font previewBaseFont;
        private PreviewTextFontSource previewTextSource;
        private FontStack previewFontStack;

        private FontSettingsScreen(FontSelectionScreen parent, FontEntry entry) {
            this.parent = parent;
            this.entry = entry;
            this.fontId = entry.source.getId();
            this.displayName = entry.source.getDisplayName();
            this.axes = entry.getSupportedVariationAxes();
            this.values = parent.getVariationSettings(fontId);
            this.defaultDisplayHeight = entry.source.preserveTextLineMetrics()
                ? EmojiConfig.getFontStackTextDisplayHeight()
                : 8.0f;
            this.defaultWidthPercent = FontVariationConfig.DEFAULT_WIDTH_PERCENT;
            this.defaultVerticalOffset = FontVariationConfig.DEFAULT_Y_OFFSET;
            this.displayHeight = FontVariationConfig.getDisplayHeight(this.values, entry.source.getDisplayHeight());
            this.widthPercent = FontVariationConfig.getWidthPercent(this.values, this.defaultWidthPercent);
            this.verticalOffset = FontVariationConfig.getVerticalOffset(this.values, this.defaultVerticalOffset);
            for (VariationAxis axis : axes) {
                if (!values.containsKey(axis.getTag())) {
                    values.put(axis.getTag(), axis.getDefaultValue());
                }
            }
        }

        @Override
        public void initGui() {
            buttonList.clear();
            int panelH = getPanelHeight();
            int panelX = (width - PANEL_W) / 2;
            int panelY = getPanelY();
            int btnY = panelY + panelH - 28;
            buttonList.add(new GuiButton(BTN_SAVE, panelX + 10, btnY, 74, 20, "Save"));
            buttonList.add(new GuiButton(BTN_RESET, panelX + (PANEL_W - 60) / 2, btnY, 60, 20, "Reset"));
            buttonList.add(new GuiButton(BTN_CANCEL, panelX + PANEL_W - 84, btnY, 74, 20, "Cancel"));
            if (!entry.source.preserveTextLineMetrics() && previewTexture == null) {
                previewTexture = new PreviewTexture("font_settings_" + fontId, true);
                mc.getTextureManager()
                    .loadTexture(previewTexture.location, previewTexture);
            }
            previewBaseFont = loadPreviewBaseFont();
            previewSignature = "";
        }

        @Override
        protected void actionPerformed(GuiButton button) {
            if (button.id == BTN_CANCEL) {
                mc.displayGuiScreen(parent);
                return;
            }
            if (button.id == BTN_RESET) {
                displayHeight = defaultDisplayHeight;
                widthPercent = defaultWidthPercent;
                verticalOffset = defaultVerticalOffset;
                for (VariationAxis axis : axes) {
                    values.put(axis.getTag(), axis.getDefaultValue());
                }
                previewSignature = "";
                return;
            }
            if (button.id == BTN_SAVE) {
                parent.setVariationSettings(fontId, buildStoredSettings());
                parent.persistChanges();
                parent.rebuildLists();
                parent.showStatus("Saved settings for " + displayName, 0xFF80FF80);
                mc.displayGuiScreen(parent);
            }
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int button) {
            super.mouseClicked(mouseX, mouseY, button);
            if (button != 0) return;

            if (isInsideSizeSlider(mouseX, mouseY)) {
                draggingSize = true;
                updateSizeFromMouse(mouseX);
                return;
            }
            if (isInsideWidthSlider(mouseX, mouseY)) {
                draggingWidth = true;
                updateWidthFromMouse(mouseX);
                return;
            }
            if (isInsideYOffsetSlider(mouseX, mouseY)) {
                draggingYOffset = true;
                updateYOffsetFromMouse(mouseX);
                return;
            }
            for (int i = 0; i < axes.size(); i++) {
                if (isInsideSlider(mouseX, mouseY, i)) {
                    draggingAxis = i;
                    updateAxisFromMouse(i, mouseX);
                    return;
                }
            }
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
            if (clickedMouseButton == 0) {
                if (draggingSize) {
                    updateSizeFromMouse(mouseX);
                    return;
                }
                if (draggingWidth) {
                    updateWidthFromMouse(mouseX);
                    return;
                }
                if (draggingYOffset) {
                    updateYOffsetFromMouse(mouseX);
                    return;
                }
                if (draggingAxis >= 0 && draggingAxis < axes.size()) {
                    updateAxisFromMouse(draggingAxis, mouseX);
                    return;
                }
            }
            super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        }

        @Override
        protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
            if (state != -1) {
                draggingAxis = -1;
                draggingSize = false;
                draggingWidth = false;
                draggingYOffset = false;
            }
            super.mouseMovedOrUp(mouseX, mouseY, state);
        }

        @Override
        protected void keyTyped(char typedChar, int keyCode) {
            if (keyCode == 1) {
                mc.displayGuiScreen(parent);
                return;
            }
            if (keyCode == 28 || keyCode == 156) {
                parent.setVariationSettings(fontId, buildStoredSettings());
                parent.persistChanges();
                parent.rebuildLists();
                parent.showStatus("Saved settings for " + displayName, 0xFF80FF80);
                mc.displayGuiScreen(parent);
                return;
            }
            super.keyTyped(typedChar, keyCode);
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            parent.drawScreen(-1, -1, partialTicks);
            drawRect(0, 0, width, height, 0x90000000);

            ensurePreviewUpToDate();

            int panelH = getPanelHeight();
            int panelX = (width - PANEL_W) / 2;
            int panelY = getPanelY();

            Gui.drawRect(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY + panelH + 1, 0xA0A0A0A0);
            Gui.drawRect(panelX, panelY, panelX + PANEL_W, panelY + panelH, 0xF0101010);

            drawCenteredString(fontRendererObj, "Font Settings", width / 2, panelY + 8, 0xFFFFFF);

            String title = displayName;
            int maxTitleW = PANEL_W - 20;
            if (fontRendererObj.getStringWidth(title) > maxTitleW) {
                title = fontRendererObj.trimStringToWidth(title, maxTitleW - fontRendererObj.getStringWidth("..."))
                    + "...";
            }
            drawCenteredString(fontRendererObj, title, width / 2, panelY + 20, 0xFFD0D0D0);

            drawPreview(panelX + 10, panelY + 34, mouseX, mouseY);

            int rowsY = panelY + 34 + getPreviewHeight() + 12;
            drawSizeRow(panelX, rowsY, mouseX, mouseY);
            int nextRowIndex = 1;
            if (entry.source.preserveTextLineMetrics()) {
                drawWidthRow(panelX, rowsY + nextRowIndex * getRowHeight(), mouseX, mouseY);
                nextRowIndex++;
                drawYOffsetRow(panelX, rowsY + nextRowIndex * getRowHeight(), mouseX, mouseY);
                nextRowIndex++;
            }
            for (int i = 0; i < axes.size(); i++) {
                drawAxisRow(panelX, rowsY + (nextRowIndex + i) * getRowHeight(), i, mouseX, mouseY);
            }

            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        @Override
        public void onGuiClosed() {
            GlyphCache.invalidate(getPreviewTextSourceId());
            super.onGuiClosed();
        }

        private int getRowHeight() {
            return getLayoutMetrics()[0];
        }

        private int getPreviewHeight() {
            return getLayoutMetrics()[1];
        }

        private int getPanelHeight() {
            return getLayoutMetrics()[2];
        }

        private int getPanelY() {
            return getLayoutMetrics()[3];
        }

        private int[] getLayoutMetrics() {
            int rowH = 26;
            int previewH = 72;
            while (88 + previewH + getSliderRowCount() * rowH > height - 16 && (previewH > 44 || rowH > 20)) {
                if (previewH > 44) {
                    previewH -= 4;
                } else {
                    rowH -= 2;
                }
            }
            int panelH = Math.min(height - 16, 88 + previewH + getSliderRowCount() * rowH);
            int panelY = Math.max(8, (height - panelH) / 2);
            return new int[] { rowH, previewH, panelH, panelY };
        }

        private int getSliderRowCount() {
            return 1 + axes.size() + (entry.source.preserveTextLineMetrics() ? 2 : 0);
        }

        private void drawPreview(int x, int y, int mouseX, int mouseY) {
            int previewH = getPreviewHeight();
            Gui.drawRect(x, y, x + PREVIEW_W, y + previewH, 0x40283040);
            parent.drawOutline(x, y, PREVIEW_W, previewH, 0x50FFFFFF);
            int previewPad = 8;
            int buttonW = 96;
            int buttonH = 20;
            int previewGap = 10;
            int buttonX = x + PREVIEW_W - previewPad - buttonW;
            int buttonY = y + (previewH - buttonH) / 2;
            int chatX = x + previewPad;
            int chatW = Math.max(80, buttonX - previewGap - chatX);
            int chatStripeH = Math.min(34, Math.max(28, previewH - previewPad * 2));
            int chatY = y + (previewH - chatStripeH) / 2;
            Gui.drawRect(chatX, chatY, chatX + chatW, chatY + chatStripeH, 0x88202020);

            GuiButton previewButton = new GuiButton(-1337, buttonX, buttonY, buttonW, 20, "");
            previewButton.drawButton(mc, mouseX, mouseY);

            if (entry.source.preserveTextLineMetrics() && previewFontStack != null) {
                String buttonSample = "Button";
                int textColor = 0xE0E0E0;

                runWithPreviewFontStack(() -> {
                    drawPreviewChat(chatX, chatY, chatW, chatStripeH, textColor);
                    int buttonTextWidth = fontRendererObj.getStringWidth(buttonSample);
                    int buttonTextX = buttonX + (buttonW - buttonTextWidth) / 2;
                    int buttonTextY = buttonY + 6;
                    fontRendererObj.drawStringWithShadow(buttonSample, buttonTextX, buttonTextY, textColor);
                });
                return;
            }

            if (previewTexture != null && previewTexture.canRender()) {
                mc.getTextureManager()
                    .bindTexture(previewTexture.location);
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
                Tessellator tessellator = Tessellator.instance;
                tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
                tessellator.addVertexWithUV(x + 2, y + 2, 0, 0, 0);
                tessellator.addVertexWithUV(x + 2, y + previewH - 2, 0, 0, 1);
                tessellator.addVertexWithUV(x + PREVIEW_W - 2, y + 2, 0, 1, 0);
                tessellator.addVertexWithUV(x + PREVIEW_W - 2, y + previewH - 2, 0, 1, 1);
                tessellator.draw();
                return;
            }
            String fallback = previewTexture != null && previewTexture.isUnsupported() ? "Preview unavailable"
                : "Loading preview...";
            drawCenteredString(fontRendererObj, fallback, x + PREVIEW_W / 2, y + previewH / 2 - 4, 0xFFB7C7D7);
        }

        private void drawPreviewChat(int chatX, int chatY, int chatW, int chatH, int textColor) {
            List<String> lines = new ArrayList<>(3);
            lines.add("<alex> The quick brown fox");
            lines.add("jumps over the lazy dog");
            lines.add("This is how it will look in chat.");

            int textMaxWidth = Math.max(40, chatW - 8);
            int maxLines = Math.max(2, chatH / 10);
            List<String> visibleLines = new ArrayList<>(maxLines);
            for (String rawLine : lines) {
                String fitted = fontRendererObj.trimStringToWidth(rawLine, textMaxWidth);
                if (!fitted.isEmpty()) {
                    visibleLines.add(fitted);
                }
                if (visibleLines.size() >= maxLines) {
                    break;
                }
            }

            int lineY = chatY + 3;
            for (String line : visibleLines) {
                fontRendererObj.drawStringWithShadow(line, chatX + 4, lineY, textColor);
                lineY += 9;
                if (lineY + 8 > chatY + chatH) {
                    break;
                }
            }
        }

        private void drawSizeRow(int panelX, int rowY, int mouseX, int mouseY) {
            drawSliderRow(
                panelX,
                rowY,
                "Size",
                displayHeight,
                formatSizeValue(displayHeight),
                FontVariationConfig.MIN_SIZE,
                FontVariationConfig.MAX_SIZE,
                isInsideSizeSlider(mouseX, mouseY));
        }

        private void drawWidthRow(int panelX, int rowY, int mouseX, int mouseY) {
            drawSliderRow(
                panelX,
                rowY,
                "Width",
                widthPercent,
                formatWidthValue(widthPercent),
                FontVariationConfig.MIN_WIDTH_PERCENT,
                FontVariationConfig.MAX_WIDTH_PERCENT,
                isInsideWidthSlider(mouseX, mouseY));
        }

        private void drawYOffsetRow(int panelX, int rowY, int mouseX, int mouseY) {
            drawSliderRow(
                panelX,
                rowY,
                "Y Offset",
                verticalOffset,
                formatYOffsetValue(verticalOffset),
                FontVariationConfig.MIN_Y_OFFSET,
                FontVariationConfig.MAX_Y_OFFSET,
                isInsideYOffsetSlider(mouseX, mouseY));
        }

        private void drawAxisRow(int panelX, int rowY, int axisIndex, int mouseX, int mouseY) {
            VariationAxis axis = axes.get(axisIndex);
            float value = values.get(axis.getTag());
            drawSliderRow(
                panelX,
                rowY,
                axis.getDisplayName(),
                value,
                formatAxisValue(axis, value),
                axis.getMinValue(),
                axis.getMaxValue(),
                isInsideSlider(mouseX, mouseY, axisIndex));
        }

        private boolean isInsideSlider(int mouseX, int mouseY, int axisIndex) {
            return isInsideSliderRow(mouseX, mouseY, getAxisRowIndex(axisIndex));
        }

        private boolean isInsideSizeSlider(int mouseX, int mouseY) {
            return isInsideSliderRow(mouseX, mouseY, 0);
        }

        private boolean isInsideWidthSlider(int mouseX, int mouseY) {
            return entry.source.preserveTextLineMetrics() && isInsideSliderRow(mouseX, mouseY, 1);
        }

        private boolean isInsideYOffsetSlider(int mouseX, int mouseY) {
            return entry.source.preserveTextLineMetrics() && isInsideSliderRow(mouseX, mouseY, 2);
        }

        private boolean isInsideSliderRow(int mouseX, int mouseY, int rowIndex) {
            int panelX = (width - PANEL_W) / 2;
            int panelY = getPanelY();
            int rowsY = panelY + 34 + getPreviewHeight() + 12;
            int sliderX = panelX + 10;
            int sliderY = rowsY + rowIndex * getRowHeight() + 13;
            int sliderW = PANEL_W - 20;
            return isInside(mouseX, mouseY, sliderX, sliderY - 2, sliderW, 10);
        }

        private int getAxisRowIndex(int axisIndex) {
            return 1 + (entry.source.preserveTextLineMetrics() ? 2 : 0) + axisIndex;
        }

        private void drawSliderRow(int panelX, int rowY, String label, float value, String valueText, float min,
            float max, boolean hovered) {
            fontRendererObj.drawStringWithShadow(label, panelX + 10, rowY, 0xFFE8E8E8);
            fontRendererObj.drawStringWithShadow(
                valueText,
                panelX + PANEL_W - 10 - fontRendererObj.getStringWidth(valueText),
                rowY,
                0xFFBFD7F2);

            int sliderX = panelX + 10;
            int sliderY = rowY + 13;
            int sliderW = PANEL_W - 20;
            Gui.drawRect(sliderX, sliderY, sliderX + sliderW, sliderY + 4, hovered ? 0x704B5F75 : 0x50303840);
            float ratio = getSliderRatio(value, min, max);
            int fillW = Math.max(4, Math.round(ratio * sliderW));
            Gui.drawRect(sliderX, sliderY, sliderX + fillW, sliderY + 4, hovered ? 0xC070A7E8 : 0xA0588CC8);
            int knobX = sliderX + Math.round(ratio * (sliderW - 6));
            Gui.drawRect(knobX, sliderY - 2, knobX + 6, sliderY + 6, hovered ? 0xFFEAF5FF : 0xFFD5E7FA);
        }

        private void updateSizeFromMouse(int mouseX) {
            int panelX = (width - PANEL_W) / 2;
            int sliderX = panelX + 10;
            int sliderW = PANEL_W - 20;
            float ratio = clamp01((float) (mouseX - sliderX) / (float) sliderW);
            displayHeight = FontVariationConfig.clampDisplayHeight(
                FontVariationConfig.MIN_SIZE + (FontVariationConfig.MAX_SIZE - FontVariationConfig.MIN_SIZE) * ratio);
            previewSignature = "";
        }

        private void updateWidthFromMouse(int mouseX) {
            int panelX = (width - PANEL_W) / 2;
            int sliderX = panelX + 10;
            int sliderW = PANEL_W - 20;
            float ratio = clamp01((float) (mouseX - sliderX) / (float) sliderW);
            widthPercent = FontVariationConfig.clampWidthPercent(
                FontVariationConfig.MIN_WIDTH_PERCENT
                    + (FontVariationConfig.MAX_WIDTH_PERCENT - FontVariationConfig.MIN_WIDTH_PERCENT) * ratio);
            previewSignature = "";
        }

        private void updateYOffsetFromMouse(int mouseX) {
            int panelX = (width - PANEL_W) / 2;
            int sliderX = panelX + 10;
            int sliderW = PANEL_W - 20;
            float ratio = clamp01((float) (mouseX - sliderX) / (float) sliderW);
            verticalOffset = FontVariationConfig.clampVerticalOffset(
                FontVariationConfig.MIN_Y_OFFSET
                    + (FontVariationConfig.MAX_Y_OFFSET - FontVariationConfig.MIN_Y_OFFSET) * ratio);
            previewSignature = "";
        }

        private void updateAxisFromMouse(int axisIndex, int mouseX) {
            VariationAxis axis = axes.get(axisIndex);
            int panelX = (width - PANEL_W) / 2;
            int sliderX = panelX + 10;
            int sliderW = PANEL_W - 20;
            float ratio = clamp01((float) (mouseX - sliderX) / (float) sliderW);
            float value = axis.getMinValue() + (axis.getMaxValue() - axis.getMinValue()) * ratio;
            if ("ital".equals(axis.getTag())) {
                value = value >= (axis.getMinValue() + axis.getMaxValue()) * 0.5f ? axis.getMaxValue()
                    : axis.getMinValue();
            }
            values.put(axis.getTag(), clampFloat(value, axis.getMinValue(), axis.getMaxValue()));
            previewSignature = "";
        }

        private Map<String, Float> buildStoredSettings() {
            Map<String, Float> stored = new LinkedHashMap<>();
            if (Math.abs(displayHeight - defaultDisplayHeight) > 0.001f) {
                stored.put(FontVariationConfig.SIZE_KEY, FontVariationConfig.clampDisplayHeight(displayHeight));
            }
            if (Math.abs(widthPercent - defaultWidthPercent) > 0.001f) {
                stored.put(FontVariationConfig.WIDTH_KEY, FontVariationConfig.clampWidthPercent(widthPercent));
            }
            if (Math.abs(verticalOffset - defaultVerticalOffset) > 0.001f) {
                stored.put(FontVariationConfig.Y_OFFSET_KEY, FontVariationConfig.clampVerticalOffset(verticalOffset));
            }
            for (VariationAxis axis : axes) {
                float value = values.get(axis.getTag());
                if (Math.abs(value - axis.getDefaultValue()) > 0.0005f) {
                    stored.put(axis.getTag(), value);
                }
            }
            return stored;
        }

        private float getSliderRatio(float value, float min, float max) {
            float span = max - min;
            if (span <= 0.0001f) return 0.0f;
            return clamp01((value - min) / span);
        }

        private String formatAxisValue(VariationAxis axis, float value) {
            if ("ital".equals(axis.getTag())) {
                return value >= 0.5f ? "On" : "Off";
            }
            if (Math.abs(value - Math.round(value)) < 0.0005f) {
                return Integer.toString(Math.round(value));
            }
            return String.format(Locale.ROOT, "%.2f", value);
        }

        private String formatSizeValue(float value) {
            if (Math.abs(value - Math.round(value)) < 0.0005f) {
                return Math.round(value) + " px";
            }
            return String.format(Locale.ROOT, "%.1f px", value);
        }

        private String formatWidthValue(float value) {
            if (Math.abs(value - Math.round(value)) < 0.0005f) {
                return Math.round(value) + "%";
            }
            return String.format(Locale.ROOT, "%.1f%%", value);
        }

        private String formatYOffsetValue(float value) {
            if (Math.abs(value) < 0.0005f) {
                return "0 px";
            }
            if (Math.abs(value - Math.round(value)) < 0.0005f) {
                return String.format(Locale.ROOT, "%+d px", Math.round(value));
            }
            return String.format(Locale.ROOT, "%+.1f px", value);
        }

        private void ensurePreviewUpToDate() {
            String signature = FontVariationConfig.signature(buildStoredSettings());
            if (signature.equals(previewSignature)) return;
            previewSignature = signature;

            if (entry.source.preserveTextLineMetrics()) {
                GlyphCache.invalidate(getPreviewTextSourceId());
                previewTextSource = buildPreviewTextSource();
                if (previewTextSource == null) {
                    previewFontStack = null;
                } else {
                    previewFontStack = new FontStack(Arrays.asList(previewTextSource, new MinecraftFontSource()));
                }
                return;
            }

            if (previewTexture == null) return;
            BufferedImage image = buildLivePreviewImage(PREVIEW_W - 4, getPreviewHeight() - 4);
            if (image != null) {
                previewTexture.setImage(image);
            } else {
                previewTexture.setUnsupported("Preview unavailable");
            }
        }

        private BufferedImage buildLivePreviewImage(int width, int height) {
            ColorFont colorFont = entry.source.getColorFont();
            if (colorFont != null) {
                return buildEmojiPreviewImage(colorFont, width, height);
            }
            return null;
        }

        private BufferedImage buildEmojiPreviewImage(ColorFont colorFont, int width, int height) {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            int glyphSize = Math.max(18, Math.min(40, Math.round(displayHeight * 2.8f)));
            int spacing = 4;
            int totalWidth = PREVIEW_CODEPOINTS.length * glyphSize + (PREVIEW_CODEPOINTS.length - 1) * spacing;
            int x = Math.max(4, (width - totalWidth) / 2);
            int y = Math.max(2, (height - glyphSize) / 2);
            for (int codepoint : PREVIEW_CODEPOINTS) {
                BufferedImage glyph = colorFont.renderGlyph(codepoint, glyphSize);
                if (glyph != null) {
                    g.drawImage(glyph, x, y, null);
                }
                x += glyphSize + spacing;
            }
            g.dispose();
            return image;
        }

        private PreviewTextFontSource buildPreviewTextSource() {
            if (previewBaseFont == null) {
                return null;
            }
            return new PreviewTextFontSource(
                getPreviewTextSourceId(),
                displayName,
                previewBaseFont,
                axes,
                buildStoredSettings(),
                displayHeight);
        }

        private String getPreviewTextSourceId() {
            return "__font_preview__" + fontId;
        }

        private void runWithPreviewFontStack(Runnable task) {
            FontStack previous = ClientEmojiHandler.pushFontStackOverride(previewFontStack);
            try {
                task.run();
            } finally {
                ClientEmojiHandler.popFontStackOverride(previous);
            }
        }

        private Font loadPreviewBaseFont() {
            if (entry.source instanceof MinecraftFontSource) {
                return null;
            }
            try {
                if (entry.source instanceof CustomFontSource) {
                    File file = new File(ClientEmojiHandler.FONTS_DIR, fontId);
                    if (!file.isFile()) return null;
                    return Font.createFont(Font.TRUETYPE_FONT, file);
                }
                if ("twemoji".equals(fontId)) {
                    try (InputStream in = FontSelectionScreen.class
                        .getResourceAsStream("/assets/minemoticon/twemoji.ttf")) {
                        if (in == null) return null;
                        return Font.createFont(Font.TRUETYPE_FONT, in);
                    }
                }
            } catch (Exception ignored) {}
            return null;
        }

        private String pickPreviewSampleText() {
            return "The quick brown fox jumps over the lazy dog";
        }

        private static float clamp01(float value) {
            return Math.max(0.0f, Math.min(1.0f, value));
        }

        private static float clampFloat(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private final class FontEntry {

        final FontSource source;
        final PreviewTexture preview;
        boolean enabled;
        int lastDrawX, lastDrawY, lastDrawW;

        FontEntry(FontSource source, PreviewTexture preview, boolean enabled) {
            this.source = source;
            this.preview = preview;
            this.enabled = enabled;
        }

        boolean canRemove() {
            return !source.isBuiltIn();
        }

        boolean hasSettingsButton() {
            return enabled && !(source instanceof MinecraftFontSource);
        }

        List<VariationAxis> getSupportedVariationAxes() {
            List<VariationAxis> axes = new ArrayList<>();
            for (VariationAxis axis : source.getVariationAxes()) {
                if (axis.isSupportedForUi()) {
                    axes.add(axis);
                }
            }
            return axes;
        }

        void draw(int x, int y, int w, int mouseX, int mouseY) {
            this.lastDrawX = x;
            this.lastDrawY = y;
            this.lastDrawW = w;

            boolean hovered = isInside(mouseX, mouseY, x, y, w, CARD_HEIGHT);
            Gui.drawRect(x, y, x + w, y + CARD_HEIGHT, hovered ? 0xA0252525 : 0x90181818);
            drawOutline(x, y, w, CARD_HEIGHT, hovered ? 0x60FFFFFF : 0x30FFFFFF);

            int textX = x + CARD_PAD + 2;
            int titleY = y + CARD_PAD;

            // Title
            String title = source.getDisplayName();
            if (source instanceof MinecraftFontSource) {
                title = "Minecraft (vanilla)";
            }
            int rightButtonCount = enabled ? (hasSettingsButton() ? 4 : 3) : (canRemove() ? 2 : 1);
            int maxTitleW = w - CARD_PAD * 2 - rightButtonCount * (MINI_BTN + 2) - 4;
            if (fontRendererObj.getStringWidth(title) > maxTitleW) {
                while (fontRendererObj.getStringWidth(title + "...") > maxTitleW && title.length() > 1) {
                    title = title.substring(0, title.length() - 1);
                }
                title = title + "...";
            }
            fontRendererObj.drawStringWithShadow(title, textX, titleY, 0xFFFFFFFF);

            // Preview strip or label
            int previewY = y + 18;
            if (source instanceof MinecraftFontSource) {
                fontRendererObj.drawString("Standard bitmap font", textX, previewY + 2, 0xFF888888);
            } else if (preview != null && preview.canRender()) {
                renderPreviewStrip(preview, textX, previewY, PREVIEW_DISPLAY_SIZE);
            } else if (preview != null && preview.isUnsupported()) {
                fontRendererObj.drawString("Unsupported", textX, previewY + 2, 0xFFE48A8A);
            } else {
                fontRendererObj.drawString("Loading...", textX, previewY + 2, 0xFF707070);
            }

            // Buttons on the right side
            int btnX = x + w - CARD_PAD;
            int btnY = y + (CARD_HEIGHT - MINI_BTN) / 2;

            if (enabled) {
                // Up / Down / Disable buttons
                int idx = enabledEntries.indexOf(this);
                if (hasSettingsButton()) {
                    btnX -= MINI_BTN;
                    boolean settingsHovered = isInside(mouseX, mouseY, btnX, btnY, MINI_BTN, MINI_BTN);
                    drawMiniButton(btnX, btnY, SETTINGS_GEAR, settingsHovered, 0x60303030, 0x90405070);
                }

                btnX -= MINI_BTN;
                boolean disableHovered = isInside(mouseX, mouseY, btnX, btnY, MINI_BTN, MINI_BTN);
                drawMiniButton(btnX, btnY, "<", disableHovered, 0x60303030, 0x90403030);

                btnX -= MINI_BTN + 2;
                boolean downHovered = isInside(mouseX, mouseY, btnX, btnY, MINI_BTN, MINI_BTN);
                boolean canDown = idx >= 0 && idx < enabledEntries.size() - 1;
                drawMiniButton(btnX, btnY, "v", downHovered && canDown, canDown ? 0x60303030 : 0x40202020, 0x90303050);

                btnX -= MINI_BTN + 2;
                boolean upHovered = isInside(mouseX, mouseY, btnX, btnY, MINI_BTN, MINI_BTN);
                boolean canUp = idx > 0;
                drawMiniButton(btnX, btnY, "^", upHovered && canUp, canUp ? 0x60303030 : 0x40202020, 0x90303050);
            } else {
                // Enable button (and optionally remove)
                btnX -= MINI_BTN;
                boolean enableHovered = isInside(mouseX, mouseY, btnX, btnY, MINI_BTN, MINI_BTN);
                drawMiniButton(btnX, btnY, ">", enableHovered, 0x60303030, 0x90304030);

                if (canRemove()) {
                    btnX -= MINI_BTN + 2;
                    boolean removeHovered = isInside(mouseX, mouseY, btnX, btnY, MINI_BTN, MINI_BTN);
                    drawMiniButton(btnX, btnY, "x", removeHovered, 0x60303030, 0x90403030);
                }
            }
        }

        boolean isInsideActionButton(int mx, int my) {
            int btnX = lastDrawX + lastDrawW - CARD_PAD - MINI_BTN;
            if (enabled && hasSettingsButton()) {
                btnX -= MINI_BTN + 2;
            }
            int btnY = lastDrawY + (CARD_HEIGHT - MINI_BTN) / 2;
            return isInside(mx, my, btnX, btnY, MINI_BTN, MINI_BTN);
        }

        boolean isInsideUpButton(int mx, int my) {
            int settingsOffset = hasSettingsButton() ? MINI_BTN + 2 : 0;
            int btnX = lastDrawX + lastDrawW - CARD_PAD - settingsOffset - MINI_BTN * 3 - 4;
            int btnY = lastDrawY + (CARD_HEIGHT - MINI_BTN) / 2;
            return isInside(mx, my, btnX, btnY, MINI_BTN, MINI_BTN);
        }

        boolean isInsideDownButton(int mx, int my) {
            int settingsOffset = hasSettingsButton() ? MINI_BTN + 2 : 0;
            int btnX = lastDrawX + lastDrawW - CARD_PAD - settingsOffset - MINI_BTN * 2 - 2;
            int btnY = lastDrawY + (CARD_HEIGHT - MINI_BTN) / 2;
            return isInside(mx, my, btnX, btnY, MINI_BTN, MINI_BTN);
        }

        boolean isInsideRemoveButton(int mx, int my) {
            if (!canRemove() || enabled) return false;
            int btnX = lastDrawX + lastDrawW - CARD_PAD - MINI_BTN * 2 - 2;
            int btnY = lastDrawY + (CARD_HEIGHT - MINI_BTN) / 2;
            return isInside(mx, my, btnX, btnY, MINI_BTN, MINI_BTN);
        }

        boolean isInsideSettingsButton(int mx, int my) {
            if (!enabled || !hasSettingsButton()) return false;
            int btnX = lastDrawX + lastDrawW - CARD_PAD - MINI_BTN;
            int btnY = lastDrawY + (CARD_HEIGHT - MINI_BTN) / 2;
            return isInside(mx, my, btnX, btnY, MINI_BTN, MINI_BTN);
        }
    }

    private static final class PreviewTextFontSource extends FontSource {

        private static final FontRenderContext CONTEXT = new FontRenderContext(new AffineTransform(), true, false);
        private final String id;
        private final String displayName;
        private final Font font;
        private final float displayHeight;
        private final float widthScale;
        private final float verticalOffset;

        private PreviewTextFontSource(String id, String displayName, Font baseFont, List<VariationAxis> axes,
            Map<String, Float> settings, float displayHeight) {
            this.id = id;
            this.displayName = displayName;
            this.displayHeight = FontVariationConfig.clampDisplayHeight(displayHeight);
            this.widthScale = FontVariationConfig.getWidthScale(settings, FontVariationConfig.DEFAULT_WIDTH_PERCENT);
            this.verticalOffset = FontVariationConfig.getVerticalOffset(settings, FontVariationConfig.DEFAULT_Y_OFFSET);

            Font derived = baseFont.deriveFont(256.0f);
            this.font = ColorFont
                .applyVariationSettings(derived, axes, FontVariationConfig.copyVariationSettings(settings));
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public boolean isBuiltIn() {
            return true;
        }

        @Override
        public boolean hasGlyph(int codepoint) {
            return layoutSingleGlyphVector(codepoint) != null;
        }

        @Override
        public BufferedImage renderGlyph(int codepoint, int size) {
            return renderTextGlyph(codepoint, size);
        }

        @Override
        public BufferedImage renderTextGlyph(int codepoint, int size) {
            GlyphVector glyphVector = layoutSingleGlyphVector(codepoint);
            if (glyphVector == null) {
                return null;
            }

            Shape outline = glyphVector.getGlyphOutline(0);
            Rectangle2D bounds = outline.getBounds2D();
            float advance = getTextGlyphAdvance(codepoint, size);
            if ((bounds == null || bounds.isEmpty()) && advance <= 0.0f) {
                return null;
            }

            float pad = Math.max(1.0f, size * 0.03125f);
            LineMetrics lineMetrics = font.getLineMetrics("Hg", CONTEXT);
            double metricHeight = Math.max(1.0d, lineMetrics.getAscent() + lineMetrics.getDescent());
            double scale = Math.max(0.01d, (size - pad * 2.0d) / metricHeight);

            double minX = bounds != null ? Math.min(0.0d, bounds.getX()) : 0.0d;
            double maxX = bounds != null && !bounds.isEmpty()
                ? Math.max(
                    bounds.getMaxX(),
                    glyphVector.getGlyphPosition(1)
                        .getX())
                : glyphVector.getGlyphPosition(1)
                    .getX();
            int width = Math.max(1, (int) Math.ceil((maxX - minX) * scale + pad * 2.0d));

            BufferedImage result = new BufferedImage(width, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = result.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(
                RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g2d.setColor(java.awt.Color.WHITE);

            var transform = new AffineTransform();
            double baseline = Math.rint(pad + lineMetrics.getAscent() * scale);
            double translateX = Math.rint(pad - minX * scale);
            transform.translate(translateX, baseline);
            transform.scale(scale, scale);
            g2d.fill(transform.createTransformedShape(outline));
            g2d.dispose();
            return result;
        }

        @Override
        public boolean canRender(int[] codepoints) {
            return codepoints.length == 1 && hasGlyph(codepoints[0]);
        }

        @Override
        public BufferedImage renderGlyphs(int[] codepoints, int size) {
            return codepoints.length == 1 ? renderTextGlyph(codepoints[0], size) : null;
        }

        @Override
        public float getTextGlyphAdvance(int codepoint, int size) {
            GlyphVector glyphVector = layoutSingleGlyphVector(codepoint);
            if (glyphVector == null) {
                return -1.0f;
            }

            float pad = Math.max(1.0f, size * 0.03125f);
            LineMetrics lineMetrics = font.getLineMetrics("Hg", CONTEXT);
            double metricHeight = Math.max(1.0d, lineMetrics.getAscent() + lineMetrics.getDescent());
            double scale = Math.max(0.01d, (size - pad * 2.0d) / metricHeight);
            return (float) (glyphVector.getGlyphMetrics(0)
                .getAdvanceX() * scale);
        }

        @Override
        public float getTextGlyphOffsetX(int codepoint, int size) {
            GlyphVector glyphVector = layoutSingleGlyphVector(codepoint);
            if (glyphVector == null) {
                return 0.0f;
            }

            Shape outline = glyphVector.getGlyphOutline(0);
            Rectangle2D bounds = outline.getBounds2D();
            if (bounds == null || bounds.isEmpty()) {
                return 0.0f;
            }

            float pad = Math.max(1.0f, size * 0.03125f);
            LineMetrics lineMetrics = font.getLineMetrics("Hg", CONTEXT);
            double metricHeight = Math.max(1.0d, lineMetrics.getAscent() + lineMetrics.getDescent());
            double scale = Math.max(0.01d, (size - pad * 2.0d) / metricHeight);
            double minX = Math.min(0.0d, bounds.getX());
            double translateX = Math.rint(pad - minX * scale);
            return (float) (-translateX);
        }

        @Override
        public org.fentanylsolutions.minemoticon.font.TextRunLayout layoutTextRun(String text, int size) {
            if (text == null || text.isEmpty()) {
                return null;
            }

            GlyphVector glyphVector = font
                .layoutGlyphVector(CONTEXT, text.toCharArray(), 0, text.length(), Font.LAYOUT_LEFT_TO_RIGHT);
            int codepointCount = text.codePointCount(0, text.length());
            if (glyphVector.getNumGlyphs() != codepointCount) {
                return null;
            }

            int[] charStarts = new int[codepointCount];
            int cpIndex = 0;
            for (int i = 0; i < text.length();) {
                charStarts[cpIndex++] = i;
                i += Character.charCount(text.codePointAt(i));
            }

            int[] glyphByChar = new int[text.length()];
            java.util.Arrays.fill(glyphByChar, -1);
            for (int glyphIndex = 0; glyphIndex < glyphVector.getNumGlyphs(); glyphIndex++) {
                int charIndex = glyphVector.getGlyphCharIndex(glyphIndex);
                if (charIndex >= 0 && charIndex < glyphByChar.length && glyphByChar[charIndex] == -1) {
                    glyphByChar[charIndex] = glyphIndex;
                }
            }

            float pad = Math.max(1.0f, size * 0.03125f);
            LineMetrics lineMetrics = font.getLineMetrics("Hg", CONTEXT);
            double metricHeight = Math.max(1.0d, lineMetrics.getAscent() + lineMetrics.getDescent());
            double scale = Math.max(0.01d, (size - pad * 2.0d) / metricHeight);
            float[] penPositions = new float[codepointCount];
            int previousGlyph = -1;

            for (int i = 0; i < codepointCount; i++) {
                int glyphIndex = glyphByChar[charStarts[i]];
                if (glyphIndex < 0 || glyphIndex == previousGlyph) {
                    return null;
                }
                penPositions[i] = (float) (glyphVector.getGlyphPosition(glyphIndex)
                    .getX() * scale);
                previousGlyph = glyphIndex;
            }

            float totalAdvance = (float) (glyphVector.getGlyphPosition(glyphVector.getNumGlyphs())
                .getX() * scale);
            return new org.fentanylsolutions.minemoticon.font.TextRunLayout(penPositions, totalAdvance);
        }

        @Override
        public boolean preserveTextLineMetrics() {
            return true;
        }

        @Override
        public boolean usesTextColor() {
            return true;
        }

        @Override
        public float getDisplayHeight() {
            return displayHeight;
        }

        @Override
        public float getWidthScale() {
            return widthScale;
        }

        @Override
        public float getVerticalOffset() {
            return verticalOffset;
        }

        private GlyphVector layoutSingleGlyphVector(int codepoint) {
            String text = new String(Character.toChars(codepoint));
            GlyphVector glyphVector = font
                .layoutGlyphVector(CONTEXT, text.toCharArray(), 0, text.length(), Font.LAYOUT_LEFT_TO_RIGHT);
            if (glyphVector.getNumGlyphs() == 0) {
                return null;
            }

            int glyphCode = glyphVector.getGlyphCode(0);
            int missingGlyph = font.getMissingGlyphCode();
            if (glyphCode == missingGlyph && codepoint != ' ') {
                return null;
            }
            return glyphVector;
        }
    }

    private static final class FontImportReport {

        int addedCount;
        int skippedCount;
        String lastAddedName;
        String firstMessage;

        void recordAdded(String fileName) {
            addedCount++;
            lastAddedName = fileName;
            if (firstMessage == null) firstMessage = "Added " + fileName;
        }

        void recordSkipped(String message) {
            skippedCount++;
            if (firstMessage == null) firstMessage = message;
        }
    }

    private static class PreviewTexture extends AbstractTexture {

        final ResourceLocation location;
        private final boolean crispSampling;
        private final AtomicReference<BufferedImage> pending = new AtomicReference<>();
        private volatile boolean ready;
        private volatile boolean unsupported;
        private volatile String unsupportedReason = "";
        private volatile boolean loadRequested;

        PreviewTexture(String id) {
            this(id, false);
        }

        PreviewTexture(String id, boolean crispSampling) {
            this.location = new ResourceLocation(Minemoticon.MODID, "textures/fontpreview/" + Math.abs(id.hashCode()));
            this.crispSampling = crispSampling;
        }

        boolean tryRequestLoad() {
            if (loadRequested || ready || unsupported || pending.get() != null) return false;
            loadRequested = true;
            return true;
        }

        void setImage(BufferedImage img) {
            pending.set(img);
            ready = false;
            unsupported = false;
            unsupportedReason = "";
            loadRequested = true;
        }

        void setUnsupported(String reason) {
            pending.set(null);
            ready = false;
            unsupported = true;
            unsupportedReason = reason != null ? reason : "";
            loadRequested = true;
        }

        void reset() {
            pending.set(null);
            ready = false;
            unsupported = false;
            unsupportedReason = "";
            loadRequested = false;
        }

        boolean canRender() {
            return ready || pending.get() != null;
        }

        boolean isUnsupported() {
            return unsupported;
        }

        String getUnsupportedReason() {
            return unsupportedReason;
        }

        @Override
        public void loadTexture(IResourceManager resourceManager) {}

        @Override
        public int getGlTextureId() {
            int id = super.getGlTextureId();
            BufferedImage img = pending.getAndSet(null);
            if (img != null) {
                if (crispSampling) {
                    EmojiTextureUtil.uploadNearestTexture(id, img);
                } else {
                    EmojiTextureUtil.uploadFilteredTexture(id, img);
                }
                ready = true;
            }
            return id;
        }
    }
}
