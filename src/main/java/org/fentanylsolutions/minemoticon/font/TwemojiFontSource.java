package org.fentanylsolutions.minemoticon.font;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.colorfont.AtlasBuilder;
import org.fentanylsolutions.minemoticon.colorfont.ColorFont;

// Font source wrapping the bundled Twemoji color font from the jar.
public class TwemojiFontSource extends FontSource {

    public static final String ID = "twemoji";
    private static final String RESOURCE_PATH = "/assets/minemoticon/twemoji.ttf";

    private final ColorFont colorFont;
    private final String fontHash;

    public TwemojiFontSource(ColorFont colorFont, String fontHash) {
        this.colorFont = colorFont;
        this.fontHash = fontHash;
    }

    public static TwemojiFontSource load() {
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
            return new TwemojiFontSource(font, hash);
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
}
