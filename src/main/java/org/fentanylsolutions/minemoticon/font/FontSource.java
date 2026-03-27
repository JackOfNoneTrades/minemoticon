package org.fentanylsolutions.minemoticon.font;

import java.awt.image.BufferedImage;

import org.fentanylsolutions.minemoticon.colorfont.ColorFont;

// A single entry in the font stack. Implementations wrap either the vanilla Minecraft font,
// the bundled Twemoji font, or a user-provided TTF/OTF file.
public abstract class FontSource {

    public abstract String getId();

    public abstract String getDisplayName();

    public abstract boolean isBuiltIn();

    public abstract boolean hasGlyph(int codepoint);

    public abstract BufferedImage renderGlyph(int codepoint, int size);

    public BufferedImage renderTextGlyph(int codepoint, int size) {
        return renderGlyph(codepoint, size);
    }

    public abstract boolean canRender(int[] codepoints);

    public abstract BufferedImage renderGlyphs(int[] codepoints, int size);

    public float getTextGlyphAdvance(int codepoint, int size) {
        return -1.0f;
    }

    public boolean preserveTextLineMetrics() {
        return false;
    }

    public boolean usesTextColor() {
        return false;
    }

    // Returns the underlying ColorFont, or null for sources that don't have one (e.g. Minecraft).
    public ColorFont getColorFont() {
        return null;
    }

    // Returns the font hash for atlas cache keying, or null if not applicable.
    public String getFontHash() {
        return null;
    }
}
