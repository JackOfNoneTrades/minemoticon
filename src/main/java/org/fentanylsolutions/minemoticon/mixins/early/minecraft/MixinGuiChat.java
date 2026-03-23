package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import org.fentanylsolutions.minemoticon.config.MinemoticonGuiConfig;
import org.fentanylsolutions.minemoticon.gui.EmojiPickerGui;
import org.fentanylsolutions.minemoticon.gui.EmojiSuggestionHelper;
import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnewhorizon.gtnhlib.config.ConfigException;

@Mixin(GuiChat.class)
public abstract class MixinGuiChat extends GuiScreen {

    @Shadow
    protected GuiTextField inputField;

    @Unique
    private EmojiPickerGui minemoticon$picker;

    @Unique
    private EmojiSuggestionHelper minemoticon$suggestions;

    @Inject(method = "initGui", at = @At("TAIL"))
    private void minemoticon$initGui(CallbackInfo ci) {
        minemoticon$picker = new EmojiPickerGui(inputField, fontRendererObj, width, height);
        minemoticon$suggestions = new EmojiSuggestionHelper(inputField, fontRendererObj);
    }

    @Inject(method = "drawScreen", at = @At("TAIL"))
    private void minemoticon$drawScreen(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (minemoticon$suggestions != null) {
            minemoticon$suggestions.update();
            minemoticon$suggestions.render(mouseX, mouseY);
        }
        if (minemoticon$picker != null) {
            minemoticon$picker.render(mouseX, mouseY);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void minemoticon$mouseClicked(int mouseX, int mouseY, int button, CallbackInfo ci) {
        // Suggestions click
        if (minemoticon$suggestions != null && minemoticon$suggestions.mouseClicked(mouseX, mouseY, button)) {
            inputField.setFocused(true);
            ci.cancel();
            return;
        }

        if (minemoticon$picker == null) return;

        String insertText = minemoticon$picker.mouseClicked(mouseX, mouseY, button);
        if (insertText != null) {
            inputField.setFocused(true);
            inputField.writeText(insertText + " ");
            ci.cancel();
            return;
        }

        if (minemoticon$picker.shouldOpenConfig()) {
            try {
                mc.displayGuiScreen(new MinemoticonGuiConfig(this));
            } catch (ConfigException ignored) {}
            ci.cancel();
            return;
        }

        if (minemoticon$picker.isInsidePanel(mouseX, mouseY)) {
            ci.cancel();
        } else if (minemoticon$picker.isOpen()) {
            minemoticon$picker.toggle();
            inputField.setFocused(true);
        }
    }

    @Inject(method = "keyTyped", at = @At("HEAD"), cancellable = true)
    private void minemoticon$keyTyped(char c, int keyCode, CallbackInfo ci) {
        // Suggestions get priority when active
        if (minemoticon$suggestions != null && minemoticon$suggestions.isActive()
            && minemoticon$suggestions.keyTyped(c, keyCode)) {
            inputField.setFocused(true);
            ci.cancel();
            return;
        }

        if (minemoticon$picker != null && minemoticon$picker.keyTyped(c, keyCode)) {
            String text = minemoticon$picker.consumeInsertText();
            if (text != null) {
                inputField.setFocused(true);
                inputField.writeText(text + " ");
            }
            inputField.setFocused(true);
            ci.cancel();
        }
    }

    // Intercept outgoing chat to announce pack emojis to the server
    @Inject(method = "func_146403_a", at = @At("HEAD"))
    private void minemoticon$onSendChat(String message, CallbackInfo ci) {
        EmoteClientHandler.announceEmotesInMessage(message);
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
