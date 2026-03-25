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
import org.fentanylsolutions.minemoticon.image.EmojiImageLoader;

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
    private static final Map<String, Boolean> pendingUsable = new HashMap<>();
    private static final Map<String, String> pendingCategories = new HashMap<>();
    private static final Map<String, Boolean> pendingIsIcon = new HashMap<>();
    private static final Map<String, String> pendingNamespaces = new HashMap<>();

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
            byte[] data = sanitizePackForTransfer(pack);
            if (data == null) {
                Minemoticon.debug("Pack emoji {} could not be sanitized for upload", name);
                return;
            }
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
    public static void onEmoteBroadcast(String name, String checksum, String senderName, byte type, String category,
        String namespace, boolean isIcon) {
        if (type == PacketEmoteBroadcast.TYPE_CLIENT_EMOTE && !EmojiConfig.receiveClientEmotes) return;

        boolean usable = type == PacketEmoteBroadcast.TYPE_SERVER_PACK;
        String effectiveCategory = (category != null && !category.isEmpty()) ? category
            : (usable ? "Server" : "Remote");
        Minemoticon.debug(
            "Emote broadcast: {} from {} (checksum {}, type {}, category {})",
            name,
            senderName,
            checksum,
            type,
            effectiveCategory);

        // Store for when download completes
        pendingUsable.put(name, usable);
        pendingCategories.put(name, effectiveCategory);
        pendingIsIcon.put(name, isIcon);
        pendingNamespaces.put(name, namespace != null ? namespace : "");

        // Already registered with same checksum?
        Emoji existing = ClientEmojiHandler.EMOJI_LOOKUP.get(":" + name + ":");
        if (existing instanceof EmojiFromRemote remote && remote.getChecksum()
            .equals(checksum)) {
            return;
        }

        // Check disk cache
        File cached = EmojiImageLoader.findCachedFile(CACHE_DIR, checksum);
        if (cached != null && cached.isFile()) {
            registerRemoteEmoji(name, checksum, cached, effectiveCategory, namespace, usable, isIcon);
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
        pendingUsable.clear();
        pendingCategories.clear();
        pendingIsIcon.clear();
        pendingNamespaces.clear();
        clearRemoteEmojis();
        clearEmoteDiskCache();
    }

    private static void clearEmoteDiskCache() {
        if (!CACHE_DIR.isDirectory()) return;
        File[] files = CACHE_DIR.listFiles();
        if (files == null) return;
        int count = 0;
        for (File f : files) {
            if (f.isFile() && f.delete()) count++;
        }
        if (count > 0) {
            Minemoticon.debug("Cleared {} emote cache files", count);
        }
    }

    public static void clearRemoteEmojis() {
        for (var emoji : new java.util.ArrayList<>(ClientEmojiHandler.EMOJI_LOOKUP.values())) {
            if (emoji instanceof EmojiFromRemote remote) {
                remote.destroy();
            }
        }
        ClientEmojiHandler.EMOJI_LOOKUP.values()
            .removeIf(e -> e instanceof EmojiFromRemote);
        ClientEmojiHandler.EMOJI_LIST.removeIf(e -> e instanceof EmojiFromRemote);
        ClientEmojiHandler.EMOJI_MAP.values()
            .forEach(list -> list.removeIf(e -> e instanceof EmojiFromRemote));
        ClientEmojiHandler.EMOJI_MAP.values()
            .removeIf(java.util.List::isEmpty);
        ClientEmojiHandler.PACK_CATEGORY_ICONS.values()
            .removeIf(e -> e instanceof EmojiFromRemote);
        ClientEmojiHandler.EMOJI_BY_SHORT_NAME.values()
            .forEach(list -> list.removeIf(e -> e instanceof EmojiFromRemote));
        ClientEmojiHandler.EMOJI_BY_SHORT_NAME.values()
            .removeIf(java.util.List::isEmpty);
        ClientEmojiHandler.buildPickerData();
        Minemoticon.debug("Cleared remote emojis");
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
        try {
            String extension = EmojiImageLoader.fileExtensionForPayload(raw);
            File cacheFile = new File(CACHE_DIR, checksum + extension);
            Files.write(cacheFile.toPath(), raw);
            boolean usable = pendingUsable.getOrDefault(pending.name, false);
            String cat = pendingCategories.getOrDefault(pending.name, "Remote");
            boolean isIcon = pendingIsIcon.getOrDefault(pending.name, false);
            String ns = pendingNamespaces.getOrDefault(pending.name, "");
            pendingUsable.remove(pending.name);
            pendingCategories.remove(pending.name);
            pendingIsIcon.remove(pending.name);
            pendingNamespaces.remove(pending.name);
            Minemoticon.debug(
                "Cached remote emote {} ({} bytes, category={}, usable={})",
                pending.name,
                raw.length,
                cat,
                usable);
            registerRemoteEmoji(pending.name, checksum, cacheFile, cat, ns, usable, isIcon);
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to cache remote emote: {}", pending.name, e);
        }
    }

    private static void registerRemoteEmoji(String name, String checksum, File cacheFile, String category,
        String namespace, boolean usable, boolean isIcon) {
        var emoji = new EmojiFromRemote(name, checksum, cacheFile, category, usable);
        // Always register short key
        ClientEmojiHandler.EMOJI_LOOKUP.put(":" + name + ":", emoji);
        ClientEmojiHandler.registerShortName(":" + name + ":", emoji);
        // Register namespaced key if namespace is present (for wire format disambiguation)
        if (namespace != null && !namespace.isEmpty()) {
            ClientEmojiHandler.EMOJI_LOOKUP.put(":" + namespace + "/" + name + ":", emoji);
        }
        if (usable) {
            ClientEmojiHandler.EMOJI_MAP.computeIfAbsent(category, k -> new java.util.ArrayList<>())
                .add(emoji);
            ClientEmojiHandler.EMOJI_LIST.add(emoji);
            if (isIcon) {
                ClientEmojiHandler.PACK_CATEGORY_ICONS.put(category, emoji);
            } else {
                ClientEmojiHandler.PACK_CATEGORY_ICONS.putIfAbsent(category, emoji);
            }
            ClientEmojiHandler.buildPickerData();
        }
        Minemoticon
            .debug("Registered remote emote: {} (checksum {}, usable={}, icon={})", name, checksum, usable, isIcon);
    }

    private static String checksumForPack(EmojiFromPack pack) {
        try {
            byte[] data = sanitizePackForTransfer(pack);
            if (data == null) {
                return null;
            }
            return EmoteServerHandler.sha1(data);
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to checksum pack emoji: {}", pack.name, e);
            return null;
        }
    }

    private static byte[] sanitizePackForTransfer(EmojiFromPack pack) throws IOException {
        byte[] data = Files.readAllBytes(
            pack.getImageFile()
                .toPath());
        try {
            return EmojiImageLoader.sanitizeForTransfer(
                data,
                pack.getImageFile()
                    .getName(),
                true,
                128)
                .getBytes();
        } catch (IOException e) {
            Minemoticon.LOG.warn("Pack emoji {} is not transferable: {}", pack.name, e.getMessage());
            return null;
        }
    }
}
