package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.IChatComponent;

import org.fentanylsolutions.minemoticon.gui.ChatEmojiTooltipHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GuiNewChat.class)
public abstract class MixinGuiNewChat {

    @ModifyVariable(method = "printChatMessageWithOptionalDeletion", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private IChatComponent minemoticon$addEmojiTooltips(IChatComponent component) {
        return ChatEmojiTooltipHelper.withEmojiTooltips(component);
    }
}
