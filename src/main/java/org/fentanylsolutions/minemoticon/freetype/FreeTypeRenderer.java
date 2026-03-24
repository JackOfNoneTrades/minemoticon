package org.fentanylsolutions.minemoticon.freetype;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.fentanylsolutions.minemoticon.Minemoticon;

import com.mlomb.freetypejni.Bitmap;
import com.mlomb.freetypejni.Face;
import com.mlomb.freetypejni.FreeType;
import com.mlomb.freetypejni.FreeTypeConstants;
import com.mlomb.freetypejni.GlyphSlot;
import com.mlomb.freetypejni.Library;

/**
 * Renders color emoji glyphs using FreeType via freetype-jni.
 * Handles COLRv1, CBDT, SVG, and everything FreeType supports natively.
 * Isolated in its own package -- can be removed if problematic.
 */
public class FreeTypeRenderer implements AutoCloseable {

    private static final int FT_LOAD_FLAGS = FreeTypeConstants.FT_LOAD_COLOR | FreeTypeConstants.FT_LOAD_RENDER;
    private static final int FT_PIXEL_MODE_BGRA = 7;
    private static final int PROBE_RENDER_SIZE = 128;
    private static final int MAX_STRIKE_PROBE = 8;
    private static final int MAX_LOAD_FAILURE_LOGS = 8;

    private final Library library;
    private final Face face;
    private final Map<Integer, Boolean> renderableGlyphCache = new HashMap<>();
    private boolean valid;

    private FreeTypeRenderer(Library library, Face face) {
        this.library = library;
        this.face = face;
        this.valid = true;
    }

    public static FreeTypeRenderer load(byte[] fontData) {
        try {
            Minemoticon.LOG.info("[FreeType] Attempting to load native library...");
            if (!loadNativeLib()) {
                Minemoticon.LOG.warn("[FreeType] Native library not available");
                return null;
            }

            var library = new Library(FreeType.FT_Init_FreeType());
            if (library.getPointer() == 0) {
                Minemoticon.debug("FT_Init_FreeType failed");
                return null;
            }

            var version = library.getVersion();
            Minemoticon.debug("FreeType version: {}.{}.{}", version.getMajor(), version.getMinor(), version.getPatch());

            var face = library.newFace(fontData, 0);
            if (face == null || face.getPointer() == 0) {
                Minemoticon.debug("FT_New_Memory_Face failed");
                return null;
            }

            Minemoticon.debug(
                "FreeType loaded: {} glyphs, family={}, palettes={}",
                face.getNumGlyphs(),
                face.getFamilyName(),
                face.getNumPalettes());

            // Select the default color palette for COLRv1 rendering
            if (face.getNumPalettes() > 0) {
                face.selectPalette(0);
                Minemoticon.debug("Selected color palette 0");
            }

            return new FreeTypeRenderer(library, face);
        } catch (Throwable e) {
            Minemoticon.debug("FreeType init failed: {}", e.getMessage());
            return null;
        }
    }

    private static boolean debugLogged = false;
    private static boolean strikeFallbackLogged = false;
    private static int loadFailureLogCount = 0;

    private static class LoadedGlyph {

        final int width;
        final int rows;
        final int pitch;
        final int pixelMode;
        final ByteBuffer buffer;

        LoadedGlyph(int width, int rows, int pitch, int pixelMode, ByteBuffer buffer) {
            this.width = width;
            this.rows = rows;
            this.pitch = pitch;
            this.pixelMode = pixelMode;
            this.buffer = buffer;
        }
    }

    public synchronized boolean canRenderGlyph(int codepoint) {
        if (!valid) return false;

        Boolean cached = renderableGlyphCache.get(codepoint);
        if (cached != null) {
            return cached;
        }

        boolean renderable = loadGlyphBitmap(codepoint, PROBE_RENDER_SIZE) != null;
        renderableGlyphCache.put(codepoint, renderable);
        return renderable;
    }

    public synchronized BufferedImage renderGlyph(int codepoint, int size) {
        if (!valid) return null;
        try {
            LoadedGlyph glyph = loadGlyphBitmap(codepoint, size);
            if (glyph == null) return null;

            if (!debugLogged) {
                Minemoticon.debug(
                    "FT render cp={}: rows={} width={} pitch={} pixelMode={}",
                    codepoint,
                    glyph.rows,
                    glyph.width,
                    glyph.pitch,
                    glyph.pixelMode);
                debugLogged = true;
            }

            // Read bitmap into a BufferedImage at native size
            var bitmapImg = new BufferedImage(glyph.width, glyph.rows, BufferedImage.TYPE_INT_ARGB);
            int rowStride = Math.abs(glyph.pitch);
            if (glyph.pixelMode == FT_PIXEL_MODE_BGRA) {
                for (int y = 0; y < glyph.rows; y++) {
                    int rowBase = glyph.pitch >= 0 ? y * rowStride : (glyph.rows - 1 - y) * rowStride;
                    for (int x = 0; x < glyph.width; x++) {
                        int off = rowBase + x * 4;
                        int b = glyph.buffer.get(off) & 0xFF;
                        int g = glyph.buffer.get(off + 1) & 0xFF;
                        int r = glyph.buffer.get(off + 2) & 0xFF;
                        int a = glyph.buffer.get(off + 3) & 0xFF;
                        bitmapImg.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }
            } else {
                for (int y = 0; y < glyph.rows; y++) {
                    int rowBase = glyph.pitch >= 0 ? y * rowStride : (glyph.rows - 1 - y) * rowStride;
                    for (int x = 0; x < glyph.width; x++) {
                        int alpha = glyph.buffer.get(rowBase + x) & 0xFF;
                        bitmapImg.setRGB(x, y, (alpha << 24) | 0x00FFFFFF);
                    }
                }
            }

            // Scale to fill the target size, centered
            var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            var g2d = img.createGraphics();
            g2d.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            float scale = Math.min((float) size / glyph.width, (float) size / glyph.rows);
            int scaledW = Math.max(1, (int) (glyph.width * scale));
            int scaledH = Math.max(1, (int) (glyph.rows * scale));
            g2d.drawImage(bitmapImg, (size - scaledW) / 2, (size - scaledH) / 2, scaledW, scaledH, null);
            g2d.dispose();
            return img;
        } catch (Throwable e) {
            return null;
        }
    }

    public boolean hasGlyph(int codepoint) {
        return canRenderGlyph(codepoint);
    }

    @Override
    public void close() {
        if (!valid) return;
        valid = false;
        renderableGlyphCache.clear();
        try {
            face.delete();
            library.delete();
        } catch (Throwable ignored) {}
    }

    private static boolean nativeLoaded = false;

    private static boolean loadNativeLib() {
        if (nativeLoaded) return true;

        String os = System.getProperty("os.name", "")
            .toLowerCase();
        String arch = System.getProperty("os.arch", "")
            .toLowerCase();

        String platform;
        String libName;
        if (os.contains("mac")) {
            platform = arch.contains("aarch64") || arch.contains("arm64") ? "macos-arm64" : "macos-x64";
            libName = "libfreetype-jni.dylib";
        } else if (os.contains("win")) {
            platform = "windows-x64";
            libName = "freetype-jni.dll";
        } else {
            platform = "linux-x64";
            libName = "libfreetype-jni.so";
        }

        String resourcePath = "/natives/" + platform + "/" + libName;

        Minemoticon.LOG.info("[FreeType] Looking for native: {} (platform={})", resourcePath, platform);
        try (InputStream in = FreeTypeRenderer.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                Minemoticon.LOG.warn("[FreeType] Native lib not found in resources: {}", resourcePath);
                return false;
            }

            File tmpDir = new File(System.getProperty("java.io.tmpdir"), "minemoticon-natives");
            tmpDir.mkdirs();
            File tmpFile = new File(tmpDir, libName);

            try (var out = new FileOutputStream(tmpFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }

            System.load(tmpFile.getAbsolutePath());
            nativeLoaded = true;
            Minemoticon.debug("Loaded FreeType native from {}", resourcePath);
            return true;
        } catch (Throwable e) {
            Minemoticon.debug("Failed to load FreeType native: {}", e.getMessage());
            return false;
        }
    }

    private LoadedGlyph loadGlyphBitmap(int codepoint, int size) {
        int glyphIndex = face.getCharIndex(codepoint);
        if (glyphIndex == 0) {
            return null;
        }

        LoadedGlyph glyph = tryLoadGlyphAtPixelSize(glyphIndex, size);
        if (glyph != null) {
            return glyph;
        }

        for (int strikeIndex = 0; strikeIndex < MAX_STRIKE_PROBE; strikeIndex++) {
            glyph = tryLoadGlyphAtStrike(glyphIndex, strikeIndex);
            if (glyph != null) {
                if (!strikeFallbackLogged) {
                    Minemoticon
                        .debug("FT used bitmap strike {} for cp={} glyph={}", strikeIndex, codepoint, glyphIndex);
                    strikeFallbackLogged = true;
                }
                return glyph;
            }
        }

        return null;
    }

    private LoadedGlyph tryLoadGlyphAtPixelSize(int glyphIndex, int size) {
        face.setPixelSizes(size, size);
        return tryLoadCurrentGlyph(glyphIndex);
    }

    private LoadedGlyph tryLoadGlyphAtStrike(int glyphIndex, int strikeIndex) {
        if (face.selectSize(strikeIndex)) {
            return null;
        }
        return tryLoadCurrentGlyph(glyphIndex);
    }

    private LoadedGlyph tryLoadCurrentGlyph(int glyphIndex) {
        // loadGlyph returns FT_Error as boolean: false=success(0), true=error(nonzero)
        if (face.loadGlyph(glyphIndex, FT_LOAD_FLAGS)) {
            if (loadFailureLogCount < MAX_LOAD_FAILURE_LOGS) {
                Minemoticon.debug("FT loadGlyph failed for glyph={}", glyphIndex);
                loadFailureLogCount++;
                if (loadFailureLogCount == MAX_LOAD_FAILURE_LOGS) {
                    Minemoticon
                        .debug("FT loadGlyph failure logging suppressed after {} entries", MAX_LOAD_FAILURE_LOGS);
                }
            }
            return null;
        }

        GlyphSlot slot = face.getGlyphSlot();
        if (slot == null) return null;

        Bitmap bitmap = slot.getBitmap();
        if (bitmap == null) return null;

        int rows = bitmap.getRows();
        int width = bitmap.getWidth();
        int pitch = bitmap.getPitch();
        ByteBuffer buffer = bitmap.getBuffer();
        if (rows <= 0 || width <= 0 || pitch == 0 || buffer == null) {
            return null;
        }

        return new LoadedGlyph(width, rows, pitch, bitmap.getPixelMode(), buffer);
    }
}
