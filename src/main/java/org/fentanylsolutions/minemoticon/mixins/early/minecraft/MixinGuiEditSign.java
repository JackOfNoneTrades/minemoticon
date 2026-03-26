package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.tileentity.TileEntitySign;

import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiEditSign.class)
public class MixinGuiEditSign {

    @Shadow
    private TileEntitySign tileSign;

    @Shadow
    private int editLine;

    @Inject(method = "keyTyped", at = @At("TAIL"))
    private void minemoticon$substituteCompletedAlias(char typedChar, int keyCode, CallbackInfo ci) {
        if (tileSign == null || tileSign.signText == null || editLine < 0 || editLine >= tileSign.signText.length) {
            return;
        }

        String line = tileSign.signText[editLine];
        EmoteClientHandler.TextReplacement replacement = EmoteClientHandler
            .substituteCompletedAlias(line, line.length());
        if (replacement != null) {
            tileSign.signText[editLine] = replacement.text;
        }
    }
}
