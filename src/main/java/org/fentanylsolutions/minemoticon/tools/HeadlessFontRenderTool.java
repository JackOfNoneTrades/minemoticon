package org.fentanylsolutions.minemoticon.tools;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.fentanylsolutions.minemoticon.colorfont.ColorFont;

public final class HeadlessFontRenderTool {

    private static final int GLYPH_SIZE = 128;
    private static final int CELL_SIZE = 152;
    private static final int COLUMNS = 4;
    private static final String[] DEFAULT_SAMPLES = { "1F922", "1F602", "1F924", "1F912", "1F914", "1F62A", "1F60A",
        "1F48E", "1F973", "1F915", "1F974", "1F92E" };

    private HeadlessFontRenderTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: HeadlessFontRenderTool <font-file> <output-dir> [hex-codepoint-list]");
            System.exit(1);
        }

        Path fontPath = Paths.get(args[0]);
        Path outputDir = Paths.get(args[1]);
        int[][] samples = args.length >= 3 ? parseSamples(args[2]) : parseSamples(String.join(",", DEFAULT_SAMPLES));

        Files.createDirectories(outputDir);

        byte[] data = Files.readAllBytes(fontPath);
        ColorFont font = ColorFont.load(new ByteArrayInputStream(data));

        var glyphs = new ArrayList<RenderedGlyph>();
        var report = new StringBuilder();
        report.append("Font: ")
            .append(fontPath.toAbsolutePath())
            .append('\n');

        for (int[] sample : samples) {
            BufferedImage image = sample.length == 1 ? font.renderGlyph(sample[0], GLYPH_SIZE)
                : font.renderGlyphs(sample, GLYPH_SIZE);
            int nonTransparentPixels = countNonTransparentPixels(image);
            String hex = formatSequence(sample);
            Path glyphPath = outputDir.resolve(hex + ".png");
            String preparedSvg = sample.length == 1 ? font.debugPreparedSvg(sample[0]) : null;

            if (image != null) {
                ImageIO.write(image, "PNG", glyphPath.toFile());
            }
            if (preparedSvg != null) {
                Files.write(
                    outputDir.resolve(hex + ".svg"),
                    preparedSvg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            report.append(hex)
                .append(" : ")
                .append(image == null ? "null" : (nonTransparentPixels == 0 ? "blank" : "ok"))
                .append(" (nonTransparent=")
                .append(nonTransparentPixels)
                .append(", svg=")
                .append(preparedSvg != null)
                .append(")\n");

            glyphs.add(new RenderedGlyph(sample, image, nonTransparentPixels));
        }

        ImageIO.write(
            buildContactSheet(glyphs),
            "PNG",
            outputDir.resolve("contact-sheet.png")
                .toFile());
        Files.write(
            outputDir.resolve("report.txt"),
            report.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        System.out.println("Rendered " + glyphs.size() + " glyphs to " + outputDir.toAbsolutePath());
    }

    private static BufferedImage buildContactSheet(List<RenderedGlyph> glyphs) {
        int rows = Math.max(1, (glyphs.size() + COLUMNS - 1) / COLUMNS);
        BufferedImage sheet = new BufferedImage(COLUMNS * CELL_SIZE, rows * CELL_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        applyQualityHints(g);
        g.setColor(new Color(0x1F, 0x1F, 0x24));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));

        for (int index = 0; index < glyphs.size(); index++) {
            RenderedGlyph glyph = glyphs.get(index);
            int col = index % COLUMNS;
            int row = index / COLUMNS;
            int cellX = col * CELL_SIZE;
            int cellY = row * CELL_SIZE;

            g.setColor(new Color(0x2E, 0x2E, 0x36));
            g.fillRoundRect(cellX + 4, cellY + 4, CELL_SIZE - 8, CELL_SIZE - 8, 10, 10);

            if (glyph.image != null) {
                g.drawImage(glyph.image, cellX + 12, cellY + 10, GLYPH_SIZE, GLYPH_SIZE, null);
            } else {
                g.setColor(new Color(0x77, 0x33, 0x33));
                g.fillRect(cellX + 12, cellY + 10, GLYPH_SIZE, GLYPH_SIZE);
            }

            g.setColor(Color.WHITE);
            g.drawString(formatSequence(glyph.codepoints), cellX + 12, cellY + 144);
            g.setColor(glyph.nonTransparentPixels > 0 ? new Color(0x8B, 0xD3, 0x7B) : new Color(0xFF, 0x88, 0x88));
            g.drawString("alpha=" + glyph.nonTransparentPixels, cellX + 76, cellY + 144);
        }

        g.dispose();
        return sheet;
    }

    private static int[][] parseSamples(String csv) {
        String[] parts = csv.split(",");
        int[][] samples = new int[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            String[] seq = parts[i].trim()
                .split("-");
            samples[i] = new int[seq.length];
            for (int j = 0; j < seq.length; j++) {
                samples[i][j] = Integer.parseInt(seq[j].trim(), 16);
            }
        }
        return samples;
    }

    private static int countNonTransparentPixels(BufferedImage image) {
        if (image == null) return 0;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String formatSequence(int[] codepoints) {
        var sb = new StringBuilder();
        for (int i = 0; i < codepoints.length; i++) {
            if (i > 0) sb.append('-');
            sb.append(String.format("U+%04X", codepoints[i]));
        }
        return sb.toString();
    }

    private static void applyQualityHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static final class RenderedGlyph {

        private final int[] codepoints;
        private final BufferedImage image;
        private final int nonTransparentPixels;

        private RenderedGlyph(int[] codepoints, BufferedImage image, int nonTransparentPixels) {
            this.codepoints = codepoints;
            this.image = image;
            this.nonTransparentPixels = nonTransparentPixels;
        }
    }
}
