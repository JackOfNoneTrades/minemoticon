package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.IChatComponent;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.gui.ChatEmojiTooltipHelper;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.fentanylsolutions.minemoticon.render.FontRendererEmojiCompat;
import org.fentanylsolutions.minemoticon.text.TextStyleCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiNewChat.class)
public abstract class MixinGuiNewChat {

    @Unique
    private static int minemoticon$chatDebugLogs;

    @ModifyVariable(method = "printChatMessageWithOptionalDeletion", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private IChatComponent minemoticon$addEmojiTooltips(IChatComponent component) {
        return ChatEmojiTooltipHelper.withEmojiTooltips(component);
    }

    @ModifyVariable(method = "func_146237_a", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private IChatComponent minemoticon$addEmojiTooltipsOnRefresh(IChatComponent component) {
        return ChatEmojiTooltipHelper.withEmojiTooltips(component);
    }

    @Redirect(
        method = "drawChat",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/FontRenderer;drawStringWithShadow(Ljava/lang/String;III)I"))
    private int minemoticon$drawChatLine(FontRenderer font, String text, int x, int y, int color) {
        boolean previousColonAliasMatching = EmojiRenderer
            .pushColonAliasMatching(minemoticon$shouldMatchColonAliases(text));
        try {
            if (font instanceof FontRendererEmojiCompat compat && minemoticon$shouldUseChatCompat(text)) {
                minemoticon$logChatCompat(text, x, y, true);
                return compat.minemoticon$drawStringCompatDirect(text, x, y, color, true);
            }
            minemoticon$logChatCompat(text, x, y, false);
            return font.drawStringWithShadow(text, x, y, color);
        } finally {
            EmojiRenderer.popColonAliasMatching(previousColonAliasMatching);
        }
    }

    @Unique
    private boolean minemoticon$shouldUseChatCompat(String text) {
        if (text == null || ClientEmojiHandler.getFontStack() == null) {
            return false;
        }

        String normalized = TextStyleCompat.normalize(text);
        return TextStyleCompat.hasExtendedStyle(normalized) || EmojiRenderer.parse(normalized) != null;
    }

    @Unique
    private boolean minemoticon$shouldMatchColonAliases(String text) {
        if (text == null || text.indexOf(':') < 0) {
            return false;
        }
        String normalized = TextStyleCompat.normalize(text);
        return TextStyleCompat.hasExtendedStyle(normalized);
    }

    @Unique
    private void minemoticon$logChatCompat(String text, int x, int y, boolean compat) {
        if (minemoticon$chatDebugLogs >= 24) {
            return;
        }
        String normalized = TextStyleCompat.normalize(text);
        boolean hasEmoji = EmojiRenderer.parse(normalized) != null;
        boolean hasStyle = TextStyleCompat.hasExtendedStyle(normalized);
        if (!compat && !hasEmoji && !hasStyle) {
            return;
        }
        minemoticon$chatDebugLogs++;
        Minemoticon.debug(
            "Chat draw {} compat={} x={} y={} rawLen={} normalizedLen={} emoji={} style={} text='{}'",
            minemoticon$chatDebugLogs,
            compat,
            x,
            y,
            text == null ? -1 : text.length(),
            normalized == null ? -1 : normalized.length(),
            hasEmoji,
            hasStyle,
            minemoticon$debugSnippet(normalized));
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
