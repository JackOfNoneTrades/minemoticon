package org.fentanylsolutions.minemoticon;

import net.minecraft.entity.player.EntityPlayerMP;

import org.fentanylsolutions.minemoticon.network.EmoteServerHandler;
import org.fentanylsolutions.minemoticon.network.NetworkHandler;
import org.fentanylsolutions.minemoticon.network.PacketServerPresence;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Minemoticon.LOG.info("Minemoticon v{}", Tags.VERSION);
    }

    public void init(FMLInitializationEvent event) {
        NetworkHandler.init();
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandReloadEmojis());
        EmoteServerHandler.loadServerPacks();
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            Minemoticon.debug("Sending presence packet to {}", player.getCommandSenderName());
            NetworkHandler.INSTANCE.sendTo(new PacketServerPresence(), player);
            EmoteServerHandler.sendServerPacksToPlayer(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            EmoteServerHandler.onPlayerDisconnect(player);
        }
    }
}
