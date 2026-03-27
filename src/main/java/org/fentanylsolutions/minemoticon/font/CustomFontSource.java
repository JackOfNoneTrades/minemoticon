package org.fentanylsolutions.minemoticon.font;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.colorfont.AtlasBuilder;
import org.fentanylsolutions.minemoticon.colorfont.ColorFont;
import org.fentanylsolutions.minemoticon.colorfont.VariationAxis;

// Font source wrapping a user-provided TTF/OTF file.
public class CustomFontSource extends FontSource {

    private final String fileName;
    private final ColorFont colorFont;
    private final String fontHash;
    private final Float displayHeightOverride;
    private final float widthScale;
    private final float verticalOffset;

    public CustomFontSource(String fileName, ColorFont colorFont, String fontHash, Float displayHeightOverride,
        float widthScale, float verticalOffset) {
        this.fileName = fileName;
        this.colorFont = colorFont;
        this.fontHash = fontHash;
        this.displayHeightOverride = displayHeightOverride;
        this.widthScale = widthScale;
        this.verticalOffset = verticalOffset;
    }

    public static CustomFontSource load(File file) {
        return load(file, java.util.Collections.emptyMap());
    }

    public static CustomFontSource load(File file, Map<String, Float> variationSettings) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            ColorFont font = ColorFont
                .load(new ByteArrayInputStream(bytes), FontVariationConfig.copyVariationSettings(variationSettings));
            String hash = AtlasBuilder.sha1(bytes);
            return new CustomFontSource(
                file.getName(),
                font,
                hash,
                FontVariationConfig.getExplicitDisplayHeight(variationSettings),
                FontVariationConfig.getWidthScale(variationSettings, FontVariationConfig.DEFAULT_WIDTH_PERCENT),
                FontVariationConfig.getVerticalOffset(variationSettings, FontVariationConfig.DEFAULT_Y_OFFSET));
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
    public float getTextGlyphOffsetX(int codepoint, int size) {
        if (colorFont.hasAnyColorGlyphs()) {
            return 0.0f;
        }
        return colorFont.getTextGlyphOffsetX(codepoint, size);
    }

    @Override
    public TextRunLayout layoutTextRun(String text, int size) {
        if (colorFont.hasAnyColorGlyphs()) {
            return null;
        }
        return colorFont.layoutTextRun(text, size);
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
    public float getDisplayHeight() {
        return displayHeightOverride != null ? displayHeightOverride.floatValue() : super.getDisplayHeight();
    }

    @Override
    public float getWidthScale() {
        return widthScale;
    }

    @Override
    public float getVerticalOffset() {
        return verticalOffset;
    }

    @Override
    public ColorFont getColorFont() {
        return colorFont;
    }

    @Override
    public String getFontHash() {
        return fontHash;
    }

    @Override
    public List<VariationAxis> getVariationAxes() {
        return colorFont.hasAnyColorGlyphs() ? java.util.Collections.emptyList() : colorFont.getVariationAxes();
    }
}
