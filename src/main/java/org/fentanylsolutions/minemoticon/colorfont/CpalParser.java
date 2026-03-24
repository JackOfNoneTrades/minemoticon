package org.fentanylsolutions.minemoticon.colorfont;

import java.nio.ByteBuffer;

// Parses CPAL v0 table: palette of RGBA colors.
public class CpalParser {

    private final int[] colors; // ARGB format

    public CpalParser(ByteBuffer cpal) {
        if (cpal == null) {
            colors = new int[0];
            return;
        }
        int base = cpal.position();
        cpal.getShort(); // version
        int numPaletteEntries = cpal.getShort() & 0xFFFF;
        int numPalettes = cpal.getShort() & 0xFFFF;
        int numColorRecords = cpal.getShort() & 0xFFFF;
        int colorRecordOffset = cpal.getInt();

        // Read first palette's offset index
        int firstPaletteIndex = cpal.getShort() & 0xFFFF;

        // Read color records (BGRA order in the file)
        colors = new int[numColorRecords];
        cpal.position(base + colorRecordOffset);
        for (int i = 0; i < numColorRecords; i++) {
            int b = cpal.get() & 0xFF;
            int g = cpal.get() & 0xFF;
            int r = cpal.get() & 0xFF;
            int a = cpal.get() & 0xFF;
            colors[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    public int getColor(int paletteIndex) {
        if (paletteIndex == 0xFFFF) return 0xFF000000; // special: foreground color
        if (paletteIndex < 0 || paletteIndex >= colors.length) return 0xFF000000;
        return colors[paletteIndex];
    }

    public int getColorCount() {
        return colors.length;
    }

    public static class Empty extends CpalParser {

        public Empty() {
            super(null);
        }
    }
}
