package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketRemoteEmoteClear implements IMessage {

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<PacketRemoteEmoteClear, IMessage> {

        @Override
        public IMessage onMessage(PacketRemoteEmoteClear message, MessageContext ctx) {
            EmoteClientHandler.clearRemoteEmojis();
            return null;
        }
    }
}
