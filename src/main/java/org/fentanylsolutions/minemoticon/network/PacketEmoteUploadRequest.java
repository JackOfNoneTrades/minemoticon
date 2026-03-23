package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

// Server -> Client: "I don't have :name:, please upload it"
public class PacketEmoteUploadRequest implements IMessage {

    public String name;

    public PacketEmoteUploadRequest() {}

    public PacketEmoteUploadRequest(String name) {
        this.name = name;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        name = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, name);
    }

    public static class Handler implements IMessageHandler<PacketEmoteUploadRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketEmoteUploadRequest message, MessageContext ctx) {
            EmoteClientHandler.onUploadRequest(message.name);
            return null;
        }
    }
}
