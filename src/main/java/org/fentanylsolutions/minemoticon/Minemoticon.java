package org.fentanylsolutions.minemoticon;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = Minemoticon.MODID,
    version = Tags.VERSION,
    name = "Minemoticon",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*",
    guiFactory = "org.fentanylsolutions.minemoticon.config.MinemoticonGuiConfigFactory")
public class Minemoticon {

    public static final String MODGROUP = "org.fentanylsolutions";
    public static final String MODID = "minemoticon";
    public static final Logger LOG = LogManager.getLogger(MODID);

    private static boolean DEBUG_MODE;

    @SidedProxy(
        clientSide = MODGROUP + "." + MODID + ".ClientProxy",
        serverSide = MODGROUP + "." + MODID + ".CommonProxy")
    public static CommonProxy proxy;

    public static boolean isDebugMode() {
        return DEBUG_MODE || EmojiConfig.debugMode;
    }

    public static void debug(String message, Object... args) {
        if (isDebugMode()) {
            LOG.info("[DEBUG] " + message, args);
        }
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        DEBUG_MODE = System.getenv("MCMODDING_DEBUG_MODE") != null;
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }
}
