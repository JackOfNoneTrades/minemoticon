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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.fentanylsolutions.minemoticon.render.EmojiTextureUtil;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

public class FontSelectionScreen extends GuiScreen implements DropListener {

    private static final int BTN_ADD_FONT = 100;
    private static final int BTN_OPEN_FOLDER = 101;
    private static final int BTN_RELOAD = 102;
    private static final int BTN_BACK = 103;

    private static final int PANEL_MARGIN = 18;
    private static final int HEADER_TITLE_Y = 12;
    private static final int HEADER_STATS_Y = 28;
    private static final int LIST_TOP = 44;
    private static final int LIST_BOTTOM = 34;
    private static final int LIST_SCROLLBAR_W = 4;
    private static final int CARD_GAP = 8;
    private static final int CARD_HEIGHT = 62;
    private static final int CARD_PAD = 6;
    private static final int MINI_BTN_W = 16;
    private static final int STATUS_DURATION_MS = 3500;
    private static final int LIST_SCROLL_STEP = 20;
    private static final float LIST_SCROLL_LERP = 0.35F;
    private static final String[] SUPPORTED_EXTENSIONS = { "ttf", "otf" };
    private static final Semaphore PREVIEW_LOAD_SEMAPHORE = new Semaphore(1);

    private static final int[] PREVIEW_CODEPOINTS = { 0x1F600, 0x1F60D, 0x1F622, 0x1F525, 0x2764, 0x1F44D, 0x1F680,
        0x2603 };
    private static final int PREVIEW_GLYPH_SIZE = 48;
    private static final int PREVIEW_DISPLAY_SIZE = 14;
    private static final int PREVIEW_STRIP_W = PREVIEW_CODEPOINTS.length * (PREVIEW_DISPLAY_SIZE + 1) - 1;
    private static final int PREVIEW_STRIP_H = PREVIEW_DISPLAY_SIZE;
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

    private static final LinkedHashMap<String, CachedPreviewStrip> PREVIEW_STRIP_CACHE = new LinkedHashMap<String, CachedPreviewStrip>(
        MAX_PREVIEW_CACHE_ENTRIES,
        0.75F,
        true);
    private static long previewStripCacheBytes = 0L;

    private final GuiScreen parent;
    private final List<FontEntry> entries = new ArrayList<>();
    private final Map<String, PreviewTexture> previews = new HashMap<>();
    private final List<String> droppedFilePaths = new ArrayList<>();

    private PreviewTexture bundledPreview;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int listScrollbarX;
    private int maxListScroll;
    private int listScroll;
    private float listScrollVisual;
    private boolean draggingListScrollbar;
    private int listScrollbarDragOffset;
    private long totalCustomBytes;
    private String tooltipText;
    private int tooltipX;
    private int tooltipY;
    private String statusText;
    private int statusColor = 0xFFB0B0B0;
    private long statusUntilMs;
    private volatile boolean dragDropActive;
    private volatile float dragDropSdlX = -1.0F;
    private volatile float dragDropSdlY = -1.0F;

    public FontSelectionScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();

        listX = PANEL_MARGIN;
        listY = LIST_TOP;
        listW = width - PANEL_MARGIN * 2 - LIST_SCROLLBAR_W - 4;
        listH = height - listY - LIST_BOTTOM;
        listScrollbarX = listX + listW + 4;

        int bottomY = height - 24;
        int gap = 4;
        int btnW = Math.min(118, (width - PANEL_MARGIN * 2 - gap * 3) / 4);
        int totalW = btnW * 4 + gap * 3;
        int startX = (width - totalW) / 2;
        buttonList.add(new GuiButton(BTN_ADD_FONT, startX, bottomY, btnW, 20, "+ Add Font"));
        buttonList
            .add(new GuiButton(BTN_OPEN_FOLDER, startX + btnW + gap, bottomY, btnW, 20, "\uD83D\uDCC2 Fonts Folder"));
        buttonList.add(new GuiButton(BTN_RELOAD, startX + (btnW + gap) * 2, bottomY, btnW, 20, "\uD83D\uDD04 Reload"));
        buttonList.add(new GuiButton(BTN_BACK, startX + (btnW + gap) * 3, bottomY, btnW, 20, "Back"));

        WindowDropTarget.register();
        WindowDropTarget.addListener(this);

        reloadEntries(null);
        snapListScroll();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (Math.abs(listScrollVisual - listScroll) < 0.5F) {
            listScrollVisual = listScroll;
        } else {
            listScrollVisual += (listScroll - listScrollVisual) * LIST_SCROLL_LERP;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        tooltipText = null;

        drawCenteredString(fontRendererObj, "Manage Emoji Fonts", width / 2, HEADER_TITLE_Y, 0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            entries.size() <= 1 ? "No custom fonts yet. Add or drop a TTF/OTF file below."
                : (entries.size() - 1) + " custom fonts, " + formatBytes(totalCustomBytes) + " on disk",
            width / 2,
            HEADER_STATS_Y,
            0xB8B8B8);

        drawListBackground();
        renderEntries(mouseX, mouseY);
        renderListScrollbar(mouseX, mouseY);
        renderDragDropOverlay();
        renderStatus();

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (tooltipText != null) {
            renderTooltip(tooltipX, tooltipY, tooltipText);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_ADD_FONT) {
            addFontFromPicker();
            return;
        }
        if (button.id == BTN_OPEN_FOLDER) {
            FileUtil.openFolder(ClientEmojiHandler.FONTS_DIR);
            return;
        }
        if (button.id == BTN_RELOAD) {
            reloadEntries(null);
            showStatus("Reloaded font list", 0xFF80FF80);
            return;
        }
        if (button.id == BTN_BACK) {
            mc.displayGuiScreen(parent);
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
        if (button == 0) {
            if (isInsideListScrollbar(mouseX, mouseY)) {
                beginListScrollbarDrag(mouseY);
                return;
            }

            if (isInsideListViewport(mouseX, mouseY)) {
                refreshEntryLayouts();
                FontEntry entry = findVisibleEntry(mouseX, mouseY);
                if (entry != null) {
                    if (entry.canRemove() && entry.isInsideRemoveButton(mouseX, mouseY)) {
                        removeFont(entry);
                        return;
                    }
                    if (entry.contains(mouseX, mouseY)) {
                        selectFont(entry);
                        return;
                    }
                }
                return;
            }
        }

        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton == 0 && draggingListScrollbar) {
            updateListScrollbarDrag(mouseY);
            return;
        }

        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        if (state != -1) {
            draggingListScrollbar = false;
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

        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        if (mouseX >= listX && mouseX <= listX + listW + LIST_SCROLLBAR_W + 6
            && mouseY >= listY
            && mouseY <= listY + listH) {
            int steps = delta > 0 ? 1 : -1;
            listScroll = clamp(listScroll - steps * LIST_SCROLL_STEP, 0, maxListScroll);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawListBackground() {
        Gui.drawRect(listX - 2, listY - 2, listX + listW + LIST_SCROLLBAR_W + 6, listY + listH + 2, 0x80202020);
        Gui.drawRect(listX - 1, listY - 1, listX + listW + LIST_SCROLLBAR_W + 5, listY + listH + 1, 0xA0000000);
    }

    private void renderEntries(int mouseX, int mouseY) {
        enableScissor(listX, listY, listW + 1, listH);

        for (FontEntry entry : entries) {
            int cardY = entry.computeY(listY, getRenderedListScroll());
            if (!entry.updateLayout(listX, cardY, listW, listY, listH)) {
                continue;
            }
            entry.draw(mouseX, mouseY);
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void renderListScrollbar(int mouseX, int mouseY) {
        if (maxListScroll <= 0) {
            return;
        }

        Gui.drawRect(listScrollbarX, listY, listScrollbarX + LIST_SCROLLBAR_W, listY + listH, 0x30FFFFFF);
        int thumbH = getListScrollbarThumbHeight();
        int travel = listH - thumbH;
        int thumbY = listY + (travel <= 0 ? 0 : (int) ((float) getRenderedListScroll() / maxListScroll * travel));
        boolean hovered = mouseX >= listScrollbarX - 2 && mouseX <= listScrollbarX + LIST_SCROLLBAR_W + 2
            && mouseY >= listY
            && mouseY <= listY + listH;
        Gui.drawRect(
            listScrollbarX,
            thumbY,
            listScrollbarX + LIST_SCROLLBAR_W,
            thumbY + thumbH,
            hovered ? 0xA0FFFFFF : 0x70FFFFFF);
    }

    private void renderStatus() {
        if (statusText == null || System.currentTimeMillis() > statusUntilMs) {
            return;
        }
        drawCenteredString(fontRendererObj, statusText, width / 2, height - 36, statusColor);
    }

    private void renderDragDropOverlay() {
        if (!dragDropActive) {
            return;
        }

        Gui.drawRect(listX, listY, listX + listW + 1, listY + listH, 0x20000000);

        int popupW = Math.min(220, listW - 28);
        int popupH = 54;
        int popupX = listX + (listW - popupW) / 2;
        int popupY = listY + (listH - popupH) / 2;
        Gui.drawRect(popupX, popupY, popupX + popupW, popupY + popupH, 0xD0101010);
        drawOutline(popupX, popupY, popupW, popupH, 0xA08FD9FF);
        drawCenteredString(fontRendererObj, "Drop font files here", popupX + popupW / 2, popupY + 12, 0xFFFFFFFF);
        drawCenteredString(fontRendererObj, "TTF and OTF supported", popupX + popupW / 2, popupY + 28, 0xFFB8D8E8);
    }

    private void renderTooltip(int mouseX, int mouseY, String text) {
        int textWidth = fontRendererObj.getStringWidth(text);
        int tipX = mouseX + 12;
        int tipY = mouseY - 6;
        if (tipX + textWidth + 6 > width) {
            tipX = mouseX - textWidth - 8;
        }
        Gui.drawRect(tipX - 3, tipY - 3, tipX + textWidth + 3, tipY + 11, 0xE0000000);
        Gui.drawRect(tipX - 3, tipY - 3, tipX + textWidth + 3, tipY - 2, 0x50FF8080);
        fontRendererObj.drawStringWithShadow(text, tipX, tipY, 0xFFFFFF);
    }

    private void enableScissor(int x, int y, int w, int h) {
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int scale = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * scale, (height - y - h) * scale, w * scale, h * scale);
    }

    private FontEntry findVisibleEntry(int mouseX, int mouseY) {
        if (!isInsideListViewport(mouseX, mouseY)) {
            return null;
        }
        for (FontEntry entry : entries) {
            if (entry.visible && entry.contains(mouseX, mouseY)) {
                return entry;
            }
        }
        return null;
    }

    private boolean isInsideListViewport(int mouseX, int mouseY) {
        return mouseX >= listX && mouseX < listX + listW + LIST_SCROLLBAR_W + 6
            && mouseY >= listY
            && mouseY < listY + listH;
    }

    private boolean isInsideListScrollbar(int mouseX, int mouseY) {
        return maxListScroll > 0 && mouseX >= listScrollbarX - 2
            && mouseX <= listScrollbarX + LIST_SCROLLBAR_W + 2
            && mouseY >= listY
            && mouseY <= listY + listH;
    }

    private void beginListScrollbarDrag(int mouseY) {
        draggingListScrollbar = true;
        int thumbY = getListScrollbarThumbY();
        int thumbH = getListScrollbarThumbHeight();
        if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
            listScrollbarDragOffset = mouseY - thumbY;
        } else {
            listScrollbarDragOffset = thumbH / 2;
        }
        updateListScrollbarDrag(mouseY);
    }

    private void updateListScrollbarDrag(int mouseY) {
        int thumbH = getListScrollbarThumbHeight();
        int travel = listH - thumbH;
        if (travel <= 0 || maxListScroll <= 0) {
            listScroll = 0;
            snapListScroll();
            return;
        }

        int thumbY = clamp(mouseY - listScrollbarDragOffset, listY, listY + travel);
        float ratio = (float) (thumbY - listY) / (float) travel;
        listScroll = clamp(Math.round(ratio * maxListScroll), 0, maxListScroll);
        snapListScroll();
    }

    private int getListScrollbarThumbHeight() {
        if (maxListScroll <= 0) {
            return listH;
        }
        float viewRatio = (float) listH / (float) (listH + maxListScroll);
        return Math.max(12, (int) (listH * viewRatio));
    }

    private int getListScrollbarThumbY() {
        int thumbH = getListScrollbarThumbHeight();
        int travel = listH - thumbH;
        return listY + (travel <= 0 ? 0 : (int) ((float) getRenderedListScroll() / maxListScroll * travel));
    }

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

    private void selectFont(FontEntry entry) {
        if (entry.preview != null && entry.preview.isUnsupported()) {
            showStatus(
                trimToWidthWithEllipsis("Font failed to load: " + entry.preview.getUnsupportedReason(), width - 40),
                0xFFFF8080);
            return;
        }

        String selectedName = entry.bundled ? "" : entry.fileName;
        if (selectedName.equals(EmojiConfig.emojiFont == null ? "" : EmojiConfig.emojiFont)) {
            showStatus(
                entry.bundled ? "Bundled Twemoji is already active" : "Already using " + entry.fileName,
                0xFFB0B0B0);
            return;
        }

        EmojiConfig.emojiFont = selectedName;
        Minemoticon.LOG.info("Selected emoji font: {}", selectedName.isEmpty() ? "(bundled)" : selectedName);

        try {
            com.gtnewhorizon.gtnhlib.config.ConfigurationManager.save(EmojiConfig.class);
        } catch (Exception e) {
            Minemoticon.LOG.warn("Failed to save config after font change", e);
            showStatus("Failed to save font selection", 0xFFFF8080);
            return;
        }

        ClientEmojiHandler.reloadFont();
        reloadEntries(entry.bundled ? "" : entry.fileName);
        showStatus(entry.bundled ? "Switched to bundled Twemoji" : "Selected " + entry.fileName, 0xFF80FF80);
    }

    private void removeFont(FontEntry entry) {
        if (!entry.canRemove()) {
            return;
        }

        boolean wasSelected = entry.isSelected();
        try {
            Files.deleteIfExists(entry.file.toPath());
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to delete font {}", entry.file, e);
            showStatus("Failed to remove " + entry.fileName, 0xFFFF8080);
            return;
        }

        PreviewTexture preview = previews.remove(entry.fileName);
        if (preview != null) {
            mc.getTextureManager()
                .deleteTexture(preview.location);
        }

        if (wasSelected) {
            EmojiConfig.emojiFont = "";
            try {
                com.gtnewhorizon.gtnhlib.config.ConfigurationManager.save(EmojiConfig.class);
            } catch (Exception e) {
                Minemoticon.LOG.warn("Failed to save config after removing font {}", entry.fileName, e);
            }
            ClientEmojiHandler.reloadFont();
        }

        reloadEntries(null);
        showStatus(
            wasSelected ? "Removed " + entry.fileName + " and switched to bundled Twemoji"
                : "Removed " + entry.fileName,
            0xFF80FF80);
    }

    private FontImportReport importFontFiles(List<File> sources) {
        FontImportReport report = new FontImportReport();
        FileUtil.createFolderIfNotExists(ClientEmojiHandler.FONTS_DIR);

        for (File source : sources) {
            if (source == null) {
                continue;
            }

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
                Minemoticon.LOG.error("Failed to import font {}", source, e);
                report.recordSkipped("Failed to add " + source.getName());
            }
        }

        if (report.addedCount > 0) {
            reloadEntries(report.lastAddedName);
        }
        return report;
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

    private void reloadEntries(String focusFileName) {
        entries.clear();
        totalCustomBytes = 0L;

        FileUtil.createFolderIfNotExists(ClientEmojiHandler.FONTS_DIR);

        if (bundledPreview == null) {
            bundledPreview = new PreviewTexture("_bundled");
            mc.getTextureManager()
                .loadTexture(bundledPreview.location, bundledPreview);
        }
        ensureBundledPreviewRequested();
        entries.add(new FontEntry(true, null, "", 0L, bundledPreview));

        File[] files = ClientEmojiHandler.FONTS_DIR.listFiles(FontSelectionScreen::isSupportedFontFileName);
        if (files != null) {
            Arrays.sort(
                files,
                (a, b) -> a.getName()
                    .compareToIgnoreCase(b.getName()));
            Set<String> liveKeys = new HashSet<>();
            for (File file : files) {
                String fileName = file.getName();
                PreviewTexture preview = previews.get(fileName);
                if (preview == null) {
                    preview = new PreviewTexture(fileName);
                    previews.put(fileName, preview);
                    mc.getTextureManager()
                        .loadTexture(preview.location, preview);
                }

                entries.add(new FontEntry(false, file, fileName, file.length(), preview));
                totalCustomBytes += file.length();
                liveKeys.add(fileName);
            }

            for (String existing : new ArrayList<>(previews.keySet())) {
                if (!liveKeys.contains(existing)) {
                    PreviewTexture preview = previews.remove(existing);
                    if (preview != null) {
                        mc.getTextureManager()
                            .deleteTexture(preview.location);
                    }
                }
            }
        }

        refreshListMetrics();
        if (focusFileName != null) {
            ensureEntryVisible(findEntryByFileName(focusFileName));
        }
    }

    private void refreshListMetrics() {
        int contentHeight = entries.isEmpty() ? 0 : entries.size() * (CARD_HEIGHT + CARD_GAP) - CARD_GAP;
        maxListScroll = Math.max(0, contentHeight - listH);
        listScroll = clamp(listScroll, 0, maxListScroll);
        listScrollVisual = Math.max(0.0F, Math.min(maxListScroll, listScrollVisual));
        refreshEntryLayouts();
    }

    private void refreshEntryLayouts() {
        int renderedScroll = getRenderedListScroll();
        for (FontEntry entry : entries) {
            entry.updateLayout(listX, entry.computeY(listY, renderedScroll), listW, listY, listH);
        }
    }

    private FontEntry findEntryByFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return entries.isEmpty() ? null : entries.get(0);
        }
        for (FontEntry entry : entries) {
            if (!entry.bundled && fileName.equals(entry.fileName)) {
                return entry;
            }
        }
        return null;
    }

    private void ensureEntryVisible(FontEntry entry) {
        if (entry == null) {
            return;
        }

        int index = entries.indexOf(entry);
        if (index < 0) {
            return;
        }

        int rawTop = index * (CARD_HEIGHT + CARD_GAP);
        int rawBottom = rawTop + CARD_HEIGHT;
        if (rawTop < listScroll) {
            listScroll = rawTop;
        } else if (rawBottom > listScroll + listH) {
            listScroll = rawBottom - listH;
        }
        listScroll = clamp(listScroll, 0, maxListScroll);
        snapListScroll();
    }

    private void snapListScroll() {
        listScrollVisual = listScroll;
        refreshEntryLayouts();
    }

    private int getRenderedListScroll() {
        return Math.round(listScrollVisual);
    }

    private void showStatus(String text, int color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        statusText = text;
        statusColor = color;
        statusUntilMs = System.currentTimeMillis() + STATUS_DURATION_MS;
    }

    private void loadBundledPreview() {
        CachedPreviewStrip cached = getCachedPreviewStrip("_bundled", -1L, -1L);
        if (applyCachedPreview(cached, bundledPreview)) {
            return;
        }

        DownloadedTexture.submitToPool(() -> {
            boolean permit = false;
            try (var stream = getClass().getResourceAsStream("/assets/minemoticon/twemoji.ttf")) {
                PREVIEW_LOAD_SEMAPHORE.acquire();
                permit = true;
                if (stream == null) {
                    bundledPreview.setUnsupported("Bundled font missing");
                    cachePreviewStrip(new CachedPreviewStrip("_bundled", -1L, -1L, null, "Bundled font missing"));
                    return;
                }
                byte[] data = readAllBytes(stream);
                ColorFont font = ColorFont.load(new ByteArrayInputStream(data));
                BufferedImage strip = buildPreviewStrip(font);
                bundledPreview.setImage(strip);
                cachePreviewStrip(new CachedPreviewStrip("_bundled", -1L, -1L, strip, null));
            } catch (Exception e) {
                String reason = simplifyLoadError(e);
                bundledPreview.setUnsupported(reason);
                cachePreviewStrip(new CachedPreviewStrip("_bundled", -1L, -1L, null, reason));
                Minemoticon.debug("Failed to render bundled font preview: {}", e.getMessage());
            } finally {
                if (permit) {
                    PREVIEW_LOAD_SEMAPHORE.release();
                }
            }
        });
    }

    private void loadFontPreview(String filename, PreviewTexture preview) {
        File file = new File(ClientEmojiHandler.FONTS_DIR, filename);
        String cacheKey = file.getAbsolutePath();
        long fileSize = file.length();
        long lastModified = file.lastModified();
        CachedPreviewStrip cached = getCachedPreviewStrip(cacheKey, fileSize, lastModified);
        if (applyCachedPreview(cached, preview)) {
            return;
        }

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
                Minemoticon.LOG.warn("Font {} failed to load: {}", filename, e.getMessage());
                String reason = simplifyLoadError(e);
                preview.setUnsupported(reason);
                cachePreviewStrip(new CachedPreviewStrip(cacheKey, fileSize, lastModified, null, reason));
            } finally {
                if (permit) {
                    PREVIEW_LOAD_SEMAPHORE.release();
                }
            }
        });
    }

    private void ensureBundledPreviewRequested() {
        if (bundledPreview != null && bundledPreview.tryRequestLoad()) {
            loadBundledPreview();
        }
    }

    private void ensureCustomPreviewRequested(FontEntry entry) {
        if (entry == null || entry.bundled || entry.preview == null) {
            return;
        }
        if (entry.preview.tryRequestLoad()) {
            loadFontPreview(entry.fileName, entry.preview);
        }
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

    private void renderPreviewStrip(PreviewTexture preview, int x, int y) {
        mc.getTextureManager()
            .bindTexture(preview.location);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        int count = PREVIEW_CODEPOINTS.length;
        float texW = count * PREVIEW_GLYPH_SIZE;

        for (int i = 0; i < count; i++) {
            float u0 = (float) (i * PREVIEW_GLYPH_SIZE) / texW;
            float u1 = (float) ((i + 1) * PREVIEW_GLYPH_SIZE) / texW;
            int dx = x + i * (PREVIEW_DISPLAY_SIZE + 1);

            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
            tessellator.addVertexWithUV(dx, y, 0, u0, 0);
            tessellator.addVertexWithUV(dx, y + PREVIEW_DISPLAY_SIZE, 0, u0, 1);
            tessellator.addVertexWithUV(dx + PREVIEW_DISPLAY_SIZE, y, 0, u1, 0);
            tessellator.addVertexWithUV(dx + PREVIEW_DISPLAY_SIZE, y + PREVIEW_DISPLAY_SIZE, 0, u1, 1);
            tessellator.draw();
        }
    }

    private static byte[] readAllBytes(java.io.InputStream stream) throws IOException {
        var baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static synchronized CachedPreviewStrip getCachedPreviewStrip(String cacheKey, long fileSize,
        long lastModified) {
        CachedPreviewStrip cached = PREVIEW_STRIP_CACHE.get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (cached.fileSize == fileSize && cached.lastModified == lastModified) {
            return cached;
        }
        removePreviewStripCacheEntry(cacheKey, cached);
        return null;
    }

    private static boolean applyCachedPreview(CachedPreviewStrip cached, PreviewTexture preview) {
        if (cached == null) {
            return false;
        }
        if (cached.image != null) {
            preview.setImage(cached.image);
        } else {
            preview.setUnsupported(cached.unsupportedReason);
        }
        return true;
    }

    private static synchronized void cachePreviewStrip(CachedPreviewStrip entry) {
        CachedPreviewStrip previous = PREVIEW_STRIP_CACHE.put(entry.cacheKey, entry);
        if (previous != null) {
            previewStripCacheBytes -= estimatePreviewStripBytes(previous);
        }
        previewStripCacheBytes += estimatePreviewStripBytes(entry);
        trimPreviewStripCache();
    }

    private static void trimPreviewStripCache() {
        Iterator<Map.Entry<String, CachedPreviewStrip>> iterator = PREVIEW_STRIP_CACHE.entrySet()
            .iterator();
        while ((PREVIEW_STRIP_CACHE.size() > MAX_PREVIEW_CACHE_ENTRIES
            || previewStripCacheBytes > MAX_PREVIEW_CACHE_BYTES) && iterator.hasNext()) {
            CachedPreviewStrip eldest = iterator.next()
                .getValue();
            previewStripCacheBytes -= estimatePreviewStripBytes(eldest);
            iterator.remove();
        }
    }

    private static void removePreviewStripCacheEntry(String cacheKey, CachedPreviewStrip cached) {
        PREVIEW_STRIP_CACHE.remove(cacheKey);
        previewStripCacheBytes -= estimatePreviewStripBytes(cached);
    }

    private static long estimatePreviewStripBytes(CachedPreviewStrip entry) {
        if (entry == null || entry.image == null) {
            return 0L;
        }
        return (long) entry.image.getWidth() * (long) entry.image.getHeight() * 4L;
    }

    private String validateFontFile(File source) {
        if (source == null || !source.isFile()) {
            return "Selected file is invalid";
        }
        if (!source.canRead()) {
            return "Selected font is not readable";
        }
        if (!hasSupportedExtension(source.getName())) {
            return "Only TTF and OTF fonts are supported";
        }

        try {
            byte[] data = Files.readAllBytes(source.toPath());
            ColorFont.load(new ByteArrayInputStream(data));
            return null;
        } catch (Exception e) {
            return trimToWidthWithEllipsis("Unsupported font: " + simplifyLoadError(e), width - 40);
        }
    }

    private static String sanitizeFontFilename(String name) {
        if (name == null || name.trim()
            .isEmpty()) {
            return "font.ttf";
        }
        String safe = name.trim()
            .replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safe.isEmpty()) {
            safe = "font.ttf";
        }
        return safe;
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
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith("." + extension)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSupportedFontFileName(File file) {
        return file != null && file.isFile() && hasSupportedExtension(file.getName());
    }

    private static String simplifyLoadError(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim()
            .isEmpty()) {
            return throwable.getClass()
                .getSimpleName();
        }
        return message.trim();
    }

    private String trimToWidthWithEllipsis(String text, int maxWidth) {
        if (text == null || fontRendererObj.getStringWidth(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int ellipsisWidth = fontRendererObj.getStringWidth(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (fontRendererObj.getStringWidth(sb.toString() + c) + ellipsisWidth > maxWidth) {
                break;
            }
            sb.append(c);
        }
        return sb.append(ellipsis)
            .toString();
    }

    private void drawOutline(int x, int y, int w, int h, int color) {
        Gui.drawRect(x, y, x + w, y + 1, color);
        Gui.drawRect(x, y + h - 1, x + w, y + h, color);
        if (h > 2) {
            Gui.drawRect(x, y + 1, x + 1, y + h - 1, color);
            Gui.drawRect(x + w - 1, y + 1, x + w, y + h - 1, color);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = { "KB", "MB", "GB" };
        int unitIndex = -1;
        while (value >= 1024.0D && unitIndex < units.length - 1) {
            value /= 1024.0D;
            unitIndex++;
        }
        return String.format(Locale.ROOT, value >= 10.0D ? "%.0f %s" : "%.1f %s", value, units[unitIndex]);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void onDragBegin() {
        if (Minecraft.getMinecraft().currentScreen != this) {
            return;
        }
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
        dragDropSdlX = sdlX;
        dragDropSdlY = sdlY;
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

        float[] guiCoords = WindowDropTarget.sdlToGuiCoords(result.getSdlX(), result.getSdlY());
        int dropX = Math.round(guiCoords[0]);
        int dropY = Math.round(guiCoords[1]);
        if (!isInsideListViewport(dropX, dropY)) {
            showStatus("Drop font files onto the list", 0xFFFF8080);
            return;
        }

        applyImportReport(importFontFiles(droppedFiles));
    }

    private final class FontEntry {

        private final boolean bundled;
        private final File file;
        private final String fileName;
        private final long sizeBytes;
        private final PreviewTexture preview;
        private int x;
        private int y;
        private int w;
        private boolean visible;
        private int removeButtonX;
        private int removeButtonY;

        private FontEntry(boolean bundled, File file, String fileName, long sizeBytes, PreviewTexture preview) {
            this.bundled = bundled;
            this.file = file;
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
            this.preview = preview;
        }

        private int computeY(int baseY, int renderedScroll) {
            return baseY + entries.indexOf(this) * (CARD_HEIGHT + CARD_GAP) - renderedScroll;
        }

        private boolean updateLayout(int x, int y, int w, int clipY, int clipH) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.removeButtonX = x + w - CARD_PAD - MINI_BTN_W;
            this.removeButtonY = y + CARD_PAD;
            this.visible = y + CARD_HEIGHT > clipY && y < clipY + clipH;
            return visible;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CARD_HEIGHT;
        }

        private boolean isInsideRemoveButton(int mouseX, int mouseY) {
            return isInside(mouseX, mouseY, removeButtonX, removeButtonY, MINI_BTN_W, MINI_BTN_W);
        }

        private boolean canRemove() {
            return !bundled && file != null;
        }

        private boolean isSelected() {
            String selected = EmojiConfig.emojiFont == null ? "" : EmojiConfig.emojiFont;
            return bundled ? selected.isEmpty() : fileName.equals(selected);
        }

        private void draw(int mouseX, int mouseY) {
            if (bundled) {
                ensureBundledPreviewRequested();
            } else {
                ensureCustomPreviewRequested(this);
            }

            boolean hovered = contains(mouseX, mouseY);
            boolean selected = isSelected();
            int bg = selected ? 0xA0223624 : hovered ? 0xA0202020 : 0x90151515;
            Gui.drawRect(x, y, x + w, y + CARD_HEIGHT, bg);
            drawOutline(x, y, w, CARD_HEIGHT, selected ? 0xB86FCF7B : hovered ? 0x60FFFFFF : 0x35FFFFFF);
            Gui.drawRect(x, y, x + 2, y + CARD_HEIGHT, selected ? 0xFF7FD58A : 0x50505050);

            int contentX = x + CARD_PAD + 4;
            int topY = y + CARD_PAD;
            int reservedRight = canRemove() ? MINI_BTN_W + 10 : 4;
            String title = trimToWidthWithEllipsis(
                bundled ? "Bundled Twemoji" : fileName,
                w - (contentX - x) - reservedRight - 44);
            fontRendererObj.drawStringWithShadow(title, contentX, topY, selected ? 0xFFD8FFD8 : 0xFFFFFFFF);

            String sizeLabel = bundled ? "Built in" : formatBytes(sizeBytes);
            int sizeColor = bundled ? 0xFFB8B8B8 : 0xFFAAAAAA;
            int sizeX = x + w - CARD_PAD - reservedRight - fontRendererObj.getStringWidth(sizeLabel);
            fontRendererObj.drawStringWithShadow(sizeLabel, sizeX, topY, sizeColor);

            String status = getStatusLine();
            int statusColor = getStatusColor();
            fontRendererObj.drawStringWithShadow(
                trimToWidthWithEllipsis(status, w - (contentX - x) - reservedRight - 4),
                contentX,
                topY + 12,
                statusColor);

            int previewBoxX = contentX;
            int previewBoxY = y + 32;
            int previewBoxW = PREVIEW_STRIP_W + 8;
            int previewBoxH = PREVIEW_STRIP_H + 8;
            Gui.drawRect(
                previewBoxX - 3,
                previewBoxY - 2,
                previewBoxX + previewBoxW,
                previewBoxY + previewBoxH,
                0x28000000);
            drawOutline(previewBoxX - 3, previewBoxY - 2, previewBoxW + 3, previewBoxH + 2, 0x30FFFFFF);

            if (preview != null && preview.canRender()) {
                renderPreviewStrip(preview, previewBoxX + 1, previewBoxY + 1);
            } else if (preview != null && preview.isUnsupported()) {
                fontRendererObj.drawString(
                    trimToWidthWithEllipsis("Unsupported", previewBoxW - 8),
                    previewBoxX + 2,
                    previewBoxY + 4,
                    0xFFE48A8A);
            } else {
                fontRendererObj.drawString("Loading preview...", previewBoxX + 2, previewBoxY + 4, 0xFF909090);
            }

            String stateLabel = selected ? "ACTIVE" : "CLICK TO USE";
            int stateColor = selected ? 0xFF80FF80 : 0xFFA8A8A8;
            int stateX = x + w - CARD_PAD - fontRendererObj.getStringWidth(stateLabel);
            if (canRemove()) {
                stateX = Math.min(stateX, removeButtonX - 6 - fontRendererObj.getStringWidth(stateLabel));
            }
            fontRendererObj.drawStringWithShadow(stateLabel, stateX, y + CARD_HEIGHT - CARD_PAD - 8, stateColor);

            if (canRemove()) {
                boolean removeHovered = isInsideRemoveButton(mouseX, mouseY);
                Gui.drawRect(
                    removeButtonX,
                    removeButtonY,
                    removeButtonX + MINI_BTN_W,
                    removeButtonY + MINI_BTN_W,
                    removeHovered ? 0x90403030 : 0x60303030);
                drawOutline(
                    removeButtonX,
                    removeButtonY,
                    MINI_BTN_W,
                    MINI_BTN_W,
                    removeHovered ? 0xFFD08080 : 0x80FF9090);
                drawCenteredString(fontRendererObj, "x", removeButtonX + MINI_BTN_W / 2, removeButtonY + 4, 0xFFFFFFFF);

                if (removeHovered) {
                    tooltipText = "Remove " + fileName;
                    tooltipX = mouseX;
                    tooltipY = mouseY;
                }
            }
        }

        private String getStatusLine() {
            if (preview != null && preview.isUnsupported()) {
                return "Unsupported: " + preview.getUnsupportedReason();
            }
            if (isSelected()) {
                return bundled ? "Built-in fallback is active" : "Currently active";
            }
            return bundled ? "Always available" : "Click this card to switch fonts";
        }

        private int getStatusColor() {
            if (preview != null && preview.isUnsupported()) {
                return 0xFFE48A8A;
            }
            if (isSelected()) {
                return 0xFF98FF98;
            }
            return 0xFFA8A8A8;
        }
    }

    private static boolean isInside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static final class FontImportReport {

        private int addedCount;
        private int skippedCount;
        private String lastAddedName;
        private String firstMessage;

        private void recordAdded(String fileName) {
            addedCount++;
            lastAddedName = fileName;
            if (firstMessage == null) {
                firstMessage = "Added " + fileName;
            }
        }

        private void recordSkipped(String message) {
            skippedCount++;
            if (firstMessage == null) {
                firstMessage = message;
            }
        }
    }

    private static class PreviewTexture extends AbstractTexture {

        private final ResourceLocation location;
        private final AtomicReference<BufferedImage> pending = new AtomicReference<>();
        private volatile boolean ready;
        private volatile boolean unsupported;
        private volatile String unsupportedReason = "";
        private volatile boolean loadRequested;

        private PreviewTexture(String id) {
            this.location = new ResourceLocation(Minemoticon.MODID, "textures/fontpreview/" + Math.abs(id.hashCode()));
        }

        private boolean tryRequestLoad() {
            if (loadRequested || ready || unsupported || pending.get() != null) {
                return false;
            }
            loadRequested = true;
            return true;
        }

        private void setImage(BufferedImage img) {
            pending.set(img);
            ready = false;
            unsupported = false;
            unsupportedReason = "";
            loadRequested = true;
        }

        private void setUnsupported(String reason) {
            pending.set(null);
            ready = false;
            unsupported = true;
            unsupportedReason = reason != null ? reason : "Unknown error";
            loadRequested = true;
        }

        private boolean isReady() {
            return ready;
        }

        private boolean canRender() {
            return ready || pending.get() != null;
        }

        private boolean isUnsupported() {
            return unsupported;
        }

        private String getUnsupportedReason() {
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
