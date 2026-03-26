package org.fentanylsolutions.minemoticon;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.network.rcon.RConConsoleSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;

import org.fentanylsolutions.minemoticon.network.EmoteServerHandler;

public class CommandSendOneOff extends CommandBase {

    @Override
    public String getCommandName() {
        return "send_oneoff";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/send_oneoff <image path> : Console-only test command that broadcasts a one-off emoji";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender instanceof MinecraftServer || sender instanceof RConConsoleSource;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String rawPath = joinPath(args);
        File imageFile = new File(rawPath);
        if (!imageFile.isAbsolute()) {
            imageFile = imageFile.getAbsoluteFile();
        }
        if (!imageFile.isFile()) {
            throw new CommandException("Image file not found: " + imageFile.getPath());
        }

        String alias = aliasFromFilename(imageFile.getName());
        try {
            byte[] data = Files.readAllBytes(imageFile.toPath());
            EmoteServerHandler.registerOneOff(alias, imageFile.getName(), data);
            MinecraftServer.getServer()
                .getConfigurationManager()
                .sendChatMsg(
                    new ChatComponentTranslation(
                        "chat.type.announcement",
                        new Object[] { sender.getCommandSenderName(), new ChatComponentText(":" + alias + ":") }));
            sender.addChatMessage(
                new ChatComponentText("Broadcast one-off :" + alias + ": from " + imageFile.getAbsolutePath()));
        } catch (IOException e) {
            throw new CommandException("Failed to read image: " + e.getMessage());
        }
    }

    private static String joinPath(String[] args) {
        String joined = String.join(" ", args)
            .trim();
        if (joined.length() >= 2) {
            char first = joined.charAt(0);
            char last = joined.charAt(joined.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return joined.substring(1, joined.length() - 1);
            }
        }
        return joined;
    }

    private static String aliasFromFilename(String filename) {
        String base = filename;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }

        String sanitized = base.replaceAll("[^a-zA-Z0-9_-]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+|_+$", "");
        if (sanitized.isEmpty()) {
            sanitized = "oneoff";
        }
        if (sanitized.length() > 32) {
            sanitized = sanitized.substring(0, 32);
        }
        return sanitized;
    }
}
