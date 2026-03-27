package org.fentanylsolutions.minemoticon.font;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.minemoticon.EmojiConfig;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.lwjgl.opengl.GL11;

// Dynamic glyph atlas for character-level font stack rendering.
// Each non-Minecraft FontSource gets its own GlyphCache instance.
// Glyphs are rendered on demand and packed into a growing atlas texture.
public class GlyphCache {

    // Render at 4x display size for quality.
    private static final int RENDER_SIZE = 32;
    private static final float DEFAULT_DISPLAY_HEIGHT = 8.0f;
    private static final int INITIAL_SIZE = 512;
    private static final int MAX_SIZE = 2048;

    private final FontSource source;
    private final String textureName;
    private ResourceLocation resourceLocation;

    // Atlas state
    private BufferedImage atlasImage;
    private int atlasWidth = INITIAL_SIZE;
    private int atlasHeight = INITIAL_SIZE;
    private int cursorX = 0;
    private int cursorY = 0;
    private int rowHeight = 0;

    // UV lookup: codepoint -> [u0, v0, u1, v1]
    private final Map<Integer, float[]> uvMap = new HashMap<>();
    private final Map<Integer, Float> widthMap = new HashMap<>();
    // Track which codepoints are actively being rasterized to avoid duplicate work
    private final ConcurrentHashMap<Integer, Boolean> pendingRenders = new ConcurrentHashMap<>();

    private GlyphAtlasTexture texture;
    private boolean registered = false;

    // Global cache of GlyphCache instances per font source ID
    private static final Map<String, GlyphCache> INSTANCES = new HashMap<>();

    public static GlyphCache forSource(FontSource source) {
        return INSTANCES.computeIfAbsent(source.getId(), id -> new GlyphCache(source));
    }

    public static void invalidateAll() {
        INSTANCES.clear();
    }

    public static void dumpAllAtlases(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create atlas dump dir: " + dir);
        }

        for (GlyphCache cache : INSTANCES.values()) {
            cache.dumpAtlas(dir);
        }
    }

    private GlyphCache(FontSource source) {
        this.source = source;
        this.textureName = "glyph_cache_" + sanitizeTextureName(source.getId());
        this.atlasImage = new BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
        this.texture = new GlyphAtlasTexture(this.atlasImage);
    }

    public ResourceLocation getResourceLocation() {
        if (!registered) {
            resourceLocation = net.minecraft.client.Minecraft.getMinecraft()
                .getTextureManager()
                .getDynamicTextureLocation(textureName, texture);
            registered = true;
        }
        return resourceLocation;
    }

    public int getGlTextureId() {
        return this.texture.getGlTextureId();
    }

    // Returns UV coords, rendering the glyph synchronously on first use.
    public float[] getGlyphUV(int codepoint) {
        float[] uv = uvMap.get(codepoint);
        if (uv != null) return uv;

        if (pendingRenders.putIfAbsent(codepoint, Boolean.TRUE) != null) {
            return uvMap.get(codepoint);
        }

        try {
            renderGlyph(codepoint);
            return uvMap.get(codepoint);
        } finally {
            pendingRenders.remove(codepoint);
        }
    }

    // Returns the advance width of this glyph in display pixels (8px scale).
    // Returns 0 if not yet rendered.
    public float getGlyphWidth(int codepoint) {
        Float explicitWidth = widthMap.get(codepoint);
        if (explicitWidth != null) return explicitWidth;
        float[] uv = uvMap.get(codepoint);
        if (uv == null) return 0;
        // Width from UV ratio * display height.
        float texW = uv[2] - uv[0];
        float texH = uv[3] - uv[1];
        if (texH == 0) return minemoticon$getDisplayHeight();
        return (texW / texH) * minemoticon$getDisplayHeight();
    }

    public boolean isReady(int codepoint) {
        return uvMap.containsKey(codepoint);
    }

    private void renderGlyph(int codepoint) {
        boolean preserveLineMetrics = source.preserveTextLineMetrics();
        BufferedImage glyph = preserveLineMetrics ? source.renderTextGlyph(codepoint, RENDER_SIZE)
            : source.renderGlyph(codepoint, RENDER_SIZE);
        float measuredAdvance = source.getTextGlyphAdvance(codepoint, RENDER_SIZE);
        if (measuredAdvance > 0.0f) {
            widthMap.put(codepoint, measuredAdvance * minemoticon$getDisplayHeight() / RENDER_SIZE);
        }
        if (glyph == null) return;

        // Compute tight visible bounds
        int gw = glyph.getWidth();
        int gh = glyph.getHeight();
        int minX = gw, minY = gh, maxX = 0, maxY = 0;
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                if ((glyph.getRGB(x, y) >>> 24) > 0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }

        if (maxX < minX) {
            // Fully transparent, treat as spacing-only glyph
            return;
        }

        // Add 1px padding
        minX = Math.max(0, minX - 1);
        maxX = Math.min(gw - 1, maxX + 1);
        if (preserveLineMetrics) {
            minY = 0;
            maxY = gh - 1;
        } else {
            minY = Math.max(0, minY - 1);
            maxY = Math.min(gh - 1, maxY + 1);
        }

        int cropW = maxX - minX + 1;
        int cropH = maxY - minY + 1;

        synchronized (this) {
            // Check if we need to advance to next row
            if (cursorX + cropW > atlasWidth) {
                cursorX = 0;
                cursorY += rowHeight;
                rowHeight = 0;
            }

            // Check if we need to grow the atlas
            if (cursorY + cropH > atlasHeight) {
                if (atlasHeight < MAX_SIZE) {
                    growAtlas();
                } else {
                    Minemoticon.LOG.warn("Glyph cache atlas full for source {}", source.getId());
                    return;
                }
            }

            // Draw the cropped glyph into the atlas
            var g = atlasImage.createGraphics();
            g.drawImage(
                glyph,
                cursorX,
                cursorY,
                cursorX + cropW,
                cursorY + cropH,
                minX,
                minY,
                maxX + 1,
                maxY + 1,
                null);
            g.dispose();

            // Store UV coordinates
            float u0 = (float) cursorX / atlasWidth;
            float v0 = (float) cursorY / atlasHeight;
            float u1 = (float) (cursorX + cropW) / atlasWidth;
            float v1 = (float) (cursorY + cropH) / atlasHeight;
            uvMap.put(codepoint, new float[] { u0, v0, u1, v1 });

            // Advance cursor
            cursorX += cropW;
            rowHeight = Math.max(rowHeight, cropH);

            // Mark atlas dirty for re-upload
            texture.markDirty(atlasImage);
        }
    }

    private void growAtlas() {
        int newHeight = Math.min(atlasHeight * 2, MAX_SIZE);
        var newImage = new BufferedImage(atlasWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        var g = newImage.createGraphics();
        g.drawImage(atlasImage, 0, 0, null);
        g.dispose();

        // Rescale existing UVs
        float scale = (float) atlasHeight / newHeight;
        for (float[] uv : uvMap.values()) {
            uv[1] *= scale;
            uv[3] *= scale;
        }

        atlasImage = newImage;
        atlasHeight = newHeight;
        texture = new GlyphAtlasTexture(atlasImage);
        if (registered) {
            resourceLocation = net.minecraft.client.Minecraft.getMinecraft()
                .getTextureManager()
                .getDynamicTextureLocation(textureName, texture);
        }
    }

    private float minemoticon$getDisplayHeight() {
        return source.preserveTextLineMetrics() ? EmojiConfig.getFontStackTextDisplayHeight() : DEFAULT_DISPLAY_HEIGHT;
    }

    private static String sanitizeTextureName(String sourceId) {
        return sourceId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private synchronized void dumpAtlas(File dir) throws IOException {
        BufferedImage snapshot = new BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
        var g = snapshot.createGraphics();
        g.drawImage(atlasImage, 0, 0, null);
        g.dispose();
        ImageIO.write(snapshot, "png", new File(dir, source.getId() + ".png"));
    }

    static class GlyphAtlasTexture extends DynamicTexture {

        GlyphAtlasTexture(BufferedImage image) {
            super(image);
        }

        void markDirty(BufferedImage image) {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] data = this.getTextureData();
            int rowStride = width;
            for (int y = 0; y < height; y++) {
                image.getRGB(0, y, width, 1, data, y * rowStride, width);
            }
            this.updateDynamicTexture();
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        }
    }
}
