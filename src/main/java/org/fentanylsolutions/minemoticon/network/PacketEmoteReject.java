package org.fentanylsolutions.minemoticon.network;

import org.fentanylsolutions.minemoticon.Minemoticon;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

// Server -> Client: emote rejected
public class PacketEmoteReject implements IMessage {

    public String name;
    public String reason;

    public PacketEmoteReject() {}

    public PacketEmoteReject(String name, String reason) {
        this.name = name;
        this.reason = reason;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        name = ByteBufUtils.readUTF8String(buf);
        reason = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeUTF8String(buf, reason);
    }

    public static class Handler implements IMessageHandler<PacketEmoteReject, IMessage> {

        @Override
        public IMessage onMessage(PacketEmoteReject message, MessageContext ctx) {
            Minemoticon.LOG.warn("Emote '{}' rejected by server: {}", message.name, message.reason);
            return null;
        }
    }
}
