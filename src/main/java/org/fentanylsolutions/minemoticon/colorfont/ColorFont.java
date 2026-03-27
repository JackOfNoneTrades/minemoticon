package org.fentanylsolutions.minemoticon.colorfont;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Renders colored emoji glyphs from a COLR/CPAL OpenType font.
// Self-contained, no Minecraft dependencies -- can be moved to a library.
public class ColorFont {

    private final CmapParser cmap;
    private final ColrParser colr;
    private final CpalParser cpal;
    private final GlyfParser glyf; // null if no glyf table
    private final CbdtParser cbdt; // null if no CBDT table
    private final SvgParser svg; // null if no SVG table
    private final org.fentanylsolutions.minemoticon.freetype.FreeTypeRenderer freetype; // null if FreeType unavailable
    private final Font shapingFont; // null if AWT couldn't load the font for GSUB shaping
    private final List<VariationAxis> variationAxes;
    private final int unitsPerEm;
    private final int ascent;
    private final int descent;
    private static final FontRenderContext SHAPING_CONTEXT = new FontRenderContext(new AffineTransform(), true, false);

    private ColorFont(CmapParser cmap, ColrParser colr, CpalParser cpal, GlyfParser glyf, CbdtParser cbdt,
        SvgParser svg, org.fentanylsolutions.minemoticon.freetype.FreeTypeRenderer freetype, Font shapingFont,
        List<VariationAxis> variationAxes, int unitsPerEm, int ascent, int descent) {
        this.cmap = cmap;
        this.colr = colr;
        this.cpal = cpal;
        this.glyf = glyf;
        this.cbdt = cbdt;
        this.svg = svg;
        this.freetype = freetype;
        this.shapingFont = shapingFont;
        this.variationAxes = variationAxes;
        this.unitsPerEm = unitsPerEm;
        this.ascent = ascent;
        this.descent = descent;
    }

    public static ColorFont load(InputStream in) throws IOException {
        return load(in, Collections.emptyMap());
    }

    public static ColorFont load(InputStream in, Map<String, Float> variationSettings) throws IOException {
        var reader = OTFTableReader.load(in);

        if (!reader.hasTable("cmap")) {
            throw new IOException("Font missing cmap table");
        }

        // FreeType can handle formats we don't parse directly (for example some
        // color-only fonts), so load it before rejecting the font as unsupported.
        var ftRenderer = org.fentanylsolutions.minemoticon.freetype.FreeTypeRenderer.load(reader.getFontData());

        boolean hasGlyf = reader.hasTable("glyf") && reader.hasTable("loca");
        boolean hasCbdt = reader.hasTable("CBDT") && reader.hasTable("CBLC");
        boolean hasSvg = reader.hasTable("SVG ");

        if (!hasGlyf && !hasCbdt && !hasSvg && ftRenderer == null) {
            throw new IOException("Font has no renderable glyph data (needs glyf, CBDT, SVG, or FreeType support)");
        }

        var head = new HeadParser(reader.getTable("head"));
        var maxp = new MaxpParser(reader.getTable("maxp"));
        var cmapTable = new CmapParser(reader.getTable("cmap"));

        if (reader.hasTable("hhea")) {
            head.parseHhea(reader.getTable("hhea"));
        }

        // glyf/loca for outline rendering
        GlyfParser glyfTable = null;
        if (hasGlyf) {
            var loca = new LocaParser(reader.getTable("loca"), maxp.numGlyphs, head.indexToLocFormat);
            glyfTable = new GlyfParser(reader.getTable("glyf"), loca);
        }

        // CBDT/CBLC for bitmap rendering
        CbdtParser cbdtTable = null;
        if (hasCbdt) {
            cbdtTable = new CbdtParser(reader.getTable("CBLC"), reader.getTable("CBDT"));
        }

        // COLR/CPAL for color layers (optional)
        ColrParser colrTable;
        CpalParser cpalTable;
        if (reader.hasTable("COLR") && reader.hasTable("CPAL")) {
            colrTable = new ColrParser(reader.getTable("COLR"));
            cpalTable = new CpalParser(reader.getTable("CPAL"));
        } else {
            colrTable = new ColrParser.Empty();
            cpalTable = new CpalParser.Empty();
        }

        // SVG table (optional, best quality for SVGinOT fonts)
        SvgParser svgTable = null;
        if (reader.hasTable("SVG ")) {
            svgTable = new SvgParser(reader.getTable("SVG "), head.unitsPerEm, head.ascent, head.descent);
        }

        List<VariationAxis> variationAxes = Collections.emptyList();
        if (reader.hasTable("fvar")) {
            variationAxes = new FvarParser(reader.getTable("fvar")).getAxes();
        }

        Font awtShapingFont = null;
        try {
            awtShapingFont = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(reader.getFontData()))
                .deriveFont((float) head.unitsPerEm);
            awtShapingFont = applyVariationSettings(awtShapingFont, variationAxes, variationSettings);
        } catch (Exception ignored) {}

        return new ColorFont(
            cmapTable,
            colrTable,
            cpalTable,
            glyfTable,
            cbdtTable,
            svgTable,
            ftRenderer,
            awtShapingFont,
            variationAxes,
            head.unitsPerEm,
            head.ascent,
            head.descent);
    }

    public List<VariationAxis> getVariationAxes() {
        return variationAxes;
    }

    public boolean hasAnyColorGlyphs() {
        if (colr.getLayerCount() > 0) return true;
        if (cbdt != null && cbdt.getGlyphCount() > 0) return true;
        if (svg != null && svg.getGlyphCount() > 0) return true;
        return false;
    }

    public String debugPreparedSvg(int codepoint) {
        if (svg == null) return null;
        int glyphId = cmap.getGlyphId(codepoint);
        if (glyphId == 0 || !svg.hasSvg(glyphId)) return null;
        return svg.debugPrepareSvg(glyphId);
    }

    // Check if a multi-codepoint sequence can be rendered
    public boolean canRender(int[] codepoints) {
        if (codepoints.length == 1) return hasGlyph(codepoints[0]);

        int shapedGlyphId = findRenderableSequenceGlyphId(codepoints);
        if (shapedGlyphId != 0) return true;

        // Filter to base codepoints
        var base = new java.util.ArrayList<Integer>();
        for (int cp : codepoints) {
            if (cp != 0xFE0F && cp != 0xFE0E && cp != 0x200D) base.add(cp);
        }
        // Single base after filtering? Can render.
        if (base.size() == 1) return hasGlyph(base.get(0));
        // Multi-base: need GSUB, which we don't have
        return false;
    }

    public boolean hasGlyph(int codepoint) {
        int glyphId = cmap.getGlyphId(codepoint);
        if (glyphId == 0) {
            // cmap miss -- FreeType might still have it via GSUB or other tables
            return freetype != null && freetype.canRenderGlyph(codepoint);
        }
        if (cbdt != null && cbdt.hasBitmap(glyphId)) return true;
        if (svg != null && svg.hasSvg(glyphId)) return true;
        if (colr.hasLayers(glyphId)) return true;
        if (glyf != null && glyf.hasOutline(glyphId)) return true;
        // Last resort: FreeType for COLRv1 and other formats we can't parse
        return freetype != null && freetype.canRenderGlyph(codepoint);
    }

    // Render a single-codepoint emoji to a BufferedImage at the given pixel size.
    public BufferedImage renderGlyph(int codepoint, int size) {
        int glyphId = cmap.getGlyphId(codepoint);

        // Priority: CBDT > SVG > COLR v0 > FreeType (COLRv1) > monochrome glyf
        BufferedImage direct = renderGlyphByIdAny(glyphId, size);
        if (direct != null) return direct;

        // FreeType fallback for COLRv1 and other formats we can't parse natively
        if (freetype != null) {
            var ftImg = freetype.renderGlyph(codepoint, size);
            if (ftImg != null) return ftImg;
        }

        // Monochrome glyf outlines as last resort
        if (glyphId != 0) {
            return renderGlyphById(glyphId, size);
        }

        return null;
    }

    // Render a multi-codepoint emoji (ZWJ sequences, flags, etc.).
    // Without GSUB ligature support, we can only render sequences where
    // the non-VS codepoints reduce to a single base codepoint.
    public BufferedImage renderGlyphs(int[] codepoints, int size) {
        if (codepoints.length == 1) {
            return renderGlyph(codepoints[0], size);
        }

        int shapedGlyphId = findRenderableSequenceGlyphId(codepoints);
        if (shapedGlyphId != 0) {
            BufferedImage shaped = renderGlyphByIdAny(shapedGlyphId, size);
            if (shaped != null) return shaped;
        }

        // Filter out variation selectors and ZWJ
        var base = new java.util.ArrayList<Integer>();
        for (int cp : codepoints) {
            if (cp != 0xFE0F && cp != 0xFE0E && cp != 0x200D) {
                base.add(cp);
            }
        }

        // If only one base codepoint remains (e.g. U+2603 + U+FE0F), render it
        if (base.size() == 1) {
            return renderGlyph(base.get(0), size);
        }

        // Multi-codepoint sequences (flags, ZWJ families, skin tones)
        // need GSUB ligature lookup which we don't support yet
        return null;
    }

    public BufferedImage renderTextGlyph(int codepoint, int size) {
        if (shapingFont == null) return renderGlyph(codepoint, size);

        GlyphVector glyphVector = layoutSingleGlyphVector(codepoint);
        if (glyphVector == null) return null;

        Shape outline = glyphVector.getGlyphOutline(0);
        Rectangle2D bounds = outline.getBounds2D();
        float advance = getTextGlyphAdvance(codepoint, size);
        if ((bounds == null || bounds.isEmpty()) && advance <= 0.0f) {
            return null;
        }

        float pad = Math.max(1.0f, size * 0.03125f);
        double metricHeight = Math.max(1.0d, this.ascent - this.descent);
        double scale = Math.max(0.01d, (size - pad * 2.0d) / metricHeight);

        double minX = bounds != null ? Math.min(0.0d, bounds.getX()) : 0.0d;
        double maxX = bounds != null && !bounds.isEmpty() ? Math.max(
            bounds.getMaxX(),
            glyphVector.getGlyphPosition(1)
                .getX())
            : glyphVector.getGlyphPosition(1)
                .getX();
        int width = Math.max(1, (int) Math.ceil((maxX - minX) * scale + pad * 2.0d));

        BufferedImage result = new BufferedImage(width, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = result.createGraphics();
        applyQualityHints(g2d);
        g2d.setColor(Color.WHITE);

        var transform = new AffineTransform();
        double baseline = Math.rint(pad + this.ascent * scale);
        double translateX = Math.rint(pad - minX * scale);
        transform.translate(translateX, baseline);
        transform.scale(scale, scale);
        Shape scaledOutline = transform.createTransformedShape(outline);
        g2d.fill(scaledOutline);
        g2d.dispose();
        return result;
    }

    public float getTextGlyphAdvance(int codepoint, int size) {
        if (shapingFont == null) return -1.0f;

        GlyphVector glyphVector = layoutSingleGlyphVector(codepoint);
        if (glyphVector == null) return -1.0f;

        float pad = Math.max(1.0f, size * 0.03125f);
        double metricHeight = Math.max(1.0d, this.ascent - this.descent);
        double scale = Math.max(0.01d, (size - pad * 2.0d) / metricHeight);
        return (float) (glyphVector.getGlyphMetrics(0)
            .getAdvanceX() * scale);
    }

    public float getTextGlyphOffsetX(int codepoint, int size) {
        if (shapingFont == null) return 0.0f;

        GlyphVector glyphVector = layoutSingleGlyphVector(codepoint);
        if (glyphVector == null) return 0.0f;

        Shape outline = glyphVector.getGlyphOutline(0);
        Rectangle2D bounds = outline.getBounds2D();
        if (bounds == null || bounds.isEmpty()) {
            return 0.0f;
        }

        float pad = Math.max(1.0f, size * 0.03125f);
        double metricHeight = Math.max(1.0d, this.ascent - this.descent);
        double scale = Math.max(0.01d, (size - pad * 2.0d) / metricHeight);
        double minX = Math.min(0.0d, bounds.getX());
        double translateX = Math.rint(pad - minX * scale);
        return (float) (-translateX);
    }

    public org.fentanylsolutions.minemoticon.font.TextRunLayout layoutTextRun(String text, int size) {
        if (shapingFont == null || text == null || text.isEmpty()) return null;

        GlyphVector glyphVector = shapingFont
            .layoutGlyphVector(SHAPING_CONTEXT, text.toCharArray(), 0, text.length(), Font.LAYOUT_LEFT_TO_RIGHT);
        int codepointCount = text.codePointCount(0, text.length());
        if (glyphVector.getNumGlyphs() != codepointCount) {
            return null;
        }

        int[] charStarts = new int[codepointCount];
        int cpIndex = 0;
        for (int i = 0; i < text.length();) {
            charStarts[cpIndex++] = i;
            i += Character.charCount(text.codePointAt(i));
        }

        int[] glyphByChar = new int[text.length()];
        java.util.Arrays.fill(glyphByChar, -1);
        for (int glyphIndex = 0; glyphIndex < glyphVector.getNumGlyphs(); glyphIndex++) {
            int charIndex = glyphVector.getGlyphCharIndex(glyphIndex);
            if (charIndex >= 0 && charIndex < glyphByChar.length && glyphByChar[charIndex] == -1) {
                glyphByChar[charIndex] = glyphIndex;
            }
        }

        float pad = Math.max(1.0f, size * 0.03125f);
        double metricHeight = Math.max(1.0d, this.ascent - this.descent);
        double scale = Math.max(0.01d, (size - pad * 2.0d) / metricHeight);
        float[] penPositions = new float[codepointCount];
        int previousGlyph = -1;

        for (int i = 0; i < codepointCount; i++) {
            int glyphIndex = glyphByChar[charStarts[i]];
            if (glyphIndex < 0 || glyphIndex == previousGlyph) {
                return null;
            }
            penPositions[i] = (float) (glyphVector.getGlyphPosition(glyphIndex)
                .getX() * scale);
            previousGlyph = glyphIndex;
        }

        float totalAdvance = (float) (glyphVector.getGlyphPosition(glyphVector.getNumGlyphs())
            .getX() * scale);
        return new org.fentanylsolutions.minemoticon.font.TextRunLayout(penPositions, totalAdvance);
    }

    private GlyphVector layoutSingleGlyphVector(int codepoint) {
        if (shapingFont == null) return null;

        String text = new String(Character.toChars(codepoint));
        GlyphVector glyphVector = shapingFont
            .layoutGlyphVector(SHAPING_CONTEXT, text.toCharArray(), 0, text.length(), Font.LAYOUT_LEFT_TO_RIGHT);

        if (glyphVector.getNumGlyphs() == 0) {
            return null;
        }

        int glyphCode = glyphVector.getGlyphCode(0);
        int missingGlyph = shapingFont.getMissingGlyphCode();
        if (glyphCode == missingGlyph && codepoint != ' ') {
            return null;
        }
        return glyphVector;
    }

    private int findRenderableSequenceGlyphId(int[] codepoints) {
        if (shapingFont == null || codepoints.length == 0) return 0;

        String text = new String(codepoints, 0, codepoints.length);
        if (text.isEmpty()) return 0;

        GlyphVector glyphVector = shapingFont
            .layoutGlyphVector(SHAPING_CONTEXT, text.toCharArray(), 0, text.length(), Font.LAYOUT_LEFT_TO_RIGHT);

        int missingGlyph = shapingFont.getMissingGlyphCode();
        int candidate = 0;

        for (int i = 0; i < glyphVector.getNumGlyphs(); i++) {
            int glyphId = glyphVector.getGlyphCode(i);
            if (glyphId == 0 || glyphId == missingGlyph) {
                continue;
            }

            double advance = glyphVector.getGlyphPosition(i + 1)
                .getX()
                - glyphVector.getGlyphPosition(i)
                    .getX();
            boolean renderable = hasRenderableGlyphId(glyphId);

            if (!renderable && Math.abs(advance) < 0.01d) {
                continue;
            }
            if (!renderable) {
                return 0;
            }
            if (candidate != 0 && candidate != glyphId) {
                return 0;
            }
            candidate = glyphId;
        }

        return candidate;
    }

    private boolean hasRenderableGlyphId(int glyphId) {
        if (glyphId == 0) return false;
        if (cbdt != null && cbdt.hasBitmap(glyphId)) return true;
        if (svg != null && svg.hasSvg(glyphId)) return true;
        if (colr.hasLayers(glyphId)) return true;
        return glyf != null && glyf.hasOutline(glyphId);
    }

    private BufferedImage renderGlyphByIdAny(int glyphId, int size) {
        if (glyphId == 0) return null;
        if (cbdt != null && cbdt.hasBitmap(glyphId)) {
            return scaleBitmap(cbdt.getBitmap(glyphId), size);
        }
        if (svg != null && svg.hasSvg(glyphId)) {
            return svg.renderGlyph(glyphId, size);
        }
        if (colr.hasLayers(glyphId)) {
            return renderGlyphById(glyphId, size);
        }
        if (glyf != null && glyf.hasOutline(glyphId)) {
            return renderGlyphById(glyphId, size);
        }
        return null;
    }

    private BufferedImage renderGlyphById(int glyphId, int size) {
        // Render at 4x for high quality, downsample to target
        int renderSize = size * 4;
        var img = new BufferedImage(renderSize, renderSize, BufferedImage.TYPE_INT_ARGB);
        var g2d = img.createGraphics();
        applyQualityHints(g2d);

        var outlines = new ArrayList<GeneralPath>();
        var colors = new ArrayList<Color>();
        Rectangle2D bounds = collectGlyphLayers(glyphId, outlines, colors);
        if (bounds == null || bounds.isEmpty() || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            g2d.dispose();
            return null;
        }

        float pad = renderSize * 0.06f;
        double availW = renderSize - pad * 2.0;
        double availH = renderSize - pad * 2.0;
        double scale = Math.min(availW / bounds.getWidth(), availH / bounds.getHeight());

        var transform = new AffineTransform();
        double xShift = pad + (availW - bounds.getWidth() * scale) / 2.0 - bounds.getX() * scale;
        double yShift = pad + (availH - bounds.getHeight() * scale) / 2.0 + bounds.getMaxY() * scale;
        transform.translate(xShift, yShift);
        transform.scale(scale, -scale);

        for (int i = 0; i < outlines.size(); i++) {
            var scaled = transform.createTransformedShape(outlines.get(i));
            g2d.setColor(colors.get(i));
            g2d.fill(scaled);
        }

        g2d.dispose();

        // Downsample to target size with high quality interpolation
        var result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var rg = result.createGraphics();
        applyQualityHints(rg);
        rg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        rg.drawImage(img, 0, 0, size, size, null);
        rg.dispose();
        return result;
    }

    private BufferedImage scaleBitmap(BufferedImage src, int size) {
        var result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return result;
    }

    private Rectangle2D collectGlyphLayers(int glyphId, List<GeneralPath> outlines, List<Color> colors) {
        if (glyf == null) return null;
        Rectangle2D bounds = null;
        var layers = colr.getLayers(glyphId);
        if (layers != null) {
            for (var layer : layers) {
                GeneralPath outline = glyf.getOutline(layer.glyphId);
                if (outline == null || outline.getCurrentPoint() == null) continue;
                Rectangle2D outlineBounds = outline.getBounds2D();
                if (outlineBounds.isEmpty()) continue;
                outlines.add(outline);
                colors.add(new Color(cpal.getColor(layer.paletteIndex), true));
                bounds = unionBounds(bounds, outlineBounds);
            }
        } else {
            GeneralPath outline = glyf.getOutline(glyphId);
            if (outline != null && outline.getCurrentPoint() != null) {
                Rectangle2D outlineBounds = outline.getBounds2D();
                if (!outlineBounds.isEmpty()) {
                    outlines.add(outline);
                    colors.add(Color.WHITE);
                    bounds = unionBounds(bounds, outlineBounds);
                }
            }
        }
        return bounds;
    }

    private Rectangle2D unionBounds(Rectangle2D current, Rectangle2D next) {
        if (current == null) {
            return (Rectangle2D) next.clone();
        }
        Rectangle2D.union(current, next, current);
        return current;
    }

    private void applyQualityHints(Graphics2D g2d) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    public static Font applyVariationSettings(Font baseFont, List<VariationAxis> variationAxes,
        Map<String, Float> variationSettings) {
        if (baseFont == null || variationAxes == null
            || variationAxes.isEmpty()
            || variationSettings == null
            || variationSettings.isEmpty()) {
            return baseFont;
        }

        Map<String, VariationAxis> axisByTag = new HashMap<>();
        for (VariationAxis axis : variationAxes) {
            axisByTag.put(axis.getTag(), axis);
        }

        Map<TextAttribute, Object> attrs = new HashMap<>(baseFont.getAttributes());
        boolean changed = false;

        VariationAxis weightAxis = axisByTag.get("wght");
        if (weightAxis != null && variationSettings.containsKey("wght")) {
            float value = clamp(variationSettings.get("wght"), weightAxis.getMinValue(), weightAxis.getMaxValue());
            attrs.put(TextAttribute.WEIGHT, clamp(value / 400.0f, 0.5f, 2.75f));
            changed = true;
        }

        VariationAxis widthAxis = axisByTag.get("wdth");
        if (widthAxis != null && variationSettings.containsKey("wdth")) {
            float value = clamp(variationSettings.get("wdth"), widthAxis.getMinValue(), widthAxis.getMaxValue());
            attrs.put(TextAttribute.WIDTH, clamp(value / 100.0f, 0.5f, 2.0f));
            changed = true;
        }

        attrs.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        changed = true;

        float posture = TextAttribute.POSTURE_REGULAR;
        boolean postureChanged = false;

        VariationAxis italicAxis = axisByTag.get("ital");
        if (italicAxis != null && variationSettings.containsKey("ital")) {
            float value = clamp(variationSettings.get("ital"), italicAxis.getMinValue(), italicAxis.getMaxValue());
            posture = value >= 0.5f ? TextAttribute.POSTURE_OBLIQUE : TextAttribute.POSTURE_REGULAR;
            postureChanged = true;
        }

        VariationAxis slantAxis = axisByTag.get("slnt");
        if (slantAxis != null && variationSettings.containsKey("slnt")) {
            float value = clamp(variationSettings.get("slnt"), slantAxis.getMinValue(), slantAxis.getMaxValue());
            posture = Math.max(posture, clamp(Math.abs(value) / 45.0f, 0.0f, TextAttribute.POSTURE_OBLIQUE));
            postureChanged = true;
        }

        if (postureChanged) {
            attrs.put(TextAttribute.POSTURE, posture);
            changed = true;
        }

        return changed ? baseFont.deriveFont(attrs) : baseFont;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
