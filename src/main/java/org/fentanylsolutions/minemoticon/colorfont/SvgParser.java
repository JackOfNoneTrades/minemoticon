package org.fentanylsolutions.minemoticon.colorfont;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// Parses the SVG table from an OpenType font and renders glyphs via JSVG.
public class SvgParser {

    // Maps glyph ID -> SVG document XML string
    private final Map<Integer, String> svgDocuments = new HashMap<>();
    private final int emSquare;
    private final int ascent;
    private final int descent;

    public SvgParser(ByteBuffer svg, int unitsPerEm, int ascent, int descent) {
        this.emSquare = unitsPerEm;
        this.ascent = ascent;
        this.descent = descent;
        int base = svg.position();

        // SVG table header
        int version = svg.getShort() & 0xFFFF;
        int svgDocListOffset = svg.getInt();

        // SVG Document List
        svg.position(base + svgDocListOffset);
        int numEntries = svg.getShort() & 0xFFFF;

        for (int i = 0; i < numEntries; i++) {
            int startGlyphID = svg.getShort() & 0xFFFF;
            int endGlyphID = svg.getShort() & 0xFFFF;
            int svgDocOffset = svg.getInt();
            int svgDocLength = svg.getInt();

            // Read SVG XML
            int docPos = base + svgDocListOffset + svgDocOffset;
            if (docPos + svgDocLength <= svg.limit()) {
                byte[] xmlBytes = new byte[svgDocLength];
                int savedPos = svg.position();
                svg.position(docPos);
                svg.get(xmlBytes);
                svg.position(savedPos);
                String xmlStr = new String(xmlBytes, StandardCharsets.UTF_8);

                // The same SVG doc can cover multiple glyph IDs
                for (int gid = startGlyphID; gid <= endGlyphID; gid++) {
                    svgDocuments.put(gid, xmlStr);
                }
            }
        }
    }

    public boolean hasSvg(int glyphId) {
        return svgDocuments.containsKey(glyphId);
    }

    public String getSvg(int glyphId) {
        return svgDocuments.get(glyphId);
    }

    public int getGlyphCount() {
        return svgDocuments.size();
    }

    // Render a glyph's SVG to a BufferedImage using JSVG
    public BufferedImage renderGlyph(int glyphId, int size) {
        String svgXml = svgDocuments.get(glyphId);
        if (svgXml == null) return null;

        try {
            // Strip the font-specific transforms and render the raw 36x36 emoji SVG.
            // Font SVGs wrap content in: <g transform="translate(...) scale(S)">
            // We remove those transforms and render the base content directly.
            String svgToRender = svgXml;

            // Remove the font coordinate transforms, keep the inner content
            svgToRender = svgToRender.replaceAll("transform=\"[^\"]*translate[^\"]*scale[^\"]*\"", "");

            // Inject a viewBox for the base emoji coordinate space (typically 36x36)
            if (!svgToRender.contains("viewBox")) {
                svgToRender = svgToRender.replaceFirst("<svg", "<svg viewBox=\"0 0 36 36\"");
            }

            var parser = new com.github.weisj.jsvg.parser.SVGLoader();
            var doc = parser.load(
                new java.io.ByteArrayInputStream(svgToRender.getBytes(StandardCharsets.UTF_8)),
                null,
                com.github.weisj.jsvg.parser.LoaderContext.createDefault());
            if (doc == null) return null;

            var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            var g2d = img.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            var viewBox = new com.github.weisj.jsvg.view.ViewBox(0, 0, size, size);
            doc.render((java.awt.Component) null, g2d, viewBox);
            g2d.dispose();
            return img;
        } catch (Exception e) {
            return null;
        }
    }
}
