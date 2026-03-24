package org.fentanylsolutions.minemoticon.colorfont;

import java.nio.ByteBuffer;

// Parses loca table: glyph ID -> byte offset into glyf table.
public class LocaParser {

    private final long[] offsets;

    public LocaParser(ByteBuffer loca, int numGlyphs, int indexToLocFormat) {
        offsets = new long[numGlyphs + 1];
        if (indexToLocFormat == 0) {
            // Short format: uint16 offsets, multiply by 2
            for (int i = 0; i <= numGlyphs; i++) {
                offsets[i] = (loca.getShort() & 0xFFFFL) * 2;
            }
        } else {
            // Long format: uint32 offsets
            for (int i = 0; i <= numGlyphs; i++) {
                offsets[i] = loca.getInt() & 0xFFFFFFFFL;
            }
        }
    }

    public long getOffset(int glyphId) {
        if (glyphId < 0 || glyphId >= offsets.length - 1) return -1;
        return offsets[glyphId];
    }

    public long getLength(int glyphId) {
        if (glyphId < 0 || glyphId >= offsets.length - 1) return 0;
        return offsets[glyphId + 1] - offsets[glyphId];
    }
}
