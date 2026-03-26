package org.fentanylsolutions.minemoticon.network;

import java.util.ArrayList;
import java.util.List;

import org.fentanylsolutions.minemoticon.server.PersistentEmoteStore.OwnerEmojiEntry;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketServerEmojiListResponse implements IMessage {

    public long usedBytes;
    public long quotaBytes;
    public int usedCount;
    public int quotaCount;
    public String statusMessage;
    public List<OwnerEmojiEntry> entries = new ArrayList<>();

    public PacketServerEmojiListResponse() {}

    public PacketServerEmojiListResponse(long usedBytes, long quotaBytes, int usedCount, int quotaCount,
        String statusMessage, List<OwnerEmojiEntry> entries) {
        this.usedBytes = usedBytes;
        this.quotaBytes = quotaBytes;
        this.usedCount = usedCount;
        this.quotaCount = quotaCount;
        this.statusMessage = statusMessage != null ? statusMessage : "";
        this.entries = entries != null ? new ArrayList<>(entries) : new ArrayList<>();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        usedBytes = buf.readLong();
        quotaBytes = buf.readLong();
        usedCount = buf.readInt();
        quotaCount = buf.readInt();
        statusMessage = ByteBufUtils.readUTF8String(buf);
        int count = buf.readInt();
        entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(
                new OwnerEmojiEntry(
                    ByteBufUtils.readUTF8String(buf),
                    ByteBufUtils.readUTF8String(buf),
                    ByteBufUtils.readUTF8String(buf),
                    buf.readInt()));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(usedBytes);
        buf.writeLong(quotaBytes);
        buf.writeInt(usedCount);
        buf.writeInt(quotaCount);
        ByteBufUtils.writeUTF8String(buf, statusMessage != null ? statusMessage : "");
        buf.writeInt(entries.size());
        for (OwnerEmojiEntry entry : entries) {
            ByteBufUtils.writeUTF8String(buf, entry.checksum);
            ByteBufUtils.writeUTF8String(buf, entry.name != null ? entry.name : "");
            ByteBufUtils.writeUTF8String(buf, entry.namespace != null ? entry.namespace : "");
            buf.writeInt(entry.sizeBytes);
        }
    }

    public static class Handler implements IMessageHandler<PacketServerEmojiListResponse, IMessage> {

        @Override
        public IMessage onMessage(PacketServerEmojiListResponse message, MessageContext ctx) {
            ServerEmojiManagerClient.onListResponse(message);
            return null;
        }
    }
}
