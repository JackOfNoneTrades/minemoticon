package org.fentanylsolutions.minemoticon.font;

import java.awt.image.BufferedImage;

// Sentinel font source for the vanilla Minecraft bitmap font.
// When this source wins for a codepoint, the mixin lets vanilla handle rendering.
public class MinecraftFontSource extends FontSource {

    public static final String ID = "minecraft";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Minecraft";
    }

    @Override
    public boolean isBuiltIn() {
        return true;
    }

    @Override
    public boolean hasGlyph(int codepoint) {
        // Minecraft reliably renders Basic Latin and Latin-1 Supplement.
        // Everything else is spotty (missing unicode_page_XX.png files).
        return codepoint >= 0x20 && codepoint <= 0xFF;
    }

    @Override
    public BufferedImage renderGlyph(int codepoint, int size) {
        // Never called -- the mixin delegates to vanilla when Minecraft wins.
        return null;
    }

    @Override
    public boolean canRender(int[] codepoints) {
        return false;
    }

    @Override
    public BufferedImage renderGlyphs(int[] codepoints, int size) {
        return null;
    }
}
