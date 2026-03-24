package org.fentanylsolutions.minemoticon.colorfont;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

// Reads the OpenType table directory and provides raw table data by tag.
public class OTFTableReader {

    private final byte[] fontData;
    private final Map<String, int[]> tables = new HashMap<>(); // tag -> [offset, length]

    public OTFTableReader(byte[] fontData) {
        this.fontData = fontData;
        parseDirectory();
    }

    public byte[] getFontData() {
        return fontData;
    }

    public static OTFTableReader load(InputStream in) throws IOException {
        var baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return new OTFTableReader(baos.toByteArray());
    }

    private void parseDirectory() {
        var bb = wrap(0, 12);
        bb.getInt(); // sfVersion
        int numTables = bb.getShort() & 0xFFFF;
        bb.getShort(); // searchRange
        bb.getShort(); // entrySelector
        bb.getShort(); // rangeShift

        for (int i = 0; i < numTables; i++) {
            int off = 12 + i * 16;
            var rec = wrap(off, 16);
            byte[] tagBytes = new byte[4];
            rec.get(tagBytes);
            String tag = new String(tagBytes);
            rec.getInt(); // checksum
            int tableOffset = rec.getInt();
            int tableLength = rec.getInt();
            tables.put(tag, new int[] { tableOffset, tableLength });
        }
    }

    public boolean hasTable(String tag) {
        return tables.containsKey(tag);
    }

    public ByteBuffer getTable(String tag) {
        int[] entry = tables.get(tag);
        if (entry == null) return null;
        return wrap(entry[0], entry[1]);
    }

    public ByteBuffer wrap(int offset, int length) {
        return ByteBuffer.wrap(fontData, offset, length)
            .order(ByteOrder.BIG_ENDIAN);
    }
}
