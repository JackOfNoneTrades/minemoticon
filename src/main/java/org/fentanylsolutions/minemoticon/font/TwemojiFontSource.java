package org.fentanylsolutions.minemoticon.font;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;

import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.colorfont.AtlasBuilder;
import org.fentanylsolutions.minemoticon.colorfont.ColorFont;

// Font source wrapping the bundled Twemoji color font from the jar.
public class TwemojiFontSource extends FontSource {

    public static final String ID = "twemoji";
    private static final String RESOURCE_PATH = "/assets/minemoticon/twemoji.ttf";

    private final ColorFont colorFont;
    private final String fontHash;
    private final Float displayHeightOverride;
    private final float widthScale;
    private final float verticalOffset;

    public TwemojiFontSource(ColorFont colorFont, String fontHash, Float displayHeightOverride, float widthScale,
        float verticalOffset) {
        this.colorFont = colorFont;
        this.fontHash = fontHash;
        this.displayHeightOverride = displayHeightOverride;
        this.widthScale = widthScale;
        this.verticalOffset = verticalOffset;
    }

    public static TwemojiFontSource load() {
        return load(java.util.Collections.emptyMap());
    }

    public static TwemojiFontSource load(Map<String, Float> settings) {
        try (var stream = TwemojiFontSource.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                Minemoticon.LOG.warn("Bundled twemoji.ttf not found in jar");
                return null;
            }
            var baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) != -1) baos.write(buf, 0, n);
            byte[] bytes = baos.toByteArray();
            ColorFont font = ColorFont.load(new ByteArrayInputStream(bytes));
            String hash = AtlasBuilder.sha1(bytes);
            return new TwemojiFontSource(
                font,
                hash,
                FontVariationConfig.getExplicitDisplayHeight(settings),
                FontVariationConfig.getWidthScale(settings, FontVariationConfig.DEFAULT_WIDTH_PERCENT),
                FontVariationConfig.getVerticalOffset(settings, FontVariationConfig.DEFAULT_Y_OFFSET));
        } catch (Exception e) {
            Minemoticon.LOG.warn("Failed to load bundled twemoji font", e);
            return null;
        }
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Twemoji";
    }

    @Override
    public boolean isBuiltIn() {
        return true;
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
    public boolean canRender(int[] codepoints) {
        return colorFont.canRender(codepoints);
    }

    @Override
    public BufferedImage renderGlyphs(int[] codepoints, int size) {
        return colorFont.renderGlyphs(codepoints, size);
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
}
