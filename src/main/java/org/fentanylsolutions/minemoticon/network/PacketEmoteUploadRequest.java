package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

// Server -> Client: "I don't have :name:, please upload it"
public class PacketEmoteUploadRequest implements IMessage {

    public String name;
    public String namespace;
    public String checksum;
    public String pua;

    public PacketEmoteUploadRequest() {}

    public PacketEmoteUploadRequest(String name, String namespace, String checksum, String pua) {
        this.name = name;
        this.namespace = namespace;
        this.checksum = checksum;
        this.pua = pua;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        name = ByteBufUtils.readUTF8String(buf);
        namespace = ByteBufUtils.readUTF8String(buf);
        checksum = ByteBufUtils.readUTF8String(buf);
        pua = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeUTF8String(buf, namespace);
        ByteBufUtils.writeUTF8String(buf, checksum);
        ByteBufUtils.writeUTF8String(buf, pua);
    }

    public static class Handler implements IMessageHandler<PacketEmoteUploadRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketEmoteUploadRequest message, MessageContext ctx) {
            EmoteClientHandler.onUploadRequest(message.name, message.namespace, message.checksum, message.pua);
            return null;
        }
    }
}
