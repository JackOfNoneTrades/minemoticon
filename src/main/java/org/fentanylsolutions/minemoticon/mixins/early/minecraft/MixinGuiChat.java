package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import org.fentanylsolutions.minemoticon.gui.EmojiPickerGui;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiChat.class)
public abstract class MixinGuiChat extends GuiScreen {

    @Shadow
    protected GuiTextField inputField;

    @Unique
    private EmojiPickerGui minemoticon$picker;

    @Inject(method = "initGui", at = @At("TAIL"))
    private void minemoticon$initGui(CallbackInfo ci) {
        minemoticon$picker = new EmojiPickerGui(inputField, fontRendererObj, width, height);
    }

    @Inject(method = "drawScreen", at = @At("TAIL"))
    private void minemoticon$drawScreen(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (minemoticon$picker != null) {
            minemoticon$picker.render(mouseX, mouseY);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void minemoticon$mouseClicked(int mouseX, int mouseY, int button, CallbackInfo ci) {
        if (minemoticon$picker == null) return;

        String insertText = minemoticon$picker.mouseClicked(mouseX, mouseY, button);
        if (insertText != null) {
            inputField.writeText(insertText + " ");
            ci.cancel();
            return;
        }

        if (minemoticon$picker.isInsidePanel(mouseX, mouseY)) {
            ci.cancel();
        } else if (minemoticon$picker.isOpen()) {
            minemoticon$picker.toggle();
        }
    }

    @Inject(method = "keyTyped", at = @At("HEAD"), cancellable = true)
    private void minemoticon$keyTyped(char c, int keyCode, CallbackInfo ci) {
        if (minemoticon$picker != null && minemoticon$picker.keyTyped(c, keyCode)) {
            String text = minemoticon$picker.consumeInsertText();
            if (text != null) inputField.writeText(text + " ");
            ci.cancel();
        }
    }

    @Inject(method = "handleMouseInput", at = @At("HEAD"), cancellable = true)
    private void minemoticon$handleMouseInput(CallbackInfo ci) {
        if (minemoticon$picker == null || !minemoticon$picker.isOpen()) return;

        int delta = Mouse.getEventDWheel();
        if (delta == 0) return;

        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;

        if (minemoticon$picker.handleScroll(mouseX, mouseY, Integer.signum(delta))) {
            ci.cancel();
        }
    }

    @Inject(method = "updateScreen", at = @At("TAIL"))
    private void minemoticon$updateScreen(CallbackInfo ci) {
        if (minemoticon$picker != null) {
            minemoticon$picker.tick();
        }
    }
}
