package org.fentanylsolutions.minemoticon.colorfont;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Parses COLR v0 table: base glyph -> list of colored layers.
public class ColrParser {

    public static class Layer {

        public final int glyphId;
        public final int paletteIndex;

        public Layer(int glyphId, int paletteIndex) {
            this.glyphId = glyphId;
            this.paletteIndex = paletteIndex;
        }
    }

    private final Map<Integer, List<Layer>> layers = new HashMap<>();

    public ColrParser(ByteBuffer colr) {
        int base = colr.position();
        int version = colr.getShort() & 0xFFFF;
        int numBaseGlyphRecords = colr.getShort() & 0xFFFF;
        int baseGlyphRecordOffset = colr.getInt();
        int layerRecordOffset = colr.getInt();
        int numLayerRecords = colr.getShort() & 0xFFFF;

        // Read layer records
        Layer[] allLayers = new Layer[numLayerRecords];
        colr.position(base + layerRecordOffset);
        for (int i = 0; i < numLayerRecords; i++) {
            int glyphId = colr.getShort() & 0xFFFF;
            int paletteIndex = colr.getShort() & 0xFFFF;
            allLayers[i] = new Layer(glyphId, paletteIndex);
        }

        // Read base glyph records
        colr.position(base + baseGlyphRecordOffset);
        for (int i = 0; i < numBaseGlyphRecords; i++) {
            int baseGlyphId = colr.getShort() & 0xFFFF;
            int firstLayerIndex = colr.getShort() & 0xFFFF;
            int numLayers = colr.getShort() & 0xFFFF;

            var layerList = new ArrayList<Layer>(numLayers);
            for (int j = 0; j < numLayers; j++) {
                layerList.add(allLayers[firstLayerIndex + j]);
            }
            layers.put(baseGlyphId, layerList);
        }
    }

    public List<Layer> getLayers(int glyphId) {
        return layers.get(glyphId);
    }

    public boolean hasLayers(int glyphId) {
        return layers.containsKey(glyphId);
    }
}
