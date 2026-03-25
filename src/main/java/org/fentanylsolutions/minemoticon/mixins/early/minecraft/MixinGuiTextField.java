package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.GuiTextField;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GuiTextField.class)
public class MixinGuiTextField {

    private static final int MINEMOTICON$MAX_EMOJI_SEQUENCE_LENGTH = 32;

    @Shadow
    private String text;

    @Shadow
    private int cursorPosition;

    @Shadow
    private int selectionEnd;

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

    // Walk backwards from pos to find the start of a Unicode emoji sequence ending at pos
    private int findEmojiStart(String text, int pos) {
        if (pos <= 0 || pos > text.length()) return -1;

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
