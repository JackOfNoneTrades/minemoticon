package org.fentanylsolutions.minemoticon.colorfont;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Parses glyf table entries into GeneralPath outlines.
public class GlyfParser {

    private final byte[] glyfData;
    private final int glyfOffset;
    private final LocaParser loca;

    public GlyfParser(ByteBuffer glyf, LocaParser loca) {
        this.glyfData = glyf.array();
        this.glyfOffset = glyf.arrayOffset() + glyf.position();
        this.loca = loca;
    }

    public GeneralPath getOutline(int glyphId) {
        long offset = loca.getOffset(glyphId);
        long length = loca.getLength(glyphId);
        if (offset < 0 || length <= 0) return new GeneralPath();

        var buf = ByteBuffer.wrap(glyfData, (int) (glyfOffset + offset), (int) length)
            .order(ByteOrder.BIG_ENDIAN);
        int numberOfContours = buf.getShort();
        buf.getShort(); // xMin
        buf.getShort(); // yMin
        buf.getShort(); // xMax
        buf.getShort(); // yMax

        if (numberOfContours >= 0) {
            return parseSimpleGlyph(buf, numberOfContours);
        } else {
            return parseCompoundGlyph(buf);
        }
    }

    private GeneralPath parseSimpleGlyph(ByteBuffer buf, int numberOfContours) {
        if (numberOfContours == 0) return new GeneralPath();

        int[] endPtsOfContours = new int[numberOfContours];
        for (int i = 0; i < numberOfContours; i++) {
            endPtsOfContours[i] = buf.getShort() & 0xFFFF;
        }

        int numPoints = endPtsOfContours[numberOfContours - 1] + 1;

        // Skip instructions
        int instructionLength = buf.getShort() & 0xFFFF;
        buf.position(buf.position() + instructionLength);

        // Parse flags
        int[] flags = new int[numPoints];
        for (int i = 0; i < numPoints;) {
            int flag = buf.get() & 0xFF;
            flags[i++] = flag;
            if ((flag & 0x08) != 0) { // REPEAT
                int repeat = buf.get() & 0xFF;
                for (int j = 0; j < repeat && i < numPoints; j++) {
                    flags[i++] = flag;
                }
            }
        }

        // Parse X coordinates (delta-encoded)
        int[] xCoords = new int[numPoints];
        int x = 0;
        for (int i = 0; i < numPoints; i++) {
            int flag = flags[i];
            if ((flag & 0x02) != 0) { // X_SHORT
                int dx = buf.get() & 0xFF;
                x += ((flag & 0x10) != 0) ? dx : -dx;
            } else if ((flag & 0x10) == 0) {
                x += buf.getShort();
            }
            // else: x is same as previous
            xCoords[i] = x;
        }

        // Parse Y coordinates (delta-encoded)
        int[] yCoords = new int[numPoints];
        int y = 0;
        for (int i = 0; i < numPoints; i++) {
            int flag = flags[i];
            if ((flag & 0x04) != 0) { // Y_SHORT
                int dy = buf.get() & 0xFF;
                y += ((flag & 0x20) != 0) ? dy : -dy;
            } else if ((flag & 0x20) == 0) {
                y += buf.getShort();
            }
            yCoords[i] = y;
        }

        // Convert to GeneralPath
        var path = new GeneralPath();
        int contourStart = 0;
        for (int c = 0; c < numberOfContours; c++) {
            int contourEnd = endPtsOfContours[c];
            int numContourPoints = contourEnd - contourStart + 1;

            // Build point list for this contour
            buildContour(path, xCoords, yCoords, flags, contourStart, contourEnd);
            contourStart = contourEnd + 1;
        }
        return path;
    }

    private void buildContour(GeneralPath path, int[] xCoords, int[] yCoords, int[] flags, int start, int end) {
        int count = end - start + 1;
        if (count < 2) return;

        // Find the first on-curve point to start from
        int firstOnCurve = -1;
        for (int i = start; i <= end; i++) {
            if ((flags[i] & 0x01) != 0) {
                firstOnCurve = i;
                break;
            }
        }

        float startX, startY;
        int startIdx;
        if (firstOnCurve >= 0) {
            startX = xCoords[firstOnCurve];
            startY = yCoords[firstOnCurve];
            startIdx = firstOnCurve;
        } else {
            // All off-curve: start at midpoint between first two
            startX = (xCoords[start] + xCoords[start + 1]) / 2.0f;
            startY = (yCoords[start] + yCoords[start + 1]) / 2.0f;
            startIdx = start;
        }

        path.moveTo(startX, startY);

        int i = startIdx;
        for (int step = 0; step < count; step++) {
            int next = start + ((i - start + 1) % count);
            boolean curOnCurve = (flags[i] & 0x01) != 0;
            boolean nextOnCurve = (flags[next] & 0x01) != 0;

            if (curOnCurve && nextOnCurve) {
                path.lineTo(xCoords[next], yCoords[next]);
            } else if (curOnCurve && !nextOnCurve) {
                // Current is on-curve, next is off-curve
                int afterNext = start + ((next - start + 1) % count);
                boolean afterOnCurve = (flags[afterNext] & 0x01) != 0;
                float endX, endY;
                if (afterOnCurve) {
                    endX = xCoords[afterNext];
                    endY = yCoords[afterNext];
                } else {
                    // Implied on-curve point between two off-curve
                    endX = (xCoords[next] + xCoords[afterNext]) / 2.0f;
                    endY = (yCoords[next] + yCoords[afterNext]) / 2.0f;
                }
                path.quadTo(xCoords[next], yCoords[next], endX, endY);
                i = next;
                continue; // don't advance again
            } else if (!curOnCurve) {
                // Off-curve to next
                float endX, endY;
                if (nextOnCurve) {
                    endX = xCoords[next];
                    endY = yCoords[next];
                } else {
                    endX = (xCoords[i] + xCoords[next]) / 2.0f;
                    endY = (yCoords[i] + yCoords[next]) / 2.0f;
                }
                path.quadTo(xCoords[i], yCoords[i], endX, endY);
            }

            i = next;
        }

        path.closePath();
    }

    private GeneralPath parseCompoundGlyph(ByteBuffer buf) {
        var path = new GeneralPath();
        int flags;
        do {
            flags = buf.getShort() & 0xFFFF;
            int componentGlyphId = buf.getShort() & 0xFFFF;

            float dx = 0, dy = 0;
            float a = 1, b = 0, c = 0, d = 1;

            if ((flags & 0x0001) != 0) { // ARG_1_AND_2_ARE_WORDS
                if ((flags & 0x0002) != 0) { // ARGS_ARE_XY_VALUES
                    dx = buf.getShort();
                    dy = buf.getShort();
                } else {
                    buf.getShort();
                    buf.getShort(); // point indices, skip
                }
            } else {
                if ((flags & 0x0002) != 0) {
                    dx = buf.get();
                    dy = buf.get();
                } else {
                    buf.get();
                    buf.get();
                }
            }

            if ((flags & 0x0008) != 0) { // WE_HAVE_A_SCALE
                a = d = buf.getShort() / 16384.0f;
            } else if ((flags & 0x0040) != 0) { // WE_HAVE_AN_X_AND_Y_SCALE
                a = buf.getShort() / 16384.0f;
                d = buf.getShort() / 16384.0f;
            } else if ((flags & 0x0080) != 0) { // WE_HAVE_A_TWO_BY_TWO
                a = buf.getShort() / 16384.0f;
                b = buf.getShort() / 16384.0f;
                c = buf.getShort() / 16384.0f;
                d = buf.getShort() / 16384.0f;
            }

            GeneralPath component = getOutline(componentGlyphId);
            var transform = new AffineTransform(a, b, c, d, dx, dy);
            component.transform(transform);
            path.append(component, false);

        } while ((flags & 0x0020) != 0); // MORE_COMPONENTS

        return path;
    }
}
