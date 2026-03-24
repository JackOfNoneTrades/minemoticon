package org.fentanylsolutions.minemoticon.colorfont;

import java.nio.ByteBuffer;

public class MaxpParser {

    public final int numGlyphs;

    public MaxpParser(ByteBuffer maxp) {
        int base = maxp.position();
        maxp.position(base + 4);
        numGlyphs = maxp.getShort() & 0xFFFF;
    }
}
