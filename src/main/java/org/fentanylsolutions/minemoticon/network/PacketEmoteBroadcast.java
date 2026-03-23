package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

// Server -> Client: "emote :name: is available with this checksum"
public class PacketEmoteBroadcast implements IMessage {

    public String name;
    public String checksum;
    public String senderName;

    public PacketEmoteBroadcast() {}

    public PacketEmoteBroadcast(String name, String checksum, String senderName) {
        this.name = name;
        this.checksum = checksum;
        this.senderName = senderName;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        name = ByteBufUtils.readUTF8String(buf);
        checksum = ByteBufUtils.readUTF8String(buf);
        senderName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeUTF8String(buf, checksum);
        ByteBufUtils.writeUTF8String(buf, senderName);
    }

    public static class Handler implements IMessageHandler<PacketEmoteBroadcast, IMessage> {

        @Override
        public IMessage onMessage(PacketEmoteBroadcast message, MessageContext ctx) {
            EmoteClientHandler.onEmoteBroadcast(message.name, message.checksum, message.senderName);
            return null;
        }
    }
}
