package org.fentanylsolutions.minemoticon.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final int MAX_PARSE_CACHE_ENTRIES = 512;
    private static final int MAX_CACHED_TEXT_LENGTH = 256;
    private static final ParseCacheEntry NO_MATCH = new ParseCacheEntry(null, null);
    private static final Map<String, ParseCacheEntry> PARSE_CACHE = new LinkedHashMap<String, ParseCacheEntry>(
        MAX_PARSE_CACHE_ENTRIES,
        0.75f,
        true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ParseCacheEntry> eldest) {
            return size() > MAX_PARSE_CACHE_ENTRIES;
        }
    };

    // When true, parse() returns null unconditionally. Used by the picker
    // info bar to render :colon: text without the font mixin replacing it.
    public static boolean bypass = false;

    // Returns null if no emojis found. Otherwise returns a list where each
    // element is either a String (text) or an EmojiFromTwitmoji.
    public static List<Object> parse(String text) {
        if (bypass || ClientEmojiHandler.EMOJI_LOOKUP.isEmpty()) return null;

        ParseCacheEntry cached = getCachedParse(text);
        if (cached != null) {
            return cached.segments;
        }

        ParseResult parsed = parseInternal(text);
        cacheParse(text, parsed, parsed.sawPua);
        return parsed.segments;
    }

    public static List<ParsedSegment> parseDetailed(String text) {
        if (bypass || ClientEmojiHandler.EMOJI_LOOKUP.isEmpty()) return null;

        ParseCacheEntry cached = getCachedParse(text);
        if (cached != null) {
            return cached.detailedSegments;
        }

        ParseResult parsed = parseInternal(text);
        cacheParse(text, parsed, parsed.sawPua);
        return parsed.detailedSegments;
    }

    private static ParseResult parseInternal(String text) {
        List<ParsedSegment> detailedSegments = null;
        int lastEnd = 0;
        boolean sawPua = false;

        for (int i = 0; i < text.length();) {
            EscapeMatch escaped = matchEscapedEmoji(text, i);
            if (escaped != null) {
                if (detailedSegments == null) detailedSegments = new ArrayList<>();
                if (i > lastEnd) detailedSegments.add(ParsedSegment.text(text.substring(lastEnd, i)));
                detailedSegments.add(ParsedSegment.text(escaped.literalText));
                lastEnd = escaped.nextIndex;
                i = lastEnd;
                continue;
            }

            char current = text.charAt(i);
            String puaToken = EmojiPua.tokenAt(text, i);
            if (puaToken != null) {
                sawPua = true;
                EmoteClientHandler.onPuaObserved(puaToken);
                Emoji puaEmoji = ClientEmojiHandler.EMOJI_PUA_LOOKUP.get(puaToken);
                if (puaEmoji instanceof RenderableEmoji renderableEmoji) {
                    if (detailedSegments == null) detailedSegments = new ArrayList<>();
                    if (i > lastEnd) detailedSegments.add(ParsedSegment.text(text.substring(lastEnd, i)));
                    detailedSegments.add(ParsedSegment.emoji(puaToken, renderableEmoji));
                    lastEnd = i + EmojiPua.TOKEN_LENGTH;
                    i = lastEnd;
                    continue;
                }
                if (detailedSegments == null) detailedSegments = new ArrayList<>();
                if (i > lastEnd) detailedSegments.add(ParsedSegment.text(text.substring(lastEnd, i)));
                detailedSegments.add(ParsedSegment.text("\u25A0"));
                lastEnd = i + EmojiPua.TOKEN_LENGTH;
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
                        if (detailedSegments == null) detailedSegments = new ArrayList<>();
                        if (i > lastEnd) detailedSegments.add(ParsedSegment.text(text.substring(lastEnd, i)));
                        detailedSegments.add(ParsedSegment.emoji(key, twitmoji));
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
                if (detailedSegments == null) detailedSegments = new ArrayList<>();
                if (i > lastEnd) detailedSegments.add(ParsedSegment.text(text.substring(lastEnd, i)));
                detailedSegments.add(ParsedSegment.emoji(text.substring(i, i + matchLen), unicodeMatch));
                lastEnd = i + matchLen;
                i = lastEnd;
                continue;
            }

            i++;
        }

        if (detailedSegments == null) {
            return ParseResult.noMatch();
        }

        if (lastEnd < text.length()) {
            detailedSegments.add(ParsedSegment.text(text.substring(lastEnd)));
        }
        return ParseResult.of(detailedSegments, sawPua);
    }

    private static EscapeMatch matchEscapedEmoji(String text, int index) {
        if (index < 0 || index + 1 >= text.length() || text.charAt(index) != '\\') {
            return null;
        }

        int escapedIndex = index + 1;
        char escapedChar = text.charAt(escapedIndex);
        String escapedPuaToken = EmojiPua.tokenAt(text, escapedIndex);
        if (escapedPuaToken != null) {
            Emoji emoji = ClientEmojiHandler.EMOJI_PUA_LOOKUP.get(escapedPuaToken);
            if (emoji instanceof RenderableEmoji renderableEmoji) {
                return new EscapeMatch(getEscapedLiteralText(renderableEmoji), escapedIndex + EmojiPua.TOKEN_LENGTH);
            }
            return new EscapeMatch("\u25A0", escapedIndex + EmojiPua.TOKEN_LENGTH);
        }
        if (escapedChar == ':') {
            int end = text.indexOf(':', escapedIndex + 1);
            if (end != -1) {
                String key = text.substring(escapedIndex, end + 1);
                Emoji emoji = ClientEmojiHandler.EMOJI_LOOKUP.get(key);
                if (emoji instanceof RenderableEmoji) {
                    return new EscapeMatch(key, end + 1);
                }
            }
        }

        var candidates = ClientEmojiHandler.UNICODE_KEYS_BY_CHAR.get(escapedChar);
        if (candidates != null) {
            for (String candidate : candidates) {
                if (text.startsWith(candidate, escapedIndex)) {
                    Emoji emoji = ClientEmojiHandler.EMOJI_UNICODE_LOOKUP.get(candidate);
                    if (emoji instanceof RenderableEmoji) {
                        return new EscapeMatch(
                            getEscapedLiteralText((RenderableEmoji) emoji),
                            escapedIndex + candidate.length());
                    }
                }
            }
        }

        return null;
    }

    private static String getEscapedLiteralText(RenderableEmoji emoji) {
        if (emoji instanceof Emoji minemoticonEmoji) {
            return minemoticonEmoji.getShorterString();
        }
        return "emoji";
    }

    public static void invalidateParseCache() {
        synchronized (PARSE_CACHE) {
            PARSE_CACHE.clear();
        }
    }

    private static ParseCacheEntry getCachedParse(String text) {
        if (text == null || text.length() > MAX_CACHED_TEXT_LENGTH) {
            return null;
        }
        synchronized (PARSE_CACHE) {
            return PARSE_CACHE.get(text);
        }
    }

    private static void cacheParse(String text, ParseResult parsed, boolean sawPua) {
        if (text == null || sawPua || text.length() > MAX_CACHED_TEXT_LENGTH) {
            return;
        }
        ParseCacheEntry entry = parsed.segments == null ? NO_MATCH
            : new ParseCacheEntry(
                Collections.unmodifiableList(new ArrayList<>(parsed.segments)),
                Collections.unmodifiableList(new ArrayList<>(parsed.detailedSegments)));
        synchronized (PARSE_CACHE) {
            PARSE_CACHE.put(text, entry);
        }
    }

    private static final class ParseCacheEntry {

        private final List<Object> segments;
        private final List<ParsedSegment> detailedSegments;

        private ParseCacheEntry(List<Object> segments, List<ParsedSegment> detailedSegments) {
            this.segments = segments;
            this.detailedSegments = detailedSegments;
        }
    }

    private static final class ParseResult {

        private final List<Object> segments;
        private final List<ParsedSegment> detailedSegments;
        private final boolean sawPua;

        private ParseResult(List<Object> segments, List<ParsedSegment> detailedSegments, boolean sawPua) {
            this.segments = segments;
            this.detailedSegments = detailedSegments;
            this.sawPua = sawPua;
        }

        private static ParseResult noMatch() {
            return new ParseResult(null, null, false);
        }

        private static ParseResult of(List<ParsedSegment> detailedSegments, boolean sawPua) {
            List<Object> segments = new ArrayList<>(detailedSegments.size());
            for (ParsedSegment segment : detailedSegments) {
                if (segment.isEmoji()) {
                    segments.add(segment.getEmoji());
                } else {
                    segments.add(segment.getText());
                }
            }
            return new ParseResult(segments, detailedSegments, sawPua);
        }
    }

    public static final class ParsedSegment {

        private final String text;
        private final RenderableEmoji emoji;

        private ParsedSegment(String text, RenderableEmoji emoji) {
            this.text = text;
            this.emoji = emoji;
        }

        public static ParsedSegment text(String text) {
            return new ParsedSegment(text, null);
        }

        public static ParsedSegment emoji(String text, RenderableEmoji emoji) {
            return new ParsedSegment(text, emoji);
        }

        public String getText() {
            return text;
        }

        public RenderableEmoji getEmoji() {
            return emoji;
        }

        public boolean isEmoji() {
            return emoji != null;
        }
    }

    private static final class EscapeMatch {

        private final String literalText;
        private final int nextIndex;

        private EscapeMatch(String literalText, int nextIndex) {
            this.literalText = literalText;
            this.nextIndex = nextIndex;
        }
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
