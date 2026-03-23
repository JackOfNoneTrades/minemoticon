package org.fentanylsolutions.minemoticon.network;

import org.fentanylsolutions.minemoticon.Minemoticon;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public class NetworkHandler {

    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(Minemoticon.MODID);

    private static int packetId = 0;

    public static void init() {
        INSTANCE
            .registerMessage(PacketServerPresence.Handler.class, PacketServerPresence.class, packetId++, Side.CLIENT);
    }
}
