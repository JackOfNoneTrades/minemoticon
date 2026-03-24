package org.fentanylsolutions.minemoticon.colorfont;

import java.nio.ByteBuffer;

public class HeadParser {

    public final int unitsPerEm;
    public final int indexToLocFormat; // 0 = short, 1 = long

    public HeadParser(ByteBuffer head) {
        head.position(18);
        unitsPerEm = head.getShort() & 0xFFFF;
        head.position(50);
        indexToLocFormat = head.getShort();
    }
}
