package org.fentanylsolutions.minemoticon.font;

public final class TextRunLayout {

    private final float[] penPositions;
    private final float totalAdvance;

    public TextRunLayout(float[] penPositions, float totalAdvance) {
        this.penPositions = penPositions;
        this.totalAdvance = totalAdvance;
    }

    public int getGlyphCount() {
        return penPositions.length;
    }

    public float getPenPosition(int glyphIndex) {
        return penPositions[glyphIndex];
    }

    public float getTotalAdvance() {
        return totalAdvance;
    }
}
