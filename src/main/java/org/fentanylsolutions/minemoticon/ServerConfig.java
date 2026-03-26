package org.fentanylsolutions.minemoticon;

import net.minecraft.server.MinecraftServer;

import com.gtnewhorizon.gtnhlib.config.Config;

@Config(modid = Minemoticon.MODID, category = "server", configSubDirectory = "minemoticon")
@Config.Sync
public class ServerConfig {

    @Config.Comment("Allow clients to send custom emotes to other players")
    @Config.DefaultBoolean(true)
    public static boolean allowClientEmotes;

    @Config.Comment("Max size in bytes for a client emote image")
    @Config.DefaultInt(524288)
    @Config.RangeInt(min = 1024, max = 9999999)
    public static int maxClientEmoteSize;

    @Config.Comment("Persistent custom emoji quota per player in bytes (0 disables the byte limit)")
    @Config.DefaultInt(2097152)
    @Config.RangeInt(min = 0, max = 67108864)
    public static int maxStoredClientEmojiBytesPerUser;

    @Config.Comment("Persistent custom emoji quota per player by emoji count (0 disables the count limit)")
    @Config.DefaultInt(0)
    @Config.RangeInt(min = 0, max = 100000)
    public static int maxStoredClientEmojiCountPerUser;

    public static boolean isIntegratedSingleplayer() {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null && server.isSinglePlayer();
    }

    public static int getEffectiveMaxClientEmoteSize() {
        return isIntegratedSingleplayer() ? Integer.MAX_VALUE : maxClientEmoteSize;
    }

    public static long getEffectiveStoredClientEmojiBytesPerUser() {
        return isIntegratedSingleplayer() ? 0L : maxStoredClientEmojiBytesPerUser;
    }

    public static int getEffectiveStoredClientEmojiCountPerUser() {
        return isIntegratedSingleplayer() ? 0 : maxStoredClientEmojiCountPerUser;
    }
}
