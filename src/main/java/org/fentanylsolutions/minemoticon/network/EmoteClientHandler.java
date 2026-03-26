package org.fentanylsolutions.minemoticon.network;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.EmojiConfig;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.ServerCapabilities;
import org.fentanylsolutions.minemoticon.api.Emoji;
import org.fentanylsolutions.minemoticon.api.EmojiFromPack;
import org.fentanylsolutions.minemoticon.api.EmojiFromRemote;
import org.fentanylsolutions.minemoticon.image.EmojiImageLoader;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.fentanylsolutions.minemoticon.text.EmojiPua;

public class EmoteClientHandler {

    public static final class TextReplacement {

        public final String text;
        public final int cursorPosition;

        public TextReplacement(String text, int cursorPosition) {
            this.text = text;
            this.cursorPosition = cursorPosition;
        }
    }

    private static final int CHUNK_SIZE = 30000;
    private static final File CACHE_DIR = new File("config/minemoticon/emote_cache");
    private static final int MAX_ACTIVE_DOWNLOADS = 2;
    private static final long DOWNLOAD_TIMEOUT_MS = 15000L;
    private static final int MAX_TRANSFER_CACHE_ENTRIES = 16;
    private static final long MAX_TRANSFER_CACHE_BYTES = 8L * 1024 * 1024;

    private static class PendingDownload {

        final String checksum;
        final int totalChunks;
        final byte[][] chunks;
        int received;

        PendingDownload(String checksum, int totalChunks) {
            this.checksum = checksum;
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
        }
    }

    private static class PendingAlias {

        final String name;
        final String category;
        final String namespace;
        final String pua;
        final boolean usable;
        final boolean isIcon;

        PendingAlias(String name, String category, String namespace, String pua, boolean usable, boolean isIcon) {
            this.name = name;
            this.category = category;
            this.namespace = namespace != null ? namespace : "";
            this.pua = pua != null ? pua : "";
            this.usable = usable;
            this.isIcon = isIcon;
        }
    }

    private static class PendingLocalPua {

        final char pua;
        final String name;
        final String namespace;
        final String checksum;
        final EmojiFromPack pack;

        PendingLocalPua(char pua, String name, String namespace, String checksum, EmojiFromPack pack) {
            this.pua = pua;
            this.name = name;
            this.namespace = namespace != null ? namespace : "";
            this.checksum = checksum;
            this.pack = pack;
        }
    }

    private static final class CachedTransferPayload {

        final String cacheKey;
        final long fileSize;
        final long lastModified;
        final byte[] data;
        final String checksum;

        CachedTransferPayload(String cacheKey, long fileSize, long lastModified, byte[] data, String checksum) {
            this.cacheKey = cacheKey;
            this.fileSize = fileSize;
            this.lastModified = lastModified;
            this.data = data;
            this.checksum = checksum;
        }
    }

    private static final Map<String, PendingDownload> pendingDownloads = new HashMap<>();
    private static final Map<String, List<PendingAlias>> pendingAliases = new HashMap<>();
    private static final Set<String> requestedDownloads = new HashSet<>();
    private static final ArrayDeque<String> queuedDownloads = new ArrayDeque<>();
    private static final Map<String, Long> activeDownloads = new HashMap<>();

    private static final Map<Character, PendingLocalPua> pendingLocalPuas = new HashMap<>();
    private static final Map<String, Character> sessionPuasByChecksum = new HashMap<>();
    private static final Map<String, Character> sessionPuasByPackKey = new HashMap<>();
    private static final ArrayDeque<Character> availablePuas = new ArrayDeque<>();
    private static final Set<Character> leasedPuas = new HashSet<>();
    private static final Set<Character> requestedPuaRegistrations = new HashSet<>();
    private static final Set<Character> requestedPuaResolves = new HashSet<>();
    private static final Set<Character> missingPuas = new HashSet<>();
    private static final LinkedHashMap<String, CachedTransferPayload> transferPayloadCache = new LinkedHashMap<String, CachedTransferPayload>(
        MAX_TRANSFER_CACHE_ENTRIES,
        0.75f,
        true);
    private static long transferPayloadCacheBytes = 0L;
    private static int suppressedInputDepth = 0;

    public static void beginInputSuppression() {
        suppressedInputDepth++;
    }

    public static void endInputSuppression() {
        if (suppressedInputDepth > 0) {
            suppressedInputDepth--;
        }
    }

    public static String getInsertTextForEmoji(Emoji emoji) {
        if (emoji == null) {
            return "";
        }
        if (emoji instanceof EmojiFromRemote remote && !remote.isUsable()) {
            return emoji.getShorterString();
        }
        if (emoji instanceof EmojiFromPack pack) {
            return getInsertTextForPack(pack);
        }
        return emoji.getInsertText();
    }

    public static TextReplacement substituteCompletedAlias(String text, int cursorPosition) {
        if (suppressedInputDepth > 0 || text == null || text.isEmpty() || cursorPosition <= 0) {
            return null;
        }

        int tokenEnd = cursorPosition;
        while (tokenEnd > 0 && Character.isWhitespace(text.charAt(tokenEnd - 1))) {
            tokenEnd--;
        }
        if (tokenEnd <= 0 || text.charAt(tokenEnd - 1) != ':') {
            return null;
        }

        int tokenStart = tokenEnd - 2;
        while (tokenStart >= 0 && !Character.isWhitespace(text.charAt(tokenStart))) {
            if (text.charAt(tokenStart) == ':') {
                break;
            }
            tokenStart--;
        }
        if (tokenStart < 0 || text.charAt(tokenStart) != ':' || tokenStart >= tokenEnd - 1) {
            return null;
        }

        String token = text.substring(tokenStart, tokenEnd);
        String replacement = replacementForToken(token);
        if (replacement == null || replacement.equals(token)) {
            return null;
        }

        int trailingOffset = cursorPosition - tokenEnd;
        String replaced = text.substring(0, tokenStart) + replacement + text.substring(tokenEnd);
        int newCursor = tokenStart + replacement.length() + trailingOffset;
        return new TextReplacement(replaced, Math.max(0, Math.min(newCursor, replaced.length())));
    }

    public static void onUploadRequest(String name, String namespace, String checksum, String pua) {
        PendingLocalPua pendingLocal = pua != null && pua.length() == 1 ? pendingLocalPuas.get(pua.charAt(0)) : null;
        EmojiFromPack pack = pendingLocal != null ? pendingLocal.pack : findPackEmoji(name, namespace);
        if (pack == null) {
            Minemoticon.debug("Upload requested for unknown pack emoji: {} ({})", name, namespace);
            return;
        }

        CachedTransferPayload transfer = getTransferPayload(pack);
        if (transfer == null) {
            Minemoticon.debug("Pack emoji {} could not be sanitized for upload", name);
            return;
        }
        if (checksum != null && !checksum.isEmpty() && !checksum.equals(transfer.checksum)) {
            Minemoticon
                .debug("Upload checksum mismatch for {}: expected {}, got {}", name, checksum, transfer.checksum);
            return;
        }

        byte[] data = transfer.data;
        int totalChunks = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, data.length);
            byte[] chunk = java.util.Arrays.copyOfRange(data, start, end);
            NetworkHandler.INSTANCE.sendToServer(
                new PacketEmoteDataUpload(name, pack.getPackFolder(), transfer.checksum, pua, i, totalChunks, chunk));
        }

        Minemoticon.debug(
            "Uploading emote {} from {} ({} bytes, {} chunks, pua={})",
            name,
            pack.getPackFolder(),
            data.length,
            totalChunks,
            pua);
    }

    public static void onEmoteBroadcast(String name, String checksum, String senderName, byte type, String category,
        String namespace, String pua, boolean isIcon) {
        if (type == PacketEmoteBroadcast.TYPE_CLIENT_EMOTE && !EmojiConfig.receiveClientEmotes) {
            return;
        }

        boolean usable = type == PacketEmoteBroadcast.TYPE_SERVER_PACK;
        String effectiveCategory = (category != null && !category.isEmpty()) ? category
            : (usable ? "Server" : "Remote");
        Minemoticon.debug(
            "Emote broadcast: {} from {} (checksum {}, type {}, category {}, pua={})",
            name,
            senderName,
            checksum,
            type,
            effectiveCategory,
            pua);

        if (pua != null && pua.length() == 1) {
            char puaChar = pua.charAt(0);
            requestedPuaResolves.remove(puaChar);
            missingPuas.remove(puaChar);
            requestedPuaRegistrations.remove(puaChar);
            PendingLocalPua pendingLocal = pendingLocalPuas.remove(puaChar);
            if (pendingLocal != null && checksum != null && !checksum.isEmpty()) {
                sessionPuasByChecksum.put(checksum, puaChar);
                sessionPuasByPackKey.put(":" + pendingLocal.namespace + "/" + pendingLocal.name + ":", puaChar);
                return;
            }
        }

        if (usable && isAliasRegistered(name, namespace, checksum)) {
            return;
        }

        File cached = findCachedEmoteFile(checksum);
        if (cached != null && cached.isFile()) {
            registerRemoteEmoji(name, checksum, cached, effectiveCategory, namespace, pua, usable, isIcon);
            return;
        }

        List<PendingAlias> aliases = pendingAliases.computeIfAbsent(checksum, ignored -> new ArrayList<>());
        boolean knownAlias = aliases.stream()
            .anyMatch(alias -> alias.name.equals(name) && alias.namespace.equals(namespace != null ? namespace : ""));
        if (!knownAlias) {
            aliases.add(new PendingAlias(name, effectiveCategory, namespace, pua, usable, isIcon));
        }

        queueDownloadRequest(checksum);
    }

    public static void onEmoteReject(String name, String reason, String pua) {
        if (pua != null && pua.length() == 1 && EmojiPua.isPua(pua.charAt(0))) {
            releaseRejectedPua(pua.charAt(0));
        }

        String emojiName = name != null && !name.isEmpty() ? ":" + name + ":" : "custom emoji";
        String detail = reason != null && !reason.isEmpty() ? reason : "Server rejected the emoji";
        showClientWarning(emojiName + " was rejected by the server: " + detail);
    }

    private static void showPackTransferWarning(String emojiName, String reason) {
        String key = emojiName != null && !emojiName.isEmpty() ? ":" + emojiName + ":" : "custom emoji";
        String detail = reason != null && !reason.isEmpty() ? reason : "This emoji cannot be transferred";
        showClientWarning(key + " cannot be sent to this server: " + detail);
    }

    private static void showClientWarning(String text) {
        ChatComponentText message = new ChatComponentText("\u26A0 " + text);
        message.getChatStyle()
            .setColor(EnumChatFormatting.RED);

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI != null) {
            mc.ingameGUI.getChatGUI()
                .printChatMessage(message);
        } else {
            Minemoticon.LOG.warn("{}", message.getUnformattedTextForChat());
        }
    }

    private static void releaseRejectedPua(char pua) {
        requestedPuaRegistrations.remove(pua);
        requestedPuaResolves.remove(pua);
        missingPuas.remove(pua);

        PendingLocalPua pending = pendingLocalPuas.remove(pua);
        if (pending != null) {
            Character checksumPua = sessionPuasByChecksum.get(pending.checksum);
            if (checksumPua != null && checksumPua == pua) {
                sessionPuasByChecksum.remove(pending.checksum);
            }

            String packKey = ":" + pending.namespace + "/" + pending.name + ":";
            Character packPua = sessionPuasByPackKey.get(packKey);
            if (packPua != null && packPua == pua) {
                sessionPuasByPackKey.remove(packKey);
            }
        }

        Emoji existing = ClientEmojiHandler.EMOJI_PUA_LOOKUP.get(pua);
        if (existing instanceof EmojiFromPack || existing instanceof EmojiFromRemote) {
            ClientEmojiHandler.EMOJI_PUA_LOOKUP.remove(pua);
        }

        if (!availablePuas.contains(pua)) {
            availablePuas.addFirst(pua);
        }
        leasedPuas.add(pua);
    }

    public static void onPuaLeaseGrant(String puas) {
        if (puas == null || puas.isEmpty()) {
            return;
        }
        for (int i = 0; i < puas.length(); i++) {
            char pua = puas.charAt(i);
            if (!EmojiPua.isPua(pua) || leasedPuas.contains(pua) || availablePuas.contains(pua)) {
                continue;
            }
            leasedPuas.add(pua);
            availablePuas.addLast(pua);
        }
    }

    public static void onPuaResolveResponse(String pua, boolean found, String name, String checksum, String senderName,
        String namespace) {
        if (pua == null || pua.length() != 1 || !EmojiPua.isPua(pua.charAt(0))) {
            return;
        }

        char puaChar = pua.charAt(0);
        requestedPuaResolves.remove(puaChar);
        if (!found) {
            missingPuas.add(puaChar);
            return;
        }

        missingPuas.remove(puaChar);
        onEmoteBroadcast(name, checksum, senderName, PacketEmoteBroadcast.TYPE_CLIENT_EMOTE, "", namespace, pua, false);
    }

    public static void onPuaObserved(char pua) {
        if (!ServerCapabilities.hasServerMod() || !EmojiPua.isPua(pua)) {
            return;
        }

        PendingLocalPua pendingLocal = pendingLocalPuas.get(pua);
        if (pendingLocal != null) {
            if (requestedPuaRegistrations.add(pua)) {
                NetworkHandler.INSTANCE.sendToServer(
                    new PacketPuaRegisterRequest(
                        String.valueOf(pua),
                        pendingLocal.name,
                        pendingLocal.namespace,
                        pendingLocal.checksum));
            }
            return;
        }

        if (ClientEmojiHandler.EMOJI_PUA_LOOKUP.containsKey(pua) || missingPuas.contains(pua)) {
            return;
        }

        if (requestedPuaResolves.add(pua)) {
            NetworkHandler.INSTANCE.sendToServer(new PacketPuaResolveRequest(String.valueOf(pua)));
        }
    }

    public static void onEmoteDataDownload(String checksum, int chunkIndex, int totalChunks, byte[] data) {
        PendingDownload pending = pendingDownloads
            .computeIfAbsent(checksum, ignored -> new PendingDownload(checksum, totalChunks));
        if (chunkIndex < 0 || chunkIndex >= pending.totalChunks || pending.chunks[chunkIndex] != null) {
            return;
        }

        pending.chunks[chunkIndex] = data;
        pending.received++;

        if (pending.received >= pending.totalChunks) {
            pendingDownloads.remove(checksum);
            processDownload(pending);
        }
    }

    public static void reset() {
        reset(false);
    }

    public static void resetAndDeleteCache() {
        reset(true);
    }

    private static void reset(boolean deleteCacheFiles) {
        pendingDownloads.clear();
        pendingAliases.clear();
        requestedDownloads.clear();
        queuedDownloads.clear();
        activeDownloads.clear();
        pendingLocalPuas.clear();
        sessionPuasByChecksum.clear();
        sessionPuasByPackKey.clear();
        availablePuas.clear();
        leasedPuas.clear();
        requestedPuaRegistrations.clear();
        requestedPuaResolves.clear();
        missingPuas.clear();
        clearTransferPayloadCache();
        clearRemoteEmojis(true);
        ClientEmojiHandler.EMOJI_PUA_LOOKUP.clear();
        if (deleteCacheFiles) {
            deleteRemoteCacheFiles();
        }
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        List<String> timedOut = new ArrayList<>();
        for (Map.Entry<String, Long> entry : activeDownloads.entrySet()) {
            if (now - entry.getValue() > DOWNLOAD_TIMEOUT_MS) {
                timedOut.add(entry.getKey());
            }
        }

        for (String checksum : timedOut) {
            activeDownloads.remove(checksum);
            requestedDownloads.remove(checksum);
            pendingDownloads.remove(checksum);
            pendingAliases.remove(checksum);
            Minemoticon.debug("Timed out remote emote download for {}", checksum);
        }

        pumpQueuedDownloads();
    }

    public static void clearRemoteEmojis() {
        clearRemoteEmojis(false);
    }

    public static void invalidateTransferPayloadCache() {
        clearTransferPayloadCache();
    }

    private static void deleteRemoteCacheFiles() {
        if (!CACHE_DIR.exists()) {
            return;
        }

        File[] files = CACHE_DIR.listFiles();
        if (files == null) {
            return;
        }

        int deleted = 0;
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            if (file.delete()) {
                deleted++;
            }
        }

        Minemoticon.debug("Deleted {} remote emote cache files", deleted);
    }

    private static void clearRemoteEmojis(boolean includeUsable) {
        for (Emoji emoji : new ArrayList<>(ClientEmojiHandler.EMOJI_LOOKUP.values())) {
            if (emoji instanceof EmojiFromRemote remote && (includeUsable || !remote.isUsable())) {
                remote.destroy();
            }
        }
        ClientEmojiHandler.EMOJI_LOOKUP.values()
            .removeIf(e -> e instanceof EmojiFromRemote remote && (includeUsable || !remote.isUsable()));
        ClientEmojiHandler.EMOJI_LIST
            .removeIf(e -> e instanceof EmojiFromRemote remote && (includeUsable || !remote.isUsable()));
        ClientEmojiHandler.EMOJI_MAP.values()
            .forEach(
                list -> list
                    .removeIf(e -> e instanceof EmojiFromRemote remote && (includeUsable || !remote.isUsable())));
        ClientEmojiHandler.EMOJI_MAP.values()
            .removeIf(List::isEmpty);
        ClientEmojiHandler.PACK_CATEGORY_ICONS.entrySet()
            .removeIf(
                entry -> entry.getValue() instanceof EmojiFromRemote remote && (includeUsable || !remote.isUsable()));
        ClientEmojiHandler.EMOJI_BY_SHORT_NAME.values()
            .forEach(
                list -> list
                    .removeIf(e -> e instanceof EmojiFromRemote remote && (includeUsable || !remote.isUsable())));
        ClientEmojiHandler.EMOJI_BY_SHORT_NAME.values()
            .removeIf(List::isEmpty);
        ClientEmojiHandler.EMOJI_PUA_LOOKUP.entrySet()
            .removeIf(
                entry -> entry.getValue() instanceof EmojiFromRemote remote && (includeUsable || !remote.isUsable()));
        requestedPuaResolves.clear();
        missingPuas.clear();
        ClientEmojiHandler.buildPickerData();
        Minemoticon.debug("Cleared {} remote emojis", includeUsable ? "all" : "client");
    }

    private static void processDownload(PendingDownload pending) {
        int totalSize = 0;
        for (byte[] chunk : pending.chunks) {
            if (chunk == null) {
                return;
            }
            totalSize += chunk.length;
        }

        byte[] raw = new byte[totalSize];
        int offset = 0;
        for (byte[] chunk : pending.chunks) {
            System.arraycopy(chunk, 0, raw, offset, chunk.length);
            offset += chunk.length;
        }

        String checksum = EmoteServerHandler.sha1(raw);
        CACHE_DIR.mkdirs();
        try {
            String extension = EmojiImageLoader.fileExtensionForPayload(raw);
            File cacheFile = new File(CACHE_DIR, checksum + extension);
            Files.write(cacheFile.toPath(), raw);
            activeDownloads.remove(pending.checksum);
            requestedDownloads.remove(pending.checksum);
            List<PendingAlias> aliases = pendingAliases.remove(pending.checksum);
            if (aliases != null) {
                for (PendingAlias alias : aliases) {
                    registerRemoteEmoji(
                        alias.name,
                        checksum,
                        cacheFile,
                        alias.category,
                        alias.namespace,
                        alias.pua,
                        alias.usable,
                        alias.isIcon);
                }
            }
        } catch (IOException e) {
            activeDownloads.remove(pending.checksum);
            requestedDownloads.remove(pending.checksum);
            Minemoticon.LOG.error("Failed to cache remote emote: {}", pending.checksum, e);
        } finally {
            pumpQueuedDownloads();
        }
    }

    public static File findCachedEmoteFile(String checksum) {
        return EmojiImageLoader.findCachedFile(CACHE_DIR, checksum);
    }

    public static void queueDownloadRequest(String checksum) {
        if (checksum == null || checksum.isEmpty() || !requestedDownloads.add(checksum)) {
            return;
        }

        queuedDownloads.addLast(checksum);
        pumpQueuedDownloads();
    }

    private static boolean isAliasRegistered(String name, String namespace, String checksum) {
        String key = namespace != null && !namespace.isEmpty() ? ":" + namespace + "/" + name + ":" : ":" + name + ":";
        Emoji existing = ClientEmojiHandler.EMOJI_LOOKUP.get(key);
        return existing instanceof EmojiFromRemote remote && remote.getChecksum()
            .equals(checksum);
    }

    private static void pumpQueuedDownloads() {
        while (activeDownloads.size() < MAX_ACTIVE_DOWNLOADS && !queuedDownloads.isEmpty()) {
            String checksum = queuedDownloads.removeFirst();
            if (findCachedEmoteFile(checksum) != null) {
                requestedDownloads.remove(checksum);
                continue;
            }

            activeDownloads.put(checksum, System.currentTimeMillis());
            NetworkHandler.INSTANCE.sendToServer(new PacketEmoteDownloadRequest(checksum));
        }
    }

    private static void registerRemoteEmoji(String name, String checksum, File cacheFile, String category,
        String namespace, String pua, boolean usable, boolean isIcon) {
        EmojiFromRemote emoji = new EmojiFromRemote(name, checksum, cacheFile, category, usable);
        boolean registerAlias = usable || pua == null || pua.isEmpty();
        if (registerAlias) {
            ClientEmojiHandler.EMOJI_LOOKUP.put(":" + name + ":", emoji);
            ClientEmojiHandler.registerShortName(":" + name + ":", emoji);
            if (namespace != null && !namespace.isEmpty()) {
                ClientEmojiHandler.EMOJI_LOOKUP.put(":" + namespace + "/" + name + ":", emoji);
                ClientEmojiHandler.registerShortName(":" + namespace + "/" + name + ":", emoji);
            }
        }
        if (pua != null && pua.length() == 1) {
            char puaChar = pua.charAt(0);
            Emoji existing = ClientEmojiHandler.EMOJI_PUA_LOOKUP.get(puaChar);
            if (existing == null || existing instanceof EmojiFromRemote) {
                ClientEmojiHandler.EMOJI_PUA_LOOKUP.put(puaChar, emoji);
            }
        }
        if (usable) {
            ClientEmojiHandler.EMOJI_MAP.computeIfAbsent(category, ignored -> new ArrayList<>())
                .add(emoji);
            ClientEmojiHandler.EMOJI_LIST.add(emoji);
            if (isIcon) {
                ClientEmojiHandler.PACK_CATEGORY_ICONS.put(category, emoji);
            } else {
                ClientEmojiHandler.PACK_CATEGORY_ICONS.putIfAbsent(category, emoji);
            }
            ClientEmojiHandler.buildPickerData();
        } else {
            EmojiRenderer.invalidateParseCache();
        }
    }

    private static String replacementForToken(String token) {
        Emoji emoji = ClientEmojiHandler.EMOJI_LOOKUP.get(token);
        if (emoji == null) {
            return null;
        }
        if (emoji instanceof EmojiFromRemote remote && !remote.isUsable()) {
            return null;
        }
        String insert = getInsertTextForEmoji(emoji);
        return insert == null || insert.isEmpty() ? null : insert;
    }

    private static String getInsertTextForPack(EmojiFromPack pack) {
        if (!ServerCapabilities.hasServerMod()) {
            return pack.getInsertText();
        }

        Character existingPackPua = sessionPuasByPackKey.get(pack.getNamespaced());
        if (existingPackPua != null && EmojiPua.isPua(existingPackPua)) {
            ClientEmojiHandler.EMOJI_PUA_LOOKUP.put(existingPackPua, pack);
            return String.valueOf(existingPackPua);
        }

        String checksum = checksumForPack(pack);
        if (checksum == null) {
            return pack.getInsertText();
        }

        Character existingPua = sessionPuasByChecksum.get(checksum);
        if (existingPua != null && EmojiPua.isPua(existingPua)) {
            sessionPuasByPackKey.put(pack.getNamespaced(), existingPua);
            ClientEmojiHandler.EMOJI_PUA_LOOKUP.put(existingPua, pack);
            return String.valueOf(existingPua);
        }

        Character leasedPua = availablePuas.pollFirst();
        if (leasedPua == null) {
            return pack.getInsertText();
        }

        leasedPuas.remove(leasedPua);
        PendingLocalPua pending = new PendingLocalPua(leasedPua, pack.name, pack.getPackFolder(), checksum, pack);
        pendingLocalPuas.put(leasedPua, pending);
        sessionPuasByChecksum.put(checksum, leasedPua);
        sessionPuasByPackKey.put(pack.getNamespaced(), leasedPua);
        ClientEmojiHandler.EMOJI_PUA_LOOKUP.put(leasedPua, pack);
        return String.valueOf(leasedPua);
    }

    private static EmojiFromPack findPackEmoji(String name, String namespace) {
        Emoji emoji = namespace != null && !namespace.isEmpty()
            ? ClientEmojiHandler.EMOJI_LOOKUP.get(":" + namespace + "/" + name + ":")
            : null;
        if (emoji == null) {
            emoji = ClientEmojiHandler.EMOJI_LOOKUP.get(":" + name + ":");
        }
        return emoji instanceof EmojiFromPack pack ? pack : null;
    }

    private static String checksumForPack(EmojiFromPack pack) {
        CachedTransferPayload transfer = getTransferPayload(pack);
        return transfer != null ? transfer.checksum : null;
    }

    private static CachedTransferPayload getTransferPayload(EmojiFromPack pack) {
        File imageFile = pack.getImageFile();
        String cacheKey = imageFile.getAbsolutePath();
        long fileSize = imageFile.length();
        long lastModified = imageFile.lastModified();

        CachedTransferPayload cached = getCachedTransferPayload(cacheKey, fileSize, lastModified);
        if (cached != null) {
            return cached;
        }

        try {
            byte[] data = sanitizePackForTransfer(pack);
            if (data == null) {
                return null;
            }

            CachedTransferPayload created = new CachedTransferPayload(
                cacheKey,
                fileSize,
                lastModified,
                data,
                EmoteServerHandler.sha1(data));
            cacheTransferPayload(created);
            return created;
        } catch (IOException e) {
            Minemoticon.LOG.error("Failed to read pack emoji: {}", pack.name, e);
            showPackTransferWarning(pack.name, "Failed to read the local file");
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
            showPackTransferWarning(pack.name, e.getMessage());
            return null;
        }
    }

    private static CachedTransferPayload getCachedTransferPayload(String cacheKey, long fileSize, long lastModified) {
        CachedTransferPayload cached = transferPayloadCache.get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (cached.fileSize == fileSize && cached.lastModified == lastModified) {
            return cached;
        }
        removeTransferPayloadCacheEntry(cacheKey, cached);
        return null;
    }

    private static void cacheTransferPayload(CachedTransferPayload entry) {
        CachedTransferPayload previous = transferPayloadCache.put(entry.cacheKey, entry);
        if (previous != null) {
            transferPayloadCacheBytes -= previous.data.length;
        }
        transferPayloadCacheBytes += entry.data.length;
        trimTransferPayloadCache();
    }

    private static void trimTransferPayloadCache() {
        Iterator<Map.Entry<String, CachedTransferPayload>> iterator = transferPayloadCache.entrySet()
            .iterator();
        while ((transferPayloadCache.size() > MAX_TRANSFER_CACHE_ENTRIES
            || transferPayloadCacheBytes > MAX_TRANSFER_CACHE_BYTES) && iterator.hasNext()) {
            CachedTransferPayload eldest = iterator.next()
                .getValue();
            transferPayloadCacheBytes -= eldest.data.length;
            iterator.remove();
        }
    }

    private static void removeTransferPayloadCacheEntry(String cacheKey, CachedTransferPayload entry) {
        transferPayloadCache.remove(cacheKey);
        transferPayloadCacheBytes -= entry.data.length;
    }

    private static void clearTransferPayloadCache() {
        transferPayloadCache.clear();
        transferPayloadCacheBytes = 0L;
    }
}
