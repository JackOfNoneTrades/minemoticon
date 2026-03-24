package org.fentanylsolutions.minemoticon;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import org.fentanylsolutions.minemoticon.network.EmoteServerHandler;

public class CommandReloadEmojis extends CommandBase {

    @Override
    public String getCommandName() {
        return "reload_emojis";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/reload_emojis - Reload server emoji packs and resync with clients";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // OP
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        int count = EmoteServerHandler.reloadServerPacks();
        sender.addChatMessage(new ChatComponentText("Reloaded server emoji packs: " + count + " emojis synced"));
    }
}
