package org.fentanylsolutions.minemoticon.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

// Server -> Client: chunk of emote image data
public class PacketEmoteDataDownload implements IMessage {

    public String checksum;
    public int chunkIndex;
    public int totalChunks;
    public byte[] data;

    public PacketEmoteDataDownload() {}

    public PacketEmoteDataDownload(String checksum, int chunkIndex, int totalChunks, byte[] data) {
        this.checksum = checksum;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        checksum = ByteBufUtils.readUTF8String(buf);
        chunkIndex = buf.readInt();
        totalChunks = buf.readInt();
        int len = buf.readInt();
        data = new byte[len];
        buf.readBytes(data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, checksum);
        buf.writeInt(chunkIndex);
        buf.writeInt(totalChunks);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    public static class Handler implements IMessageHandler<PacketEmoteDataDownload, IMessage> {

        @Override
        public IMessage onMessage(PacketEmoteDataDownload message, MessageContext ctx) {
            EmoteClientHandler
                .onEmoteDataDownload(message.checksum, message.chunkIndex, message.totalChunks, message.data);
            return null;
        }
    }
}
