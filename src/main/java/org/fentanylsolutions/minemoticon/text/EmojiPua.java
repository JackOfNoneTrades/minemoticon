package org.fentanylsolutions.minemoticon.text;

public final class EmojiPua {

    public static final int START = 0xE000;
    public static final int END = 0xF8FF;
    public static final int COUNT = END - START + 1;

    private EmojiPua() {}

    public static boolean isPua(char c) {
        return c >= START && c <= END;
    }

    public static char fromIndex(int index) {
        if (index < 0 || index >= COUNT) {
            throw new IllegalArgumentException("PUA index out of range: " + index);
        }
        return (char) (START + index);
    }

    public static int toIndex(char c) {
        return c - START;
    }

    public static String toString(char c) {
        return String.valueOf(c);
    }
}
