package org.fentanylsolutions.minemoticon.network;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import org.fentanylsolutions.fentlib.util.QoiUtil;
import org.fentanylsolutions.fentlib.util.WebpUtil;
import org.fentanylsolutions.minemoticon.EmojiPackLoader;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.ServerConfig;

public class EmoteServerHandler {

    private static final int CHUNK_SIZE = 30000;
    private static final int MAX_DIMENSION = 128;
    private static final int MAX_EMOTES_PER_MESSAGE = 5;

    public static class CachedEmote {

        public final String checksum;
        public final byte[] data;
        public final String sender;
        public final byte type;
        public final String category;
        public final String namespace;
        public final boolean isIcon;

        public CachedEmote(String checksum, byte[] data, String sender, byte type, String category, String namespace,
            boolean isIcon) {
            this.checksum = checksum;
            this.data = data;
            this.sender = sender;
            this.type = type;
            this.category = category != null ? category : "";
            this.namespace = namespace != null ? namespace : "";
            this.isIcon = isIcon;
        }
    }

    private static class PendingUpload {

        final String name;
        final String senderName;
        final int totalChunks;
        final byte[][] chunks;
        int received;

        PendingUpload(String name, String senderName, int totalChunks) {
            this.name = name;
            this.senderName = senderName;
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
        }
    }

    private static final Map<String, CachedEmote> emoteCache = new ConcurrentHashMap<>();
    // Keyed by "playerName:emoteName"
    private static final Map<String, PendingUpload> pendingUploads = new ConcurrentHashMap<>();

    public static void onEmoteAnnounce(EntityPlayerMP player, String name, String checksum) {
        if (!ServerConfig.allowClientEmotes) {
            NetworkHandler.INSTANCE.sendTo(new PacketEmoteReject(name, "Custom emotes disabled"), player);
            return;
        }

        if (!isValidName(name)) {
            NetworkHandler.INSTANCE.sendTo(new PacketEmoteReject(name, "Invalid emote name"), player);
            return;
        }

        Minemoticon.debug("Emote announce from {}: {} ({})", player.getCommandSenderName(), name, checksum);

        // Check cache
        CachedEmote cached = emoteCache.get(name);
        if (cached != null && cached.checksum.equals(checksum)) {
            broadcastEmote(name, cached, player);
            return;
        }

        // Request upload
        NetworkHandler.INSTANCE.sendTo(new PacketEmoteUploadRequest(name), player);
    }

    public static void onEmoteDataUpload(EntityPlayerMP player, String name, int chunkIndex, int totalChunks,
        byte[] data) {
        if (!ServerConfig.allowClientEmotes) return;

        String key = player.getCommandSenderName() + ":" + name;
        var pending = pendingUploads
            .computeIfAbsent(key, k -> new PendingUpload(name, player.getCommandSenderName(), totalChunks));

        if (chunkIndex < 0 || chunkIndex >= pending.totalChunks) return;
        if (pending.chunks[chunkIndex] != null) return; // duplicate

        pending.chunks[chunkIndex] = data;
        pending.received++;

        Minemoticon.debug(
            "Emote upload chunk {}/{} for {} from {}",
            chunkIndex + 1,
            totalChunks,
            name,
            player.getCommandSenderName());

        if (pending.received >= pending.totalChunks) {
            pendingUploads.remove(key);
            processUpload(player, pending);
        }
    }

    public static void onDownloadRequest(EntityPlayerMP player, String name) {
        CachedEmote cached = emoteCache.get(name);
        if (cached == null) {
            Minemoticon.debug("Download request for unknown emote: {}", name);
            return;
        }

        sendChunked(player, name, cached.data);
    }

    public static void onPlayerDisconnect(EntityPlayerMP player) {
        String prefix = player.getCommandSenderName() + ":";
        pendingUploads.keySet()
            .removeIf(k -> k.startsWith(prefix));
    }

    private static void processUpload(EntityPlayerMP player, PendingUpload pending) {
        // Reassemble
        int totalSize = 0;
        for (byte[] chunk : pending.chunks) {
            if (chunk == null) return;
            totalSize += chunk.length;
        }

        if (totalSize > ServerConfig.maxClientEmoteSize) {
            NetworkHandler.INSTANCE.sendTo(
                new PacketEmoteReject(
                    pending.name,
                    "Emote too large (" + totalSize + " > " + ServerConfig.maxClientEmoteSize + ")"),
                player);
            return;
        }

        byte[] raw = new byte[totalSize];
        int offset = 0;
        for (byte[] chunk : pending.chunks) {
            System.arraycopy(chunk, 0, raw, offset, chunk.length);
            offset += chunk.length;
        }

        // Sanitize
        byte[] sanitized = sanitize(raw);
        if (sanitized == null) {
            NetworkHandler.INSTANCE.sendTo(new PacketEmoteReject(pending.name, "Invalid image data"), player);
            return;
        }

        String checksum = sha1(sanitized);
        var cached = new CachedEmote(
            checksum,
            sanitized,
            pending.senderName,
            PacketEmoteBroadcast.TYPE_CLIENT_EMOTE,
            "",
            "",
            false);
        emoteCache.put(pending.name, cached);

        Minemoticon.debug(
            "Cached emote '{}' from {} ({} bytes, checksum {})",
            pending.name,
            pending.senderName,
            sanitized.length,
            checksum);

        broadcastEmote(pending.name, cached, player);
    }

    // Try decoding image bytes as PNG/JPG, QOI, or WebP
    private static BufferedImage decodeImage(byte[] raw) {
        // Try standard ImageIO first (PNG, JPG, GIF, BMP)
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
            if (img != null) return img;
        } catch (Exception ignored) {}

        // Try QOI
        try {
            return QoiUtil.readImage(raw);
        } catch (Exception ignored) {}

        // Try WebP
        try {
            return WebpUtil.readImage(raw);
        } catch (Exception ignored) {}

        return null;
    }

    private static byte[] sanitize(byte[] raw) {
        return sanitize(raw, true);
    }

    private static byte[] sanitize(byte[] raw, boolean enforceMaxDimension) {
        try {
            BufferedImage img = decodeImage(raw);
            if (img == null) return null;

            if (enforceMaxDimension && (img.getWidth() > MAX_DIMENSION || img.getHeight() > MAX_DIMENSION)) {
                Minemoticon.debug(
                    "Emote rejected: dimensions {}x{} exceed max {}",
                    img.getWidth(),
                    img.getHeight(),
                    MAX_DIMENSION);
                return null;
            }

            // Re-encode as clean PNG (strips EXIF, ICC, malicious chunks)
            var clean = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            clean.getGraphics()
                .drawImage(img, 0, 0, null);

            var baos = new ByteArrayOutputStream();
            ImageIO.write(clean, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            Minemoticon.LOG.warn("Failed to sanitize emote image", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void broadcastEmote(String name, CachedEmote cached, EntityPlayerMP excludePlayer) {
        var packet = new PacketEmoteBroadcast(
            name,
            cached.checksum,
            cached.sender,
            cached.type,
            cached.category,
            cached.namespace,
            cached.isIcon);
        List<EntityPlayerMP> players = MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;
        for (var mp : players) {
            if (mp != excludePlayer) {
                NetworkHandler.INSTANCE.sendTo(packet, mp);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void broadcastToAll(String name, CachedEmote cached) {
        var packet = new PacketEmoteBroadcast(
            name,
            cached.checksum,
            cached.sender,
            cached.type,
            cached.category,
            cached.namespace,
            cached.isIcon);
        List<EntityPlayerMP> players = MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;
        for (var mp : players) {
            NetworkHandler.INSTANCE.sendTo(packet, mp);
        }
    }

    private static void sendChunked(EntityPlayerMP player, String name, byte[] data) {
        int totalChunks = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, data.length);
            byte[] chunk = Arrays.copyOfRange(data, start, end);
            NetworkHandler.INSTANCE.sendTo(new PacketEmoteDataDownload(name, i, totalChunks, chunk), player);
        }
    }

    private static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 32) return false;
        return name.matches("[a-zA-Z0-9_-]+");
    }

    // -- Server packs --

    public static void loadServerPacks() {
        var packs = EmojiPackLoader.loadServerPacks();
        int count = 0;
        for (var pack : packs) {
            String iconName = pack.iconEmojiName;
            if (iconName == null && !pack.entries.isEmpty()) {
                iconName = pack.entries.get(0).name;
            }

            for (var entry : pack.entries) {
                try {
                    byte[] raw = java.nio.file.Files.readAllBytes(entry.imageFile.toPath());
                    byte[] sanitized = sanitize(raw, false);
                    if (sanitized == null) {
                        Minemoticon.LOG.warn("Skipping invalid server pack emoji: {}", entry.name);
                        continue;
                    }
                    String checksum = sha1(sanitized);
                    boolean isIcon = entry.name.equals(iconName);
                    emoteCache.put(
                        entry.name,
                        new CachedEmote(
                            checksum,
                            sanitized,
                            "",
                            PacketEmoteBroadcast.TYPE_SERVER_PACK,
                            pack.displayName,
                            pack.folderName,
                            isIcon));
                    count++;
                } catch (Exception e) {
                    Minemoticon.LOG.error("Failed to load server pack emoji: {}", entry.name, e);
                }
            }
        }
        Minemoticon.LOG.info("Loaded {} server pack emojis", count);
    }

    @SuppressWarnings("unchecked")
    public static int reloadServerPacks() {
        // Clear old server pack entries
        emoteCache.values()
            .removeIf(c -> c.type == PacketEmoteBroadcast.TYPE_SERVER_PACK);

        // Reload
        loadServerPacks();

        // Notify all clients: clear + re-broadcast
        List<EntityPlayerMP> players = MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;
        var clearPacket = new PacketServerPackClear();
        for (var mp : players) {
            NetworkHandler.INSTANCE.sendTo(clearPacket, mp);
        }

        int count = 0;
        for (var entry : emoteCache.entrySet()) {
            if (entry.getValue().type == PacketEmoteBroadcast.TYPE_SERVER_PACK) {
                broadcastToAll(entry.getKey(), entry.getValue());
                count++;
            }
        }
        return count;
    }

    public static void sendServerPacksToPlayer(EntityPlayerMP player) {
        for (var entry : emoteCache.entrySet()) {
            if (entry.getValue().type == PacketEmoteBroadcast.TYPE_SERVER_PACK) {
                var cached = entry.getValue();
                var packet = new PacketEmoteBroadcast(
                    entry.getKey(),
                    cached.checksum,
                    "",
                    PacketEmoteBroadcast.TYPE_SERVER_PACK,
                    cached.category,
                    cached.namespace,
                    cached.isIcon);
                NetworkHandler.INSTANCE.sendTo(packet, player);
            }
        }
    }

    // -- One-off emojis (API for bridge plugins) --

    public static void registerOneOff(String name, byte[] imageData) {
        byte[] sanitized = sanitize(imageData);
        if (sanitized == null) {
            Minemoticon.LOG.warn("Failed to register one-off emote: {} (invalid image)", name);
            return;
        }
        String checksum = sha1(sanitized);
        var cached = new CachedEmote(checksum, sanitized, "", PacketEmoteBroadcast.TYPE_ONE_OFF, "", "", false);
        emoteCache.put(name, cached);
        broadcastToAll(name, cached);
        Minemoticon.debug("Registered one-off emote: {} (checksum {})", name, checksum);
    }

    public static String sha1(byte[] data) {
        try {
            var md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            var sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Arrays.hashCode(data));
        }
    }
}
