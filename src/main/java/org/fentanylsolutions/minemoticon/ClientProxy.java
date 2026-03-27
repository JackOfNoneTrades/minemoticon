package org.fentanylsolutions.minemoticon;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ScreenShotHelper;

import org.fentanylsolutions.minemoticon.config.MinemoticonGuiConfig;
import org.fentanylsolutions.minemoticon.font.GlyphCache;
import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.fentanylsolutions.minemoticon.network.ServerEmojiManagerClient;

import com.gtnewhorizon.gtnhlib.config.ConfigException;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class ClientProxy extends CommonProxy {

    private static final boolean DEV_AUTO_CAPTURE = minemoticon$isEnabled("MINEMOTICON_DEV_CAPTURE");
    private static final int DEV_CAPTURE_DELAY_TICKS = 20;
    private static final boolean DEV_CAPTURE_CONFIG = !"mainmenu"
        .equalsIgnoreCase(System.getenv("MINEMOTICON_DEV_CAPTURE_TARGET"));

    private boolean minemoticon$devCaptureOpenedConfig;
    private boolean minemoticon$devCaptureComplete;
    private int minemoticon$devCaptureTicks;

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
        Minecraft.getMinecraft()
            .func_152344_a(new Runnable() {

                @Override
                public void run() {
                    EmoteClientHandler.reset();
                }
            });
        ServerEmojiManagerClient.reset();
    }

    @SubscribeEvent
    public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minemoticon.debug("Disconnected from server, clearing remote emoji state and cache");
        ServerCapabilities.reset();
        // Schedule GL-sensitive cleanup on the client thread since disconnect fires on Netty IO thread
        Minecraft.getMinecraft()
            .func_152344_a(new Runnable() {

                @Override
                public void run() {
                    EmoteClientHandler.resetAndDeleteCache();
                }
            });
        ServerEmojiManagerClient.reset();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            EmoteClientHandler.tick();
            minemoticon$runDevCapture();
        }
    }

    private void minemoticon$runDevCapture() {
        if (!DEV_AUTO_CAPTURE || minemoticon$devCaptureComplete) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }

        if (!minemoticon$devCaptureOpenedConfig) {
            if (mc.currentScreen instanceof GuiMainMenu) {
                if (!DEV_CAPTURE_CONFIG) {
                    minemoticon$devCaptureOpenedConfig = true;
                    minemoticon$devCaptureTicks = 0;
                    return;
                }
                try {
                    mc.displayGuiScreen(new MinemoticonGuiConfig(mc.currentScreen));
                    minemoticon$devCaptureOpenedConfig = true;
                    minemoticon$devCaptureTicks = 0;
                } catch (ConfigException e) {
                    Minemoticon.LOG.error("Failed to open Minemoticon config for dev capture", e);
                    minemoticon$devCaptureComplete = true;
                    mc.shutdown();
                }
            }
            return;
        }

        if (DEV_CAPTURE_CONFIG && !(mc.currentScreen instanceof MinemoticonGuiConfig)) {
            minemoticon$devCaptureTicks = 0;
            return;
        }

        minemoticon$devCaptureTicks++;
        if (minemoticon$devCaptureTicks < DEV_CAPTURE_DELAY_TICKS) {
            return;
        }

        String screenshotName = System.getenv("MINEMOTICON_DEV_CAPTURE_NAME");
        if (screenshotName == null || screenshotName.trim()
            .isEmpty()) {
            screenshotName = "minemoticon-dev-" + System.currentTimeMillis() + ".png";
        }

        ScreenShotHelper
            .saveScreenshot(mc.mcDataDir, screenshotName, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        try {
            GlyphCache.dumpAllAtlases(new File(mc.mcDataDir, "screenshots/glyph-atlas-dumps"));
        } catch (Exception e) {
            Minemoticon.LOG.warn("Failed to dump glyph atlases for dev capture", e);
        }
        minemoticon$devCaptureComplete = true;
        mc.shutdown();
    }

    private static boolean minemoticon$isEnabled(String envName) {
        String value = System.getenv(envName);
        return value != null && !value.isEmpty() && !"0".equals(value) && !"false".equalsIgnoreCase(value);
    }
}
