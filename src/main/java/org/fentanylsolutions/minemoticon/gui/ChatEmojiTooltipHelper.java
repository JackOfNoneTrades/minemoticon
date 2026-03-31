package org.fentanylsolutions.minemoticon.gui;

import java.util.Iterator;
import java.util.List;

import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import org.fentanylsolutions.minemoticon.api.Emoji;
import org.fentanylsolutions.minemoticon.api.EmojiFromPack;
import org.fentanylsolutions.minemoticon.api.EmojiFromRemote;
import org.fentanylsolutions.minemoticon.api.RenderableEmoji;
import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.fentanylsolutions.minemoticon.text.EmojiPua;

public final class ChatEmojiTooltipHelper {

    private ChatEmojiTooltipHelper() {}

    public static IChatComponent withEmojiTooltips(IChatComponent component) {
        if (component == null) {
            return null;
        }

        ChatComponentText rebuilt = new ChatComponentText("");
        boolean changed = false;

        Iterator<IChatComponent> iterator = component.iterator();
        while (iterator.hasNext()) {
            IChatComponent leaf = iterator.next();
            changed |= appendLeaf(rebuilt, leaf);
        }

        return changed ? rebuilt : component;
    }

    private static boolean appendLeaf(ChatComponentText rebuilt, IChatComponent leaf) {
        String text = leaf.getUnformattedTextForChat();
        if (text == null || text.isEmpty()) {
            return false;
        }

        ChatStyle style = leaf.getChatStyle();
        if (style.getChatHoverEvent() != null) {
            appendText(rebuilt, text, style.createDeepCopy());
            return false;
        }

        List<EmojiRenderer.ParsedSegment> segments = EmojiRenderer.parseDetailed(text);
        if (segments == null || !containsEmoji(segments)) {
            appendText(rebuilt, text, style.createDeepCopy());
            return false;
        }

        for (EmojiRenderer.ParsedSegment segment : segments) {
            if (segment.isEmoji()) {
                ChatStyle emojiStyle = style.createDeepCopy();
                String tooltipText = getTooltipText(segment);
                if (!tooltipText.isEmpty()) {
                    emojiStyle.setChatHoverEvent(
                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(tooltipText)));
                }
                appendText(rebuilt, segment.getText(), emojiStyle);
            } else if (!segment.getText()
                .isEmpty()) {
                    appendText(rebuilt, segment.getText(), style.createDeepCopy());
                }
        }

        return true;
    }

    private static void appendText(ChatComponentText rebuilt, String text, ChatStyle style) {
        if (text == null || text.isEmpty()) {
            return;
        }

        ChatComponentText child = new ChatComponentText(text);
        child.setChatStyle(style);
        rebuilt.appendSibling(child);
    }

    private static boolean containsEmoji(List<EmojiRenderer.ParsedSegment> segments) {
        for (EmojiRenderer.ParsedSegment segment : segments) {
            if (segment.isEmoji()) {
                return true;
            }
        }
        return false;
    }

    private static String getTooltipText(EmojiRenderer.ParsedSegment segment) {
        RenderableEmoji emoji = segment.getEmoji();
        if (emoji instanceof EmojiFromRemote remoteEmoji && !remoteEmoji.getHoverText()
            .isEmpty()) {
            return remoteEmoji.getHoverText();
        }
        String puaTooltip = EmoteClientHandler.getTooltipTextForPua(segment.getText());
        if (!puaTooltip.isEmpty()) {
            return puaTooltip;
        }
        if (EmojiPua.isPuaToken(segment.getText())) {
            return "";
        }
        if (emoji instanceof EmojiFromPack packEmoji) {
            return "\\" + packEmoji.getNamespaced();
        }
        if (emoji instanceof Emoji minemoticonEmoji) {
            return "\\" + minemoticonEmoji.getShorterString();
        }
        return "emoji";
    }
}
