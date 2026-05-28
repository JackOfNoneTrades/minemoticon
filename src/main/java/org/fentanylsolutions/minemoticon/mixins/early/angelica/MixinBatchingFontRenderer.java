package org.fentanylsolutions.minemoticon.mixins.early.angelica;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.font.FontSource;
import org.fentanylsolutions.minemoticon.font.FontStack;
import org.fentanylsolutions.minemoticon.font.MinecraftFontSource;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.fentanylsolutions.minemoticon.render.FontRendererEmojiCompat;
import org.fentanylsolutions.minemoticon.text.TextStyleCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.gtnewhorizons.angelica.client.font.BatchingFontRenderer", priority = 2000)
public abstract class MixinBatchingFontRenderer {

    @Unique
    private static int minemoticon$batchingDebugLogs;

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
        text = TextStyleCompat.normalize(text);
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

        boolean previousColonAliasMatching = EmojiRenderer
            .pushColonAliasMatching(minemoticon$shouldMatchColonAliases(text));
        try {
            if (!minemoticon$shouldUseCompatString(text)) {
                return;
            }

            minemoticon$logBatchingCompat(text, anchorX, anchorY, stringOffset, stringLength);
            int endX = ((FontRendererEmojiCompat) this.underlying).minemoticon$drawStringCompatDirect(
                text,
                Math.round(anchorX),
                Math.round(anchorY),
                color,
                enableShadow);
            cir.setReturnValue((float) endX);
        } finally {
            EmojiRenderer.popColonAliasMatching(previousColonAliasMatching);
        }
    }

    private boolean minemoticon$shouldUseCompatString(String text) {
        if (EmojiRenderer.parse(text) != null) {
            return true;
        }
        if (TextStyleCompat.hasExtendedStyle(text)) {
            return true;
        }

        FontStack stack = ClientEmojiHandler.getFontStack();
        if (stack == null) {
            return false;
        }

        int i = 0;
        while (i < text.length()) {
            int tokenEnd = TextStyleCompat.tokenEnd(text, i);
            if (tokenEnd > i) {
                i = tokenEnd;
                continue;
            }
            int cp = text.codePointAt(i);
            FontSource source = stack.resolve(cp);
            if (source != null && !(source instanceof MinecraftFontSource)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    @Unique
    private boolean minemoticon$shouldMatchColonAliases(String text) {
        if (text == null || text.indexOf(':') < 0) {
            return false;
        }
        return TextStyleCompat.hasExtendedStyle(text);
    }

    @Unique
    private void minemoticon$logBatchingCompat(String text, float x, float y, int offset, int length) {
        if (minemoticon$batchingDebugLogs >= 16) {
            return;
        }
        minemoticon$batchingDebugLogs++;
        Minemoticon.debug(
            "Angelica batching compat {} x={} y={} offset={} length={} emoji={} style={} text='{}'",
            minemoticon$batchingDebugLogs,
            x,
            y,
            offset,
            length,
            EmojiRenderer.parse(text) != null,
            TextStyleCompat.hasExtendedStyle(text),
            minemoticon$debugSnippet(text));
    }

    @Unique
    private static String minemoticon$debugSnippet(String value) {
        if (value == null) {
            return "null";
        }
        String escaped = value.replace('\u00A7', '&')
            .replace('\n', ' ');
        return escaped.length() > 100 ? escaped.substring(0, 100) : escaped;
    }
}
