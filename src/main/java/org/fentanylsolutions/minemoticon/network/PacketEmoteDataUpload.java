package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

// Client -> Server: chunk of emote image data
public class PacketEmoteDataUpload implements IMessage {

    public String name;
    public int chunkIndex;
    public int totalChunks;
    public byte[] data;

    public PacketEmoteDataUpload() {}

    public PacketEmoteDataUpload(String name, int chunkIndex, int totalChunks, byte[] data) {
        this.name = name;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        name = ByteBufUtils.readUTF8String(buf);
        chunkIndex = buf.readInt();
        totalChunks = buf.readInt();
        int len = buf.readInt();
        data = new byte[len];
        buf.readBytes(data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, name);
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
                message.chunkIndex,
                message.totalChunks,
                message.data);
            return null;
        }
    }
}
