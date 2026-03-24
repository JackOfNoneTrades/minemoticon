package org.fentanylsolutions.minemoticon.api;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.colorfont.ColorFont;
import org.fentanylsolutions.minemoticon.render.EmojiTextureUtil;

// Stock emoji rendered from a COLR font. Texture created lazily on first render.
public class EmojiFromFont extends Emoji implements RenderableEmoji {

    private static int debugFailureLogs;

    private final ColorFont primaryFont;
    private final ColorFont fallbackFont;
    private final RenderableEmoji fallbackEmoji;
    private final int[] codepoints;
    private FontTexture texture;
    private ResourceLocation resourceLocation;

    public EmojiFromFont(ColorFont primaryFont, ColorFont fallbackFont, RenderableEmoji fallbackEmoji,
        int[] codepoints) {
        this.primaryFont = primaryFont;
        this.fallbackFont = fallbackFont;
        this.fallbackEmoji = fallbackEmoji;
        this.codepoints = codepoints;
    }

    private void checkLoad() {
        if (texture != null) return;

        texture = new FontTexture(primaryFont, fallbackFont, codepoints);
        resourceLocation = new ResourceLocation(Minemoticon.MODID, "textures/font/" + name + "_" + version);
        Minecraft.getMinecraft()
            .getTextureManager()
            .loadTexture(resourceLocation, texture);
    }

    @Override
    public ResourceLocation getResourceLocation() {
        checkLoad();
        if (texture != null && texture.isFailed() && fallbackEmoji != null) {
            return fallbackEmoji.getResourceLocation();
        }
        return resourceLocation;
    }

    @Override
    public boolean isLoaded() {
        if (texture != null && texture.isFailed() && fallbackEmoji != null) {
            return fallbackEmoji.isLoaded();
        }
        return texture != null && texture.isUploaded();
    }

    @Override
    public String getInsertText() {
        return unicodeString != null ? unicodeString : super.getInsertText();
    }

    // Inner texture class that renders the glyph on a background thread
    private static class FontTexture extends AbstractTexture {

        private static final int RENDER_SIZE = 96; // render oversized, then let GL downsample smoothly

        private final ColorFont primaryFont;
        private final ColorFont fallbackFont;
        private final int[] codepoints;
        private final AtomicReference<BufferedImage> pendingImage = new AtomicReference<>();
        private volatile boolean uploaded;
        private volatile boolean failed;

        FontTexture(ColorFont primaryFont, ColorFont fallbackFont, int[] codepoints) {
            this.primaryFont = primaryFont;
            this.fallbackFont = fallbackFont;
            this.codepoints = codepoints;
        }

        boolean isUploaded() {
            return uploaded;
        }

        boolean isFailed() {
            return failed;
        }

        @Override
        public void loadTexture(IResourceManager resourceManager) {
            DownloadedTexture.submitToPool(() -> {
                try {
                    BufferedImage img = tryRender(primaryFont);
                    if (img == null && fallbackFont != null && fallbackFont != primaryFont) {
                        img = tryRender(fallbackFont);
                    }
                    if (isUsableImage(img)) {
                        pendingImage.set(img);
                    } else {
                        failed = true;
                        if (debugFailureLogs < 8) {
                            debugFailureLogs++;
                            Minemoticon
                                .debug("Font glyph render failed or was blank for {}", formatCodepoints(codepoints));
                        }
                    }
                } catch (Throwable t) {
                    failed = true;
                    if (debugFailureLogs < 8) {
                        debugFailureLogs++;
                        Minemoticon
                            .debug("Font glyph render crashed for {}: {}", formatCodepoints(codepoints), t.toString());
                    }
                }
            });
        }

        @Override
        public int getGlTextureId() {
            int id = super.getGlTextureId();
            BufferedImage img = pendingImage.getAndSet(null);
            if (img != null) {
                EmojiTextureUtil.uploadFilteredTexture(id, img);
                uploaded = true;
            }
            return id;
        }

        private BufferedImage tryRender(ColorFont font) {
            if (font == null) return null;
            if (codepoints.length == 1) {
                return font.renderGlyph(codepoints[0], RENDER_SIZE);
            }
            return font.renderGlyphs(codepoints, RENDER_SIZE);
        }

        private boolean isUsableImage(BufferedImage img) {
            if (img == null) return false;
            int width = img.getWidth();
            int height = img.getHeight();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (((img.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static String formatCodepoints(int[] codepoints) {
            var sb = new StringBuilder();
            for (int i = 0; i < codepoints.length; i++) {
                if (i > 0) sb.append('-');
                sb.append(
                    Integer.toHexString(codepoints[i])
                        .toUpperCase());
            }
            return sb.toString();
        }
    }
}
