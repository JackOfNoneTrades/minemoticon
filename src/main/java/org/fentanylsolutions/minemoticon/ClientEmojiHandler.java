package org.fentanylsolutions.minemoticon;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
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
    public static boolean error = false;

    public static void setup() {
        if (EmojiConfig.enableTwemoji) {
            loadTwemojis();
        }
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
        } catch (Exception e) {
            error = true;
            Minemoticon.LOG.error("Failed to load twemojis", e);
        }
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
