package org.fentanylsolutions.minemoticon.network;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import org.fentanylsolutions.minemoticon.EmojiPackLoader;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.ServerConfig;
import org.fentanylsolutions.minemoticon.image.EmojiImageLoader;
import org.fentanylsolutions.minemoticon.server.PersistentEmoteStore;
import org.fentanylsolutions.minemoticon.server.PersistentEmoteStore.OwnerEmojiEntry;
import org.fentanylsolutions.minemoticon.text.EmojiPua;

public class EmoteServerHandler {

    private static final int CHUNK_SIZE = 30000;
    private static final int MAX_DIMENSION = 128;
    private static final int MAX_QUEUED_DOWNLOADS_PER_PLAYER = 256;
    private static final int MAX_CHUNKS_PER_TICK_PER_PLAYER = 2;
    private static final int TARGET_FREE_PUAS_PER_PLAYER = 16;

    public static class CachedEmote {

        public final String name;
        public final String checksum;
        public final byte[] data;
        public final String sender;
        public final byte type;
        public final String category;
        public final String namespace;
        public final String pua;
        public final boolean isIcon;

        public CachedEmote(String name, String checksum, byte[] data, String sender, byte type, String category,
            String namespace, String pua, boolean isIcon) {
            this.name = name;
            this.checksum = checksum;
            this.data = data;
            this.sender = sender;
            this.type = type;
            this.category = category != null ? category : "";
            this.namespace = namespace != null ? namespace : "";
            this.pua = pua != null ? pua : "";
            this.isIcon = isIcon;
        }
    }

    private static class PendingUpload {

        final String name;
        final String namespace;
        final String checksum;
        final String senderName;
        final char pua;
        final int totalChunks;
        final byte[][] chunks;
        int received;

        PendingUpload(String name, String namespace, String checksum, String senderName, char pua, int totalChunks) {
            this.name = name;
            this.namespace = namespace != null ? namespace : "";
            this.checksum = checksum;
            this.senderName = senderName;
            this.pua = pua;
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
        }
    }

    private static class PendingDownload {

        final String checksum;
        byte[] data;
        int nextChunkIndex;
        int totalChunks;

        PendingDownload(String checksum) {
            this.checksum = checksum;
        }
    }

    private static class PlayerDownloadQueue {

        final ArrayDeque<String> queuedChecksums = new ArrayDeque<>();
        final Set<String> activeOrQueuedChecksums = new HashSet<>();
        PendingDownload activeDownload;

        boolean isEmpty() {
            return activeDownload == null && queuedChecksums.isEmpty();
        }
    }

    private static final Map<String, CachedEmote> emoteCache = new ConcurrentHashMap<>();
    private static final Map<String, PendingUpload> pendingUploads = new ConcurrentHashMap<>();
    private static final Map<String, PlayerDownloadQueue> downloadQueues = new ConcurrentHashMap<>();
    private static final Map<String, ArrayDeque<Character>> availablePuasByOwner = new ConcurrentHashMap<>();
    private static final Map<String, Set<Character>> leasedPuasByOwner = new ConcurrentHashMap<>();
    private static final Map<Character, String> leasedPuaOwners = new ConcurrentHashMap<>();

    public static void bootstrapPersistentStore() {
        PersistentEmoteStore.bootstrap();
        Minemoticon.LOG.info("Loaded {} persistent client emoji aliases", PersistentEmoteStore.getAliasCount());
    }

    public static void onPuaRegisterRequest(EntityPlayerMP player, char pua, String name, String namespace,
        String checksum) {
        if (!ServerConfig.allowClientEmotes) {
            NetworkHandler.INSTANCE
                .sendTo(new PacketEmoteReject(name, "Custom emotes disabled", String.valueOf(pua)), player);
            return;
        }

        if (!EmojiPua.isPua(pua)) {
            NetworkHandler.INSTANCE.sendTo(new PacketEmoteReject(name, "Invalid emoji reference"), player);
            return;
        }

        if (!isValidName(name) || (namespace != null && !namespace.isEmpty() && !isValidName(namespace))) {
            NetworkHandler.INSTANCE
                .sendTo(new PacketEmoteReject(name, "Invalid emote name", String.valueOf(pua)), player);
            return;
        }

        String sender = player.getCommandSenderName();
        if (!consumeLeasedPua(sender, pua)) {
            NetworkHandler.INSTANCE
                .sendTo(new PacketEmoteReject(name, "Emoji reference is not available", String.valueOf(pua)), player);
            return;
        }

        Minemoticon
            .debug("PUA register from {}: {} ({}, pua={}, namespace={})", sender, name, checksum, pua, namespace);

        PersistentEmoteStore.AssetMeta persistent = PersistentEmoteStore.getAsset(checksum);
        if (persistent != null) {
            var claim = PersistentEmoteStore.claimExisting(sender, name, namespace, checksum, pua);
            if (claim == null) {
                restoreConsumedPua(sender, pua);
                NetworkHandler.INSTANCE
                    .sendTo(new PacketEmoteReject(name, "Unknown emote asset", String.valueOf(pua)), player);
                return;
            }
            if (claim.quotaExceeded) {
                restoreConsumedPua(sender, pua);
                NetworkHandler.INSTANCE.sendTo(
                    new PacketEmoteReject(
                        name,
                        quotaMessage(claim.usedBytes, claim.quotaBytes, claim.usedCount, claim.quotaCount),
                        String.valueOf(pua)),
                    player);
                return;
            }

            finalizeConsumedPua(sender, pua);
            refillPuas(player);
            PersistentEmoteStore.BroadcastAlias alias = PersistentEmoteStore.getAliasForOwnerChecksum(sender, checksum);
            broadcastAlias(name, namespace, checksum, sender, null, alias != null ? alias.pua : String.valueOf(pua));
            return;
        }

        NetworkHandler.INSTANCE
            .sendTo(new PacketEmoteUploadRequest(name, namespace, checksum, String.valueOf(pua)), player);
    }

    public static void onPuaResolveRequest(EntityPlayerMP player, char pua) {
        if (!EmojiPua.isPua(pua)) {
            return;
        }

        PersistentEmoteStore.BroadcastAlias alias = PersistentEmoteStore.getAliasForPua(pua);
        if (alias == null) {
            NetworkHandler.INSTANCE.sendTo(new PacketPuaResolveResponse(String.valueOf(pua)), player);
            return;
        }

        NetworkHandler.INSTANCE.sendTo(
            new PacketPuaResolveResponse(alias.name, alias.checksum, alias.owner, alias.namespace, alias.pua),
            player);
    }

    public static void onEmoteDataUpload(EntityPlayerMP player, String name, String namespace, String checksum,
        String pua, int chunkIndex, int totalChunks, byte[] data) {
        if (!ServerConfig.allowClientEmotes) return;
        if (pua == null || pua.length() != 1 || !EmojiPua.isPua(pua.charAt(0))) {
            return;
        }

        String key = player.getCommandSenderName() + ":" + checksum;
        PendingUpload pending = pendingUploads.computeIfAbsent(
            key,
            ignored -> new PendingUpload(
                name,
                namespace,
                checksum,
                player.getCommandSenderName(),
                pua.charAt(0),
                totalChunks));

        if (chunkIndex < 0 || chunkIndex >= pending.totalChunks) return;
        if (pending.chunks[chunkIndex] != null) return;

        pending.chunks[chunkIndex] = data;
        pending.received++;

        if (pending.received >= pending.totalChunks) {
            pendingUploads.remove(key);
            processUpload(player, pending);
        }
    }

    public static void onDownloadRequest(EntityPlayerMP player, String checksum) {
        if (checksum == null || checksum.isEmpty()) {
            return;
        }
        if (!PersistentEmoteStore.hasAsset(checksum) && findCachedByChecksum(checksum) == null) {
            Minemoticon.debug("Download request for unknown checksum: {}", checksum);
            return;
        }

        PlayerDownloadQueue queue = downloadQueues
            .computeIfAbsent(player.getCommandSenderName(), ignored -> new PlayerDownloadQueue());

        if (queue.activeOrQueuedChecksums.contains(checksum)) {
            return;
        }

        if (queue.activeOrQueuedChecksums.size() >= MAX_QUEUED_DOWNLOADS_PER_PLAYER) {
            Minemoticon.debug(
                "Dropping emote download request for {} from {} because queue is full",
                checksum,
                player.getCommandSenderName());
            return;
        }

        queue.activeOrQueuedChecksums.add(checksum);
        queue.queuedChecksums.addLast(checksum);
    }

    public static void onPlayerDisconnect(EntityPlayerMP player) {
        String owner = player.getCommandSenderName();
        String prefix = owner + ":";
        List<Character> orphanedPuas = new ArrayList<>();
        for (PendingUpload pending : pendingUploads.values()) {
            if (owner.equals(pending.senderName)) {
                orphanedPuas.add(pending.pua);
            }
        }
        pendingUploads.keySet()
            .removeIf(key -> key.startsWith(prefix));
        downloadQueues.remove(player.getCommandSenderName());
        availablePuasByOwner.remove(owner);
        Set<Character> leased = leasedPuasByOwner.remove(owner);
        if (leased != null) {
            orphanedPuas.addAll(leased);
        }
        for (char pua : orphanedPuas) {
            leasedPuaOwners.remove(pua);
        }
    }

    public static void tick() {
        for (EntityPlayerMP player : getPlayers()) {
            pumpDownloadQueue(player);
        }
    }

    public static void sendPuaLeasesToPlayer(EntityPlayerMP player) {
        refillPuas(player);
    }

    public static void resyncPersistentCustomEmotes() {
        List<EntityPlayerMP> players = getPlayers();
        PacketRemoteEmoteClear clearPacket = new PacketRemoteEmoteClear();
        for (EntityPlayerMP player : players) {
            NetworkHandler.INSTANCE.sendTo(clearPacket, player);
        }
    }

    public static void sendStoredEmojiListToPlayer(EntityPlayerMP player, String statusMessage) {
        List<OwnerEmojiEntry> entries = PersistentEmoteStore.getEntriesForOwner(player.getCommandSenderName());
        NetworkHandler.INSTANCE.sendTo(
            new PacketServerEmojiListResponse(
                PersistentEmoteStore.getUsedBytes(player.getCommandSenderName()),
                PersistentEmoteStore.getQuotaBytes(),
                PersistentEmoteStore.getUsedCount(player.getCommandSenderName()),
                PersistentEmoteStore.getQuotaCount(),
                statusMessage,
                entries),
            player);
    }

    public static boolean deleteStoredEmojiForPlayer(EntityPlayerMP player, String checksum) {
        boolean removed = PersistentEmoteStore.removeOwnerEmoji(player.getCommandSenderName(), checksum);
        if (removed) {
            resyncPersistentCustomEmotes();
        }
        return removed;
    }

    public static int clearStoredEmojiForPlayer(String owner) {
        int removed = PersistentEmoteStore.clearOwner(owner);
        if (removed > 0) {
            resyncPersistentCustomEmotes();
        }
        return removed;
    }

    public static int clearAllStoredEmojis() {
        int removed = PersistentEmoteStore.clearAll();
        if (removed > 0) {
            resyncPersistentCustomEmotes();
        }
        return removed;
    }

    private static void processUpload(EntityPlayerMP player, PendingUpload pending) {
        int maxClientEmoteSize = ServerConfig.getEffectiveMaxClientEmoteSize();
        int totalSize = 0;
        for (byte[] chunk : pending.chunks) {
            if (chunk == null) return;
            totalSize += chunk.length;
        }

        if (totalSize > maxClientEmoteSize) {
            restoreConsumedPua(pending.senderName, pending.pua);
            NetworkHandler.INSTANCE.sendTo(
                new PacketEmoteReject(
                    pending.name,
                    "Emote too large (" + totalSize + " > " + maxClientEmoteSize + ")",
                    String.valueOf(pending.pua)),
                player);
            return;
        }

        byte[] raw = new byte[totalSize];
        int offset = 0;
        for (byte[] chunk : pending.chunks) {
            System.arraycopy(chunk, 0, raw, offset, chunk.length);
            offset += chunk.length;
        }

        byte[] sanitized = sanitize(raw, pending.name, true);
        if (sanitized == null) {
            restoreConsumedPua(pending.senderName, pending.pua);
            NetworkHandler.INSTANCE
                .sendTo(new PacketEmoteReject(pending.name, "Invalid image data", String.valueOf(pending.pua)), player);
            return;
        }

        if (sanitized.length > maxClientEmoteSize) {
            restoreConsumedPua(pending.senderName, pending.pua);
            NetworkHandler.INSTANCE.sendTo(
                new PacketEmoteReject(
                    pending.name,
                    "Emote too large after sanitizing (" + sanitized.length + " > " + maxClientEmoteSize + ")",
                    String.valueOf(pending.pua)),
                player);
            return;
        }

        String checksum = sha1(sanitized);
        if (pending.checksum != null && !pending.checksum.isEmpty() && !pending.checksum.equals(checksum)) {
            restoreConsumedPua(pending.senderName, pending.pua);
            NetworkHandler.INSTANCE
                .sendTo(new PacketEmoteReject(pending.name, "Checksum mismatch", String.valueOf(pending.pua)), player);
            return;
        }

        try {
            String extension = EmojiImageLoader.fileExtensionForPayload(sanitized);
            var result = PersistentEmoteStore.store(
                pending.senderName,
                pending.name,
                pending.namespace,
                checksum,
                sanitized,
                extension,
                pending.pua);
            if (result.quotaExceeded) {
                restoreConsumedPua(pending.senderName, pending.pua);
                NetworkHandler.INSTANCE.sendTo(
                    new PacketEmoteReject(
                        pending.name,
                        quotaMessage(result.usedBytes, result.quotaBytes, result.usedCount, result.quotaCount),
                        String.valueOf(pending.pua)),
                    player);
                return;
            }
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to persist uploaded emote {}", pending.name, e);
            restoreConsumedPua(pending.senderName, pending.pua);
            NetworkHandler.INSTANCE.sendTo(
                new PacketEmoteReject(pending.name, "Failed to persist emote", String.valueOf(pending.pua)),
                player);
            return;
        }

        Minemoticon.debug("Stored emote '{}' from {} (checksum {})", pending.name, pending.senderName, checksum);

        finalizeConsumedPua(pending.senderName, pending.pua);
        refillPuas(player);
        PersistentEmoteStore.BroadcastAlias alias = PersistentEmoteStore
            .getAliasForOwnerChecksum(pending.senderName, checksum);
        broadcastAlias(
            pending.name,
            pending.namespace,
            checksum,
            pending.senderName,
            null,
            alias != null ? alias.pua : String.valueOf(pending.pua));
    }

    private static byte[] sanitize(byte[] raw, String sourceName, boolean enforceMaxDimension) {
        try {
            return EmojiImageLoader.sanitizeForTransfer(raw, sourceName, enforceMaxDimension, MAX_DIMENSION)
                .getBytes();
        } catch (Exception e) {
            Minemoticon.LOG.warn("Failed to sanitize emote image", e);
            return null;
        }
    }

    private static void broadcastAlias(String name, String namespace, String checksum, String sender,
        CachedEmote cached, String pua) {
        PacketEmoteBroadcast packet = cached != null
            ? new PacketEmoteBroadcast(
                name,
                checksum,
                sender,
                cached.type,
                cached.category,
                namespace != null && !namespace.isEmpty() ? namespace : cached.namespace,
                cached.pua,
                cached.isIcon)
            : new PacketEmoteBroadcast(
                name,
                checksum,
                sender,
                PacketEmoteBroadcast.TYPE_CLIENT_EMOTE,
                "",
                namespace,
                pua,
                false);

        for (EntityPlayerMP player : getPlayers()) {
            NetworkHandler.INSTANCE.sendTo(packet, player);
        }
    }

    private static void broadcastToAll(String name, CachedEmote cached) {
        PacketEmoteBroadcast packet = new PacketEmoteBroadcast(
            name,
            cached.checksum,
            cached.sender,
            cached.type,
            cached.category,
            cached.namespace,
            cached.pua,
            cached.isIcon);
        for (EntityPlayerMP player : getPlayers()) {
            NetworkHandler.INSTANCE.sendTo(packet, player);
        }
    }

    private static List<EntityPlayerMP> getPlayers() {
        @SuppressWarnings("unchecked")
        List<EntityPlayerMP> players = MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;
        return players;
    }

    private static void pumpDownloadQueue(EntityPlayerMP player) {
        PlayerDownloadQueue queue = downloadQueues.get(player.getCommandSenderName());
        if (queue == null) {
            return;
        }

        int remainingChunks = MAX_CHUNKS_PER_TICK_PER_PLAYER;
        while (remainingChunks > 0) {
            if (queue.activeDownload == null) {
                String checksum = queue.queuedChecksums.pollFirst();
                if (checksum == null) {
                    break;
                }

                PendingDownload pending = loadPendingDownload(checksum);
                if (pending == null) {
                    queue.activeOrQueuedChecksums.remove(checksum);
                    continue;
                }
                queue.activeDownload = pending;
            }

            PendingDownload active = queue.activeDownload;
            int sent = sendNextChunks(player, active, remainingChunks);
            remainingChunks -= sent;

            if (active.nextChunkIndex >= active.totalChunks) {
                queue.activeOrQueuedChecksums.remove(active.checksum);
                queue.activeDownload = null;
                continue;
            }

            if (sent <= 0) {
                break;
            }
        }

        if (queue.isEmpty()) {
            downloadQueues.remove(player.getCommandSenderName());
        }
    }

    private static PendingDownload loadPendingDownload(String checksum) {
        byte[] data = null;
        try {
            data = PersistentEmoteStore.readPayload(checksum);
        } catch (IOException e) {
            Minemoticon.LOG.warn("Failed to read persistent emote payload {}", checksum, e);
        }

        if (data == null) {
            CachedEmote cached = findCachedByChecksum(checksum);
            if (cached != null) {
                data = cached.data;
            }
        }

        if (data == null) {
            return null;
        }

        PendingDownload pending = new PendingDownload(checksum);
        pending.data = data;
        pending.totalChunks = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        return pending;
    }

    private static int sendNextChunks(EntityPlayerMP player, PendingDownload pending, int budgetChunks) {
        int sent = 0;
        while (sent < budgetChunks && pending.nextChunkIndex < pending.totalChunks) {
            int start = pending.nextChunkIndex * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, pending.data.length);
            byte[] chunk = Arrays.copyOfRange(pending.data, start, end);
            NetworkHandler.INSTANCE.sendTo(
                new PacketEmoteDataDownload(pending.checksum, pending.nextChunkIndex, pending.totalChunks, chunk),
                player);
            pending.nextChunkIndex++;
            sent++;
        }
        return sent;
    }

    private static CachedEmote findCachedByChecksum(String checksum) {
        for (CachedEmote cached : emoteCache.values()) {
            if (cached.checksum.equals(checksum)) {
                return cached;
            }
        }
        return null;
    }

    private static String quotaMessage(long usedBytes, long quotaBytes, int usedCount, int quotaCount) {
        boolean hasByteQuota = quotaBytes > 0;
        boolean hasCountQuota = quotaCount > 0;
        if (!hasByteQuota && !hasCountQuota) {
            return "Emoji quota exceeded";
        }

        List<String> parts = new ArrayList<>();
        if (hasCountQuota) {
            parts.add(usedCount + " / " + quotaCount + " emoji");
        }
        if (hasByteQuota) {
            parts.add(formatBytes(usedBytes) + " / " + formatBytes(quotaBytes));
        }
        return "Emoji quota exceeded (" + String.join(", ", parts) + ")";
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 32) return false;
        return name.matches("[a-zA-Z0-9_-]+");
    }

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
                    byte[] sanitized = sanitize(raw, entry.imageFile.getName(), false);
                    if (sanitized == null) {
                        Minemoticon.LOG.warn("Skipping invalid server pack emoji: {}", entry.name);
                        continue;
                    }
                    String checksum = sha1(sanitized);
                    boolean isIcon = entry.name.equals(iconName);
                    emoteCache.put(
                        entry.name,
                        new CachedEmote(
                            entry.name,
                            checksum,
                            sanitized,
                            "",
                            PacketEmoteBroadcast.TYPE_SERVER_PACK,
                            pack.displayName,
                            pack.folderName,
                            "",
                            isIcon));
                    count++;
                } catch (Exception e) {
                    Minemoticon.LOG.error("Failed to load server pack emoji: {}", entry.name, e);
                }
            }
        }
        Minemoticon.LOG.info("Loaded {} server pack emojis", count);
    }

    public static int reloadServerPacks() {
        emoteCache.values()
            .removeIf(cached -> cached.type == PacketEmoteBroadcast.TYPE_SERVER_PACK);

        loadServerPacks();

        PacketServerPackClear clearPacket = new PacketServerPackClear();
        for (EntityPlayerMP player : getPlayers()) {
            NetworkHandler.INSTANCE.sendTo(clearPacket, player);
        }

        int count = 0;
        for (Map.Entry<String, CachedEmote> entry : emoteCache.entrySet()) {
            if (entry.getValue().type == PacketEmoteBroadcast.TYPE_SERVER_PACK) {
                broadcastToAll(entry.getKey(), entry.getValue());
                count++;
            }
        }
        return count;
    }

    public static void sendServerPacksToPlayer(EntityPlayerMP player) {
        for (Map.Entry<String, CachedEmote> entry : emoteCache.entrySet()) {
            if (entry.getValue().type == PacketEmoteBroadcast.TYPE_SERVER_PACK) {
                CachedEmote cached = entry.getValue();
                NetworkHandler.INSTANCE.sendTo(
                    new PacketEmoteBroadcast(
                        entry.getKey(),
                        cached.checksum,
                        "",
                        PacketEmoteBroadcast.TYPE_SERVER_PACK,
                        cached.category,
                        cached.namespace,
                        cached.pua,
                        cached.isIcon),
                    player);
            }
        }
    }

    public static void registerOneOff(String name, byte[] imageData) {
        registerOneOff(name, name, imageData);
    }

    public static void registerOneOff(String name, String sourceName, byte[] imageData) {
        byte[] sanitized = sanitize(imageData, sourceName, true);
        if (sanitized == null) {
            Minemoticon.LOG.warn("Failed to register one-off emote: {} (invalid image)", name);
            return;
        }
        String checksum = sha1(sanitized);
        CachedEmote cached = new CachedEmote(
            name,
            checksum,
            sanitized,
            "",
            PacketEmoteBroadcast.TYPE_ONE_OFF,
            "",
            "",
            "",
            false);
        emoteCache.put(name, cached);
        broadcastToAll(name, cached);
        Minemoticon.debug("Registered one-off emote: {} (checksum {})", name, checksum);
    }

    public static String sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(Arrays.hashCode(data));
        }
    }

    private static char allocateLeasedPua() {
        boolean[] used = new boolean[EmojiPua.COUNT];
        for (char pua : PersistentEmoteStore.getAssignedPuas()) {
            used[EmojiPua.toIndex(pua)] = true;
        }
        for (char pua : leasedPuaOwners.keySet()) {
            used[EmojiPua.toIndex(pua)] = true;
        }
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) {
                return EmojiPua.fromIndex(i);
            }
        }
        throw new IllegalStateException("Ran out of private-use emoji references");
    }

    private static void refillPuas(EntityPlayerMP player) {
        if (player == null) {
            return;
        }

        String owner = player.getCommandSenderName();
        ArrayDeque<Character> available = availablePuasByOwner.computeIfAbsent(owner, ignored -> new ArrayDeque<>());
        Set<Character> leased = leasedPuasByOwner
            .computeIfAbsent(owner, ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        StringBuilder granted = new StringBuilder();

        while (available.size() < TARGET_FREE_PUAS_PER_PLAYER) {
            char pua = allocateLeasedPua();
            available.addLast(pua);
            leased.add(pua);
            leasedPuaOwners.put(pua, owner);
            granted.append(pua);
        }

        if (granted.length() > 0) {
            NetworkHandler.INSTANCE.sendTo(new PacketPuaLeaseGrant(granted.toString()), player);
        }
    }

    private static boolean consumeLeasedPua(String owner, char pua) {
        ArrayDeque<Character> available = availablePuasByOwner.get(owner);
        if (available == null || !available.remove(pua)) {
            return false;
        }
        return leasedPuaOwners.containsKey(pua) && owner.equals(leasedPuaOwners.get(pua));
    }

    private static void restoreConsumedPua(String owner, char pua) {
        if (!EmojiPua.isPua(pua) || owner == null || !owner.equals(leasedPuaOwners.get(pua))) {
            return;
        }

        ArrayDeque<Character> available = availablePuasByOwner.computeIfAbsent(owner, ignored -> new ArrayDeque<>());
        if (!available.contains(pua)) {
            available.addFirst(pua);
        }
        leasedPuasByOwner.computeIfAbsent(owner, ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
            .add(pua);
    }

    private static void finalizeConsumedPua(String owner, char pua) {
        ArrayDeque<Character> available = availablePuasByOwner.get(owner);
        if (available != null) {
            available.remove(pua);
        }
        Set<Character> leased = leasedPuasByOwner.get(owner);
        if (leased != null) {
            leased.remove(pua);
            if (leased.isEmpty()) {
                leasedPuasByOwner.remove(owner);
            }
        }
        leasedPuaOwners.remove(pua);
    }

}
