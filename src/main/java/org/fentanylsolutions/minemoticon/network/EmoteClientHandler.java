package org.fentanylsolutions.minemoticon.network;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.EmojiConfig;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.ServerCapabilities;
import org.fentanylsolutions.minemoticon.api.Emoji;
import org.fentanylsolutions.minemoticon.api.EmojiFromPack;
import org.fentanylsolutions.minemoticon.api.EmojiFromRemote;

public class EmoteClientHandler {

    private static final int CHUNK_SIZE = 30000;
    private static final File CACHE_DIR = new File("config/minemoticon/emote_cache");

    private static class PendingDownload {

        final String name;
        final int totalChunks;
        final byte[][] chunks;
        int received;

        PendingDownload(String name, int totalChunks) {
            this.name = name;
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
        }
    }

    private static final Map<String, PendingDownload> pendingDownloads = new HashMap<>();

    // Called when the client is about to send a chat message containing pack emojis
    public static void announceEmotesInMessage(String message) {
        if (!ServerCapabilities.hasServerMod()) return;

        int count = 0;
        for (int i = 0; i < message.length() && count < 5; i++) {
            if (message.charAt(i) != ':') continue;
            int end = message.indexOf(':', i + 1);
            if (end == -1) continue;

            String key = message.substring(i, end + 1);
            Emoji emoji = ClientEmojiHandler.EMOJI_LOOKUP.get(key);
            if (emoji instanceof EmojiFromPack pack) {
                String checksum = checksumForPack(pack);
                if (checksum != null) {
                    NetworkHandler.INSTANCE.sendToServer(new PacketChatEmoteAnnounce(pack.name, checksum));
                    count++;
                    Minemoticon.debug("Announcing emote {} (checksum {})", pack.name, checksum);
                }
            }
            i = end;
        }
    }

    // Server wants us to upload an emote
    public static void onUploadRequest(String name) {
        Emoji emoji = ClientEmojiHandler.EMOJI_LOOKUP.get(":" + name + ":");
        if (!(emoji instanceof EmojiFromPack pack)) {
            Minemoticon.debug("Upload requested for unknown pack emoji: {}", name);
            return;
        }

        try {
            byte[] data = Files.readAllBytes(
                pack.getImageFile()
                    .toPath());
            int totalChunks = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;

            for (int i = 0; i < totalChunks; i++) {
                int start = i * CHUNK_SIZE;
                int end = Math.min(start + CHUNK_SIZE, data.length);
                byte[] chunk = Arrays.copyOfRange(data, start, end);
                NetworkHandler.INSTANCE.sendToServer(new PacketEmoteDataUpload(name, i, totalChunks, chunk));
            }

            Minemoticon.debug("Uploading emote {} ({} bytes, {} chunks)", name, data.length, totalChunks);
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to read pack emoji file for upload: {}", name, e);
        }
    }

    // Server is telling us an emote is available
    public static void onEmoteBroadcast(String name, String checksum, String senderName) {
        if (!EmojiConfig.receiveClientEmotes) return;

        Minemoticon.debug("Emote broadcast: {} from {} (checksum {})", name, senderName, checksum);

        // Already registered?
        Emoji existing = ClientEmojiHandler.EMOJI_LOOKUP.get(":" + name + ":");
        if (existing instanceof EmojiFromRemote remote && remote.getChecksum()
            .equals(checksum)) {
            return;
        }

        // Check disk cache
        File cached = new File(CACHE_DIR, checksum + ".png");
        if (cached.isFile()) {
            registerRemoteEmoji(name, checksum, cached);
            return;
        }

        // Request download
        NetworkHandler.INSTANCE.sendToServer(new PacketEmoteDownloadRequest(name));
    }

    // Receiving chunked image data from server
    public static void onEmoteDataDownload(String name, int chunkIndex, int totalChunks, byte[] data) {
        var pending = pendingDownloads.computeIfAbsent(name, k -> new PendingDownload(name, totalChunks));

        if (chunkIndex < 0 || chunkIndex >= pending.totalChunks) return;
        if (pending.chunks[chunkIndex] != null) return;

        pending.chunks[chunkIndex] = data;
        pending.received++;

        if (pending.received >= pending.totalChunks) {
            pendingDownloads.remove(name);
            processDownload(pending);
        }
    }

    public static void reset() {
        pendingDownloads.clear();
    }

    private static void processDownload(PendingDownload pending) {
        int totalSize = 0;
        for (byte[] chunk : pending.chunks) {
            if (chunk == null) return;
            totalSize += chunk.length;
        }

        byte[] raw = new byte[totalSize];
        int offset = 0;
        for (byte[] chunk : pending.chunks) {
            System.arraycopy(chunk, 0, raw, offset, chunk.length);
            offset += chunk.length;
        }

        String checksum = EmoteServerHandler.sha1(raw);

        // Save to cache
        CACHE_DIR.mkdirs();
        File cacheFile = new File(CACHE_DIR, checksum + ".png");
        try {
            Files.write(cacheFile.toPath(), raw);
            Minemoticon.debug("Cached remote emote {} ({} bytes)", pending.name, raw.length);
            registerRemoteEmoji(pending.name, checksum, cacheFile);
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to cache remote emote: {}", pending.name, e);
        }
    }

    private static void registerRemoteEmoji(String name, String checksum, File cacheFile) {
        var emoji = new EmojiFromRemote(name, checksum, cacheFile);
        ClientEmojiHandler.EMOJI_LOOKUP.put(":" + name + ":", emoji);
        Minemoticon.debug("Registered remote emote: {} (checksum {})", name, checksum);
    }

    private static String checksumForPack(EmojiFromPack pack) {
        try {
            byte[] data = Files.readAllBytes(
                pack.getImageFile()
                    .toPath());
            return EmoteServerHandler.sha1(data);
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to checksum pack emoji: {}", pack.name, e);
            return null;
        }
    }
}
