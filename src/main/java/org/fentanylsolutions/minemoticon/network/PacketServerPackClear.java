package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

// Server -> Client: clear all server pack and remote emojis before resync
public class PacketServerPackClear implements IMessage {

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<PacketServerPackClear, IMessage> {

        @Override
        public IMessage onMessage(PacketServerPackClear message, MessageContext ctx) {
            EmoteClientHandler.clearRemoteEmojis();
            return null;
        }
    }
}
