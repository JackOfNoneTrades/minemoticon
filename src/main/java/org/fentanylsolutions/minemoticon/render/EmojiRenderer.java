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
import org.fentanylsolutions.minemoticon.api.EmojiFromResource;
import org.fentanylsolutions.minemoticon.api.RenderableEmoji;
import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.fentanylsolutions.minemoticon.text.EmojiPua;
import org.fentanylsolutions.minemoticon.text.TextStyleCompat;
import org.lwjgl.opengl.GL11;

public class EmojiRenderer {

    public static final float EMOJI_SIZE = 10.0f;
    private static final RenderableEmoji UNRESOLVED_PUA_PLACEHOLDER = UnresolvedPuaPlaceholder.INSTANCE;
    private static final int MAX_PARSE_CACHE_ENTRIES = 512;
    private static final int MAX_CACHED_TEXT_LENGTH = 256;
    private static final int MAX_COLON_ALIAS_LENGTH = 64;
    private static final ParseCacheEntry NO_MATCH = new ParseCacheEntry(null, null);
    private static final ThreadLocal<Boolean> COLON_ALIAS_MATCHING = new ThreadLocal<>();
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
        if (bypass || text == null) return null;

        boolean cacheable = !isColonAliasMatchingAllowed();
        if (cacheable) {
            ParseCacheEntry cached = getCachedParse(text);
            if (cached != null) {
                return cached.segments;
            }
        }

        ParseResult parsed = parseInternal(text);
        if (cacheable) {
            cacheParse(text, parsed, parsed.sawPua);
        }
        return parsed.segments;
    }

    public static List<ParsedSegment> parseDetailed(String text) {
        if (bypass || text == null) return null;

        boolean cacheable = !isColonAliasMatchingAllowed();
        if (cacheable) {
            ParseCacheEntry cached = getCachedParse(text);
            if (cached != null) {
                return cached.detailedSegments;
            }
        }

        ParseResult parsed = parseInternal(text);
        if (cacheable) {
            cacheParse(text, parsed, parsed.sawPua);
        }
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

            EmojiMatch match = matchAtInternal(text, i);
            if (match != null) {
                sawPua |= match.sawPua;
                if (detailedSegments == null) detailedSegments = new ArrayList<>();
                if (i > lastEnd) detailedSegments.add(ParsedSegment.text(text.substring(lastEnd, i)));
                detailedSegments.add(ParsedSegment.emoji(match.text, match.emoji));
                lastEnd = i + match.length;
                i = lastEnd;
                continue;
            }

            i += Character.charCount(text.codePointAt(i));
        }

        if (detailedSegments == null) {
            return ParseResult.noMatch();
        }

        if (lastEnd < text.length()) {
            detailedSegments.add(ParsedSegment.text(text.substring(lastEnd)));
        }
        return ParseResult.of(detailedSegments, sawPua);
    }

    public static EmojiMatch matchAt(String text, int index) {
        if (bypass) {
            return null;
        }
        return matchAtInternal(text, index);
    }

    private static EmojiMatch matchAtInternal(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) {
            return null;
        }

        EmojiMatch puaMatch = matchPuaEmoji(text, index);
        if (puaMatch != null) {
            return puaMatch;
        }

        EmojiMatch resourceMatch = matchNamespacedResourceEmoji(text, index);
        if (resourceMatch != null) {
            return resourceMatch;
        }

        EmojiMatch colonAliasMatch = matchColonAliasEmoji(text, index);
        if (colonAliasMatch != null) {
            return colonAliasMatch;
        }

        return matchUnicodeEmoji(text, index);
    }

    private static EmojiMatch matchPuaEmoji(String text, int index) {
        String puaToken = EmojiPua.tokenAt(text, index);
        int length = EmojiPua.TOKEN_LENGTH;
        if (puaToken == null) {
            SplitPuaMatch splitMatch = matchSplitPuaToken(text, index);
            if (splitMatch == null) {
                return null;
            }
            puaToken = splitMatch.token;
            length = splitMatch.length;
        }

        EmoteClientHandler.onPuaObserved(puaToken);
        Emoji puaEmoji = ClientEmojiHandler.EMOJI_PUA_LOOKUP.get(puaToken);
        if (puaEmoji instanceof RenderableEmoji renderableEmoji) {
            return new EmojiMatch(puaToken, renderableEmoji, length, true);
        }
        return new EmojiMatch(puaToken, UNRESOLVED_PUA_PLACEHOLDER, length, true);
    }

    private static SplitPuaMatch matchSplitPuaToken(String text, int index) {
        if (!EmojiPua.isPua(text.charAt(index))) {
            return null;
        }

        StringBuilder token = new StringBuilder(EmojiPua.TOKEN_LENGTH);
        int i = index;
        while (i < text.length() && token.length() < EmojiPua.TOKEN_LENGTH) {
            int tokenEnd = TextStyleCompat.tokenEnd(text, i);
            if (tokenEnd > i) {
                i = tokenEnd;
                continue;
            }

            char c = text.charAt(i);
            if (!EmojiPua.isPua(c)) {
                return null;
            }
            token.append(c);
            i++;
        }

        String puaToken = token.toString();
        if (!EmojiPua.isPuaToken(puaToken)) {
            return null;
        }
        return new SplitPuaMatch(puaToken, i - index);
    }

    private static EmojiMatch matchUnicodeEmoji(String text, int index) {
        var candidates = ClientEmojiHandler.UNICODE_KEYS_BY_CHAR.get(text.charAt(index));
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            int length = matchVisibleChars(text, index, candidate);
            if (length > 0) {
                Emoji emoji = ClientEmojiHandler.EMOJI_UNICODE_LOOKUP.get(candidate);
                if (emoji instanceof RenderableEmoji renderableEmoji) {
                    return new EmojiMatch(candidate, renderableEmoji, length, false);
                }
            }
        }
        return null;
    }

    private static int matchVisibleChars(String text, int index, String candidate) {
        int textIndex = index;
        int candidateIndex = 0;
        while (candidateIndex < candidate.length()) {
            if (textIndex >= text.length()) {
                return -1;
            }

            int tokenEnd = TextStyleCompat.tokenEnd(text, textIndex);
            if (tokenEnd > textIndex) {
                textIndex = tokenEnd;
                continue;
            }

            if (text.charAt(textIndex) != candidate.charAt(candidateIndex)) {
                return -1;
            }
            textIndex++;
            candidateIndex++;
        }
        return textIndex - index;
    }

    public static boolean pushColonAliasMatching(boolean enabled) {
        boolean previous = isColonAliasMatchingAllowed();
        if (enabled) {
            COLON_ALIAS_MATCHING.set(Boolean.TRUE);
        }
        return previous;
    }

    public static void popColonAliasMatching(boolean previous) {
        if (previous) {
            COLON_ALIAS_MATCHING.set(Boolean.TRUE);
        } else {
            COLON_ALIAS_MATCHING.remove();
        }
    }

    public static boolean isColonAliasMatchingAllowed() {
        return Boolean.TRUE.equals(COLON_ALIAS_MATCHING.get());
    }

    private static EmojiMatch matchNamespacedResourceEmoji(String text, int index) {
        if (text.charAt(index) != ':') {
            return null;
        }

        int end = text.indexOf(':', index + 1);
        if (end == -1) {
            return null;
        }

        String key = text.substring(index, end + 1);
        if (key.indexOf('/') <= 0) {
            return null;
        }

        Emoji emoji = ClientEmojiHandler.EMOJI_LOOKUP.get(key);
        if (emoji instanceof EmojiFromResource && emoji instanceof RenderableEmoji renderableEmoji) {
            return new EmojiMatch(key, renderableEmoji, key.length(), false);
        }
        return null;
    }

    private static EmojiMatch matchColonAliasEmoji(String text, int index) {
        if (!isColonAliasMatchingAllowed() || text.charAt(index) != ':') {
            return null;
        }

        StringBuilder key = new StringBuilder();
        for (int i = index; i < text.length() && key.length() <= MAX_COLON_ALIAS_LENGTH;) {
            int tokenEnd = TextStyleCompat.tokenEnd(text, i);
            if (tokenEnd > i) {
                i = tokenEnd;
                continue;
            }

            int cp = text.codePointAt(i);
            key.appendCodePoint(cp);
            i += Character.charCount(cp);
            if (cp == ':' && key.length() > 1) {
                Emoji emoji = ClientEmojiHandler.EMOJI_LOOKUP.get(key.toString());
                if (emoji instanceof RenderableEmoji renderableEmoji) {
                    return new EmojiMatch(key.toString(), renderableEmoji, i - index, false);
                }
                return null;
            }
        }
        return null;
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

    public static final class EmojiMatch {

        private final String text;
        private final RenderableEmoji emoji;
        private final int length;
        private final boolean sawPua;

        private EmojiMatch(String text, RenderableEmoji emoji, int length, boolean sawPua) {
            this.text = text;
            this.emoji = emoji;
            this.length = length;
            this.sawPua = sawPua;
        }

        public String getText() {
            return text;
        }

        public RenderableEmoji getEmoji() {
            return emoji;
        }

        public int getLength() {
            return length;
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

    private static final class SplitPuaMatch {

        private final String token;
        private final int length;

        private SplitPuaMatch(String token, int length) {
            this.token = token;
            this.length = length;
        }
    }

    public static void renderQuad(RenderableEmoji emoji, float x, float y) {
        if (emoji == UNRESOLVED_PUA_PLACEHOLDER) {
            renderUnresolvedPlaceholder(x, y);
            return;
        }

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

    private static void renderUnresolvedPlaceholder(float x, float y) {
        boolean textureWasEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        if (textureWasEnabled) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
        if (!blendWasEnabled) {
            GL11.glEnable(GL11.GL_BLEND);
        }
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        float inset = 1.0f;
        float x0 = x + inset;
        float y0 = y + inset;
        float x1 = x + EMOJI_SIZE - inset;
        float y1 = y + EMOJI_SIZE - inset;
        float border = 0.75f;

        Tessellator tessellator = Tessellator.instance;

        GL11.glColor4f(0.85f, 0.85f, 0.85f, 0.85f);
        tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
        tessellator.addVertex(x0 + border, y0 + border, 0.0);
        tessellator.addVertex(x0 + border, y1 - border, 0.0);
        tessellator.addVertex(x1 - border, y0 + border, 0.0);
        tessellator.addVertex(x1 - border, y1 - border, 0.0);
        tessellator.draw();

        GL11.glColor4f(0.35f, 0.35f, 0.35f, 1.0f);
        tessellator.startDrawing(GL11.GL_LINE_LOOP);
        tessellator.addVertex(x0, y0, 0.0);
        tessellator.addVertex(x0, y1, 0.0);
        tessellator.addVertex(x1, y1, 0.0);
        tessellator.addVertex(x1, y0, 0.0);
        tessellator.draw();

        if (textureWasEnabled) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }
        if (!blendWasEnabled) {
            GL11.glDisable(GL11.GL_BLEND);
        }
    }

    private static final class UnresolvedPuaPlaceholder implements RenderableEmoji {

        private static final UnresolvedPuaPlaceholder INSTANCE = new UnresolvedPuaPlaceholder();

        @Override
        public net.minecraft.util.ResourceLocation getResourceLocation() {
            return null;
        }

        @Override
        public boolean isLoaded() {
            return true;
        }
    }
}
