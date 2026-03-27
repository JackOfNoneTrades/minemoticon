package org.fentanylsolutions.minemoticon.config;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
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
import org.fentanylsolutions.minemoticon.font.FontSource;
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
    private boolean dirty;

    public FontSelectionScreen(GuiScreen parent) {
        this.parent = parent;
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

        drawCenteredString(fontRendererObj, "Font Stack", width / 2, HEADER_Y, 0xFFFFFF);

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
        enabledEntries.add(entry);
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
        dirty = true;
        refreshMetrics();
        showStatus("Removed " + entry.source.getDisplayName(), 0xFF80FF80);
    }

    private void applyAndClose() {
        if (dirty) {
            String[] ids = new String[enabledEntries.size()];
            for (int i = 0; i < enabledEntries.size(); i++) {
                ids[i] = enabledEntries.get(i).source.getId();
            }
            EmojiConfig.fontStack = ids;
            try {
                com.gtnewhorizon.gtnhlib.config.ConfigurationManager.save(EmojiConfig.class);
            } catch (Exception e) {
                Minemoticon.LOG.warn("Failed to save font stack config", e);
            }
            ClientEmojiHandler.reloadFontStack();
        }
        mc.displayGuiScreen(parent);
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
        drawCenteredString(fontRendererObj, statusText, width / 2, height - LIST_BOTTOM + 2, statusColor);
    }

    private void renderDragDropOverlay() {
        if (!dragDropActive) return;
        Gui.drawRect(leftX, leftY, leftX + leftW, leftY + leftH, 0x40000000);
        drawCenteredString(fontRendererObj, "Drop TTF/OTF here", leftX + leftW / 2, leftY + leftH / 2 - 4, 0xFF8FD9FF);
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
        String cacheKey = file.getAbsolutePath();
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
                ColorFont font = ColorFont.load(new ByteArrayInputStream(data));
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
        synchronized (droppedFilePaths) {
            droppedFilePaths.clear();
        }
    }

    @Override
    public void onDragPosition(float sdlX, float sdlY) {}

    @Override
    public void onDropFile(String filePath, float sdlX, float sdlY) {
        synchronized (droppedFilePaths) {
            droppedFilePaths.add(filePath);
        }
    }

    @Override
    public void onDragComplete(WindowDropTarget.DropResult result) {
        dragDropActive = false;
        if (Minecraft.getMinecraft().currentScreen != this) {
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
            int maxTitleW = w - CARD_PAD * 2 - (enabled ? (MINI_BTN + 2) * 3 + 4 : MINI_BTN + 6);
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
            int btnY = lastDrawY + (CARD_HEIGHT - MINI_BTN) / 2;
            return isInside(mx, my, btnX, btnY, MINI_BTN, MINI_BTN);
        }

        boolean isInsideUpButton(int mx, int my) {
            int btnX = lastDrawX + lastDrawW - CARD_PAD - MINI_BTN * 3 - 4;
            int btnY = lastDrawY + (CARD_HEIGHT - MINI_BTN) / 2;
            return isInside(mx, my, btnX, btnY, MINI_BTN, MINI_BTN);
        }

        boolean isInsideDownButton(int mx, int my) {
            int btnX = lastDrawX + lastDrawW - CARD_PAD - MINI_BTN * 2 - 2;
            int btnY = lastDrawY + (CARD_HEIGHT - MINI_BTN) / 2;
            return isInside(mx, my, btnX, btnY, MINI_BTN, MINI_BTN);
        }

        boolean isInsideRemoveButton(int mx, int my) {
            if (!canRemove() || enabled) return false;
            int btnX = lastDrawX + lastDrawW - CARD_PAD - MINI_BTN * 2 - 2;
            int btnY = lastDrawY + (CARD_HEIGHT - MINI_BTN) / 2;
            return isInside(mx, my, btnX, btnY, MINI_BTN, MINI_BTN);
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
        private final AtomicReference<BufferedImage> pending = new AtomicReference<>();
        private volatile boolean ready;
        private volatile boolean unsupported;
        private volatile String unsupportedReason = "";
        private volatile boolean loadRequested;

        PreviewTexture(String id) {
            this.location = new ResourceLocation(Minemoticon.MODID, "textures/fontpreview/" + Math.abs(id.hashCode()));
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
                EmojiTextureUtil.uploadFilteredTexture(id, img);
                ready = true;
            }
            return id;
        }
    }
}
