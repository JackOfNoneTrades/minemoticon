package org.fentanylsolutions.minemoticon.config;

import net.minecraft.client.gui.GuiScreen;

import org.fentanylsolutions.minemoticon.EmojiConfig;
import org.fentanylsolutions.minemoticon.Minemoticon;

import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.SimpleGuiConfig;

public class MinemoticonGuiConfig extends SimpleGuiConfig {

    public MinemoticonGuiConfig(GuiScreen parent) throws ConfigException {
        super(parent, Minemoticon.MODID, Minemoticon.MODID, true, EmojiConfig.class);
    }
}
