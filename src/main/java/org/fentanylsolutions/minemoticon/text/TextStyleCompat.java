package org.fentanylsolutions.minemoticon.text;

public final class TextStyleCompat {

    public static final char FORMAT = '\u00A7';
    public static final int SECTION_X_PAYLOAD = 12;
    public static final int SECTION_X_LENGTH = 14;
    public static final int GRADIENT_PAYLOAD = 28;
    public static final int GRADIENT_LENGTH = 30;

    private static final String VALID_SINGLE_CODES = "0123456789abcdefklmnorqzv";

    private static String lastInput;
    private static String lastOutput;

    private TextStyleCompat() {}

    public static String normalize(String text) {
        if (text == null) {
            return null;
        }

        if (text == lastInput) {
            return lastOutput;
        }

        String out = normalizeImpl(text);
        lastInput = text;
        lastOutput = out;
        return out;
    }

    private static String normalizeImpl(String text) {
        int idx = text.indexOf('&');
        if (idx < 0) {
            return text;
        }

        int len = text.length();
        StringBuilder sb = null;
        int last = 0;
        while (idx >= 0 && idx + 1 < len) {
            if (text.charAt(idx + 1) == '#' && idx + 7 < len && isHexRun(text, idx + 2, 6)) {
                if (sb == null) {
                    sb = new StringBuilder(len + 16);
                }
                sb.append(text, last, idx);
                appendSectionX(sb, text, idx + 2);
                last = idx + 8;
                idx = text.indexOf('&', last);
                continue;
            }

            if (Character.toLowerCase(text.charAt(idx + 1)) == 'g' && idx + 17 < len
                && text.charAt(idx + 2) == '&'
                && text.charAt(idx + 3) == '#'
                && text.charAt(idx + 10) == '&'
                && text.charAt(idx + 11) == '#'
                && isHexRun(text, idx + 4, 6)
                && isHexRun(text, idx + 12, 6)) {
                if (sb == null) {
                    sb = new StringBuilder(len + 16);
                }
                sb.append(text, last, idx);
                sb.append(FORMAT)
                    .append('g');
                appendSectionX(sb, text, idx + 4);
                appendSectionX(sb, text, idx + 12);
                last = idx + 18;
                idx = text.indexOf('&', last);
                continue;
            }

            char code = Character.toLowerCase(text.charAt(idx + 1));
            if (VALID_SINGLE_CODES.indexOf(code) >= 0) {
                if (sb == null) {
                    sb = new StringBuilder(len + 16);
                }
                sb.append(text, last, idx);
                sb.append(FORMAT)
                    .append(text.charAt(idx + 1));
                last = idx + 2;
            }
            idx = text.indexOf('&', idx + 1);
        }

        if (sb == null) {
            return text;
        }
        sb.append(text, last, len);
        return sb.toString();
    }

    private static void appendSectionX(StringBuilder sb, String text, int start) {
        sb.append(FORMAT)
            .append('x');
        for (int i = 0; i < 6; i++) {
            sb.append(FORMAT)
                .append(text.charAt(start + i));
        }
    }

    private static boolean isHexRun(CharSequence text, int start, int count) {
        if (start < 0 || start + count > text.length()) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (Character.digit(text.charAt(start + i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    public static int tokenEnd(CharSequence text, int index) {
        if (text == null || index < 0 || index + 1 >= text.length() || text.charAt(index) != FORMAT) {
            return -1;
        }

        char code = Character.toLowerCase(text.charAt(index + 1));
        if (code == 'x' && parseSectionXAt(text, index) >= 0) {
            return index + SECTION_X_LENGTH;
        }
        if (code == 'g' && index + GRADIENT_LENGTH <= text.length()
            && parseSectionXAt(text, index + 2) >= 0
            && parseSectionXAt(text, index + 2 + SECTION_X_LENGTH) >= 0) {
            return index + GRADIENT_LENGTH;
        }
        return index + 2;
    }

    public static int inputTokenEnd(CharSequence text, int index) {
        int normalizedEnd = tokenEnd(text, index);
        if (normalizedEnd > index) {
            return normalizedEnd;
        }
        return rawTokenEnd(text, index);
    }

    public static int rawTokenEnd(CharSequence text, int index) {
        if (text == null || index < 0 || index + 1 >= text.length() || text.charAt(index) != '&') {
            return -1;
        }

        int length = text.length();
        if (text.charAt(index + 1) == '#' && index + 7 < length && isHexRun(text, index + 2, 6)) {
            return index + 8;
        }

        if (Character.toLowerCase(text.charAt(index + 1)) == 'g' && index + 17 < length
            && text.charAt(index + 2) == '&'
            && text.charAt(index + 3) == '#'
            && text.charAt(index + 10) == '&'
            && text.charAt(index + 11) == '#'
            && isHexRun(text, index + 4, 6)
            && isHexRun(text, index + 12, 6)) {
            return index + 18;
        }

        char code = Character.toLowerCase(text.charAt(index + 1));
        if (VALID_SINGLE_CODES.indexOf(code) >= 0) {
            return index + 2;
        }
        return -1;
    }

    public static int parseSectionXAt(CharSequence text, int index) {
        if (text == null || index < 0 || index + SECTION_X_LENGTH > text.length()) {
            return -1;
        }
        if (text.charAt(index) != FORMAT || Character.toLowerCase(text.charAt(index + 1)) != 'x') {
            return -1;
        }
        return parseHexPairs(text, index + 2, 6);
    }

    public static int parseHexPairs(CharSequence text, int start, int count) {
        if (text == null || start < 0 || start + count * 2 > text.length()) {
            return -1;
        }

        int rgb = 0;
        for (int i = 0; i < count; i++) {
            int pos = start + i * 2;
            if (text.charAt(pos) != FORMAT) {
                return -1;
            }
            int digit = Character.digit(text.charAt(pos + 1), 16);
            if (digit < 0) {
                return -1;
            }
            rgb = rgb << 4 | digit;
        }
        return rgb;
    }

    public static boolean hasExtendedStyle(String text) {
        if (text == null) {
            return false;
        }

        String normalized = normalize(text);
        for (int i = 0; i + 1 < normalized.length();) {
            if (normalized.charAt(i) != FORMAT) {
                i++;
                continue;
            }

            char code = Character.toLowerCase(normalized.charAt(i + 1));
            int tokenEnd = tokenEnd(normalized, i);
            if (code == 'x' || code == 'g' || code == 'q' || code == 'z') {
                return true;
            }
            i = tokenEnd > i ? tokenEnd : i + 2;
        }
        return false;
    }

    public static String activeFormatPrefix(String text, int end) {
        if (text == null || end <= 0) {
            return "";
        }

        String normalized = normalize(text.substring(0, Math.min(end, text.length())));
        String colorToken = "";
        String effectToken = "";
        boolean random = false;
        boolean bold = false;
        boolean strikethrough = false;
        boolean underline = false;
        boolean italic = false;
        boolean wave = false;

        for (int i = 0; i + 1 < normalized.length();) {
            int tokenEnd = tokenEnd(normalized, i);
            if (tokenEnd <= i) {
                i++;
                continue;
            }

            char code = Character.toLowerCase(normalized.charAt(i + 1));
            if (code == 'x') {
                colorToken = normalized.substring(i, tokenEnd);
                effectToken = "";
            } else if (code == 'g') {
                effectToken = normalized.substring(i, tokenEnd);
            } else if (code == 'q') {
                effectToken = normalized.substring(i, tokenEnd);
            } else if (code == 'z') {
                wave = !wave;
            } else if (code == 'r') {
                colorToken = "";
                effectToken = "";
                random = false;
                bold = false;
                strikethrough = false;
                underline = false;
                italic = false;
                wave = false;
            } else if (code >= '0' && code <= '9' || code >= 'a' && code <= 'f') {
                colorToken = normalized.substring(i, tokenEnd);
                effectToken = "";
                random = false;
                bold = false;
                strikethrough = false;
                underline = false;
                italic = false;
            } else if (code == 'k') {
                random = true;
            } else if (code == 'l') {
                bold = true;
            } else if (code == 'm') {
                strikethrough = true;
            } else if (code == 'n') {
                underline = true;
            } else if (code == 'o') {
                italic = true;
            }

            i = tokenEnd;
        }

        StringBuilder prefix = new StringBuilder();
        prefix.append(colorToken);
        prefix.append(effectToken);
        if (wave) {
            prefix.append(FORMAT)
                .append('z');
        }
        if (random) {
            prefix.append(FORMAT)
                .append('k');
        }
        if (bold) {
            prefix.append(FORMAT)
                .append('l');
        }
        if (strikethrough) {
            prefix.append(FORMAT)
                .append('m');
        }
        if (underline) {
            prefix.append(FORMAT)
                .append('n');
        }
        if (italic) {
            prefix.append(FORMAT)
                .append('o');
        }
        return prefix.toString();
    }
}
