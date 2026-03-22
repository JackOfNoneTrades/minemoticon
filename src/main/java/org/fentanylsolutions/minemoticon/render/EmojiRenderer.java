package org.fentanylsolutions.minemoticon.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.api.Emoji;
import org.fentanylsolutions.minemoticon.api.EmojiFromTwitmoji;
import org.lwjgl.opengl.GL11;

public class EmojiRenderer {

    public static final float EMOJI_SIZE = 9.0f;

    // When true, parse() returns null unconditionally. Used by the picker
    // info bar to render :colon: text without the font mixin replacing it.
    public static boolean bypass = false;

    // Returns null if no emojis found. Otherwise returns a list where each
    // element is either a String (text) or an EmojiFromTwitmoji.
    public static List<Object> parse(String text) {
        if (bypass || ClientEmojiHandler.EMOJI_LOOKUP.isEmpty()) return null;

        List<Object> segments = null;
        int lastEnd = 0;

        for (int i = 0; i < text.length();) {
            // Try :colon: syntax
            if (text.charAt(i) == ':') {
                int end = text.indexOf(':', i + 1);
                if (end != -1) {
                    String key = text.substring(i, end + 1);
                    Emoji emoji = ClientEmojiHandler.EMOJI_LOOKUP.get(key);
                    if (emoji instanceof EmojiFromTwitmoji twitmoji) {
                        if (segments == null) segments = new ArrayList<>();
                        if (i > lastEnd) segments.add(text.substring(lastEnd, i));
                        segments.add(twitmoji);
                        lastEnd = end + 1;
                        i = lastEnd;
                        continue;
                    }
                }
            }

            // Try Unicode emoji match
            int matchLen = 0;
            EmojiFromTwitmoji unicodeMatch = null;
            var candidates = ClientEmojiHandler.UNICODE_KEYS_BY_CHAR.get(text.charAt(i));
            if (candidates != null) {
                for (String candidate : candidates) { // sorted longest-first
                    if (text.startsWith(candidate, i)) {
                        Emoji e = ClientEmojiHandler.EMOJI_UNICODE_LOOKUP.get(candidate);
                        if (e instanceof EmojiFromTwitmoji t) {
                            unicodeMatch = t;
                            matchLen = candidate.length();
                            break;
                        }
                    }
                }
            }
            if (unicodeMatch != null) {
                if (segments == null) segments = new ArrayList<>();
                if (i > lastEnd) segments.add(text.substring(lastEnd, i));
                segments.add(unicodeMatch);
                lastEnd = i + matchLen;
                i = lastEnd;
                continue;
            }

            i++;
        }

        if (segments == null) return null;

        if (lastEnd < text.length()) {
            segments.add(text.substring(lastEnd));
        }
        return segments;
    }

    public static void renderQuad(EmojiFromTwitmoji emoji, float x, float y) {
        var texLoc = emoji.getResourceLocation();
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(texLoc);

        // Reset color to white so the emoji texture isn't tinted
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

        float top = y;
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        GL11.glTexCoord2f(0, 0);
        GL11.glVertex3f(x, top, 0);
        GL11.glTexCoord2f(0, 1);
        GL11.glVertex3f(x, top + EMOJI_SIZE, 0);
        GL11.glTexCoord2f(1, 0);
        GL11.glVertex3f(x + EMOJI_SIZE, top, 0);
        GL11.glTexCoord2f(1, 1);
        GL11.glVertex3f(x + EMOJI_SIZE, top + EMOJI_SIZE, 0);
        GL11.glEnd();
    }
}
