package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

// Server -> Client: "emote :name: is available with this checksum"
public class PacketEmoteBroadcast implements IMessage {

    public static final byte TYPE_CLIENT_EMOTE = 0;
    public static final byte TYPE_SERVER_PACK = 1;
    public static final byte TYPE_ONE_OFF = 2;

    public String name;
    public String checksum;
    public String senderName;
    public String category;
    public byte type;
    public boolean isIcon;

    public PacketEmoteBroadcast() {}

    public PacketEmoteBroadcast(String name, String checksum, String senderName, byte type, String category,
        boolean isIcon) {
        this.name = name;
        this.checksum = checksum;
        this.senderName = senderName;
        this.type = type;
        this.category = category != null ? category : "";
        this.isIcon = isIcon;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        name = ByteBufUtils.readUTF8String(buf);
        checksum = ByteBufUtils.readUTF8String(buf);
        senderName = ByteBufUtils.readUTF8String(buf);
        type = buf.readByte();
        category = ByteBufUtils.readUTF8String(buf);
        isIcon = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeUTF8String(buf, checksum);
        ByteBufUtils.writeUTF8String(buf, senderName);
        buf.writeByte(type);
        ByteBufUtils.writeUTF8String(buf, category);
        buf.writeBoolean(isIcon);
    }

    public static class Handler implements IMessageHandler<PacketEmoteBroadcast, IMessage> {

        @Override
        public IMessage onMessage(PacketEmoteBroadcast message, MessageContext ctx) {
            EmoteClientHandler.onEmoteBroadcast(
                message.name,
                message.checksum,
                message.senderName,
                message.type,
                message.category,
                message.isIcon);
            return null;
        }
    }
}
