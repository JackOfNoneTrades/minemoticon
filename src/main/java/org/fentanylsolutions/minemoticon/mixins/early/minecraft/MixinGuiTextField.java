package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.fentanylsolutions.minemoticon.render.FontRendererEmojiCompat;
import org.fentanylsolutions.minemoticon.text.EmojiPua;
import org.fentanylsolutions.minemoticon.text.TextStyleCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiTextField.class)
public abstract class MixinGuiTextField {

    private static final int MINEMOTICON$MAX_EMOJI_SEQUENCE_LENGTH = 32;

    @Shadow
    private String text;

    @Shadow
    private int cursorPosition;

    @Shadow
    private int selectionEnd;

    @Shadow
    private int lineScrollOffset;

    @Shadow
    public abstract boolean isFocused();

    @Shadow
    public abstract void setText(String text);

    @Shadow
    public abstract void setCursorPosition(int position);

    @Shadow
    public abstract int getWidth();

    @Unique
    private int minemoticon$drawTextBoxStringCall;

    @Unique
    private boolean minemoticon$drawTextBoxRenderedWholePreview;

    @Unique
    private static int minemoticon$textFieldDebugLogs;

    // Expand cursor movement to skip over full Unicode emoji sequences
    @ModifyVariable(method = "moveCursorBy", at = @At("HEAD"), argsOnly = true)
    private int minemoticon$expandEmojiCursorMove(int amount) {
        if (text == null || text.isEmpty()) return amount;

        if (amount == -1) {
            int start = findEmojiStart(text, selectionEnd);
            if (start >= 0) {
                return start - selectionEnd;
            }
        } else if (amount == 1) {
            int end = findEmojiEnd(text, selectionEnd);
            if (end > selectionEnd) {
                return end - selectionEnd;
            }
        }

        return amount;
    }

    // Expand single-char delete to cover full Unicode emoji sequences
    @ModifyVariable(method = "deleteFromCursor", at = @At("HEAD"), argsOnly = true)
    private int minemoticon$expandEmojiDelete(int amount) {
        if (text == null || text.isEmpty()) return amount;

        if (amount == -1) {
            // Backspace: check if chars before cursor form a Unicode emoji
            int start = findEmojiStart(text, cursorPosition);
            if (start >= 0) {
                return start - cursorPosition; // negative offset to delete whole emoji
            }
        } else if (amount == 1) {
            // Delete key: check if chars after cursor form a Unicode emoji
            int end = findEmojiEnd(text, cursorPosition);
            if (end > cursorPosition) {
                return end - cursorPosition;
            }
        }

        return amount;
    }

    @ModifyVariable(method = "setCursorPosition", at = @At("HEAD"), argsOnly = true)
    private int minemoticon$snapCursorPositionToEmojiBoundary(int position) {
        return snapToEmojiBoundary(position, cursorPosition);
    }

    @ModifyVariable(method = "setSelectionPos", at = @At("HEAD"), argsOnly = true)
    private int minemoticon$snapSelectionPositionToEmojiBoundary(int position) {
        return snapToEmojiBoundary(position, selectionEnd);
    }

    @Inject(method = "writeText", at = @At("TAIL"))
    private void minemoticon$substituteCompletedAlias(String insertedText, CallbackInfo ci) {
        if (!isFocused()) {
            return;
        }

        EmoteClientHandler.TextReplacement replacement = EmoteClientHandler
            .substituteCompletedAlias(text, cursorPosition);
        if (replacement == null) {
            return;
        }

        setText(replacement.text);
        setCursorPosition(replacement.cursorPosition);
    }

    @Inject(method = "drawTextBox", at = @At("HEAD"))
    private void minemoticon$resetTextBoxDrawState(CallbackInfo ci) {
        minemoticon$drawTextBoxStringCall = 0;
        minemoticon$drawTextBoxRenderedWholePreview = false;
    }

    @Redirect(
        method = "drawTextBox",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/FontRenderer;drawStringWithShadow(Ljava/lang/String;III)I"))
    private int minemoticon$drawTextBoxSegment(FontRenderer font, String segment, int x, int y, int color) {
        if ("_".equals(segment)) {
            return font.drawStringWithShadow(segment, x, y, color);
        }

        String visible = minemoticon$getVisibleText(font);
        if (!minemoticon$needsWholePreviewDraw(visible)) {
            return font.drawStringWithShadow(segment, x, y, color);
        }

        boolean previousColonAliasMatching = EmojiRenderer
            .pushColonAliasMatching(minemoticon$shouldMatchColonAliases(visible));
        try {
            int call = minemoticon$drawTextBoxStringCall++;
            if (call == 0) {
                String prefix = TextStyleCompat.activeFormatPrefix(text, lineScrollOffset);
                font.drawStringWithShadow(prefix + visible, x, y, color);
                minemoticon$drawTextBoxRenderedWholePreview = true;
                minemoticon$logTextFieldCompat(font, visible, segment, x);
                return minemoticon$textBoxShadowEnd(font, visible, x);
            }

            if (minemoticon$drawTextBoxRenderedWholePreview) {
                return minemoticon$textBoxShadowEnd(font, visible, x);
            }
            return font.drawStringWithShadow(segment, x, y, color);
        } finally {
            EmojiRenderer.popColonAliasMatching(previousColonAliasMatching);
        }
    }

    @Unique
    private int minemoticon$textBoxShadowEnd(FontRenderer font, String visible, int x) {
        String prefix = TextStyleCompat.activeFormatPrefix(text, lineScrollOffset);
        String beforeCursor = minemoticon$getVisibleTextBeforeCursor(visible);
        int width = font instanceof FontRendererEmojiCompat compat
            ? compat.minemoticon$getStringWidthCompatDirect(prefix + beforeCursor)
            : font.getStringWidth(prefix + beforeCursor);
        return x + width + 1;
    }

    @Unique
    private String minemoticon$getVisibleTextBeforeCursor(String visible) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        int start = Math.max(0, Math.min(lineScrollOffset, text.length()));
        int end = Math.max(start, Math.min(cursorPosition, text.length()));
        String beforeCursor = text.substring(start, end);
        if (visible == null) {
            return beforeCursor;
        }
        if (beforeCursor.length() > visible.length()) {
            return visible;
        }
        if (!visible.startsWith(beforeCursor)) {
            return visible.substring(0, Math.min(beforeCursor.length(), visible.length()));
        }
        return beforeCursor;
    }

    @Unique
    private void minemoticon$logTextFieldCompat(FontRenderer font, String visible, String segment, int x) {
        if (minemoticon$textFieldDebugLogs >= 16) {
            return;
        }
        minemoticon$textFieldDebugLogs++;
        int vanillaWidth = font.getStringWidth(segment);
        String beforeCursor = minemoticon$getVisibleTextBeforeCursor(visible);
        int compatWidth = font instanceof FontRendererEmojiCompat compat
            ? compat.minemoticon$getStringWidthCompatDirect(
                TextStyleCompat.activeFormatPrefix(text, lineScrollOffset) + beforeCursor)
            : vanillaWidth;
        Minemoticon.debug(
            "TextField compat draw {} x={} visibleLen={} segmentLen={} preCaretLen={} vanillaWidth={} compatWidth={} cursor={} scroll={} visible='{}' segment='{}'",
            minemoticon$textFieldDebugLogs,
            x,
            visible.length(),
            segment.length(),
            beforeCursor.length(),
            vanillaWidth,
            compatWidth,
            cursorPosition,
            lineScrollOffset,
            minemoticon$debugSnippet(visible),
            minemoticon$debugSnippet(segment));
    }

    @Unique
    private static String minemoticon$debugSnippet(String value) {
        if (value == null) {
            return "null";
        }
        String escaped = value.replace('\u00A7', '&')
            .replace('\n', ' ');
        return escaped.length() > 80 ? escaped.substring(0, 80) : escaped;
    }

    @Unique
    private String minemoticon$getVisibleText(FontRenderer font) {
        if (text == null || text.isEmpty() || lineScrollOffset >= text.length()) {
            return "";
        }
        int start = Math.max(0, lineScrollOffset);
        return font.trimStringToWidth(text.substring(start), getWidth());
    }

    @Unique
    private boolean minemoticon$shouldMatchColonAliases(String visible) {
        String prefix = TextStyleCompat.activeFormatPrefix(text, lineScrollOffset);
        String candidate = prefix + visible;
        return candidate.indexOf(':') >= 0 && TextStyleCompat.hasExtendedStyle(TextStyleCompat.normalize(candidate));
    }

    @Unique
    private boolean minemoticon$needsWholePreviewDraw(String visible) {
        String prefix = TextStyleCompat.activeFormatPrefix(text, lineScrollOffset);
        String candidate = prefix + visible;
        if (candidate.isEmpty()) {
            return false;
        }

        String normalized = TextStyleCompat.normalize(candidate);
        return EmojiRenderer.parse(normalized) != null || TextStyleCompat.hasExtendedStyle(normalized);
    }

    // Walk backwards from pos to find the start of a Unicode emoji sequence ending at pos
    private int findEmojiStart(String text, int pos) {
        if (pos <= 0 || pos > text.length()) return -1;

        if (pos >= EmojiPua.TOKEN_LENGTH) {
            String puaToken = EmojiPua.tokenAt(text, pos - EmojiPua.TOKEN_LENGTH);
            if (puaToken != null) {
                return pos - EmojiPua.TOKEN_LENGTH;
            }
        }

        // Prefer the longest emoji ending at pos so we do not stop on trailing components
        // inside a larger ZWJ ligature.
        for (int len = Math.min(MINEMOTICON$MAX_EMOJI_SEQUENCE_LENGTH, pos); len >= 2; len--) {
            int start = pos - len;
            if (start < 0) break;
            String candidate = text.substring(start, pos);
            var keys = ClientEmojiHandler.UNICODE_KEYS_BY_CHAR.get(candidate.charAt(0));
            if (keys != null) {
                for (String key : keys) {
                    if (key.equals(candidate)) {
                        return start;
                    }
                }
            }
        }

        // Check for surrogate pairs (codepoints above U+FFFF)
        if (pos >= 2 && Character.isLowSurrogate(text.charAt(pos - 1))
            && Character.isHighSurrogate(text.charAt(pos - 2))) {
            return pos - 2;
        }

        // Check for variation selectors (FE0F, FE0E) after a base char
        if (pos >= 2) {
            char prev = text.charAt(pos - 1);
            if (prev == '\uFE0F' || prev == '\uFE0E') {
                return pos - 2; // delete base char + variation selector together
            }
        }

        return -1;
    }

    // Find the end of a Unicode emoji sequence starting at pos
    private int findEmojiEnd(String text, int pos) {
        if (pos < 0 || pos >= text.length()) return pos;

        String puaToken = EmojiPua.tokenAt(text, pos);
        if (puaToken != null) {
            return pos + EmojiPua.TOKEN_LENGTH;
        }

        var keys = ClientEmojiHandler.UNICODE_KEYS_BY_CHAR.get(text.charAt(pos));
        if (keys != null) {
            for (String key : keys) { // sorted longest-first
                if (text.startsWith(key, pos)) {
                    return pos + key.length();
                }
            }
        }

        // Surrogate pair
        if (pos + 1 < text.length() && Character.isHighSurrogate(text.charAt(pos))
            && Character.isLowSurrogate(text.charAt(pos + 1))) {
            int end = pos + 2;
            // Also consume trailing variation selector
            if (end < text.length() && (text.charAt(end) == '\uFE0F' || text.charAt(end) == '\uFE0E')) {
                end++;
            }
            return end;
        }

        return pos;
    }

    private int snapToEmojiBoundary(int position, int previousPosition) {
        if (text == null || text.isEmpty()) return position;
        if (position <= 0 || position >= text.length()) return position;

        int[] bounds = findContainingEmojiBounds(text, position);
        if (bounds == null) {
            return position;
        }

        int start = bounds[0];
        int end = bounds[1];

        if (Math.abs(position - previousPosition) == 1) {
            return position < previousPosition ? start : end;
        }

        int midpoint = start + (end - start) / 2;
        return position <= midpoint ? start : end;
    }

    private int[] findContainingEmojiBounds(String text, int pos) {
        if (pos <= 0 || pos >= text.length()) return null;

        int puaStart = pos - 1;
        if (puaStart >= 0) {
            String leftToken = EmojiPua.tokenAt(text, puaStart);
            if (leftToken != null && pos > puaStart && pos < puaStart + EmojiPua.TOKEN_LENGTH) {
                return new int[] { puaStart, puaStart + EmojiPua.TOKEN_LENGTH };
            }
        }

        String currentToken = EmojiPua.tokenAt(text, pos);
        if (currentToken != null) {
            return new int[] { pos, pos + EmojiPua.TOKEN_LENGTH };
        }

        int scanStart = Math.max(0, pos - MINEMOTICON$MAX_EMOJI_SEQUENCE_LENGTH);
        int bestStart = -1;
        int bestEnd = -1;

        for (int start = pos - 1; start >= scanStart; start--) {
            var keys = ClientEmojiHandler.UNICODE_KEYS_BY_CHAR.get(text.charAt(start));
            if (keys == null) {
                continue;
            }

            for (String key : keys) { // sorted longest-first
                int end = start + key.length();
                if (end <= pos || end > text.length()) {
                    continue;
                }
                if (text.startsWith(key, start)) {
                    if (bestStart < 0 || end - start > bestEnd - bestStart) {
                        bestStart = start;
                        bestEnd = end;
                    }
                    break;
                }
            }
        }

        if (bestStart >= 0 && pos > bestStart && pos < bestEnd) {
            return new int[] { bestStart, bestEnd };
        }

        if (Character.isLowSurrogate(text.charAt(pos)) && Character.isHighSurrogate(text.charAt(pos - 1))) {
            return new int[] { pos - 1, pos + 1 };
        }

        char current = text.charAt(pos);
        if (current == '\uFE0F' || current == '\uFE0E') {
            return new int[] { pos - 1, pos + 1 };
        }

        return null;
    }
}
