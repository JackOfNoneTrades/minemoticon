package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.tileentity.TileEntitySign;

import org.fentanylsolutions.minemoticon.gui.EmojiPickerGui;
import org.fentanylsolutions.minemoticon.gui.EmojiSuggestionHelper;
import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.fentanylsolutions.minemoticon.text.EmojiPua;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiEditSign.class)
public class MixinGuiEditSign extends GuiScreen {

    @Unique
    private static final int SIGN_TEXT_WIDTH = 90;

    @Unique
    private static final int SIGN_LINE_HEIGHT = 12;

    @Unique
    private static final int SIGN_TEXT_TOP_OFFSET = 34;

    @Unique
    private static final int SIGN_POPUP_SIDE_OFFSET = 70;

    @Unique
    private static final int SIGN_POPUP_TOP_OFFSET = 64;

    @Shadow
    private TileEntitySign tileSign;

    @Shadow
    private int editLine;

    @Unique
    private EmojiPickerGui minemoticon$picker;

    @Unique
    private EmojiSuggestionHelper minemoticon$suggestions;

    @Unique
    private boolean minemoticon$wasMouseDown;

    @Inject(method = "initGui", at = @At("TAIL"))
    private void minemoticon$initGui(CallbackInfo ci) {
        minemoticon$picker = new EmojiPickerGui(null, fontRendererObj, width, height, false);
        minemoticon$suggestions = new EmojiSuggestionHelper(
            minemoticon$getSignTextInput(),
            fontRendererObj,
            false,
            false,
            width,
            height);
    }

    @Inject(method = "drawScreen", at = @At("TAIL"))
    private void minemoticon$drawScreen(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (minemoticon$picker == null) return;

        int dwheel = Mouse.getDWheel();
        if (dwheel != 0 && minemoticon$picker.isOpen()) {
            minemoticon$picker.handleScroll(mouseX, mouseY, Integer.signum(dwheel));
        }

        boolean mouseDown = Mouse.isButtonDown(0);
        if (mouseDown && !minemoticon$wasMouseDown) {
            minemoticon$handleClick(mouseX, mouseY);
        }
        minemoticon$wasMouseDown = mouseDown;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            if (minemoticon$suggestions != null && minemoticon$hasEditableLine()) {
                minemoticon$suggestions.update();
                minemoticon$suggestions.render(mouseX, mouseY);
            }

            minemoticon$picker.render(mouseX, mouseY);
        } finally {
            GL11.glPopAttrib();
        }
    }

    @Unique
    private void minemoticon$handleClick(int mouseX, int mouseY) {
        if (minemoticon$suggestions != null && minemoticon$suggestions.mouseClicked(mouseX, mouseY, 0)) {
            minemoticon$registerSuggestionInsert();
            return;
        }

        String insertText = minemoticon$picker.mouseClicked(mouseX, mouseY, 0);
        if (insertText != null) {
            minemoticon$insertText(insertText);
            return;
        }

        if (!minemoticon$picker.isInsidePanel(mouseX, mouseY) && minemoticon$picker.isOpen()) {
            minemoticon$picker.toggle();
        }
    }

    @Inject(method = "keyTyped", at = @At("HEAD"), cancellable = true)
    private void minemoticon$pickerKeyTyped(char c, int keyCode, CallbackInfo ci) {
        if (minemoticon$suggestions != null && minemoticon$suggestions.isActive()
            && minemoticon$suggestions.keyTyped(c, keyCode)) {
            minemoticon$registerSuggestionInsert();
            ci.cancel();
            return;
        }

        if (minemoticon$picker != null && minemoticon$picker.keyTyped(c, keyCode)) {
            String text = minemoticon$picker.consumeInsertText();
            if (text != null) {
                minemoticon$insertText(text);
            }
            ci.cancel();
            return;
        }
    }

    @Inject(method = "keyTyped", at = @At("TAIL"))
    private void minemoticon$substituteCompletedAlias(char typedChar, int keyCode, CallbackInfo ci) {
        if (tileSign == null || tileSign.signText == null || editLine < 0 || editLine >= tileSign.signText.length) {
            return;
        }

        String line = tileSign.signText[editLine];
        EmoteClientHandler.TextReplacement replacement = EmoteClientHandler
            .substituteCompletedAlias(line, line.length());
        if (replacement != null) {
            tileSign.signText[editLine] = replacement.text;
        }
    }

    @Unique
    private void minemoticon$insertText(String text) {
        if (!minemoticon$hasEditableLine()) {
            return;
        }
        String current = tileSign.signText[editLine];
        if (current.length() + text.length() <= 15) {
            tileSign.signText[editLine] = current + text;
        }
    }

    @Unique
    private EmojiSuggestionHelper.TextInput minemoticon$getSignTextInput() {
        return new EmojiSuggestionHelper.TextInput() {

            @Override
            public String getText() {
                return minemoticon$hasEditableLine() ? tileSign.signText[editLine] : "";
            }

            @Override
            public int getCursorPosition() {
                return getText().length();
            }

            @Override
            public boolean setText(String text) {
                if (!minemoticon$hasEditableLine() || text.length() > 15) {
                    return false;
                }
                tileSign.signText[editLine] = text;
                return true;
            }

            @Override
            public void setCursorPosition(int position) {}

            @Override
            public int getX() {
                return width / 2 - SIGN_TEXT_WIDTH / 2;
            }

            @Override
            public int getY() {
                return height / 2 - SIGN_TEXT_TOP_OFFSET + editLine * SIGN_LINE_HEIGHT;
            }

            @Override
            public int getHeight() {
                return SIGN_LINE_HEIGHT;
            }

            @Override
            public int getVisibleWidth() {
                return SIGN_TEXT_WIDTH;
            }

            @Override
            public int getScrollOffset() {
                return 0;
            }

            @Override
            public boolean hasBackground() {
                return false;
            }

            @Override
            public int getPopupX(int preferredX, int popupWidth, int screenWidth) {
                int rightX = width / 2 + SIGN_POPUP_SIDE_OFFSET;
                if (rightX + popupWidth <= screenWidth - 2) {
                    return rightX;
                }
                return width / 2 - SIGN_POPUP_SIDE_OFFSET - popupWidth;
            }

            @Override
            public int getPopupY(int preferredY, int popupHeight, int screenHeight) {
                return height / 2 - SIGN_POPUP_TOP_OFFSET;
            }
        };
    }

    @Unique
    private void minemoticon$registerSuggestionInsert() {
        String text = minemoticon$suggestions.consumeInsertText();
        if (EmojiPua.isPuaToken(text)) {
            EmoteClientHandler.onPuaObserved(text);
        }
    }

    @Unique
    private boolean minemoticon$hasEditableLine() {
        return tileSign != null && tileSign.signText != null && editLine >= 0 && editLine < tileSign.signText.length;
    }
}
