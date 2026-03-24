package org.fentanylsolutions.minemoticon;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.fentanylsolutions.minemoticon.api.Emoji;
import org.fentanylsolutions.minemoticon.api.EmojiFromFont;
import org.fentanylsolutions.minemoticon.api.EmojiFromPack;
import org.fentanylsolutions.minemoticon.api.EmojiFromTwitmoji;
import org.fentanylsolutions.minemoticon.colorfont.ColorFont;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public class ClientEmojiHandler {

    public static final Map<String, List<Emoji>> EMOJI_MAP = new LinkedHashMap<>();
    public static final List<Emoji> EMOJI_LIST = new ArrayList<>();
    public static final List<Emoji> EMOJI_WITH_TEXTS = new ArrayList<>();
    public static final Map<String, Emoji> EMOJI_LOOKUP = new HashMap<>();
    // All emojis by short name (for suggestion helper collision display)
    public static final Map<String, List<Emoji>> EMOJI_BY_SHORT_NAME = new HashMap<>();
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
    // Pack emojis tracked separately for reload cleanup
    private static final List<EmojiFromPack> PACK_EMOJIS = new ArrayList<>();
    // Maps pack category name -> icon emoji (for category pill in picker)
    public static final Map<String, Emoji> PACK_CATEGORY_ICONS = new HashMap<>();
    public static boolean error = false;
    private static volatile boolean ready = false;
    private static ColorFont colorFont;

    public static boolean isReady() {
        return ready;
    }

    public static void setup() {
        // Load color font for stock emoji rendering
        loadColorFont();

        // Load packs synchronously (local files, fast)
        loadPacks();

        // Load twemoji from bundled/cached data synchronously (instant, no network)
        if (EmojiConfig.enableTwemoji) {
            String localData = loadLocalEmojiJson();
            if (localData != null) {
                parseTwemojis(localData);
            }
        }

        buildPickerData();
        ready = true;
        Minemoticon.LOG.info("Loaded {} emojis ({} from packs)", EMOJI_LIST.size(), PACK_EMOJIS.size());

        // Check for emoji data updates in the background
        if (EmojiConfig.enableTwemoji && EmojiConfig.checkForEmojiUpdates) {
            new Thread(() -> {
                String updated = downloadEmojiJsonUpdate();
                if (updated != null) {
                    clearTwemojiData();
                    parseTwemojis(updated);
                    buildPickerData();
                    Minemoticon.LOG.info("Updated emoji data, now {} emojis", EMOJI_LIST.size());
                }
            }, "Minemoticon Emoji Updater").start();
        }
    }

    public static void reloadPacks() {
        // Destroy old pack textures
        for (var pack : PACK_EMOJIS) {
            pack.destroy();
            for (String key : pack.strings) {
                EMOJI_LOOKUP.remove(key);
            }
            EMOJI_LIST.remove(pack);
        }
        EMOJI_BY_SHORT_NAME.values()
            .forEach(list -> list.removeIf(e -> e instanceof EmojiFromPack));
        EMOJI_BY_SHORT_NAME.values()
            .removeIf(List::isEmpty);
        for (var cat : new ArrayList<>(EMOJI_MAP.keySet())) {
            if (!isStandardCategory(cat)) {
                EMOJI_MAP.remove(cat);
            }
        }
        PACK_EMOJIS.clear();
        PACK_CATEGORY_ICONS.clear();

        loadPacks();
        buildPickerData();
        Minemoticon.LOG.info("Reloaded packs: {} pack emojis", PACK_EMOJIS.size());
    }

    private static void loadPacks() {
        var packs = EmojiPackLoader.loadPacks();
        for (var pack : packs) {
            EmojiFromPack iconEmoji = null;

            for (var entry : pack.entries) {
                var emoji = new EmojiFromPack(entry.name, pack.displayName, pack.folderName, entry.imageFile);
                // Namespaced key always registered (for wire format)
                EMOJI_LOOKUP.put(emoji.getNamespaced(), emoji);
                // Short key: pack overrides stock for rendering
                EMOJI_LOOKUP.put(":" + entry.name + ":", emoji);
                registerShortName(":" + entry.name + ":", emoji);
                EMOJI_MAP.computeIfAbsent(pack.displayName, k -> new ArrayList<>())
                    .add(emoji);
                EMOJI_LIST.add(emoji);
                PACK_EMOJIS.add(emoji);

                if (entry.name.equals(pack.iconEmojiName)) {
                    iconEmoji = emoji;
                }
                if (iconEmoji == null) {
                    iconEmoji = emoji; // first emoji as fallback
                }
            }

            if (iconEmoji != null) {
                PACK_CATEGORY_ICONS.put(pack.displayName, iconEmoji);
            }
        }
    }

    private static void loadColorFont() {
        try (var stream = ClientEmojiHandler.class.getResourceAsStream("/assets/minemoticon/twemoji.ttf")) {
            if (stream != null) {
                colorFont = ColorFont.load(stream);
                Minemoticon.LOG.info("Loaded Twemoji COLR font");
            } else {
                Minemoticon.LOG.warn("Twemoji font not found in resources, falling back to CDN");
            }
        } catch (Exception e) {
            Minemoticon.LOG.warn("Failed to load Twemoji font, falling back to CDN", e);
        }
    }

    private static final String EMOJI_JSON_URL = "https://raw.githubusercontent.com/iamcal/emoji-data/master/emoji.json";
    private static final File EMOJI_CACHE_FILE = new File("config/minemoticon/emoji_data.json");
    private static final String BUNDLED_RESOURCE = "/assets/minemoticon/emoji_data.json";

    // Load emoji JSON from cache or bundled resource (no network)
    private static String loadLocalEmojiJson() {
        // Try disk cache first
        if (EMOJI_CACHE_FILE.isFile()) {
            try {
                var data = new String(Files.readAllBytes(EMOJI_CACHE_FILE.toPath()), StandardCharsets.UTF_8);
                Minemoticon.debug("Loaded emoji data from cache ({} bytes)", data.length());
                return data;
            } catch (IOException e) {
                Minemoticon.LOG.warn("Failed to read emoji cache", e);
            }
        }

        // Fall back to bundled resource in jar
        try (var stream = ClientEmojiHandler.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (stream != null) {
                var scanner = new Scanner(stream, StandardCharsets.UTF_8.toString());
                scanner.useDelimiter("\\A");
                var data = scanner.hasNext() ? scanner.next() : "";
                Minemoticon.debug("Loaded emoji data from bundled resource ({} bytes)", data.length());
                return data;
            }
        } catch (IOException e) {
            Minemoticon.LOG.warn("Failed to read bundled emoji data", e);
        }

        return null;
    }

    // Check for updated emoji data, returns new JSON or null if no update
    private static String downloadEmojiJsonUpdate() {
        try {
            var conn = (HttpURLConnection) new URL(EMOJI_JSON_URL).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Minemoticon)");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            if (EMOJI_CACHE_FILE.isFile()) {
                conn.setIfModifiedSince(EMOJI_CACHE_FILE.lastModified());
            }

            conn.connect();
            int code = conn.getResponseCode();

            if (code == 304) {
                Minemoticon.debug("Emoji data not modified, using current data");
                return null;
            }

            if (code / 100 == 2) {
                try (var scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8.toString())) {
                    scanner.useDelimiter("\\A");
                    String fresh = scanner.hasNext() ? scanner.next() : "";
                    if (!fresh.isEmpty()) {
                        EMOJI_CACHE_FILE.getParentFile()
                            .mkdirs();
                        Files.write(EMOJI_CACHE_FILE.toPath(), fresh.getBytes(StandardCharsets.UTF_8));
                        Minemoticon.debug("Downloaded updated emoji data ({} bytes)", fresh.length());
                        return fresh;
                    }
                }
            }

            Minemoticon.LOG.warn("Emoji data download returned HTTP {}", code);
        } catch (IOException e) {
            Minemoticon.debug("Failed to check for emoji updates: {}", e.getMessage());
        }
        return null;
    }

    private static void parseTwemojis(String jsonText) {
        try {
            var root = new JsonParser().parse(jsonText);

            for (JsonElement element : root.getAsJsonArray()) {
                var obj = element.getAsJsonObject();
                if (!obj.get("has_img_twitter")
                    .getAsBoolean()) continue;
                if ("Component".equals(
                    obj.get("category")
                        .getAsString()))
                    continue;

                String emojiName = obj.get("short_name")
                    .getAsString();
                String location = obj.get("image")
                    .getAsString();
                int sort = obj.get("sort_order")
                    .getAsInt();
                String category = obj.get("category")
                    .getAsString();
                String unified = obj.get("unified")
                    .getAsString();
                String unicodeStr = unifiedToString(unified);

                // Parse codepoints for font rendering
                int[] codepoints = unified.chars()
                    .count() > 0 ? java.util.Arrays.stream(unified.split("-"))
                        .mapToInt(hex -> Integer.parseInt(hex, 16))
                        .toArray() : new int[0];

                // Try font rendering first, fall back to CDN download
                Emoji emoji;
                if (colorFont != null && codepoints.length > 0 && colorFont.hasGlyph(codepoints[0])) {
                    var fontEmoji = new EmojiFromFont(colorFont, codepoints);
                    fontEmoji.name = emojiName;
                    fontEmoji.location = location;
                    fontEmoji.sort = sort;
                    fontEmoji.category = category;
                    fontEmoji.unicodeString = unicodeStr;
                    emoji = fontEmoji;
                } else {
                    var cdnEmoji = new EmojiFromTwitmoji();
                    cdnEmoji.name = emojiName;
                    cdnEmoji.location = location;
                    cdnEmoji.sort = sort;
                    cdnEmoji.category = category;
                    cdnEmoji.unicodeString = unicodeStr;
                    emoji = cdnEmoji;
                }

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
                    registerShortName(key, emoji);
                }

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
            UNICODE_KEYS_BY_CHAR.values()
                .forEach(list -> list.sort((a, b) -> b.length() - a.length()));
        } catch (Exception e) {
            error = true;
            Minemoticon.LOG.error("Failed to parse twemoji data", e);
        }
    }

    private static boolean isStockEmoji(Emoji e) {
        return e instanceof EmojiFromTwitmoji || e instanceof EmojiFromFont;
    }

    private static void clearTwemojiData() {
        EMOJI_LIST.removeIf(ClientEmojiHandler::isStockEmoji);
        EMOJI_WITH_TEXTS.removeIf(ClientEmojiHandler::isStockEmoji);
        EMOJI_LOOKUP.values()
            .removeIf(ClientEmojiHandler::isStockEmoji);
        EMOJI_UNICODE_LOOKUP.values()
            .removeIf(ClientEmojiHandler::isStockEmoji);
        EMOJI_BY_SHORT_NAME.values()
            .forEach(list -> list.removeIf(ClientEmojiHandler::isStockEmoji));
        EMOJI_BY_SHORT_NAME.values()
            .removeIf(List::isEmpty);
        UNICODE_KEYS_BY_CHAR.clear();
        for (String cat : CATEGORY_ORDER) {
            EMOJI_MAP.remove(cat);
        }
    }

    public static void buildPickerData() {
        CATEGORIES.clear();
        PICKER_LINES.clear();
        CATEGORY_LINE_INDEX.clear();

        // Server pack categories first (contain EmojiFromRemote with usable=true)
        for (var entry : EMOJI_MAP.entrySet()) {
            if (!isStandardCategory(entry.getKey()) && hasRemoteUsable(entry.getValue())) {
                addCategory(entry.getKey(), entry.getValue());
            }
        }
        // Then client pack categories (non-standard, no remote usable)
        for (var entry : EMOJI_MAP.entrySet()) {
            if (!isStandardCategory(entry.getKey()) && !CATEGORIES.contains(entry.getKey())) {
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

    public static void registerShortName(String shortKey, Emoji emoji) {
        EMOJI_BY_SHORT_NAME.computeIfAbsent(shortKey, k -> new ArrayList<>())
            .add(emoji);
    }

    private static boolean hasRemoteUsable(List<Emoji> emojis) {
        for (var e : emojis) {
            if (e instanceof org.fentanylsolutions.minemoticon.api.EmojiFromRemote r && r.isUsable()) return true;
        }
        return false;
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

}
