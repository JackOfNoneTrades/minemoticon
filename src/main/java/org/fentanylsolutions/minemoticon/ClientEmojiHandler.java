package org.fentanylsolutions.minemoticon;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.fentanylsolutions.minemoticon.api.Emoji;
import org.fentanylsolutions.minemoticon.api.EmojiFromTwitmoji;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public class ClientEmojiHandler {

    public static final Map<String, List<Emoji>> EMOJI_MAP = new LinkedHashMap<>();
    public static final List<Emoji> EMOJI_LIST = new ArrayList<>();
    public static final List<Emoji> EMOJI_WITH_TEXTS = new ArrayList<>();
    public static final Map<String, Emoji> EMOJI_LOOKUP = new HashMap<>();
    // Keyed by first char of the unicode string, values sorted longest-first
    public static final Map<Character, List<String>> UNICODE_KEYS_BY_CHAR = new HashMap<>();
    public static final Map<String, Emoji> EMOJI_UNICODE_LOOKUP = new HashMap<>();
    public static final List<String> CATEGORIES = new ArrayList<>();
    // Flat list: String (category name) or Emoji[] (row of up to 9)
    public static final List<Object> PICKER_LINES = new ArrayList<>();
    // Maps category name to its line index in PICKER_LINES
    public static final Map<String, Integer> CATEGORY_LINE_INDEX = new LinkedHashMap<>();

    // Standard category order matching most chat platforms
    private static final String[] CATEGORY_ORDER = { "Smileys & Emotion", "People & Body", "Animals & Nature",
        "Food & Drink", "Travel & Places", "Activities", "Objects", "Symbols", "Flags" };
    public static boolean error = false;

    public static void setup() {
        if (EmojiConfig.enableTwemoji) {
            loadTwemojis();
        }
        buildPickerData();
        Minemoticon.LOG.info("Loaded {} emojis", EMOJI_LIST.size());
    }

    public static void loadTwemojis() {
        try {
            var jsonText = readStringFromURL("https://raw.githubusercontent.com/iamcal/emoji-data/master/emoji.json");
            var root = new JsonParser().parse(jsonText);

            for (JsonElement element : root.getAsJsonArray()) {
                var obj = element.getAsJsonObject();
                if (!obj.get("has_img_twitter")
                    .getAsBoolean()) continue;
                if ("Component".equals(
                    obj.get("category")
                        .getAsString()))
                    continue;

                var emoji = new EmojiFromTwitmoji();
                emoji.name = obj.get("short_name")
                    .getAsString();
                emoji.location = obj.get("image")
                    .getAsString();
                emoji.sort = obj.get("sort_order")
                    .getAsInt();
                emoji.category = obj.get("category")
                    .getAsString();

                obj.get("short_names")
                    .getAsJsonArray()
                    .forEach(e -> emoji.strings.add(":" + e.getAsString() + ":"));

                if (emoji.strings.contains(":face_with_symbols_on_mouth:")) {
                    emoji.strings.add(":swear:");
                }

                if (!obj.get("texts")
                    .isJsonNull()) {
                    obj.get("texts")
                        .getAsJsonArray()
                        .forEach(e -> emoji.texts.add(e.getAsString()));
                }

                for (String key : emoji.strings) {
                    EMOJI_LOOKUP.put(key, emoji);
                }

                String unified = obj.get("unified")
                    .getAsString();
                emoji.unicodeString = unifiedToString(unified);
                registerUnicode(unified, emoji);

                EMOJI_MAP.computeIfAbsent(emoji.category, k -> new ArrayList<>())
                    .add(emoji);
                EMOJI_LIST.add(emoji);

                if (!emoji.texts.isEmpty()) {
                    EMOJI_WITH_TEXTS.add(emoji);
                }
            }

            EMOJI_WITH_TEXTS.sort(Comparator.comparingInt(e -> e.sort));
            EMOJI_MAP.values()
                .forEach(list -> list.sort(Comparator.comparingInt(e -> e.sort)));

            // Sort unicode keys longest-first so we match the most specific variant
            UNICODE_KEYS_BY_CHAR.values()
                .forEach(list -> list.sort((a, b) -> b.length() - a.length()));
        } catch (Exception e) {
            error = true;
            Minemoticon.LOG.error("Failed to load twemojis", e);
        }
    }

    private static void buildPickerData() {
        CATEGORIES.clear();
        PICKER_LINES.clear();
        CATEGORY_LINE_INDEX.clear();

        // Server/custom categories first (anything not in the standard list)
        for (var entry : EMOJI_MAP.entrySet()) {
            if (!isStandardCategory(entry.getKey())) {
                addCategory(entry.getKey(), entry.getValue());
            }
        }
        // Then standard categories in platform-standard order
        for (String cat : CATEGORY_ORDER) {
            if (EMOJI_MAP.containsKey(cat)) {
                addCategory(cat, EMOJI_MAP.get(cat));
            }
        }
    }

    private static boolean isStandardCategory(String name) {
        for (String cat : CATEGORY_ORDER) {
            if (cat.equals(name)) return true;
        }
        return false;
    }

    private static void addCategory(String name, List<Emoji> emojis) {
        CATEGORIES.add(name);
        CATEGORY_LINE_INDEX.put(name, PICKER_LINES.size());
        PICKER_LINES.add(name);
        for (int i = 0; i < emojis.size(); i += 9) {
            var row = new Emoji[9];
            for (int j = 0; j < 9 && i + j < emojis.size(); j++) {
                row[j] = emojis.get(i + j);
            }
            PICKER_LINES.add(row);
        }
    }

    private static void registerUnicode(String unified, Emoji emoji) {
        String str = unifiedToString(unified);
        EMOJI_UNICODE_LOOKUP.put(str, emoji);
        UNICODE_KEYS_BY_CHAR.computeIfAbsent(str.charAt(0), k -> new ArrayList<>())
            .add(str);

        // Also register without variation selectors so both ☃ and ☃️ work
        String stripped = unified.replaceAll("-FE0F", "");
        if (!stripped.equals(unified)) {
            String strStripped = unifiedToString(stripped);
            EMOJI_UNICODE_LOOKUP.putIfAbsent(strStripped, emoji);
            UNICODE_KEYS_BY_CHAR.computeIfAbsent(strStripped.charAt(0), k -> new ArrayList<>())
                .add(strStripped);
        }
    }

    private static String unifiedToString(String unified) {
        var sb = new StringBuilder();
        for (String hex : unified.split("-")) {
            sb.appendCodePoint(Integer.parseInt(hex, 16));
        }
        return sb.toString();
    }

    public static String readStringFromURL(String requestURL) {
        try (var scanner = new Scanner(new URL(requestURL).openStream(), StandardCharsets.UTF_8.toString())) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to read from URL: {}", requestURL, e);
            return "";
        }
    }
}
