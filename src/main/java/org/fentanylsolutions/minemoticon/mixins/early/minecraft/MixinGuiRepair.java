package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.GuiRepair;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.ContainerRepair;

import org.fentanylsolutions.minemoticon.gui.EmojiPickerGui;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRepair.class)
public abstract class MixinGuiRepair extends GuiContainer {

    @Shadow
    private GuiTextField field_147091_w;

    @Shadow
    private ContainerRepair field_147092_v;

    @Shadow
    private void func_147090_g() {}

    @Unique
    private EmojiPickerGui minemoticon$picker;

    private MixinGuiRepair() {
        super(null);
    }

    @Inject(method = "initGui", at = @At("TAIL"))
    private void minemoticon$initGui(CallbackInfo ci) {
        int btnX = guiLeft + 62 + 103 + 13;
        int btnY = guiTop + 24;
        minemoticon$picker = new EmojiPickerGui(field_147091_w, fontRendererObj, width, height, btnX, btnY);
    }

    @Inject(method = "drawScreen", at = @At("TAIL"))
    private void minemoticon$drawScreen(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (minemoticon$picker == null || !canInput()) return;

        int dwheel = Mouse.getDWheel();
        if (dwheel != 0 && minemoticon$picker.isOpen()) {
            minemoticon$picker.handleScroll(mouseX, mouseY, Integer.signum(dwheel));
        }

        minemoticon$picker.render(mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void minemoticon$mouseClicked(int mouseX, int mouseY, int button, CallbackInfo ci) {
        if (minemoticon$picker == null || !canInput()) return;

        String insertText = minemoticon$picker.mouseClicked(mouseX, mouseY, button);
        if (insertText != null) {
            field_147091_w.setFocused(true);
            field_147091_w.writeText(insertText);
            func_147090_g();
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
        if (minemoticon$picker != null && canInput() && minemoticon$picker.keyTyped(c, keyCode)) {
            String text = minemoticon$picker.consumeInsertText();
            if (text != null) {
                field_147091_w.setFocused(true);
                field_147091_w.writeText(text);
                func_147090_g();
            }
            ci.cancel();
        }
    }

    @Unique
    private boolean canInput() {
        return field_147092_v.getSlot(0)
            .getHasStack();
    }
}
