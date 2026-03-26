package org.fentanylsolutions.minemoticon.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.api.Emoji;
import org.fentanylsolutions.minemoticon.api.RenderableEmoji;
import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.fentanylsolutions.minemoticon.text.EmojiPua;
import org.lwjgl.opengl.GL11;

public class EmojiRenderer {

    public static final float EMOJI_SIZE = 10.0f;

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
            RenderableEmoji puaMatch = null;
            char current = text.charAt(i);
            if (EmojiPua.isPua(current)) {
                EmoteClientHandler.onPuaObserved(current);
            }
            Emoji puaEmoji = ClientEmojiHandler.EMOJI_PUA_LOOKUP.get(current);
            if (puaEmoji instanceof RenderableEmoji renderableEmoji) {
                puaMatch = renderableEmoji;
            }
            if (puaMatch != null) {
                if (segments == null) segments = new ArrayList<>();
                if (i > lastEnd) segments.add(text.substring(lastEnd, i));
                segments.add(puaMatch);
                lastEnd = i + 1;
                i = lastEnd;
                continue;
            }
            if (EmojiPua.isPua(current)) {
                if (segments == null) segments = new ArrayList<>();
                if (i > lastEnd) segments.add(text.substring(lastEnd, i));
                segments.add("\u25A0");
                lastEnd = i + 1;
                i = lastEnd;
                continue;
            }

            // Try :colon: syntax
            if (current == ':') {
                int end = text.indexOf(':', i + 1);
                if (end != -1) {
                    String key = text.substring(i, end + 1);
                    Emoji emoji = ClientEmojiHandler.EMOJI_LOOKUP.get(key);
                    if (emoji instanceof RenderableEmoji twitmoji) {
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
            RenderableEmoji unicodeMatch = null;
            var candidates = ClientEmojiHandler.UNICODE_KEYS_BY_CHAR.get(text.charAt(i));
            if (candidates != null) {
                for (String candidate : candidates) { // sorted longest-first
                    if (text.startsWith(candidate, i)) {
                        Emoji e = ClientEmojiHandler.EMOJI_UNICODE_LOOKUP.get(candidate);
                        if (e instanceof RenderableEmoji t) {
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

    public static void renderQuad(RenderableEmoji emoji, float x, float y) {
        var texLoc = emoji.getResourceLocation();
        if (texLoc == null) return;
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(texLoc);

        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        if (!blendWasEnabled) {
            GL11.glEnable(GL11.GL_BLEND);
        }
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

        float[] uv = emoji.getUV();
        float u0 = uv != null ? uv[0] : 0;
        float v0 = uv != null ? uv[1] : 0;
        float u1 = uv != null ? uv[2] : 1;
        float v1 = uv != null ? uv[3] : 1;

        float top = y;

        Tessellator tessellator = Tessellator.instance;

        tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
        tessellator.addVertexWithUV(x, top, 0, u0, v0);
        tessellator.addVertexWithUV(x, top + EMOJI_SIZE, 0, u0, v1);
        tessellator.addVertexWithUV(x + EMOJI_SIZE, top, 0, u1, v0);
        tessellator.addVertexWithUV(x + EMOJI_SIZE, top + EMOJI_SIZE, 0, u1, v1);
        tessellator.draw();

        if (!blendWasEnabled) {
            GL11.glDisable(GL11.GL_BLEND);
        }
    }
}
