package org.fentanylsolutions.minemoticon.font;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class FontVariationConfig {

    public static final String SIZE_KEY = "size";
    public static final String WIDTH_KEY = "width";
    public static final String Y_OFFSET_KEY = "y";
    public static final float MIN_SIZE = 8.0f;
    public static final float MAX_SIZE = 32.0f;
    public static final float DEFAULT_WIDTH_PERCENT = 100.0f;
    public static final float MIN_WIDTH_PERCENT = 50.0f;
    public static final float MAX_WIDTH_PERCENT = 200.0f;
    public static final float DEFAULT_Y_OFFSET = 0.0f;
    public static final float MIN_Y_OFFSET = -8.0f;
    public static final float MAX_Y_OFFSET = 8.0f;

    private FontVariationConfig() {}

    public static Map<String, Map<String, Float>> parse(String[] entries) {
        Map<String, Map<String, Float>> result = new LinkedHashMap<>();
        if (entries == null) {
            return result;
        }

        for (String entry : entries) {
            if (entry == null || entry.trim()
                .isEmpty()) continue;
            int split = entry.indexOf('|');
            if (split <= 0 || split >= entry.length() - 1) continue;

            String fontId = entry.substring(0, split);
            String settingsPart = entry.substring(split + 1);
            Map<String, Float> settings = parseSettings(settingsPart);
            if (!settings.isEmpty()) {
                result.put(fontId, settings);
            }
        }
        return result;
    }

    public static String[] encode(Map<String, Map<String, Float>> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return new String[0];
        }

        List<String> encoded = new ArrayList<>();
        TreeMap<String, Map<String, Float>> sortedFonts = new TreeMap<>(overrides);
        for (Map.Entry<String, Map<String, Float>> fontEntry : sortedFonts.entrySet()) {
            if (fontEntry.getValue() == null || fontEntry.getValue()
                .isEmpty()) continue;
            StringBuilder builder = new StringBuilder(fontEntry.getKey()).append('|');
            boolean first = true;
            TreeMap<String, Float> sortedSettings = new TreeMap<>(fontEntry.getValue());
            for (Map.Entry<String, Float> setting : sortedSettings.entrySet()) {
                if (!first) builder.append(';');
                first = false;
                builder.append(setting.getKey())
                    .append('=')
                    .append(formatValue(setting.getValue()));
            }
            encoded.add(builder.toString());
        }
        return encoded.toArray(new String[0]);
    }

    public static Map<String, Float> copySettings(Map<String, Float> settings) {
        if (settings == null || settings.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(settings);
    }

    public static String signature(Map<String, Float> settings) {
        if (settings == null || settings.isEmpty()) {
            return "default";
        }

        StringBuilder builder = new StringBuilder();
        TreeMap<String, Float> sortedSettings = new TreeMap<>(settings);
        for (Map.Entry<String, Float> entry : sortedSettings.entrySet()) {
            if (builder.length() > 0) builder.append(';');
            builder.append(entry.getKey())
                .append('=')
                .append(formatValue(entry.getValue()));
        }
        return builder.toString();
    }

    public static Map<String, Float> copyVariationSettings(Map<String, Float> settings) {
        Map<String, Float> copy = copySettings(settings);
        copy.remove(SIZE_KEY);
        copy.remove(WIDTH_KEY);
        copy.remove(Y_OFFSET_KEY);
        return copy;
    }

    public static Float getExplicitDisplayHeight(Map<String, Float> settings) {
        if (settings == null) return null;
        Float value = settings.get(SIZE_KEY);
        if (value == null) return null;
        return clampDisplayHeight(value);
    }

    public static float getDisplayHeight(Map<String, Float> settings, float defaultValue) {
        Float value = getExplicitDisplayHeight(settings);
        return value != null ? value : clampDisplayHeight(defaultValue);
    }

    public static float getWidthPercent(Map<String, Float> settings, float defaultValue) {
        if (settings == null) return clampWidthPercent(defaultValue);
        Float value = settings.get(WIDTH_KEY);
        return value != null ? clampWidthPercent(value) : clampWidthPercent(defaultValue);
    }

    public static float getWidthScale(Map<String, Float> settings, float defaultValuePercent) {
        return getWidthPercent(settings, defaultValuePercent) / 100.0f;
    }

    public static float clampWidthPercent(float value) {
        return Math.max(MIN_WIDTH_PERCENT, Math.min(MAX_WIDTH_PERCENT, value));
    }

    public static float getVerticalOffset(Map<String, Float> settings, float defaultValue) {
        if (settings == null) return clampVerticalOffset(defaultValue);
        Float value = settings.get(Y_OFFSET_KEY);
        return value != null ? clampVerticalOffset(value) : clampVerticalOffset(defaultValue);
    }

    public static float clampVerticalOffset(float value) {
        return Math.max(MIN_Y_OFFSET, Math.min(MAX_Y_OFFSET, value));
    }

    public static float clampDisplayHeight(float value) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, value));
    }

    private static Map<String, Float> parseSettings(String settingsPart) {
        if (settingsPart == null || settingsPart.trim()
            .isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Float> settings = new LinkedHashMap<>();
        for (String chunk : settingsPart.split(";")) {
            if (chunk == null || chunk.trim()
                .isEmpty()) continue;
            int split = chunk.indexOf('=');
            if (split <= 0 || split >= chunk.length() - 1) continue;
            String tag = chunk.substring(0, split)
                .trim()
                .toLowerCase(Locale.ROOT);
            try {
                float value = Float.parseFloat(
                    chunk.substring(split + 1)
                        .trim());
                settings.put(tag, value);
            } catch (NumberFormatException ignored) {}
        }
        return settings;
    }

    private static String formatValue(float value) {
        if (Math.abs(value - Math.round(value)) < 0.0005f) {
            return Integer.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
