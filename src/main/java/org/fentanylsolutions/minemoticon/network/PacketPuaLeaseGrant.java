package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketPuaLeaseGrant implements IMessage {

    public String puas;

    public PacketPuaLeaseGrant() {}

    public PacketPuaLeaseGrant(String puas) {
        this.puas = puas != null ? puas : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        puas = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, puas != null ? puas : "");
    }

    public static class Handler implements IMessageHandler<PacketPuaLeaseGrant, IMessage> {

        @Override
        public IMessage onMessage(PacketPuaLeaseGrant message, MessageContext ctx) {
            EmoteClientHandler.onPuaLeaseGrant(message.puas);
            return null;
        }
    }
}
