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
    public static EmojiAtlas loadOrBuild(ColorFont font, String fontHash, List<GlyphEntry> glyphs) {
        var atlas = new EmojiAtlas();
        CACHE_DIR.mkdirs();

        File pngFile = new File(CACHE_DIR, "atlas_" + fontHash + ".png");
        File jsonFile = new File(CACHE_DIR, "atlas_" + fontHash + ".json");

        if (pngFile.isFile() && jsonFile.isFile()) {
            // Load cached atlas on background thread
            DownloadedTexture.submitToPool(() -> loadFromCache(atlas, pngFile, jsonFile));
        } else {
            // Build atlas on background thread
            DownloadedTexture.submitToPool(() -> buildAndCache(atlas, font, glyphs, pngFile, jsonFile));
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

    private static void buildAndCache(EmojiAtlas atlas, ColorFont font, List<GlyphEntry> glyphs, File pngFile,
        File jsonFile) {
        try {
            Minemoticon.LOG.info("Building emoji atlas ({} glyphs)...", glyphs.size());
            long start = System.currentTimeMillis();

            int rows = (glyphs.size() + COLS - 1) / COLS;
            int atlasW = COLS * CELL_SIZE;
            int atlasH = rows * CELL_SIZE;

            var atlasImg = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
            var g2d = atlasImg.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Map<String, float[]> uvs = new HashMap<>();
            Map<String, int[]> pixelCoords = new HashMap<>(); // for JSON cache

            for (int i = 0; i < glyphs.size(); i++) {
                var entry = glyphs.get(i);
                int col = i % COLS;
                int row = i / COLS;
                int px = col * CELL_SIZE;
                int py = row * CELL_SIZE;

                // Render glyph at high res
                BufferedImage glyph;
                if (entry.codepoints.length == 1) {
                    glyph = font.renderGlyph(entry.codepoints[0], RENDER_SIZE);
                } else {
                    glyph = font.renderGlyphs(entry.codepoints, RENDER_SIZE);
                }

                if (glyph != null) {
                    // Draw downsampled into atlas cell
                    g2d.drawImage(glyph, px, py, CELL_SIZE, CELL_SIZE, null);
                }

                float u0 = (float) px / atlasW;
                float v0 = (float) py / atlasH;
                float u1 = (float) (px + CELL_SIZE) / atlasW;
                float v1 = (float) (py + CELL_SIZE) / atlasH;
                uvs.put(entry.unified, new float[] { u0, v0, u1, v1 });
                pixelCoords.put(entry.unified, new int[] { px, py, CELL_SIZE, CELL_SIZE });
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
}
