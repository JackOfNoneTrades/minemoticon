package org.fentanylsolutions.minemoticon.colorfont;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

// Renders colored emoji glyphs from a COLR/CPAL OpenType font.
// Self-contained, no Minecraft dependencies -- can be moved to a library.
public class ColorFont {

    private final CmapParser cmap;
    private final ColrParser colr;
    private final CpalParser cpal;
    private final GlyfParser glyf; // null if no glyf table (CBDT-only fonts)
    private final CbdtParser cbdt; // null if no CBDT table
    private final int unitsPerEm;
    private final int ascent;
    private final int descent;

    private ColorFont(CmapParser cmap, ColrParser colr, CpalParser cpal, GlyfParser glyf, CbdtParser cbdt,
        int unitsPerEm, int ascent, int descent) {
        this.cmap = cmap;
        this.colr = colr;
        this.cpal = cpal;
        this.glyf = glyf;
        this.cbdt = cbdt;
        this.unitsPerEm = unitsPerEm;
        this.ascent = ascent;
        this.descent = descent;
    }

    public static ColorFont load(InputStream in) throws IOException {
        var reader = OTFTableReader.load(in);

        if (!reader.hasTable("cmap")) {
            throw new IOException("Font missing cmap table");
        }
        boolean hasGlyf = reader.hasTable("glyf") && reader.hasTable("loca");
        boolean hasCbdt = reader.hasTable("CBDT") && reader.hasTable("CBLC");

        if (!hasGlyf && !hasCbdt) {
            throw new IOException("Font has no renderable glyph data (needs glyf or CBDT)");
        }

        var head = new HeadParser(reader.getTable("head"));
        var maxp = new MaxpParser(reader.getTable("maxp"));
        var cmapTable = new CmapParser(reader.getTable("cmap"));

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

        if (reader.hasTable("hhea")) {
            head.parseHhea(reader.getTable("hhea"));
        }

        return new ColorFont(
            cmapTable,
            colrTable,
            cpalTable,
            glyfTable,
            cbdtTable,
            head.unitsPerEm,
            head.ascent,
            head.descent);
    }

    public boolean hasAnyColorGlyphs() {
        return colr.getLayerCount() > 0;
    }

    // Check if a multi-codepoint sequence can be rendered
    public boolean canRender(int[] codepoints) {
        if (codepoints.length == 1) return hasGlyph(codepoints[0]);

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
        if (glyphId == 0) return false;
        // Has CBDT bitmap, COLR layers, or actual glyf outline data
        if (cbdt != null && cbdt.hasBitmap(glyphId)) return true;
        if (colr.hasLayers(glyphId)) return true;
        return glyf != null && glyf.hasOutline(glyphId);
    }

    // Render a single-codepoint emoji to a BufferedImage at the given pixel size.
    public BufferedImage renderGlyph(int codepoint, int size) {
        int glyphId = cmap.getGlyphId(codepoint);
        if (glyphId == 0) return null;

        // Try CBDT bitmap first (pre-rendered, best quality)
        if (cbdt != null && cbdt.hasBitmap(glyphId)) {
            return scaleBitmap(cbdt.getBitmap(glyphId), size);
        }
        return renderGlyphById(glyphId, size);
    }

    // Render a multi-codepoint emoji (ZWJ sequences, flags, etc.).
    // Without GSUB ligature support, we can only render sequences where
    // the non-VS codepoints reduce to a single base codepoint.
    public BufferedImage renderGlyphs(int[] codepoints, int size) {
        if (codepoints.length == 1) {
            return renderGlyph(codepoints[0], size);
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
                    colors.add(Color.BLACK);
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
}
