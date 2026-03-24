package org.fentanylsolutions.minemoticon.colorfont;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.imageio.ImageIO;

// Parses the SVG table from an OpenType font and renders glyphs via JSVG.
public class SvgParser {

    private static final int MAX_SVG_DOC_SIZE = 32 * 1024 * 1024;
    private static final double OUTPUT_PAD_RATIO = 0.06;
    private static final Pattern SVG_REF_PATTERN = Pattern
        .compile("url\\(#([^\\)]+)\\)|(?:xlink:href|href)=\"#([^\"]+)\"");
    private static final String[] IMAGE_MAGICK_CANDIDATES = { "/opt/homebrew/bin/magick", "/usr/local/bin/magick",
        "magick" };
    private static volatile String imageMagickCommand;
    private static volatile boolean imageMagickChecked;

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

            // Large shared SVG documents are common in Noto Color Emoji.
            int docPos = base + svgDocListOffset + svgDocOffset;
            if (svgDocLength <= 0 || svgDocLength > MAX_SVG_DOC_SIZE) continue;
            if (docPos + svgDocLength <= svg.limit()) {
                byte[] xmlBytes = new byte[svgDocLength];
                int savedPos = svg.position();
                svg.position(docPos);
                svg.get(xmlBytes);
                svg.position(savedPos);
                String xmlStr = decodeSvgDocument(xmlBytes);
                if (xmlStr == null || xmlStr.isEmpty()) continue;

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

    public String debugPrepareSvg(int glyphId) {
        String svgXml = svgDocuments.get(glyphId);
        if (svgXml == null) return null;
        return prepareSvg(svgXml, glyphId);
    }

    public int getGlyphCount() {
        return svgDocuments.size();
    }

    // Render a glyph's SVG to a BufferedImage using JSVG
    public BufferedImage renderGlyph(int glyphId, int size) {
        String svgXml = svgDocuments.get(glyphId);
        if (svgXml == null) return null;

        try {
            String svgToRender = prepareSvg(svgXml, glyphId);
            BufferedImage img = renderGlyphWithJsvg(svgToRender, size);
            if (isUsableImage(img)) {
                return normalizeRenderedGlyph(img, size);
            }

            BufferedImage magickImg = renderGlyphWithImageMagick(svgToRender, size);
            if (isUsableImage(magickImg)) {
                return normalizeRenderedGlyph(magickImg, size);
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    private String prepareSvg(String svgXml, int glyphId) {
        // Check if this is a multi-glyph document (like Noto) with <g id="glyphN"> elements
        String glyphTag = "id=\"glyph" + glyphId + "\"";
        if (svgXml.contains(glyphTag)) {
            // Extract the specific glyph group and wrap in a standalone SVG
            String extracted = extractGlyphGroup(svgXml, glyphId);
            if (extracted != null) {
                // Noto glyph art often extends past the nominal em square on the right
                // (tears, hands, confetti, etc), so give the extracted glyph some extra
                // horizontal room and trim transparent padding after rasterization.
                String defs = extractReferencedDefs(svgXml, extracted);
                int leftPad = Math.max(32, emSquare / 16);
                int rightPad = Math.max(128, emSquare / 4);
                return "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" "
                    + "viewBox=\""
                    + (-leftPad)
                    + " "
                    + (-ascent)
                    + " "
                    + (emSquare + leftPad + rightPad)
                    + " "
                    + (ascent - descent)
                    + "\">"
                    + defs
                    + extracted
                    + "</svg>";
            }
        }

        // Twemoji-style: standalone SVG per glyph with font transforms
        String result = svgXml;
        // Strip font coordinate transforms (translate + scale)
        result = result.replaceAll("transform=\"[^\"]*translate[^\"]*scale[^\"]*\"", "");
        // Inject viewBox for the raw emoji coordinate space
        if (!result.contains("viewBox")) {
            result = result.replaceFirst("<svg", "<svg viewBox=\"0 0 36 36\"");
        }
        return result;
    }

    private String extractGlyphGroup(String svgXml, int glyphId) {
        String startTag = "<g id=\"glyph" + glyphId + "\">";
        int start = svgXml.indexOf(startTag);
        if (start < 0) {
            // Try without closing >
            startTag = "<g id=\"glyph" + glyphId + "\"";
            start = svgXml.indexOf(startTag);
            if (start < 0) return null;
            // Find the actual >
            int tagEnd = svgXml.indexOf('>', start);
            if (tagEnd < 0) return null;
            startTag = svgXml.substring(start, tagEnd + 1);
        }

        // Find matching </g>
        int depth = 1;
        int pos = start + startTag.length();
        while (pos < svgXml.length() && depth > 0) {
            int nextOpen = svgXml.indexOf("<g", pos);
            int nextClose = svgXml.indexOf("</g>", pos);
            if (nextClose < 0) break;
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                pos = nextOpen + 2;
            } else {
                depth--;
                if (depth == 0) {
                    return svgXml.substring(start, nextClose + 4);
                }
                pos = nextClose + 4;
            }
        }
        return null;
    }

    private String extractDefs(String svgXml) {
        int defsStart = svgXml.indexOf("<defs>");
        if (defsStart < 0) defsStart = svgXml.indexOf("<defs ");
        if (defsStart < 0) return "";
        int defsEnd = svgXml.indexOf("</defs>", defsStart);
        if (defsEnd < 0) return "";
        return svgXml.substring(defsStart, defsEnd + 7);
    }

    private String decodeSvgDocument(byte[] xmlBytes) {
        try {
            if (xmlBytes.length >= 2 && (xmlBytes[0] & 0xFF) == 0x1F && (xmlBytes[1] & 0xFF) == 0x8B) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(xmlBytes));
                    var out = new ByteArrayOutputStream(xmlBytes.length * 2)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = gzip.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            }
            return new String(xmlBytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private String extractReferencedDefs(String svgXml, String svgFragment) {
        Set<String> referencedIds = findReferencedIds(svgFragment);
        if (referencedIds.isEmpty()) {
            return "";
        }

        var queue = new ArrayDeque<>(referencedIds);
        var visited = new LinkedHashSet<String>();
        var extractedDefs = new StringBuilder();

        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!visited.add(id)) {
                continue;
            }

            String definition = extractElementById(svgXml, id);
            if (definition == null) {
                continue;
            }

            extractedDefs.append(definition);
            for (String nestedId : findReferencedIds(definition)) {
                if (!visited.contains(nestedId)) {
                    queue.addLast(nestedId);
                }
            }
        }

        if (extractedDefs.length() == 0) {
            return "";
        }

        return "<defs>" + extractedDefs + "</defs>";
    }

    private Set<String> findReferencedIds(String svgFragment) {
        var ids = new LinkedHashSet<String>();
        Matcher matcher = SVG_REF_PATTERN.matcher(svgFragment);
        while (matcher.find()) {
            String id = matcher.group(1);
            if (id == null) {
                id = matcher.group(2);
            }
            if (id != null && !id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private String extractElementById(String svgXml, String id) {
        int idPos = svgXml.indexOf("id=\"" + id + "\"");
        if (idPos < 0) {
            return null;
        }

        int start = svgXml.lastIndexOf('<', idPos);
        if (start < 0 || start + 1 >= svgXml.length()) {
            return null;
        }
        if (svgXml.startsWith("</", start)) {
            return null;
        }

        int nameStart = start + 1;
        while (nameStart < svgXml.length() && Character.isWhitespace(svgXml.charAt(nameStart))) {
            nameStart++;
        }
        int nameEnd = nameStart;
        while (nameEnd < svgXml.length()) {
            char ch = svgXml.charAt(nameEnd);
            if (Character.isWhitespace(ch) || ch == '>' || ch == '/') {
                break;
            }
            nameEnd++;
        }
        if (nameEnd <= nameStart) {
            return null;
        }

        String tagName = svgXml.substring(nameStart, nameEnd);
        int openEnd = svgXml.indexOf('>', nameEnd);
        if (openEnd < 0) {
            return null;
        }
        if (svgXml.charAt(openEnd - 1) == '/') {
            return svgXml.substring(start, openEnd + 1);
        }

        int depth = 1;
        int pos = openEnd + 1;
        while (depth > 0 && pos < svgXml.length()) {
            int nextOpen = svgXml.indexOf("<" + tagName, pos);
            int nextClose = svgXml.indexOf("</" + tagName + ">", pos);
            if (nextClose < 0) {
                return null;
            }
            if (nextOpen >= 0 && nextOpen < nextClose) {
                int nestedOpenEnd = svgXml.indexOf('>', nextOpen);
                if (nestedOpenEnd < 0) {
                    return null;
                }
                if (svgXml.charAt(nestedOpenEnd - 1) != '/') {
                    depth++;
                }
                pos = nestedOpenEnd + 1;
            } else {
                depth--;
                pos = nextClose + tagName.length() + 3;
                if (depth == 0) {
                    return svgXml.substring(start, pos);
                }
            }
        }

        return null;
    }

    private BufferedImage renderGlyphWithJsvg(String svgToRender, int size) {
        var loaderContext = com.github.weisj.jsvg.parser.LoaderContext.builder()
            .documentLimits(new com.github.weisj.jsvg.parser.DocumentLimits(100, 100, 500000))
            .build();
        var parser = new com.github.weisj.jsvg.parser.SVGLoader();
        var doc = parser
            .load(new ByteArrayInputStream(svgToRender.getBytes(StandardCharsets.UTF_8)), null, loaderContext);
        if (doc == null) return null;

        var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g2d = img.createGraphics();
        applyQualityHints(g2d);
        var viewBox = new com.github.weisj.jsvg.view.ViewBox(0, 0, size, size);
        doc.render((java.awt.Component) null, g2d, viewBox);
        g2d.dispose();
        return img;
    }

    private BufferedImage renderGlyphWithImageMagick(String svgToRender, int size) {
        String magick = findImageMagickCommand();
        if (magick == null) return null;

        File svgFile = null;
        File pngFile = null;
        try {
            svgFile = File.createTempFile("minemoticon-svg-", ".svg");
            pngFile = File.createTempFile("minemoticon-svg-", ".png");
            Files.write(svgFile.toPath(), svgToRender.getBytes(StandardCharsets.UTF_8));

            var process = new ProcessBuilder(
                magick,
                "-background",
                "none",
                svgFile.getAbsolutePath(),
                pngFile.getAbsolutePath()).redirectErrorStream(true)
                    .start();

            // Drain ImageMagick output so the process can't block on a full pipe.
            try (var ignored = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                while (ignored.read(buffer) != -1) {}
            }

            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || !pngFile.isFile()) {
                return null;
            }

            BufferedImage img = ImageIO.read(pngFile);
            if (img == null) return null;
            return fitToSquare(img, size);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            if (svgFile != null) svgFile.delete();
            if (pngFile != null) pngFile.delete();
        }
    }

    private String findImageMagickCommand() {
        if (imageMagickChecked) {
            return imageMagickCommand;
        }
        synchronized (SvgParser.class) {
            if (imageMagickChecked) {
                return imageMagickCommand;
            }
            for (String candidate : IMAGE_MAGICK_CANDIDATES) {
                try {
                    var process = new ProcessBuilder(candidate, "-version").redirectErrorStream(true)
                        .start();
                    try (var ignored = process.getInputStream()) {
                        byte[] buffer = new byte[512];
                        while (ignored.read(buffer) != -1) {}
                    }
                    if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) {
                        imageMagickCommand = candidate;
                        imageMagickChecked = true;
                        return candidate;
                    }
                    process.destroyForcibly();
                } catch (InterruptedException ignored) {
                    Thread.currentThread()
                        .interrupt();
                } catch (IOException ignored) {}
            }
            imageMagickChecked = true;
            return null;
        }
    }

    private BufferedImage fitToSquare(BufferedImage src, int size) {
        if (src.getWidth() == size && src.getHeight() == size) {
            return src;
        }
        var result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = result.createGraphics();
        applyQualityHints(g);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        double scale = Math.min(size / (double) src.getWidth(), size / (double) src.getHeight());
        int drawW = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int drawH = Math.max(1, (int) Math.round(src.getHeight() * scale));
        int drawX = (size - drawW) / 2;
        int drawY = (size - drawH) / 2;
        g.drawImage(src, drawX, drawY, drawW, drawH, null);
        g.dispose();
        return result;
    }

    private boolean isUsableImage(BufferedImage img) {
        if (img == null) return false;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private BufferedImage normalizeRenderedGlyph(BufferedImage src, int size) {
        int[] bounds = computeVisibleBounds(src);
        if (bounds == null) {
            return null;
        }

        var result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = result.createGraphics();
        applyQualityHints(g);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        double pad = size * OUTPUT_PAD_RATIO;
        double availW = size - pad * 2.0;
        double availH = size - pad * 2.0;
        double scale = Math.min(availW / bounds[2], availH / bounds[3]);
        int drawW = Math.max(1, (int) Math.round(bounds[2] * scale));
        int drawH = Math.max(1, (int) Math.round(bounds[3] * scale));
        int drawX = (int) Math.round((size - drawW) / 2.0);
        int drawY = (int) Math.round((size - drawH) / 2.0);

        g.drawImage(
            src,
            drawX,
            drawY,
            drawX + drawW,
            drawY + drawH,
            bounds[0],
            bounds[1],
            bounds[0] + bounds[2],
            bounds[1] + bounds[3],
            null);
        g.dispose();
        return result;
    }

    private int[] computeVisibleBounds(BufferedImage glyph) {
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

        int pad = Math.max(1, width / 64);
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(width - 1, maxX + pad);
        maxY = Math.min(height - 1, maxY + pad);
        return new int[] { minX, minY, maxX - minX + 1, maxY - minY + 1 };
    }

    private void applyQualityHints(Graphics2D g2d) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }
}
