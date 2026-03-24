package org.fentanylsolutions.minemoticon.colorfont;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

// Parses CBLC + CBDT tables to extract embedded PNG bitmaps per glyph.
// Stores raw byte ranges during construction; decodes PNGs on demand.
public class CbdtParser {

    private static class BitmapRef {

        final int offset; // into CBDT data
        final int length;
        final int imageFormat;

        BitmapRef(int offset, int length, int imageFormat) {
            this.offset = offset;
            this.length = length;
            this.imageFormat = imageFormat;
        }
    }

    private final Map<Integer, BitmapRef> refs = new HashMap<>();
    private final byte[] cbdtData;
    private final int cbdtBase;
    private final int ppemX;

    public CbdtParser(ByteBuffer cblc, ByteBuffer cbdt) {
        this.cbdtData = cbdt.array();
        this.cbdtBase = cbdt.arrayOffset() + cbdt.position();
        int cblcBase = cblc.position();

        cblc.getShort(); // majorVersion
        cblc.getShort(); // minorVersion
        int numSizes = cblc.getInt();

        // Find the largest strike (or first if ppem is 0)
        int bestStrike = 0;
        int bestPpem = 0;
        for (int s = 0; s < numSizes; s++) {
            cblc.position(cblcBase + 8 + s * 48 + 40);
            int px = cblc.get() & 0xFF;
            if (px > bestPpem) {
                bestPpem = px;
                bestStrike = s;
            }
        }
        this.ppemX = bestPpem;
        if (numSizes == 0) return;

        // Parse index subtables for the best strike
        int strikeOff = cblcBase + 8 + bestStrike * 48;
        cblc.position(strikeOff);
        int indexSubTableArrayOffset = cblc.getInt();
        int indexTablesSize = cblc.getInt();
        int numberOfIndexSubTables = cblc.getInt();
        int subtableArrayStart = cblcBase + indexSubTableArrayOffset;

        for (int i = 0; i < numberOfIndexSubTables; i++) {
            cblc.position(subtableArrayStart + i * 8);
            int firstGlyph = cblc.getShort() & 0xFFFF;
            int lastGlyph = cblc.getShort() & 0xFFFF;
            int additionalOffset = cblc.getInt();

            int subtableOff = subtableArrayStart + additionalOffset;
            cblc.position(subtableOff);
            int indexFormat = cblc.getShort() & 0xFFFF;
            int imageFormat = cblc.getShort() & 0xFFFF;
            int imageDataOffset = cblc.getInt();

            if (indexFormat == 1) {
                int[] offsets = new int[lastGlyph - firstGlyph + 2];
                for (int j = 0; j < offsets.length; j++) offsets[j] = cblc.getInt();
                for (int g = firstGlyph; g <= lastGlyph; g++) {
                    int start = imageDataOffset + offsets[g - firstGlyph];
                    int end = imageDataOffset + offsets[g - firstGlyph + 1];
                    if (end > start) refs.put(g, new BitmapRef(start, end - start, imageFormat));
                }
            } else if (indexFormat == 2) {
                int imageSize = cblc.getInt();
                for (int g = firstGlyph; g <= lastGlyph; g++) {
                    int start = imageDataOffset + (g - firstGlyph) * imageSize;
                    refs.put(g, new BitmapRef(start, imageSize, imageFormat));
                }
            } else if (indexFormat == 3) {
                int[] offsets = new int[lastGlyph - firstGlyph + 2];
                for (int j = 0; j < offsets.length; j++) offsets[j] = cblc.getShort() & 0xFFFF;
                for (int g = firstGlyph; g <= lastGlyph; g++) {
                    int start = imageDataOffset + offsets[g - firstGlyph];
                    int end = imageDataOffset + offsets[g - firstGlyph + 1];
                    if (end > start) refs.put(g, new BitmapRef(start, end - start, imageFormat));
                }
            }
        }
    }

    public boolean hasBitmap(int glyphId) {
        return refs.containsKey(glyphId);
    }

    public BufferedImage getBitmap(int glyphId) {
        var ref = refs.get(glyphId);
        if (ref == null) return null;

        try {
            int pos = cbdtBase + ref.offset;
            int skipBytes;
            if (ref.imageFormat == 17) {
                skipBytes = 5; // smallGlyphMetrics
            } else if (ref.imageFormat == 18) {
                skipBytes = 8; // bigGlyphMetrics
            } else if (ref.imageFormat == 19) {
                skipBytes = 0; // no metrics
            } else {
                return null;
            }

            pos += skipBytes;
            int pngLen;
            pngLen = ((cbdtData[pos] & 0xFF) << 24) | ((cbdtData[pos + 1] & 0xFF) << 16)
                | ((cbdtData[pos + 2] & 0xFF) << 8)
                | (cbdtData[pos + 3] & 0xFF);
            pos += 4;

            if (pngLen <= 0 || pos + pngLen > cbdtData.length) return null;
            return ImageIO.read(new ByteArrayInputStream(cbdtData, pos, pngLen));
        } catch (Exception e) {
            return null;
        }
    }

    public int getGlyphCount() {
        return refs.size();
    }

    public int getPpem() {
        return ppemX;
    }
}
