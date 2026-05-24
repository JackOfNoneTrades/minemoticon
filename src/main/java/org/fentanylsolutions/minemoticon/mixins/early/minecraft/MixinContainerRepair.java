package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ContainerRepair;

import org.fentanylsolutions.minemoticon.network.EmoteServerHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ContainerRepair.class)
public abstract class MixinContainerRepair {

    @Shadow
    @Final
    private EntityPlayer thePlayer;

    @ModifyVariable(method = "updateItemName", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String minemoticon$canonicalizeItemName(String name) {
        if (thePlayer == null || thePlayer.worldObj == null || thePlayer.worldObj.isRemote) {
            return name;
        }
        return EmoteServerHandler.canonicalizePlayerText(thePlayer.getCommandSenderName(), name);
    }
}
