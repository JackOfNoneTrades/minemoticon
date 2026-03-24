package org.fentanylsolutions.minemoticon.colorfont;

import java.nio.ByteBuffer;

public class MaxpParser {

    public final int numGlyphs;

    public MaxpParser(ByteBuffer maxp) {
        maxp.position(4);
        numGlyphs = maxp.getShort() & 0xFFFF;
    }
}
