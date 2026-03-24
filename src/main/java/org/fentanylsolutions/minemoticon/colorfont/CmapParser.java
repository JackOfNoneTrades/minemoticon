package org.fentanylsolutions.minemoticon.colorfont;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

// Parses cmap table: Unicode codepoint -> glyph ID.
// Supports format 4 (BMP) and format 12 (full Unicode).
public class CmapParser {

    private final Map<Integer, Integer> map = new HashMap<>();

    public CmapParser(ByteBuffer cmap) {
        int base = cmap.position();
        cmap.getShort(); // version
        int numSubtables = cmap.getShort() & 0xFFFF;

        // Find the best subtable: prefer format 12 (platformID=3, encodingID=10),
        // fall back to format 4 (platformID=3, encodingID=1)
        int format12Offset = -1;
        int format4Offset = -1;

        for (int i = 0; i < numSubtables; i++) {
            int platformID = cmap.getShort() & 0xFFFF;
            int encodingID = cmap.getShort() & 0xFFFF;
            int offset = cmap.getInt();
            if (platformID == 3 && encodingID == 10) format12Offset = offset;
            if (platformID == 3 && encodingID == 1) format4Offset = offset;
        }

        if (format12Offset >= 0) parseFormat12(cmap, base + format12Offset);
        if (format4Offset >= 0) parseFormat4(cmap, base + format4Offset);
    }

    public int getGlyphId(int codepoint) {
        return map.getOrDefault(codepoint, 0);
    }

    public boolean hasGlyph(int codepoint) {
        return map.containsKey(codepoint) && map.get(codepoint) != 0;
    }

    private void parseFormat4(ByteBuffer buf, int offset) {
        buf.position(offset);
        int format = buf.getShort() & 0xFFFF;
        if (format != 4) return;

        int length = buf.getShort() & 0xFFFF;
        buf.getShort(); // language
        int segCount = (buf.getShort() & 0xFFFF) / 2;
        buf.getShort(); // searchRange
        buf.getShort(); // entrySelector
        buf.getShort(); // rangeShift

        int[] endCodes = new int[segCount];
        for (int i = 0; i < segCount; i++) endCodes[i] = buf.getShort() & 0xFFFF;
        buf.getShort(); // reservedPad
        int[] startCodes = new int[segCount];
        for (int i = 0; i < segCount; i++) startCodes[i] = buf.getShort() & 0xFFFF;
        short[] idDeltas = new short[segCount];
        for (int i = 0; i < segCount; i++) idDeltas[i] = buf.getShort();
        int idRangeOffsetsPos = buf.position();
        int[] idRangeOffsets = new int[segCount];
        for (int i = 0; i < segCount; i++) idRangeOffsets[i] = buf.getShort() & 0xFFFF;

        for (int i = 0; i < segCount; i++) {
            if (startCodes[i] == 0xFFFF) break;
            for (int c = startCodes[i]; c <= endCodes[i]; c++) {
                int glyphId;
                if (idRangeOffsets[i] == 0) {
                    glyphId = (c + idDeltas[i]) & 0xFFFF;
                } else {
                    int glyphIdOffset = idRangeOffsetsPos + i * 2 + idRangeOffsets[i] + (c - startCodes[i]) * 2;
                    buf.position(glyphIdOffset);
                    glyphId = buf.getShort() & 0xFFFF;
                    if (glyphId != 0) glyphId = (glyphId + idDeltas[i]) & 0xFFFF;
                }
                if (glyphId != 0) map.putIfAbsent(c, glyphId);
            }
        }
    }

    private void parseFormat12(ByteBuffer buf, int offset) {
        buf.position(offset);
        int format = buf.getShort() & 0xFFFF;
        if (format != 12) return;

        buf.getShort(); // reserved
        buf.getInt(); // length
        buf.getInt(); // language
        int numGroups = buf.getInt();

        for (int i = 0; i < numGroups; i++) {
            int startCode = buf.getInt();
            int endCode = buf.getInt();
            int startGlyph = buf.getInt();
            for (int c = startCode; c <= endCode; c++) {
                map.put(c, startGlyph + (c - startCode));
            }
        }
    }
}
