package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketServerEmojiDelete implements IMessage {

    public String checksum;

    public PacketServerEmojiDelete() {}

    public PacketServerEmojiDelete(String checksum) {
        this.checksum = checksum;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        checksum = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, checksum);
    }

    public static class Handler implements IMessageHandler<PacketServerEmojiDelete, IMessage> {

        @Override
        public IMessage onMessage(PacketServerEmojiDelete message, MessageContext ctx) {
            boolean removed = EmoteServerHandler
                .deleteStoredEmojiForPlayer(ctx.getServerHandler().playerEntity, message.checksum);
            EmoteServerHandler.sendStoredEmojiListToPlayer(
                ctx.getServerHandler().playerEntity,
                removed ? "Deleted emoji" : "Emoji not found");
            return null;
        }
    }
}
