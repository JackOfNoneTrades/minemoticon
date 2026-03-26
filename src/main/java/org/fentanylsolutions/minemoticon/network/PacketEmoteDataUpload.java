package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

// Client -> Server: chunk of emote image data
public class PacketEmoteDataUpload implements IMessage {

    public String name;
    public String namespace;
    public String checksum;
    public String pua;
    public int chunkIndex;
    public int totalChunks;
    public byte[] data;

    public PacketEmoteDataUpload() {}

    public PacketEmoteDataUpload(String name, String namespace, String checksum, String pua, int chunkIndex,
        int totalChunks, byte[] data) {
        this.name = name;
        this.namespace = namespace;
        this.checksum = checksum;
        this.pua = pua;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        name = ByteBufUtils.readUTF8String(buf);
        namespace = ByteBufUtils.readUTF8String(buf);
        checksum = ByteBufUtils.readUTF8String(buf);
        pua = ByteBufUtils.readUTF8String(buf);
        chunkIndex = buf.readInt();
        totalChunks = buf.readInt();
        int len = buf.readInt();
        data = new byte[len];
        buf.readBytes(data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeUTF8String(buf, namespace);
        ByteBufUtils.writeUTF8String(buf, checksum);
        ByteBufUtils.writeUTF8String(buf, pua);
        buf.writeInt(chunkIndex);
        buf.writeInt(totalChunks);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    public static class Handler implements IMessageHandler<PacketEmoteDataUpload, IMessage> {

        @Override
        public IMessage onMessage(PacketEmoteDataUpload message, MessageContext ctx) {
            EmoteServerHandler.onEmoteDataUpload(
                ctx.getServerHandler().playerEntity,
                message.name,
                message.namespace,
                message.checksum,
                message.pua,
                message.chunkIndex,
                message.totalChunks,
                message.data);
            return null;
        }
    }
}
