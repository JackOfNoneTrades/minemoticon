package org.fentanylsolutions.minemoticon;

import com.gtnewhorizon.gtnhlib.config.Config;

@Config(modid = Minemoticon.MODID, category = "emoji", configSubDirectory = "minemoticon")
@Config.RequiresMcRestart
public class EmojiConfig {

    @Config.Comment("Enable loading Twitter emojis (Twemoji)")
    @Config.DefaultBoolean(true)
    public static boolean enableTwemoji;

    @Config.Comment("Max concurrent emoji image downloads")
    @Config.DefaultInt(4)
    @Config.RangeInt(min = 1, max = 16)
    public static int maxDownloadThreads;

    @Config.Comment("Emoji shown on the picker button (without colons)")
    @Config.DefaultString("grinning")
    public static String pickerButtonEmoji;

    @Config.Comment("Close the picker after selecting an emoji")
    @Config.DefaultBoolean(true)
    public static boolean closePickerOnSelect;

    @Config.Comment("Render custom emotes sent by other players")
    @Config.DefaultBoolean(true)
    public static boolean receiveClientEmotes;

    @Config.Comment("Check for emoji data updates on startup (uses If-Modified-Since)")
    @Config.DefaultBoolean(true)
    public static boolean checkForEmojiUpdates;

    @Config.Comment("Emoji font filename in config/minemoticon/fonts/ (empty = bundled Twemoji)")
    @Config.DefaultString("")
    public static String emojiFont;

    @Config.Comment("Enable debug logging")
    @Config.DefaultBoolean(false)
    public static boolean debugMode;
}
