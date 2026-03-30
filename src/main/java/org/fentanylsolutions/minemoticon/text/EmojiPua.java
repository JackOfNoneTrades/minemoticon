package org.fentanylsolutions.minemoticon.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public final class EmojiPua {

    public static final int START = 0xE000;
    public static final int END = 0xF8FF;
    public static final int COUNT = END - START + 1;
    public static final int TOKEN_LENGTH = 2;
    public static final int TOKEN_COUNT = COUNT * (COUNT - 1);
    public static final int RESERVED_ONE_OFF_COUNT = Math.min(10000, TOKEN_COUNT);

    private EmojiPua() {}

    public static boolean isPua(char c) {
        return c >= START && c <= END;
    }

    public static boolean isPuaToken(String token) {
        return token != null && token.length() == TOKEN_LENGTH
            && isPua(token.charAt(0))
            && isPua(token.charAt(1))
            && token.charAt(0) != token.charAt(1);
    }

    public static String tokenAt(String text, int index) {
        if (text == null || index < 0 || index + TOKEN_LENGTH > text.length()) {
            return null;
        }
        String token = text.substring(index, index + TOKEN_LENGTH);
        return isPuaToken(token) ? token : null;
    }

    public static String fromTokenIndex(int index) {
        if (index < 0 || index >= TOKEN_COUNT) {
            throw new IllegalArgumentException("PUA token index out of range: " + index);
        }

        int first = index / (COUNT - 1);
        int secondIndex = index % (COUNT - 1);
        int second = secondIndex >= first ? secondIndex + 1 : secondIndex;
        return new String(new char[] { (char) (START + first), (char) (START + second) });
    }

    public static int toTokenIndex(String token) {
        if (!isPuaToken(token)) {
            return -1;
        }

        int first = token.charAt(0) - START;
        int second = token.charAt(1) - START;
        int secondIndex = second > first ? second - 1 : second;
        return first * (COUNT - 1) + secondIndex;
    }

    public static int reservedOneOffStartIndex() {
        return TOKEN_COUNT - RESERVED_ONE_OFF_COUNT;
    }

    public static boolean isReservedOneOffIndex(int index) {
        return index >= reservedOneOffStartIndex() && index < TOKEN_COUNT;
    }

    public static boolean isReservedOneOffToken(String token) {
        int index = toTokenIndex(token);
        return index >= 0 && isReservedOneOffIndex(index);
    }

    public static String fromReservedOneOffSlot(int slot) {
        if (slot < 0 || slot >= RESERVED_ONE_OFF_COUNT) {
            throw new IllegalArgumentException("Reserved one-off slot out of range: " + slot);
        }
        return fromTokenIndex(reservedOneOffStartIndex() + slot);
    }

    public static String encodeLeasePayload(Iterable<String> tokens) {
        if (tokens == null) {
            return "";
        }

        StringBuilder payload = new StringBuilder();
        Iterator<String> iterator = tokens.iterator();
        while (iterator.hasNext()) {
            String token = iterator.next();
            if (isPuaToken(token)) {
                payload.append(token);
            }
        }
        return payload.toString();
    }

    public static List<String> decodeLeasePayload(String payload) {
        if (payload == null || payload.isEmpty()) {
            return Collections.emptyList();
        }
        if (payload.length() % TOKEN_LENGTH != 0) {
            throw new IllegalArgumentException("Invalid PUA lease payload length: " + payload.length());
        }

        List<String> tokens = new ArrayList<>(payload.length() / TOKEN_LENGTH);
        for (int i = 0; i < payload.length(); i += TOKEN_LENGTH) {
            String token = payload.substring(i, i + TOKEN_LENGTH);
            if (!isPuaToken(token)) {
                throw new IllegalArgumentException("Invalid PUA token in payload");
            }
            tokens.add(token);
        }
        return tokens;
    }
}
