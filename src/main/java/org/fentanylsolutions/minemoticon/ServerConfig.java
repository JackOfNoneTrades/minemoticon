package org.fentanylsolutions.minemoticon;

import com.gtnewhorizon.gtnhlib.config.Config;

@Config(modid = Minemoticon.MODID, category = "server", configSubDirectory = "minemoticon")
@Config.Sync
public class ServerConfig {

    @Config.Comment("Allow clients to send custom emotes to other players")
    @Config.DefaultBoolean(true)
    public static boolean allowClientEmotes;

    @Config.Comment("Max size in bytes for a client emote image")
    @Config.DefaultInt(32768)
    @Config.RangeInt(min = 1024, max = 262144)
    public static int maxClientEmoteSize;
}
