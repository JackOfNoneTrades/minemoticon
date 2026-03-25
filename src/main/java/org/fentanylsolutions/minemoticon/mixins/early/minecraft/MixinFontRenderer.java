package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.FontRenderer;

import org.fentanylsolutions.minemoticon.api.RenderableEmoji;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.fentanylsolutions.minemoticon.render.FontRendererEmojiCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FontRenderer.class, priority = 2000)
public abstract class MixinFontRenderer implements FontRendererEmojiCompat {

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
    private boolean bidiFlag;

    @Shadow
    public abstract int getStringWidth(String text);

    @Shadow
    public abstract int getCharWidth(char c);

    @Shadow
    protected abstract void renderStringAtPos(String text, boolean shadow);

    @Shadow
    protected abstract String bidiReorder(String text);

    @Shadow
    protected abstract void resetStyles();

    @Unique
    private boolean minemoticon$rendering = false;

    @Unique
    private boolean minemoticon$measuringWidth = false;

    @Unique
    private boolean minemoticon$renderingCompatString = false;

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

    @Inject(method = "drawString(Ljava/lang/String;IIIZ)I", at = @At("HEAD"), cancellable = true)
    private void minemoticon$drawStringCompat(String text, int x, int y, int color, boolean dropShadow,
        CallbackInfoReturnable<Integer> cir) {
        if (minemoticon$renderingCompatString) return;
        if (EmojiRenderer.parse(text) == null) return;

        minemoticon$renderingCompatString = true;
        try {
            cir.setReturnValue(minemoticon$drawStringVanillaCompat(text, x, y, color, dropShadow));
        } finally {
            minemoticon$renderingCompatString = false;
        }
    }

    @Inject(method = "renderString", at = @At("HEAD"), cancellable = true)
    private void minemoticon$renderStringCompat(String text, int x, int y, int color, boolean dropShadow,
        CallbackInfoReturnable<Integer> cir) {
        if (minemoticon$renderingCompatString) return;
        if (EmojiRenderer.parse(text) == null) return;

        minemoticon$renderingCompatString = true;
        try {
            cir.setReturnValue(minemoticon$renderStringVanillaCompat(text, x, y, color, dropShadow));
        } finally {
            minemoticon$renderingCompatString = false;
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

    @Unique
    private int minemoticon$drawStringVanillaCompat(String text, int x, int y, int color, boolean dropShadow) {
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_ALPHA_TEST);
        this.resetStyles();

        if (dropShadow) {
            int shadowWidth = minemoticon$renderStringVanillaCompat(text, x + 1, y + 1, color, true);
            int normalWidth = minemoticon$renderStringVanillaCompat(text, x, y, color, false);
            return Math.max(shadowWidth, normalWidth);
        }

        return minemoticon$renderStringVanillaCompat(text, x, y, color, false);
    }

    @Override
    public int minemoticon$drawStringCompatDirect(String text, int x, int y, int color, boolean dropShadow) {
        return minemoticon$drawStringVanillaCompat(text, x, y, color, dropShadow);
    }

    @Unique
    private int minemoticon$renderStringVanillaCompat(String text, int x, int y, int color, boolean dropShadow) {
        if (text == null) {
            return 0;
        }

        if (this.bidiFlag) {
            text = this.bidiReorder(text);
        }

        if ((color & 0xFC000000) == 0) {
            color |= 0xFF000000;
        }

        if (dropShadow) {
            color = (color & 0xFCFCFC) >> 2 | color & 0xFF000000;
        }

        this.red = (float) (color >> 16 & 255) / 255.0F;
        this.blue = (float) (color >> 8 & 255) / 255.0F;
        this.green = (float) (color & 255) / 255.0F;
        this.alpha = (float) (color >> 24 & 255) / 255.0F;
        org.lwjgl.opengl.GL11.glColor4f(this.red, this.blue, this.green, this.alpha);
        this.posX = x;
        this.posY = y;
        this.renderStringAtPos(text, dropShadow);
        return (int) this.posX;
    }
}
