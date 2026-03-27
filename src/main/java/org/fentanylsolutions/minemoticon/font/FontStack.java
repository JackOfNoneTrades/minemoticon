package org.fentanylsolutions.minemoticon.font;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.fentanylsolutions.minemoticon.colorfont.ColorFont;

// Ordered list of font sources. The first source with a glyph wins.
public class FontStack {

    // Sentinel stored in glyphCache when no source claims a codepoint.
    private static final FontSource NO_SOURCE = new MinecraftFontSource() {

        @Override
        public String getId() {
            return "__none__";
        }
    };

    private final List<FontSource> enabled;
    private final Map<Integer, FontSource> glyphCache = new HashMap<>();

    public FontStack(List<FontSource> enabled) {
        this.enabled = Collections.unmodifiableList(new ArrayList<>(enabled));
    }

    // Resolve which font source should render this codepoint. Returns null if none claim it.
    public FontSource resolve(int codepoint) {
        FontSource cached = glyphCache.get(codepoint);
        if (cached != null) {
            return cached == NO_SOURCE ? null : cached;
        }

        for (FontSource source : enabled) {
            if (source.hasGlyph(codepoint)) {
                glyphCache.put(codepoint, source);
                return source;
            }
        }

        glyphCache.put(codepoint, NO_SOURCE);
        return null;
    }

    public void invalidate() {
        glyphCache.clear();
    }

    public List<FontSource> getEnabled() {
        return enabled;
    }

    // Returns the first ColorFont-backed source in the stack (for emoji pipeline compat).
    public ColorFont getEmojiFont() {
        for (FontSource source : enabled) {
            ColorFont cf = source.getColorFont();
            if (cf != null) return cf;
        }
        return null;
    }

    // Returns the second ColorFont-backed source (fallback for emoji pipeline).
    public ColorFont getEmojiFallbackFont() {
        boolean foundFirst = false;
        for (FontSource source : enabled) {
            ColorFont cf = source.getColorFont();
            if (cf != null) {
                if (foundFirst) return cf;
                foundFirst = true;
            }
        }
        return null;
    }

    // Returns the font hash of the primary emoji font, for atlas cache keying.
    public String getEmojiFontHash() {
        for (FontSource source : enabled) {
            String hash = source.getFontHash();
            if (hash != null) return hash;
        }
        return null;
    }

    // Check if the primary emoji font is the bundled Twemoji (for atlas optimization).
    public boolean isPrimaryEmojiTwemoji() {
        for (FontSource source : enabled) {
            if (source.getColorFont() != null) {
                return source instanceof TwemojiFontSource;
            }
        }
        return false;
    }
}
