package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

// Client -> Server: "I used :name: in chat, here's its checksum"
public class PacketChatEmoteAnnounce implements IMessage {

    public String name;
    public String namespace;
    public String checksum;

    public PacketChatEmoteAnnounce() {}

    public PacketChatEmoteAnnounce(String name, String namespace, String checksum) {
        this.name = name;
        this.namespace = namespace;
        this.checksum = checksum;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        name = ByteBufUtils.readUTF8String(buf);
        namespace = ByteBufUtils.readUTF8String(buf);
        checksum = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeUTF8String(buf, namespace);
        ByteBufUtils.writeUTF8String(buf, checksum);
    }

    public static class Handler implements IMessageHandler<PacketChatEmoteAnnounce, IMessage> {

        @Override
        public IMessage onMessage(PacketChatEmoteAnnounce message, MessageContext ctx) {
            return null;
        }
    }
}
