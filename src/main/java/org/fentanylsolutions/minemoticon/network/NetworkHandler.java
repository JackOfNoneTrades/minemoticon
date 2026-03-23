package org.fentanylsolutions.minemoticon.network;

import org.fentanylsolutions.minemoticon.Minemoticon;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public class NetworkHandler {

    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(Minemoticon.MODID);

    private static int packetId = 0;

    public static void init() {
        // S->C
        INSTANCE
            .registerMessage(PacketServerPresence.Handler.class, PacketServerPresence.class, packetId++, Side.CLIENT);
        INSTANCE.registerMessage(
            PacketEmoteUploadRequest.Handler.class,
            PacketEmoteUploadRequest.class,
            packetId++,
            Side.CLIENT);
        INSTANCE
            .registerMessage(PacketEmoteBroadcast.Handler.class, PacketEmoteBroadcast.class, packetId++, Side.CLIENT);
        INSTANCE.registerMessage(
            PacketEmoteDataDownload.Handler.class,
            PacketEmoteDataDownload.class,
            packetId++,
            Side.CLIENT);
        INSTANCE.registerMessage(PacketEmoteReject.Handler.class, PacketEmoteReject.class, packetId++, Side.CLIENT);

        // C->S
        INSTANCE.registerMessage(
            PacketChatEmoteAnnounce.Handler.class,
            PacketChatEmoteAnnounce.class,
            packetId++,
            Side.SERVER);
        INSTANCE
            .registerMessage(PacketEmoteDataUpload.Handler.class, PacketEmoteDataUpload.class, packetId++, Side.SERVER);
        INSTANCE.registerMessage(
            PacketEmoteDownloadRequest.Handler.class,
            PacketEmoteDownloadRequest.class,
            packetId++,
            Side.SERVER);
    }
}
