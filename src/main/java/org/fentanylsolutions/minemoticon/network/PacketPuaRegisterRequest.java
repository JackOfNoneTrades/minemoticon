package org.fentanylsolutions.minemoticon.network;

import org.fentanylsolutions.minemoticon.text.EmojiPua;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketPuaRegisterRequest implements IMessage {

    public String pua;
    public String name;
    public String namespace;
    public String checksum;

    public PacketPuaRegisterRequest() {}

    public PacketPuaRegisterRequest(String pua, String name, String namespace, String checksum) {
        this.pua = pua != null ? pua : "";
        this.name = name != null ? name : "";
        this.namespace = namespace != null ? namespace : "";
        this.checksum = checksum != null ? checksum : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pua = ByteBufUtils.readUTF8String(buf);
        name = ByteBufUtils.readUTF8String(buf);
        namespace = ByteBufUtils.readUTF8String(buf);
        checksum = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, pua != null ? pua : "");
        ByteBufUtils.writeUTF8String(buf, name != null ? name : "");
        ByteBufUtils.writeUTF8String(buf, namespace != null ? namespace : "");
        ByteBufUtils.writeUTF8String(buf, checksum != null ? checksum : "");
    }

    public static class Handler implements IMessageHandler<PacketPuaRegisterRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketPuaRegisterRequest message, MessageContext ctx) {
            if (EmojiPua.isPuaToken(message.pua)) {
                EmoteServerHandler.onPuaRegisterRequest(
                    ctx.getServerHandler().playerEntity,
                    message.pua,
                    message.name,
                    message.namespace,
                    message.checksum);
            }
            return null;
        }
    }
}
