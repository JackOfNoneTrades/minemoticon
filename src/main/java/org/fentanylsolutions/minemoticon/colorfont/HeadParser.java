package org.fentanylsolutions.minemoticon.colorfont;

import java.nio.ByteBuffer;

public class HeadParser {

    public final int unitsPerEm;
    public final int indexToLocFormat; // 0 = short, 1 = long
    public int ascent; // from hhea
    public int descent; // from hhea (negative)

    public HeadParser(ByteBuffer head) {
        int base = head.position();
        head.position(base + 18);
        unitsPerEm = head.getShort() & 0xFFFF;
        head.position(base + 50);
        indexToLocFormat = head.getShort();
    }

    public void parseHhea(ByteBuffer hhea) {
        int base = hhea.position();
        hhea.position(base + 4);
        ascent = hhea.getShort();
        descent = hhea.getShort();
    }
}
