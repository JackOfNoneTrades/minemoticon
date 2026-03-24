package org.fentanylsolutions.minemoticon.colorfont;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.api.DownloadedTexture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class AtlasBuilder {

    private static final File CACHE_DIR = new File("config/minemoticon/cache");
    private static final String ATLAS_RENDER_VERSION = "v4";
    private static final int CELL_SIZE = 128;
    private static final int RENDER_SIZE = 256; // 2x supersample
    private static final int COLS = 48;

    public static class GlyphEntry {

        public final String unified; // e.g. "1F600" or "1F468-200D-1F469"
        public final int[] codepoints;

        public GlyphEntry(String unified, int[] codepoints) {
            this.unified = unified;
            this.codepoints = codepoints;
        }
    }

    // Try loading cached atlas, or start building in background. Returns the atlas immediately.
    public static EmojiAtlas loadOrBuild(ColorFont primaryFont, ColorFont fallbackFont, String fontHash,
        List<GlyphEntry> glyphs) {
        var atlas = new EmojiAtlas();
        CACHE_DIR.mkdirs();

        String atlasKey = fontHash + "_" + ATLAS_RENDER_VERSION;
        File pngFile = new File(CACHE_DIR, "atlas_" + atlasKey + ".png");
        File jsonFile = new File(CACHE_DIR, "atlas_" + atlasKey + ".json");

        if (pngFile.isFile() && jsonFile.isFile()) {
            DownloadedTexture.submitToPool(() -> loadFromCache(atlas, pngFile, jsonFile));
        } else {
            DownloadedTexture
                .submitToPool(() -> buildAndCache(atlas, primaryFont, fallbackFont, glyphs, pngFile, jsonFile));
        }

        return atlas;
    }

    private static void loadFromCache(EmojiAtlas atlas, File pngFile, File jsonFile) {
        try {
            Minemoticon.debug("Loading emoji atlas from cache: {}", pngFile.getName());
            long start = System.currentTimeMillis();

            BufferedImage img = ImageIO.read(pngFile);
            Map<String, float[]> uvs = loadUVs(jsonFile, img.getWidth(), img.getHeight());
            atlas.setUVs(uvs);
            atlas.setImage(img);

            Minemoticon.debug("Atlas loaded in {}ms ({} glyphs)", System.currentTimeMillis() - start, uvs.size());
        } catch (Exception e) {
            Minemoticon.LOG.error("Failed to load cached atlas", e);
        }
    }

    private static void buildAndCache(EmojiAtlas atlas, ColorFont primaryFont, ColorFont fallbackFont,
        List<GlyphEntry> glyphs, File pngFile, File jsonFile) {
        try {
            Minemoticon.LOG.info("Building emoji atlas ({} glyphs)...", glyphs.size());
            long start = System.currentTimeMillis();

            if (glyphs.isEmpty()) {
                Minemoticon.LOG.warn("No glyphs to build atlas from");
                return;
            }

            int rows = (glyphs.size() + COLS - 1) / COLS;
            int atlasW = COLS * CELL_SIZE;
            int atlasH = rows * CELL_SIZE;

            var atlasImg = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
            var g2d = atlasImg.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(
                RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            Map<String, float[]> uvs = new HashMap<>();
            Map<String, int[]> pixelCoords = new HashMap<>(); // for JSON cache

            for (int i = 0; i < glyphs.size(); i++) {
                var entry = glyphs.get(i);
                int col = i % COLS;
                int row = i / COLS;
                int px = col * CELL_SIZE;
                int py = row * CELL_SIZE;

                // Render glyph: try primary font, fall back to bundled
                BufferedImage glyph = renderFromFont(primaryFont, entry, RENDER_SIZE);
                if (glyph == null && fallbackFont != null && fallbackFont != primaryFont) {
                    glyph = renderFromFont(fallbackFont, entry, RENDER_SIZE);
                }

                if (glyph != null) {
                    // Draw downsampled into atlas cell
                    g2d.drawImage(glyph, px, py, CELL_SIZE, CELL_SIZE, null);
                }

                int[] visibleBounds = glyph != null ? computeVisibleBounds(glyph) : null;
                int uvPx = px;
                int uvPy = py;
                int uvW = CELL_SIZE;
                int uvH = CELL_SIZE;
                if (visibleBounds != null) {
                    uvPx += visibleBounds[0];
                    uvPy += visibleBounds[1];
                    uvW = visibleBounds[2];
                    uvH = visibleBounds[3];
                }

                float u0 = (float) uvPx / atlasW;
                float v0 = (float) uvPy / atlasH;
                float u1 = (float) (uvPx + uvW) / atlasW;
                float v1 = (float) (uvPy + uvH) / atlasH;
                uvs.put(entry.unified, new float[] { u0, v0, u1, v1 });
                pixelCoords.put(entry.unified, new int[] { uvPx, uvPy, uvW, uvH });
            }

            g2d.dispose();

            // Save to cache
            ImageIO.write(atlasImg, "png", pngFile);
            saveUVs(jsonFile, pixelCoords);

            atlas.setUVs(uvs);
            atlas.setImage(atlasImg);

            long elapsed = System.currentTimeMillis() - start;
            Minemoticon.LOG.info("Atlas built in {}ms ({} glyphs, {}x{})", elapsed, glyphs.size(), atlasW, atlasH);
        } catch (Exception e) {
            Minemoticon.LOG.error("Failed to build emoji atlas", e);
        }
    }

    private static void saveUVs(File jsonFile, Map<String, int[]> pixelCoords) throws IOException {
        var root = new JsonObject();
        for (var entry : pixelCoords.entrySet()) {
            var arr = new com.google.gson.JsonArray();
            for (int v : entry.getValue()) arr.add(new com.google.gson.JsonPrimitive(v));
            root.add(entry.getKey(), arr);
        }
        try (var writer = new FileWriter(jsonFile)) {
            writer.write(root.toString());
        }
    }

    private static Map<String, float[]> loadUVs(File jsonFile, int atlasW, int atlasH) throws IOException {
        var uvs = new HashMap<String, float[]>();
        try (var reader = new FileReader(jsonFile)) {
            var root = new JsonParser().parse(reader)
                .getAsJsonObject();
            for (var entry : root.entrySet()) {
                var arr = entry.getValue()
                    .getAsJsonArray();
                int px = arr.get(0)
                    .getAsInt();
                int py = arr.get(1)
                    .getAsInt();
                int pw = arr.get(2)
                    .getAsInt();
                int ph = arr.get(3)
                    .getAsInt();
                uvs.put(
                    entry.getKey(),
                    new float[] { (float) px / atlasW, (float) py / atlasH, (float) (px + pw) / atlasW,
                        (float) (py + ph) / atlasH });
            }
        }
        return uvs;
    }

    private static BufferedImage renderFromFont(ColorFont font, GlyphEntry entry, int size) {
        if (font == null) return null;
        try {
            if (entry.codepoints.length == 1) {
                return font.renderGlyph(entry.codepoints[0], size);
            } else {
                return font.renderGlyphs(entry.codepoints, size);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static String sha1(byte[] data) {
        try {
            var md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            var sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static int[] computeVisibleBounds(BufferedImage glyph) {
        int width = glyph.getWidth();
        int height = glyph.getHeight();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (((glyph.getRGB(x, y) >>> 24) & 0xFF) == 0) continue;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }

        if (maxX < minX || maxY < minY) {
            return null;
        }

        // Keep a little transparent border so antialiased edges don't get clipped.
        int pad = Math.max(1, width / 64);
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(width - 1, maxX + pad);
        maxY = Math.min(height - 1, maxY + pad);

        double scaleX = (double) CELL_SIZE / width;
        double scaleY = (double) CELL_SIZE / height;
        int scaledMinX = Math.max(0, (int) Math.floor(minX * scaleX));
        int scaledMinY = Math.max(0, (int) Math.floor(minY * scaleY));
        int scaledMaxX = Math.min(CELL_SIZE - 1, (int) Math.ceil((maxX + 1) * scaleX) - 1);
        int scaledMaxY = Math.min(CELL_SIZE - 1, (int) Math.ceil((maxY + 1) * scaleY) - 1);

        return new int[] { scaledMinX, scaledMinY, Math.max(1, scaledMaxX - scaledMinX + 1),
            Math.max(1, scaledMaxY - scaledMinY + 1) };
    }
}
