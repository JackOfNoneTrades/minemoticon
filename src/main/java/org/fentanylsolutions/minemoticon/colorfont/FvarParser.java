package org.fentanylsolutions.minemoticon.colorfont;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FvarParser {

    private final List<VariationAxis> axes;

    public FvarParser(ByteBuffer table) {
        this.axes = parse(table);
    }

    public List<VariationAxis> getAxes() {
        return axes;
    }

    private static List<VariationAxis> parse(ByteBuffer table) {
        if (table == null || table.remaining() < 16) {
            return Collections.emptyList();
        }

        ByteBuffer bb = table.slice()
            .order(ByteOrder.BIG_ENDIAN);
        bb.getShort(); // majorVersion
        bb.getShort(); // minorVersion
        int offsetToData = bb.getShort() & 0xFFFF; // axesArrayOffset
        bb.getShort(); // reserved
        int axisCount = bb.getShort() & 0xFFFF;
        int axisSize = bb.getShort() & 0xFFFF;
        bb.getShort(); // instanceCount
        bb.getShort(); // instanceSize

        if (axisCount <= 0 || axisSize < 20 || offsetToData <= 0 || offsetToData >= bb.limit()) {
            return Collections.emptyList();
        }

        List<VariationAxis> result = new ArrayList<>(axisCount);
        for (int i = 0; i < axisCount; i++) {
            int pos = offsetToData + i * axisSize;
            if (pos < 0 || pos + 20 > bb.limit()) {
                break;
            }

            ByteBuffer axis = bb.duplicate();
            axis.position(pos);

            byte[] tagBytes = new byte[4];
            axis.get(tagBytes);
            String tag = new String(tagBytes, StandardCharsets.US_ASCII);
            float minValue = readFixed(axis.getInt());
            float defaultValue = readFixed(axis.getInt());
            float maxValue = readFixed(axis.getInt());
            axis.getShort(); // flags
            axis.getShort(); // axisNameId

            result.add(new VariationAxis(tag, minValue, defaultValue, maxValue));
        }
        return Collections.unmodifiableList(result);
    }

    private static float readFixed(int fixed1616) {
        return fixed1616 / 65536.0f;
    }
}
