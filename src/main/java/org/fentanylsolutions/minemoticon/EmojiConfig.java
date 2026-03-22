package org.fentanylsolutions.minemoticon;

import com.gtnewhorizon.gtnhlib.config.Config;

@Config(modid = Minemoticon.MODID, category = "emoji")
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
}
