package org.fentanylsolutions.minemoticon.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import org.fentanylsolutions.minemoticon.network.EmoteServerHandler;

public class CommandClearEmojis extends CommandBase {

    @Override
    public String getCommandName() {
        return "clear_emojis";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/clear_emojis [player] - Clear all persistent custom emojis, or only one player's";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            int removed = EmoteServerHandler.clearAllStoredEmojis();
            sender.addChatMessage(new ChatComponentText("Cleared " + removed + " stored custom emojis"));
            return;
        }

        String player = args[0];
        int removed = EmoteServerHandler.clearStoredEmojiForPlayer(player);
        sender.addChatMessage(new ChatComponentText("Cleared " + removed + " stored custom emojis for " + player));
    }
}
