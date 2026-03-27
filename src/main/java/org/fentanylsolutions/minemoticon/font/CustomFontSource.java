package org.fentanylsolutions.minemoticon.font;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;

import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.colorfont.AtlasBuilder;
import org.fentanylsolutions.minemoticon.colorfont.ColorFont;

// Font source wrapping a user-provided TTF/OTF file.
public class CustomFontSource extends FontSource {

    private final String fileName;
    private final ColorFont colorFont;
    private final String fontHash;

    public CustomFontSource(String fileName, ColorFont colorFont, String fontHash) {
        this.fileName = fileName;
        this.colorFont = colorFont;
        this.fontHash = fontHash;
    }

    public static CustomFontSource load(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            ColorFont font = ColorFont.load(new ByteArrayInputStream(bytes));
            String hash = AtlasBuilder.sha1(bytes);
            return new CustomFontSource(file.getName(), font, hash);
        } catch (Exception e) {
            Minemoticon.LOG.warn("Failed to load custom font: {}", file.getName(), e);
            return null;
        }
    }

    @Override
    public String getId() {
        return fileName;
    }

    @Override
    public String getDisplayName() {
        return fileName;
    }

    @Override
    public boolean isBuiltIn() {
        return false;
    }

    @Override
    public boolean hasGlyph(int codepoint) {
        return colorFont.hasGlyph(codepoint);
    }

    @Override
    public BufferedImage renderGlyph(int codepoint, int size) {
        return colorFont.renderGlyph(codepoint, size);
    }

    @Override
    public BufferedImage renderTextGlyph(int codepoint, int size) {
        if (colorFont.hasAnyColorGlyphs()) {
            return colorFont.renderGlyph(codepoint, size);
        }
        return colorFont.renderTextGlyph(codepoint, size);
    }

    @Override
    public boolean canRender(int[] codepoints) {
        return colorFont.canRender(codepoints);
    }

    @Override
    public BufferedImage renderGlyphs(int[] codepoints, int size) {
        return colorFont.renderGlyphs(codepoints, size);
    }

    @Override
    public float getTextGlyphAdvance(int codepoint, int size) {
        if (colorFont.hasAnyColorGlyphs()) {
            return -1.0f;
        }
        return colorFont.getTextGlyphAdvance(codepoint, size);
    }

    @Override
    public boolean preserveTextLineMetrics() {
        return !colorFont.hasAnyColorGlyphs();
    }

    @Override
    public boolean usesTextColor() {
        return !colorFont.hasAnyColorGlyphs();
    }

    @Override
    public ColorFont getColorFont() {
        return colorFont;
    }

    @Override
    public String getFontHash() {
        return fontHash;
    }
}
