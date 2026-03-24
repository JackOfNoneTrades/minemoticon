package org.fentanylsolutions.minemoticon.colorfont;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

// Renders colored emoji glyphs from a COLR/CPAL OpenType font.
// Self-contained, no Minecraft dependencies -- can be moved to a library.
public class ColorFont {

    private final CmapParser cmap;
    private final ColrParser colr;
    private final CpalParser cpal;
    private final GlyfParser glyf;
    private final int unitsPerEm;

    private ColorFont(CmapParser cmap, ColrParser colr, CpalParser cpal, GlyfParser glyf, int unitsPerEm) {
        this.cmap = cmap;
        this.colr = colr;
        this.cpal = cpal;
        this.glyf = glyf;
        this.unitsPerEm = unitsPerEm;
    }

    public static ColorFont load(InputStream in) throws IOException {
        var reader = OTFTableReader.load(in);

        if (!reader.hasTable("COLR") || !reader.hasTable("CPAL")) {
            throw new IOException("Font does not contain COLR/CPAL tables");
        }

        var head = new HeadParser(reader.getTable("head"));
        var maxp = new MaxpParser(reader.getTable("maxp"));
        var loca = new LocaParser(reader.getTable("loca"), maxp.numGlyphs, head.indexToLocFormat);
        var cmapTable = new CmapParser(reader.getTable("cmap"));
        var colrTable = new ColrParser(reader.getTable("COLR"));
        var cpalTable = new CpalParser(reader.getTable("CPAL"));
        var glyfTable = new GlyfParser(reader.getTable("glyf"), loca);

        return new ColorFont(cmapTable, colrTable, cpalTable, glyfTable, head.unitsPerEm);
    }

    public boolean hasGlyph(int codepoint) {
        int glyphId = cmap.getGlyphId(codepoint);
        return glyphId != 0 && colr.hasLayers(glyphId);
    }

    // Render a single-codepoint emoji to a BufferedImage at the given pixel size.
    public BufferedImage renderGlyph(int codepoint, int size) {
        int glyphId = cmap.getGlyphId(codepoint);
        if (glyphId == 0) return null;
        return renderGlyphById(glyphId, size);
    }

    // Render a multi-codepoint emoji (ZWJ sequences, flags, etc.).
    // Tries the combined sequence first via ligature lookup; falls back to first codepoint.
    public BufferedImage renderGlyphs(int[] codepoints, int size) {
        // For COLR fonts, multi-codepoint sequences are typically handled via GSUB ligatures
        // that map the sequence to a single glyph. Try the first codepoint as a simple fallback.
        // A full implementation would parse GSUB, but most single-codepoint emojis work without it.
        if (codepoints.length == 1) {
            return renderGlyph(codepoints[0], size);
        }

        // Try each codepoint -- first one that has COLR layers wins
        for (int cp : codepoints) {
            if (cp == 0xFE0F || cp == 0x200D) continue; // skip variation selector and ZWJ
            int gid = cmap.getGlyphId(cp);
            if (gid != 0 && colr.hasLayers(gid)) {
                return renderGlyphById(gid, size);
            }
        }
        return null;
    }

    private BufferedImage renderGlyphById(int glyphId, int size) {
        var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        float scale = (float) size / unitsPerEm;

        // Transform: scale from font units to pixels, flip Y axis (font Y goes up, image Y goes down)
        var transform = new AffineTransform();
        transform.scale(scale, -scale);
        transform.translate(0, -unitsPerEm);

        var layers = colr.getLayers(glyphId);
        if (layers != null) {
            for (var layer : layers) {
                GeneralPath outline = glyf.getOutline(layer.glyphId);
                if (outline == null) continue;
                GeneralPath scaled = (GeneralPath) outline.clone();
                scaled.transform(transform);
                int argb = cpal.getColor(layer.paletteIndex);
                g2d.setColor(new Color(argb, true));
                g2d.fill(scaled);
            }
        } else {
            // No COLR entry -- render as monochrome fallback
            GeneralPath outline = glyf.getOutline(glyphId);
            if (outline != null) {
                GeneralPath scaled = (GeneralPath) outline.clone();
                scaled.transform(transform);
                g2d.setColor(Color.BLACK);
                g2d.fill(scaled);
            }
        }

        g2d.dispose();
        return img;
    }
}
