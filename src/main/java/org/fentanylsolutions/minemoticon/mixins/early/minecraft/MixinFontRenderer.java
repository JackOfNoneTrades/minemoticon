package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.FontRenderer;

import org.fentanylsolutions.minemoticon.api.EmojiFromTwitmoji;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FontRenderer.class)
public abstract class MixinFontRenderer {

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
    protected abstract void renderStringAtPos(String text, boolean shadow);

    @Unique
    private boolean minemoticon$rendering = false;

    @Inject(method = "renderStringAtPos", at = @At("HEAD"), cancellable = true)
    private void minemoticon$renderWithEmojis(String text, boolean shadow, CallbackInfo ci) {
        if (minemoticon$rendering) return;

        var segments = EmojiRenderer.parse(text);
        if (segments == null) return;

        ci.cancel();
        minemoticon$rendering = true;
        try {
            for (var seg : segments) {
                if (seg instanceof EmojiFromTwitmoji emoji) {
                    if (!shadow) {
                        EmojiRenderer.renderQuad(emoji, this.posX, this.posY);
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
