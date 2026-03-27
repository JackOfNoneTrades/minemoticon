package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

import org.fentanylsolutions.minemoticon.text.EmojiPua;

public class PacketPuaResolveRequest implements IMessage {

    public String pua;

    public PacketPuaResolveRequest() {}

    public PacketPuaResolveRequest(String pua) {
        this.pua = pua != null ? pua : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pua = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, pua != null ? pua : "");
    }

    public static class Handler implements IMessageHandler<PacketPuaResolveRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketPuaResolveRequest message, MessageContext ctx) {
            if (EmojiPua.isPuaToken(message.pua)) {
                EmoteServerHandler.onPuaResolveRequest(ctx.getServerHandler().playerEntity, message.pua);
            }
            return null;
        }
    }
}
