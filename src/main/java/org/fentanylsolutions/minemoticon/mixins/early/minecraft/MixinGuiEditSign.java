package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.tileentity.TileEntitySign;

import org.fentanylsolutions.minemoticon.gui.EmojiPickerGui;
import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiEditSign.class)
public class MixinGuiEditSign extends GuiScreen {

    @Shadow
    private TileEntitySign tileSign;

    @Shadow
    private int editLine;

    @Unique
    private EmojiPickerGui minemoticon$picker;

    @Unique
    private boolean minemoticon$wasMouseDown;

    @Inject(method = "initGui", at = @At("TAIL"))
    private void minemoticon$initGui(CallbackInfo ci) {
        minemoticon$picker = new EmojiPickerGui(null, fontRendererObj, width, height);
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

        minemoticon$picker.render(mouseX, mouseY);
    }

    @Unique
    private void minemoticon$handleClick(int mouseX, int mouseY) {
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
        if (tileSign == null || tileSign.signText == null || editLine < 0 || editLine >= tileSign.signText.length) {
            return;
        }
        String current = tileSign.signText[editLine];
        if (current.length() + text.length() <= 15) {
            tileSign.signText[editLine] = current + text;
        }
    }
}
