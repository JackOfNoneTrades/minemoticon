package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.gui.GuiRepair;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.ContainerRepair;

import org.fentanylsolutions.minemoticon.gui.EmojiPickerGui;
import org.fentanylsolutions.minemoticon.network.EmoteClientHandler;
import org.fentanylsolutions.minemoticon.text.EmojiPua;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cpw.mods.fml.common.Loader;

@Mixin(GuiRepair.class)
public abstract class MixinGuiRepair extends GuiContainer {

    @Unique
    private static final int PICKER_BUTTON_SIZE = 12;

    @Unique
    private static final int PICKER_MARGIN = 4;

    @Unique
    private static final int SCREEN_MARGIN = 2;

    @Unique
    private static final int NEI_FOOTER_HEIGHT = 20;

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
        int[] buttonPos = minemoticon$getPickerButtonPosition();
        int btnX = buttonPos[0];
        int btnY = buttonPos[1];
        minemoticon$picker = new EmojiPickerGui(field_147091_w, fontRendererObj, width, height, btnX, btnY, false);
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
            minemoticon$registerInsertedPua(insertText);
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
                minemoticon$registerInsertedPua(text);
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

    @Unique
    private void minemoticon$registerInsertedPua(String text) {
        if (EmojiPua.isPuaToken(text)) {
            EmoteClientHandler.onPuaObserved(text);
        }
    }

    @Unique
    private int[] minemoticon$getPickerButtonPosition() {
        int x = guiLeft + xSize - PICKER_BUTTON_SIZE - PICKER_MARGIN;
        int y = guiTop + ySize + PICKER_MARGIN;
        return minemoticon$clampToScreen(x, y);
    }

    @Unique
    private int[] minemoticon$clampToScreen(int x, int y) {
        int maxY = height - PICKER_BUTTON_SIZE - SCREEN_MARGIN;
        if (Loader.isModLoaded("NotEnoughItems")) {
            maxY -= NEI_FOOTER_HEIGHT + PICKER_MARGIN;
        }
        return new int[] { minemoticon$clamp(x, SCREEN_MARGIN, width - PICKER_BUTTON_SIZE - SCREEN_MARGIN),
            minemoticon$clamp(y, SCREEN_MARGIN, maxY) };
    }

    @Unique
    private int minemoticon$clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
