package org.fentanylsolutions.minemoticon.network;

import io.netty.buffer.ByteBuf;

final class EmoteTransferLimits {

    static final int CHUNK_SIZE_BYTES = 30_000;
    static final int MAX_CHUNKS = 4096;
    static final int MAX_SERVER_EMOJI_LIST_ENTRIES = 10_000;

    private EmoteTransferLimits() {}

    static boolean isValidChecksum(String checksum) {
        if (checksum == null || checksum.length() != 40) {
            return false;
        }
        for (int i = 0; i < checksum.length(); i++) {
            char c = checksum.charAt(i);
            boolean hex = c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    static boolean isValidChunkShape(int chunkIndex, int totalChunks) {
        return totalChunks > 0 && totalChunks <= MAX_CHUNKS && chunkIndex >= 0 && chunkIndex < totalChunks;
    }

    static byte[] readChunk(ByteBuf buf) {
        int len = buf.readInt();
        if (len < 0 || len > CHUNK_SIZE_BYTES || len > buf.readableBytes()) {
            throw new IllegalArgumentException("Invalid emote chunk length: " + len);
        }

        byte[] data = new byte[len];
        buf.readBytes(data);
        return data;
    }

    static void writeChunk(ByteBuf buf, byte[] data) {
        byte[] safeData = data != null ? data : new byte[0];
        if (safeData.length > CHUNK_SIZE_BYTES) {
            throw new IllegalArgumentException("Emote chunk length exceeds " + CHUNK_SIZE_BYTES + " bytes");
        }
        buf.writeInt(safeData.length);
        buf.writeBytes(safeData);
    }

    static int readBoundedCount(ByteBuf buf, int max, String fieldName) {
        int count = buf.readInt();
        if (count < 0 || count > max) {
            throw new IllegalArgumentException("Invalid " + fieldName + " count: " + count);
        }
        return count;
    }
}
