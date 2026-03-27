package org.fentanylsolutions.minemoticon.colorfont;

public class VariationAxis {

    private final String tag;
    private final float minValue;
    private final float defaultValue;
    private final float maxValue;

    public VariationAxis(String tag, float minValue, float defaultValue, float maxValue) {
        this.tag = tag;
        this.minValue = minValue;
        this.defaultValue = defaultValue;
        this.maxValue = maxValue;
    }

    public String getTag() {
        return tag;
    }

    public float getMinValue() {
        return minValue;
    }

    public float getDefaultValue() {
        return defaultValue;
    }

    public float getMaxValue() {
        return maxValue;
    }

    public boolean isSupportedForUi() {
        return "wght".equals(tag) || "wdth".equals(tag) || "ital".equals(tag) || "slnt".equals(tag);
    }

    public String getDisplayName() {
        if ("wght".equals(tag)) return "Weight";
        if ("wdth".equals(tag)) return "Width";
        if ("ital".equals(tag)) return "Italic";
        if ("slnt".equals(tag)) return "Slant";
        return tag;
    }
}
