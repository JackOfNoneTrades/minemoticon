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
    private static final int BTN_SELECT_FONT = 9992;
    private static final int BTN_OPEN_FONTS = 9993;

    public MinemoticonGuiConfig(GuiScreen parent) throws ConfigException {
        super(parent, Minemoticon.MODID, Minemoticon.MODID, true, EmojiConfig.class, ServerConfig.class);
    }

    @Override
    public void initGui() {
        super.initGui();

        // Custom buttons in a row above the bottom Forge buttons
        int btnY = this.height - 52;
        int totalW = this.width - 20;
        int x = 10;
        int gap = 4;
        int btnW = (totalW - gap * 3) / 4;

        this.buttonList.add(new GuiButton(BTN_SELECT_FONT, x, btnY, btnW, 20, "\uD83C\uDFA8 Emoji Font"));
        x += btnW + gap;
        this.buttonList.add(new GuiButton(BTN_OPEN_FONTS, x, btnY, btnW, 20, "\uD83D\uDCC2 Fonts Folder"));
        x += btnW + gap;
        this.buttonList.add(new GuiButton(BTN_OPEN_FOLDER, x, btnY, btnW, 20, "\uD83D\uDCC2 Packs Folder"));
        x += btnW + gap;
        this.buttonList.add(new GuiButton(BTN_RELOAD, x, btnY, btnW, 20, "\uD83D\uDD04 Reload Packs"));
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
        if (button.id == BTN_SELECT_FONT) {
            mc.displayGuiScreen(new FontSelectionScreen(this));
            return;
        }
        if (button.id == BTN_OPEN_FONTS) {
            FileUtil.openFolder(ClientEmojiHandler.FONTS_DIR);
            return;
        }
        super.actionPerformed(button);
    }
}
