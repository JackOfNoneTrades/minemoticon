package org.fentanylsolutions.minemoticon.freetype;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.colorfont.VariationAxis;
import org.fentanylsolutions.minemoticon.font.TextRunLayout;

import com.mlomb.freetypejni.Bitmap;
import com.mlomb.freetypejni.Face;
import com.mlomb.freetypejni.FreeType;
import com.mlomb.freetypejni.FreeTypeConstants;
import com.mlomb.freetypejni.GlyphSlot;
import com.mlomb.freetypejni.Kerning;
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
    private float[] regularVariationCoordinates;
    private float[] boldVariationCoordinates;
    private boolean variationCoordinatesApplied = false;
    private boolean usingBoldVariation = false;
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
        final int bitmapLeft;
        final int bitmapTop;
        final float advanceX;

        LoadedGlyph(int width, int rows, int pitch, int pixelMode, ByteBuffer buffer, int bitmapLeft, int bitmapTop,
            float advanceX) {
            this.width = width;
            this.rows = rows;
            this.pitch = pitch;
            this.pixelMode = pixelMode;
            this.buffer = buffer;
            this.bitmapLeft = bitmapLeft;
            this.bitmapTop = bitmapTop;
            this.advanceX = advanceX;
        }
    }

    public synchronized void configureVariations(List<VariationAxis> variationAxes, Map<String, Float> settings) {
        if (variationAxes == null || variationAxes.isEmpty()) {
            return;
        }

        regularVariationCoordinates = new float[variationAxes.size()];
        for (int i = 0; i < variationAxes.size(); i++) {
            VariationAxis axis = variationAxes.get(i);
            float value = axis.getDefaultValue();
            if (settings != null && settings.containsKey(axis.getTag())) {
                value = settings.get(axis.getTag());
            }
            regularVariationCoordinates[i] = clamp(value, axis.getMinValue(), axis.getMaxValue());
        }

        int weightIndex = -1;
        for (int i = 0; i < variationAxes.size(); i++) {
            VariationAxis axis = variationAxes.get(i);
            if ("wght".equals(axis.getTag()) && axis.getMaxValue() >= 700.0f) {
                weightIndex = i;
                break;
            }
        }

        if (weightIndex < 0) {
            boldVariationCoordinates = null;
            applyVariationCoordinates(false);
            return;
        }

        VariationAxis weightAxis = variationAxes.get(weightIndex);
        float currentWeight = regularVariationCoordinates[weightIndex];
        float boldWeight = clamp(Math.max(currentWeight, 700.0f), weightAxis.getMinValue(), weightAxis.getMaxValue());
        if (Math.abs(boldWeight - currentWeight) < 0.01f) {
            boldVariationCoordinates = null;
            applyVariationCoordinates(false);
            return;
        }

        boldVariationCoordinates = regularVariationCoordinates.clone();
        boldVariationCoordinates[weightIndex] = boldWeight;
        applyVariationCoordinates(false);
    }

    public synchronized boolean supportsBoldVariation() {
        return boldVariationCoordinates != null;
    }

    public synchronized boolean canRenderGlyph(int codepoint) {
        if (!valid) return false;

        Boolean cached = renderableGlyphCache.get(codepoint);
        if (cached != null) {
            return cached;
        }

        // Check that the codepoint is actually mapped in the font (not .notdef).
        // Without this, FreeType renders the .notdef glyph for unmapped characters
        // and we'd incorrectly claim we can render them.
        if (face.getCharIndex(codepoint) == 0) {
            renderableGlyphCache.put(codepoint, false);
            return false;
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

            BufferedImage bitmapImg = toBitmapImage(glyph);
            if (bitmapImg == null) return null;

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

    public synchronized BufferedImage renderTextGlyph(int codepoint, int size, boolean bold, int ascent, int descent) {
        if (!valid) return null;
        try {
            LoadedGlyph glyph = loadGlyphBitmap(codepoint, size, bold, true);
            if (glyph == null) return null;

            float pad = Math.max(1.0f, size * 0.03125f);
            float minX = Math.min(0.0f, glyph.bitmapLeft);
            float maxX = Math.max(glyph.advanceX, glyph.bitmapLeft + glyph.width);
            int imageWidth = Math.max(1, (int) Math.ceil(maxX - minX + pad * 2.0f));
            var img = new BufferedImage(imageWidth, size, BufferedImage.TYPE_INT_ARGB);

            if (glyph.width <= 0 || glyph.rows <= 0) {
                return img;
            }

            BufferedImage bitmapImg = toBitmapImage(glyph);
            if (bitmapImg == null) return img;

            double metricHeight = Math.max(1.0d, ascent - descent);
            int baseline = (int) Math.rint(ascent * size / metricHeight);
            int drawX = Math.round(pad + glyph.bitmapLeft - minX);
            int drawY = baseline - glyph.bitmapTop;

            var g2d = img.createGraphics();
            g2d.drawImage(bitmapImg, drawX, drawY, null);
            g2d.dispose();
            return img;
        } catch (Throwable e) {
            return null;
        }
    }

    public synchronized float getTextGlyphAdvance(int codepoint, int size, boolean bold) {
        if (!valid) return -1.0f;
        LoadedGlyph glyph = loadGlyphBitmap(codepoint, size, bold, true);
        return glyph != null ? glyph.advanceX : -1.0f;
    }

    public synchronized float getTextGlyphOffsetX(int codepoint, int size, boolean bold) {
        if (!valid) return 0.0f;
        LoadedGlyph glyph = loadGlyphBitmap(codepoint, size, bold, true);
        if (glyph == null) return 0.0f;
        float pad = Math.max(1.0f, size * 0.03125f);
        return Math.min(0.0f, glyph.bitmapLeft) - pad;
    }

    public synchronized TextRunLayout layoutTextRun(String text, int size, boolean bold) {
        if (!valid || text == null || text.isEmpty()) return null;

        applyVariationCoordinates(bold);
        face.setPixelSizes(size, size);

        int codepointCount = text.codePointCount(0, text.length());
        float[] penPositions = new float[codepointCount];
        float penX = 0.0f;
        int previousGlyphIndex = 0;
        int glyphPosition = 0;

        for (int i = 0; i < text.length();) {
            int codepoint = text.codePointAt(i);
            int glyphIndex = face.getCharIndex(codepoint);
            if (glyphIndex == 0) {
                return null;
            }

            if (previousGlyphIndex != 0 && face.hasKerning()) {
                Kerning kerning = face.getKerning(previousGlyphIndex, glyphIndex);
                if (kerning != null) {
                    penX += kerning.getHorizontalKerning() / 64.0f;
                }
            }

            penPositions[glyphPosition++] = penX;

            LoadedGlyph glyph = tryLoadGlyphAtPixelSize(glyphIndex, size, true);
            if (glyph == null) {
                return null;
            }
            penX += glyph.advanceX;
            previousGlyphIndex = glyphIndex;
            i += Character.charCount(codepoint);
        }

        return new TextRunLayout(penPositions, penX);
    }

    public boolean hasGlyph(int codepoint) {
        return canRenderGlyph(codepoint);
    }

    private BufferedImage toBitmapImage(LoadedGlyph glyph) {
        if (glyph == null || glyph.width <= 0 || glyph.rows <= 0 || glyph.buffer == null) {
            return null;
        }

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
        return bitmapImg;
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

        String normalizedArch = normalizeArch(arch);
        if (normalizedArch == null) {
            Minemoticon.LOG.warn("[FreeType] Unsupported architecture: {}", arch);
            return false;
        }

        String platform = detectPlatform(os, normalizedArch);
        if (platform == null) {
            Minemoticon.LOG.warn("[FreeType] Unsupported operating system: {}", os);
            return false;
        }

        String libName = platform.startsWith("windows-") ? "freetype-jni.dll"
            : platform.startsWith("macos-") ? "libfreetype-jni.dylib" : "libfreetype-jni.so";

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

    private static String detectPlatform(String os, String arch) {
        if (os.contains("mac")) {
            return "macos-" + arch;
        }
        if (os.contains("win")) {
            return "windows-" + arch;
        }
        if (os.contains("linux")) {
            return "linux-" + arch;
        }
        return null;
    }

    private static String normalizeArch(String arch) {
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) {
            return "x64";
        }
        return null;
    }

    private LoadedGlyph loadGlyphBitmap(int codepoint, int size) {
        return loadGlyphBitmap(codepoint, size, false, false);
    }

    private LoadedGlyph loadGlyphBitmap(int codepoint, int size, boolean bold, boolean allowEmptyBitmap) {
        applyVariationCoordinates(bold);

        int glyphIndex = face.getCharIndex(codepoint);
        if (glyphIndex == 0) {
            return null;
        }

        LoadedGlyph glyph = tryLoadGlyphAtPixelSize(glyphIndex, size, allowEmptyBitmap);
        if (glyph != null) {
            return glyph;
        }

        for (int strikeIndex = 0; strikeIndex < MAX_STRIKE_PROBE; strikeIndex++) {
            glyph = tryLoadGlyphAtStrike(glyphIndex, strikeIndex, allowEmptyBitmap);
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

    private void applyVariationCoordinates(boolean bold) {
        float[] coords = bold && boldVariationCoordinates != null ? boldVariationCoordinates
            : regularVariationCoordinates;
        if (coords == null) {
            usingBoldVariation = false;
            return;
        }

        boolean targetBold = coords == boldVariationCoordinates;
        if (variationCoordinatesApplied && targetBold == usingBoldVariation) {
            return;
        }

        if (face.setVarDesignCoordinates(coords)) {
            variationCoordinatesApplied = true;
            usingBoldVariation = targetBold;
        }
    }

    private LoadedGlyph tryLoadGlyphAtPixelSize(int glyphIndex, int size, boolean allowEmptyBitmap) {
        face.setPixelSizes(size, size);
        return tryLoadCurrentGlyph(glyphIndex, allowEmptyBitmap);
    }

    private LoadedGlyph tryLoadGlyphAtStrike(int glyphIndex, int strikeIndex, boolean allowEmptyBitmap) {
        if (face.selectSize(strikeIndex)) {
            return null;
        }
        return tryLoadCurrentGlyph(glyphIndex, allowEmptyBitmap);
    }

    private LoadedGlyph tryLoadCurrentGlyph(int glyphIndex, boolean allowEmptyBitmap) {
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

        GlyphSlot.Advance advance = slot.getAdvance();
        float advanceX = advance != null ? advance.getX() / 64.0f : 0.0f;

        int rows = bitmap.getRows();
        int width = bitmap.getWidth();
        int pitch = bitmap.getPitch();
        ByteBuffer buffer = bitmap.getBuffer();
        if (rows <= 0 || width <= 0 || pitch == 0 || buffer == null) {
            return allowEmptyBitmap && advanceX > 0.0f
                ? new LoadedGlyph(0, 0, 0, bitmap.getPixelMode(), null, 0, 0, advanceX)
                : null;
        }

        return new LoadedGlyph(
            width,
            rows,
            pitch,
            bitmap.getPixelMode(),
            buffer,
            slot.getBitmapLeft(),
            slot.getBitmapTop(),
            advanceX);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
