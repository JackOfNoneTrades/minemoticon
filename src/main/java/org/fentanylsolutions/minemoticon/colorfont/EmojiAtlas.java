package org.fentanylsolutions.minemoticon.colorfont;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;

// Holds a single atlas texture with UV lookup for all glyphs.
// Shared by all EmojiFromAtlas instances for a given font.
public class EmojiAtlas {

    private final Map<String, float[]> uvLookup = new HashMap<>(); // unified -> [u0, v0, u1, v1]
    private final AtlasTexture texture = new AtlasTexture();
    private volatile boolean ready;

    public boolean isReady() {
        return ready;
    }

    public float[] getUV(String unified) {
        return uvLookup.get(unified);
    }

    public void setUVs(Map<String, float[]> uvs) {
        uvLookup.putAll(uvs);
    }

    public void setImage(BufferedImage image) {
        texture.setPendingImage(image);
        ready = true;
    }

    public AtlasTexture getTexture() {
        return texture;
    }

    public boolean hasGlyph(String unified) {
        return uvLookup.containsKey(unified);
    }

    // GL texture that uploads the atlas image lazily
    public static class AtlasTexture extends AbstractTexture {

        private final AtomicReference<BufferedImage> pendingImage = new AtomicReference<>();
        private volatile boolean uploaded;

        void setPendingImage(BufferedImage img) {
            pendingImage.set(img);
        }

        public boolean isUploaded() {
            return uploaded;
        }

        @Override
        public void loadTexture(IResourceManager resourceManager) {
            // No-op; image is set externally via setPendingImage
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
