package org.fentanylsolutions.minemoticon.api;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.colorfont.ColorFont;

// Stock emoji rendered from a COLR font. Texture created lazily on first render.
public class EmojiFromFont extends Emoji implements RenderableEmoji {

    private static final int RENDER_SIZE = 64; // render at 64px, downscaled by GL

    private final ColorFont font;
    private final int[] codepoints;
    private FontTexture texture;
    private ResourceLocation resourceLocation;

    public EmojiFromFont(ColorFont font, int[] codepoints) {
        this.font = font;
        this.codepoints = codepoints;
    }

    private void checkLoad() {
        if (texture != null) return;

        texture = new FontTexture(font, codepoints);
        resourceLocation = new ResourceLocation(Minemoticon.MODID, "textures/font/" + name + "_" + version);
        Minecraft.getMinecraft()
            .getTextureManager()
            .loadTexture(resourceLocation, texture);
    }

    @Override
    public ResourceLocation getResourceLocation() {
        checkLoad();
        return resourceLocation;
    }

    @Override
    public boolean isLoaded() {
        return texture != null && texture.isUploaded();
    }

    @Override
    public String getInsertText() {
        return unicodeString != null ? unicodeString : super.getInsertText();
    }

    // Inner texture class that renders the glyph on a background thread
    private static class FontTexture extends AbstractTexture {

        private final ColorFont font;
        private final int[] codepoints;
        private final AtomicReference<BufferedImage> pendingImage = new AtomicReference<>();
        private volatile boolean uploaded;

        FontTexture(ColorFont font, int[] codepoints) {
            this.font = font;
            this.codepoints = codepoints;
        }

        boolean isUploaded() {
            return uploaded;
        }

        @Override
        public void loadTexture(IResourceManager resourceManager) {
            DownloadedTexture.submitToPool(() -> {
                BufferedImage img;
                if (codepoints.length == 1) {
                    img = font.renderGlyph(codepoints[0], RENDER_SIZE);
                } else {
                    img = font.renderGlyphs(codepoints, RENDER_SIZE);
                }
                if (img != null) {
                    pendingImage.set(img);
                }
            });
        }

        @Override
        public int getGlTextureId() {
            int id = super.getGlTextureId();
            BufferedImage img = pendingImage.getAndSet(null);
            if (img != null) {
                TextureUtil.uploadTextureImageAllocate(id, img, false, false);
                uploaded = true;
            }
            return id;
        }
    }
}
