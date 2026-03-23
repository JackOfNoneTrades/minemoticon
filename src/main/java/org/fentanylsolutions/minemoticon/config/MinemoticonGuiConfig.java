package org.fentanylsolutions.minemoticon.config;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.fentanylsolutions.fentlib.util.FileUtil;
import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.EmojiConfig;
import org.fentanylsolutions.minemoticon.EmojiPackLoader;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.ServerConfig;

import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.SimpleGuiConfig;

public class MinemoticonGuiConfig extends SimpleGuiConfig {

    private static final int BTN_RELOAD = 9990;
    private static final int BTN_OPEN_FOLDER = 9991;

    public MinemoticonGuiConfig(GuiScreen parent) throws ConfigException {
        super(parent, Minemoticon.MODID, Minemoticon.MODID, true, EmojiConfig.class, ServerConfig.class);
    }

    @Override
    public void initGui() {
        super.initGui();

        // Place buttons at the bottom-right, next to the existing buttons
        int btnY = this.height - 28;
        int btnX = this.width - 90;

        var reloadBtn = new GuiButton(BTN_RELOAD, btnX, btnY, 40, 20, "\uD83D\uDD04 Reload");
        var folderBtn = new GuiButton(BTN_OPEN_FOLDER, btnX + 44, btnY, 44, 20, "\uD83D\uDCC2 Packs");

        this.buttonList.add(reloadBtn);
        this.buttonList.add(folderBtn);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_RELOAD) {
            ClientEmojiHandler.reloadPacks();
            return;
        }
        if (button.id == BTN_OPEN_FOLDER) {
            FileUtil.openFolder(EmojiPackLoader.getPacksFolder());
            return;
        }
        super.actionPerformed(button);
    }
}
