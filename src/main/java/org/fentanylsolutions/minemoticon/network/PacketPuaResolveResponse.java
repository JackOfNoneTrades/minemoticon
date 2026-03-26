package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketPuaResolveResponse implements IMessage {

    public String pua;
    public boolean found;
    public String name;
    public String checksum;
    public String senderName;
    public String namespace;

    public PacketPuaResolveResponse() {}

    public PacketPuaResolveResponse(String pua) {
        this.pua = pua != null ? pua : "";
        this.found = false;
        this.name = "";
        this.checksum = "";
        this.senderName = "";
        this.namespace = "";
    }

    public PacketPuaResolveResponse(String name, String checksum, String senderName, String namespace, String pua) {
        this.pua = pua != null ? pua : "";
        this.found = true;
        this.name = name != null ? name : "";
        this.checksum = checksum != null ? checksum : "";
        this.senderName = senderName != null ? senderName : "";
        this.namespace = namespace != null ? namespace : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pua = ByteBufUtils.readUTF8String(buf);
        found = buf.readBoolean();
        name = ByteBufUtils.readUTF8String(buf);
        checksum = ByteBufUtils.readUTF8String(buf);
        senderName = ByteBufUtils.readUTF8String(buf);
        namespace = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, pua != null ? pua : "");
        buf.writeBoolean(found);
        ByteBufUtils.writeUTF8String(buf, name != null ? name : "");
        ByteBufUtils.writeUTF8String(buf, checksum != null ? checksum : "");
        ByteBufUtils.writeUTF8String(buf, senderName != null ? senderName : "");
        ByteBufUtils.writeUTF8String(buf, namespace != null ? namespace : "");
    }

    public static class Handler implements IMessageHandler<PacketPuaResolveResponse, IMessage> {

        @Override
        public IMessage onMessage(PacketPuaResolveResponse message, MessageContext ctx) {
            EmoteClientHandler.onPuaResolveResponse(
                message.pua,
                message.found,
                message.name,
                message.checksum,
                message.senderName,
                message.namespace);
            return null;
        }
    }
}
