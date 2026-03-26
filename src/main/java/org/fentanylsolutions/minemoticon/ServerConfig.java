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
    @Config.RangeInt(min = 1024, max = 9999999)
    public static int maxClientEmoteSize;

    @Config.Comment("Persistent custom emoji quota per player in bytes")
    @Config.DefaultInt(2097152)
    @Config.RangeInt(min = 32768, max = 67108864)
    public static int maxStoredClientEmojiBytesPerUser;
}
