package org.fentanylsolutions.minemoticon.network;

import org.fentanylsolutions.minemoticon.Minemoticon;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public class NetworkHandler {

    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(Minemoticon.MODID);

    private static final int ID_SERVER_PRESENCE = 0;
    private static final int ID_EMOTE_UPLOAD_REQUEST = 1;
    private static final int ID_EMOTE_BROADCAST = 2;
    private static final int ID_EMOTE_DATA_DOWNLOAD = 3;
    private static final int ID_EMOTE_REJECT = 4;
    private static final int ID_SERVER_PACK_CLEAR = 5;
    private static final int ID_CHAT_EMOTE_ANNOUNCE = 6;
    private static final int ID_EMOTE_DATA_UPLOAD = 7;
    private static final int ID_EMOTE_DOWNLOAD_REQUEST = 8;
    private static final int ID_REMOTE_EMOTE_CLEAR = 9;
    private static final int ID_SERVER_EMOJI_LIST_RESPONSE = 10;
    private static final int ID_SERVER_EMOJI_LIST_REQUEST = 11;
    private static final int ID_SERVER_EMOJI_DELETE = 12;
    private static final int ID_SERVER_EMOJI_CLEAR_MINE = 13;
    private static final int ID_PUA_LEASE_GRANT = 14;
    private static final int ID_PUA_REGISTER_REQUEST = 15;
    private static final int ID_PUA_RESOLVE_REQUEST = 16;
    private static final int ID_PUA_RESOLVE_RESPONSE = 17;

    public static void init() {
        INSTANCE.registerMessage(
            PacketServerPresence.Handler.class,
            PacketServerPresence.class,
            ID_SERVER_PRESENCE,
            Side.CLIENT);
        INSTANCE.registerMessage(
            PacketEmoteUploadRequest.Handler.class,
            PacketEmoteUploadRequest.class,
            ID_EMOTE_UPLOAD_REQUEST,
            Side.CLIENT);
        INSTANCE.registerMessage(
            PacketEmoteBroadcast.Handler.class,
            PacketEmoteBroadcast.class,
            ID_EMOTE_BROADCAST,
            Side.CLIENT);
        INSTANCE.registerMessage(
            PacketEmoteDataDownload.Handler.class,
            PacketEmoteDataDownload.class,
            ID_EMOTE_DATA_DOWNLOAD,
            Side.CLIENT);
        INSTANCE
            .registerMessage(PacketEmoteReject.Handler.class, PacketEmoteReject.class, ID_EMOTE_REJECT, Side.CLIENT);
        INSTANCE.registerMessage(
            PacketServerPackClear.Handler.class,
            PacketServerPackClear.class,
            ID_SERVER_PACK_CLEAR,
            Side.CLIENT);
        INSTANCE.registerMessage(
            PacketRemoteEmoteClear.Handler.class,
            PacketRemoteEmoteClear.class,
            ID_REMOTE_EMOTE_CLEAR,
            Side.CLIENT);
        INSTANCE.registerMessage(
            PacketServerEmojiListResponse.Handler.class,
            PacketServerEmojiListResponse.class,
            ID_SERVER_EMOJI_LIST_RESPONSE,
            Side.CLIENT);
        INSTANCE.registerMessage(
            PacketPuaLeaseGrant.Handler.class,
            PacketPuaLeaseGrant.class,
            ID_PUA_LEASE_GRANT,
            Side.CLIENT);
        INSTANCE.registerMessage(
            PacketPuaResolveResponse.Handler.class,
            PacketPuaResolveResponse.class,
            ID_PUA_RESOLVE_RESPONSE,
            Side.CLIENT);

        INSTANCE.registerMessage(
            PacketChatEmoteAnnounce.Handler.class,
            PacketChatEmoteAnnounce.class,
            ID_CHAT_EMOTE_ANNOUNCE,
            Side.SERVER);
        INSTANCE.registerMessage(
            PacketEmoteDataUpload.Handler.class,
            PacketEmoteDataUpload.class,
            ID_EMOTE_DATA_UPLOAD,
            Side.SERVER);
        INSTANCE.registerMessage(
            PacketEmoteDownloadRequest.Handler.class,
            PacketEmoteDownloadRequest.class,
            ID_EMOTE_DOWNLOAD_REQUEST,
            Side.SERVER);
        INSTANCE.registerMessage(
            PacketServerEmojiListRequest.Handler.class,
            PacketServerEmojiListRequest.class,
            ID_SERVER_EMOJI_LIST_REQUEST,
            Side.SERVER);
        INSTANCE.registerMessage(
            PacketServerEmojiDelete.Handler.class,
            PacketServerEmojiDelete.class,
            ID_SERVER_EMOJI_DELETE,
            Side.SERVER);
        INSTANCE.registerMessage(
            PacketServerEmojiClearMine.Handler.class,
            PacketServerEmojiClearMine.class,
            ID_SERVER_EMOJI_CLEAR_MINE,
            Side.SERVER);
        INSTANCE.registerMessage(
            PacketPuaRegisterRequest.Handler.class,
            PacketPuaRegisterRequest.class,
            ID_PUA_REGISTER_REQUEST,
            Side.SERVER);
        INSTANCE.registerMessage(
            PacketPuaResolveRequest.Handler.class,
            PacketPuaResolveRequest.class,
            ID_PUA_RESOLVE_REQUEST,
            Side.SERVER);
    }
}
