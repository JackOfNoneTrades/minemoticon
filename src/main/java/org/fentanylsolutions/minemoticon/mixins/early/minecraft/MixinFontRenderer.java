package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.FontRenderer;

import org.fentanylsolutions.minemoticon.api.RenderableEmoji;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FontRenderer.class)
public abstract class MixinFontRenderer {

    @Unique
    private static final float minemoticon$INLINE_EMOJI_Y_OFFSET = -(EmojiRenderer.EMOJI_SIZE - 8.0f) / 2.0f;

    @Shadow
    private float posX;

    @Shadow
    private float posY;

    @Shadow
    private float red;

    @Shadow
    private float green;

    @Shadow
    private float blue;

    @Shadow
    private float alpha;

    @Shadow
    public abstract int getStringWidth(String text);

    @Shadow
    public abstract int getCharWidth(char c);

    @Shadow
    protected abstract void renderStringAtPos(String text, boolean shadow);

    @Unique
    private boolean minemoticon$rendering = false;

    @Unique
    private boolean minemoticon$measuringWidth = false;

    @Inject(method = "getStringWidth", at = @At("HEAD"), cancellable = true)
    private void minemoticon$fixStringWidth(String text, CallbackInfoReturnable<Integer> cir) {
        if (minemoticon$measuringWidth) return;

        var segments = EmojiRenderer.parse(text);
        if (segments == null) return;

        minemoticon$measuringWidth = true;
        try {
            int width = 0;
            for (var seg : segments) {
                if (seg instanceof RenderableEmoji) {
                    width += (int) EmojiRenderer.EMOJI_SIZE;
                } else {
                    width += this.getStringWidth((String) seg);
                }
            }
            cir.setReturnValue(width);
        } finally {
            minemoticon$measuringWidth = false;
        }
    }

    @Inject(method = "renderStringAtPos", at = @At("HEAD"), cancellable = true)
    private void minemoticon$renderWithEmojis(String text, boolean shadow, CallbackInfo ci) {
        if (minemoticon$rendering) return;

        var segments = EmojiRenderer.parse(text);
        if (segments == null) return;

        ci.cancel();
        minemoticon$rendering = true;
        try {
            for (var seg : segments) {
                if (seg instanceof RenderableEmoji emoji) {
                    if (!shadow) {
                        EmojiRenderer.renderQuad(emoji, this.posX, this.posY + minemoticon$INLINE_EMOJI_Y_OFFSET);
                        // Restore font color after emoji quad reset it to white
                        org.lwjgl.opengl.GL11.glColor4f(this.red, this.green, this.blue, this.alpha);
                    }
                    this.posX += EmojiRenderer.EMOJI_SIZE;
                } else {
                    this.renderStringAtPos((String) seg, shadow);
                }
            }
        } finally {
            minemoticon$rendering = false;
        }
    }
}
