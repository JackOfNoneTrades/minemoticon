package org.fentanylsolutions.minemoticon;

import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.fentanylsolutions.minemoticon.network.ServerEmojiManagerClient;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientEmojiHandler.setup();
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        Minemoticon.debug("Connected to server, resetting server capabilities");
        ServerCapabilities.reset();
        EmoteClientHandler.reset();
        ServerEmojiManagerClient.reset();
    }

    @SubscribeEvent
    public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minemoticon.debug("Disconnected from server, clearing remote emoji state and cache");
        ServerCapabilities.reset();
        EmoteClientHandler.resetAndDeleteCache();
        ServerEmojiManagerClient.reset();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            EmoteClientHandler.tick();
        }
    }
}
