package org.fentanylsolutions.minemoticon.config;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.fentlib.util.FileUtil;
import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.EmojiConfig;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.api.DownloadedTexture;
import org.fentanylsolutions.minemoticon.colorfont.ColorFont;
import org.fentanylsolutions.minemoticon.render.EmojiTextureUtil;
import org.lwjgl.opengl.GL11;

// Lists TTFs in the fonts dir with emoji preview strips.
public class FontSelectionScreen extends GuiScreen {

    private static final int BTN_OPEN_FOLDER = 100;
    private static final int BTN_BACK = 101;
    private static final int BTN_DEFAULT = 102;
    private static final int FONT_BTN_START = 200;

    // Sample codepoints to render in the preview strip
    private static final int[] PREVIEW_CODEPOINTS = { 0x1F600, 0x1F60D, 0x1F622, 0x1F525, 0x2764, 0x1F44D, 0x1F680,
        0x2603 };
    private static final int PREVIEW_GLYPH_SIZE = 48;
    private static final int PREVIEW_DISPLAY_SIZE = 14;

    private final GuiScreen parent;
    private final List<String> fontFiles = new ArrayList<>();
    private final Map<String, PreviewTexture> previews = new HashMap<>();
    private PreviewTexture bundledPreview;

    public FontSelectionScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        fontFiles.clear();

        var dir = ClientEmojiHandler.FONTS_DIR;
        dir.mkdirs();
        File[] files = dir.listFiles(
            (d, name) -> name.toLowerCase()
                .endsWith(".ttf"));
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) fontFiles.add(f.getName());
        }

        int y = 30;
        int btnW = Math.min(300, width - 40);
        int btnX = (width - btnW) / 2;

        String defaultLabel = "Bundled Twemoji";
        if (EmojiConfig.emojiFont == null || EmojiConfig.emojiFont.isEmpty()) {
            defaultLabel = "\u00a7a> " + defaultLabel;
        }
        buttonList.add(new GuiButton(BTN_DEFAULT, btnX, y, btnW, 20, defaultLabel));
        y += 24;

        for (int i = 0; i < fontFiles.size(); i++) {
            String name = fontFiles.get(i);
            String label = name.replace(".ttf", "")
                .replace(".TTF", "");
            if (name.equals(EmojiConfig.emojiFont)) {
                label = "\u00a7a> " + label;
            }
            buttonList.add(new GuiButton(FONT_BTN_START + i, btnX, y, btnW, 20, label));
            y += 24;
        }

        int bottomY = height - 28;
        buttonList
            .add(new GuiButton(BTN_OPEN_FOLDER, btnX, bottomY, btnW / 2 - 2, 20, "\uD83D\uDCC2 Open Fonts Folder"));
        buttonList.add(new GuiButton(BTN_BACK, btnX + btnW / 2 + 2, bottomY, btnW / 2 - 2, 20, "Back"));

        // Start loading previews
        if (bundledPreview == null) {
            bundledPreview = new PreviewTexture("_bundled");
            mc.getTextureManager()
                .loadTexture(bundledPreview.location, bundledPreview);
            loadBundledPreview();
        }
        for (String name : fontFiles) {
            if (!previews.containsKey(name)) {
                var preview = new PreviewTexture(name);
                mc.getTextureManager()
                    .loadTexture(preview.location, preview);
                previews.put(name, preview);
                loadFontPreview(name, preview);
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Select Emoji Font", width / 2, 12, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);

        // Draw previews next to each button
        int btnW = Math.min(300, width - 40);
        int previewX = (width + btnW) / 2 + 6;

        for (var btn : buttonList) {
            PreviewTexture preview = null;
            if (btn.id == BTN_DEFAULT) {
                preview = bundledPreview;
            } else if (btn.id >= FONT_BTN_START && btn.id < FONT_BTN_START + fontFiles.size()) {
                preview = previews.get(fontFiles.get(btn.id - FONT_BTN_START));
            }

            if (preview != null) {
                if (preview.isReady()) {
                    renderPreviewStrip(preview, previewX, btn.yPosition + 2);
                } else if (preview.isUnsupported()) {
                    fontRendererObj.drawString("\u00a78No COLR table", previewX, btn.yPosition + 6, 0x888888);
                }
            }
        }
    }

    private void renderPreviewStrip(PreviewTexture preview, int x, int y) {
        mc.getTextureManager()
            .bindTexture(preview.location);
        GL11.glColor4f(1, 1, 1, 1);

        int count = PREVIEW_CODEPOINTS.length;
        float texW = count * PREVIEW_GLYPH_SIZE;

        for (int i = 0; i < count; i++) {
            float u0 = (float) (i * PREVIEW_GLYPH_SIZE) / texW;
            float u1 = (float) ((i + 1) * PREVIEW_GLYPH_SIZE) / texW;
            int dx = x + i * (PREVIEW_DISPLAY_SIZE + 1);

            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            GL11.glTexCoord2f(u0, 0);
            GL11.glVertex3f(dx, y, 0);
            GL11.glTexCoord2f(u0, 1);
            GL11.glVertex3f(dx, y + PREVIEW_DISPLAY_SIZE, 0);
            GL11.glTexCoord2f(u1, 0);
            GL11.glVertex3f(dx + PREVIEW_DISPLAY_SIZE, y, 0);
            GL11.glTexCoord2f(u1, 1);
            GL11.glVertex3f(dx + PREVIEW_DISPLAY_SIZE, y + PREVIEW_DISPLAY_SIZE, 0);
            GL11.glEnd();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_BACK) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id == BTN_OPEN_FOLDER) {
            FileUtil.openFolder(ClientEmojiHandler.FONTS_DIR);
            return;
        }
        if (button.id == BTN_DEFAULT) {
            selectFont("");
            return;
        }
        int idx = button.id - FONT_BTN_START;
        if (idx >= 0 && idx < fontFiles.size()) {
            selectFont(fontFiles.get(idx));
        }
    }

    private void selectFont(String fontName) {
        EmojiConfig.emojiFont = fontName;
        Minemoticon.LOG.info("Selected emoji font: {}", fontName.isEmpty() ? "(bundled)" : fontName);

        // Save config to disk
        try {
            com.gtnewhorizon.gtnhlib.config.ConfigurationManager.save(EmojiConfig.class);
        } catch (Exception e) {
            Minemoticon.LOG.warn("Failed to save config after font change", e);
        }

        // Reload font + emojis
        ClientEmojiHandler.reloadFont();

        mc.displayGuiScreen(parent);
    }

    private void loadBundledPreview() {
        DownloadedTexture.submitToPool(() -> {
            try (var stream = getClass().getResourceAsStream("/assets/minemoticon/twemoji.ttf")) {
                if (stream == null) return;
                var baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = stream.read(buf)) != -1) baos.write(buf, 0, n);
                var font = ColorFont.load(new ByteArrayInputStream(baos.toByteArray()));
                renderPreview(font, bundledPreview);
            } catch (Exception e) {
                Minemoticon.debug("Failed to render bundled font preview: {}", e.getMessage());
            }
        });
    }

    private void loadFontPreview(String filename, PreviewTexture preview) {
        DownloadedTexture.submitToPool(() -> {
            try {
                byte[] data = Files.readAllBytes(new File(ClientEmojiHandler.FONTS_DIR, filename).toPath());
                var font = ColorFont.load(new ByteArrayInputStream(data));
                renderPreview(font, preview);
            } catch (Exception e) {
                Minemoticon.LOG.warn("Font {} failed to load: {}", filename, e.getMessage());
                preview.setUnsupported(e.getMessage());
            }
        });
    }

    private void renderPreview(ColorFont font, PreviewTexture preview) {
        int count = PREVIEW_CODEPOINTS.length;
        var strip = new BufferedImage(count * PREVIEW_GLYPH_SIZE, PREVIEW_GLYPH_SIZE, BufferedImage.TYPE_INT_ARGB);
        var g = strip.createGraphics();

        for (int i = 0; i < count; i++) {
            var glyph = font.renderGlyph(PREVIEW_CODEPOINTS[i], PREVIEW_GLYPH_SIZE);
            if (glyph != null) {
                g.drawImage(glyph, i * PREVIEW_GLYPH_SIZE, 0, null);
            }
        }
        g.dispose();
        preview.setImage(strip);
    }

    private static class PreviewTexture extends AbstractTexture {

        final ResourceLocation location;
        private final AtomicReference<BufferedImage> pending = new AtomicReference<>();
        private volatile boolean ready;
        private volatile boolean unsupported;

        PreviewTexture(String id) {
            this.location = new ResourceLocation(Minemoticon.MODID, "textures/fontpreview/" + Math.abs(id.hashCode()));
        }

        void setImage(BufferedImage img) {
            pending.set(img);
        }

        String unsupportedReason;

        void setUnsupported(String reason) {
            unsupported = true;
            unsupportedReason = reason;
        }

        boolean isReady() {
            return ready;
        }

        boolean isUnsupported() {
            return unsupported;
        }

        @Override
        public void loadTexture(IResourceManager resourceManager) {}

        @Override
        public int getGlTextureId() {
            int id = super.getGlTextureId();
            var img = pending.getAndSet(null);
            if (img != null) {
                EmojiTextureUtil.uploadFilteredTexture(id, img);
                ready = true;
            }
            return id;
        }
    }
}
