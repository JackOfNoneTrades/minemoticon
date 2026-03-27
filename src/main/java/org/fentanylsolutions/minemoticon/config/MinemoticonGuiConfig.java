package org.fentanylsolutions.minemoticon.config;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.fentanylsolutions.minemoticon.EmojiConfig;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.ServerConfig;

import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.SimpleGuiConfig;

public class MinemoticonGuiConfig extends SimpleGuiConfig {

    private static final int BTN_SELECT_FONT = 9992;
    private static final int BTN_SERVER_EMOJIS = 9993;
    private static final int BTN_MANAGE_PACKS = 9994;

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
        int btnW = (totalW - gap * 2) / 3;

        this.buttonList.add(new GuiButton(BTN_SELECT_FONT, x, btnY, btnW, 20, "\uD83C\uDFA8 Fonts"));
        x += btnW + gap;
        this.buttonList.add(new GuiButton(BTN_SERVER_EMOJIS, x, btnY, btnW, 20, "\u2601 Server Cache"));
        x += btnW + gap;
        this.buttonList.add(new GuiButton(BTN_MANAGE_PACKS, x, btnY, btnW, 20, "\u270F Emoji Packs"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_SELECT_FONT) {
            mc.displayGuiScreen(new FontSelectionScreen(this));
            return;
        }
        if (button.id == BTN_SERVER_EMOJIS) {
            mc.displayGuiScreen(new ServerEmojiManagementScreen(this));
            return;
        }
        if (button.id == BTN_MANAGE_PACKS) {
            mc.displayGuiScreen(new PackManagementScreen(this));
            return;
        }
        super.actionPerformed(button);
    }
}
