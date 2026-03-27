package org.fentanylsolutions.minemoticon.mixins.early.angelica;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.font.FontSource;
import org.fentanylsolutions.minemoticon.font.FontStack;
import org.fentanylsolutions.minemoticon.font.MinecraftFontSource;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.fentanylsolutions.minemoticon.render.FontRendererEmojiCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.gtnewhorizons.angelica.client.font.BatchingFontRenderer", priority = 2000)
public abstract class MixinBatchingFontRenderer {

    @Shadow(remap = false)
    protected FontRenderer underlying;

    @Shadow(remap = false)
    private boolean isSplash;

    @Inject(
        method = "drawString(FFIZZLjava/lang/CharSequence;II)F",
        at = @At("HEAD"),
        cancellable = true,
        remap = false)
    private void minemoticon$drawEmojiString(float anchorX, float anchorY, int color, boolean enableShadow,
        boolean unicodeFlag, CharSequence string, int stringOffset, int stringLength,
        CallbackInfoReturnable<Float> cir) {
        if (string == null || string.length() == 0) {
            return;
        }

        int safeOffset = Math.max(0, Math.min(stringOffset, string.length()));
        int safeEnd = Math.max(safeOffset, Math.min(safeOffset + stringLength, string.length()));
        String text = string.subSequence(safeOffset, safeEnd)
            .toString();
        if (ClientEmojiHandler.getFontStack() == null) {
            return;
        }
        if (this.isSplash) {
            return;
        }
        if (!Minecraft.getMinecraft()
            .func_152345_ab()) {
            return;
        }
        if (!minemoticon$shouldUseCompatString(text)) {
            return;
        }

        int endX = ((FontRendererEmojiCompat) this.underlying)
            .minemoticon$drawStringCompatDirect(text, Math.round(anchorX), Math.round(anchorY), color, enableShadow);
        cir.setReturnValue((float) endX);
    }

    private boolean minemoticon$shouldUseCompatString(String text) {
        if (EmojiRenderer.parse(text) != null) {
            return true;
        }

        FontStack stack = ClientEmojiHandler.getFontStack();
        if (stack == null) {
            return false;
        }

        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            FontSource source = stack.resolve(cp);
            if (source != null && !(source instanceof MinecraftFontSource)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }
}
