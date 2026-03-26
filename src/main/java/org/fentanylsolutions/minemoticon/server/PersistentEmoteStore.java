package org.fentanylsolutions.minemoticon.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;

import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.ServerConfig;
import org.fentanylsolutions.minemoticon.text.EmojiPua;

public final class PersistentEmoteStore {

    public static final String DATA_NAME = "minemoticon_persistent_emotes";

    private PersistentEmoteStore() {}

    public static synchronized void bootstrap() {
        StoreData data = getData();
        if (data == null) {
            return;
        }

        File dir = getStorageDir();
        if (dir == null) {
            return;
        }

        dir.mkdirs();
        int removed = data.pruneMissingAssets(dir);
        boolean assigned = data.ensurePuaAssignments();
        if (assigned) {
            data.markDirty();
            Minemoticon.LOG.info("Assigned persistent PUA references for existing emoji assets");
        }
        if (removed > 0) {
            data.markDirty();
            Minemoticon.LOG.info("Pruned {} missing persistent emoji assets", removed);
        }
    }

    public static synchronized boolean hasAsset(String checksum) {
        StoreData data = getData();
        return data != null && data.assets.containsKey(checksum);
    }

    public static synchronized AssetMeta getAsset(String checksum) {
        StoreData data = getData();
        if (data == null) {
            return null;
        }
        AssetMeta meta = data.assets.get(checksum);
        return meta == null ? null : meta.copy();
    }

    public static synchronized QuotaCheckResult checkQuota(String owner, String checksum, int sizeBytes) {
        StoreData data = getData();
        if (data == null) {
            return QuotaCheckResult.denied(0, 0, 0, 0);
        }

        long used = data.getUsedBytes(owner);
        int usedCount = data.getUsedCount(owner);
        long limit = ServerConfig.maxStoredClientEmojiBytesPerUser;
        int countLimit = ServerConfig.maxStoredClientEmojiCountPerUser;
        long additional = data.ownerHasChecksum(owner, checksum) ? 0L : sizeBytes;
        int additionalCount = data.ownerHasChecksum(owner, checksum) ? 0 : 1;

        boolean bytesAllowed = limit <= 0 || used + additional <= limit;
        boolean countAllowed = countLimit <= 0 || usedCount + additionalCount <= countLimit;
        return bytesAllowed && countAllowed ? QuotaCheckResult.allowed(used, limit, usedCount, countLimit)
            : QuotaCheckResult.denied(used, limit, usedCount, countLimit);
    }

    public static synchronized StoreResult store(String owner, String name, String namespace, String checksum,
        byte[] sanitizedPayload, String extension, char preferredPua) throws IOException {
        StoreData data = getData();
        if (data == null) {
            throw new IOException("World save is not available");
        }

        AssetMeta existing = data.assets.get(checksum);
        int sizeBytes = sanitizedPayload.length;
        QuotaCheckResult quota = checkQuota(owner, checksum, sanitizedPayload.length);
        if (!quota.allowed) {
            return StoreResult.quotaExceeded(quota.usedBytes, quota.quotaBytes, quota.usedCount, quota.quotaCount);
        }

        File storageDir = getStorageDir();
        if (storageDir == null) {
            throw new IOException("Persistent emoji storage directory is not available");
        }
        storageDir.mkdirs();

        if (existing == null) {
            Files.write(resolveAssetFile(storageDir, checksum, extension).toPath(), sanitizedPayload);
            data.assets.put(checksum, new AssetMeta(checksum, extension, sizeBytes, preferredPua));
        } else {
            extension = existing.extension;
        }

        data.putOwnerEmoji(owner, checksum, name, namespace, preferredPua);
        data.markDirty();
        return StoreResult.stored(
            data.getUsedBytes(owner),
            ServerConfig.maxStoredClientEmojiBytesPerUser,
            data.getUsedCount(owner),
            ServerConfig.maxStoredClientEmojiCountPerUser,
            checksum,
            extension);
    }

    public static synchronized StoreResult claimExisting(String owner, String name, String namespace, String checksum,
        char preferredPua) {
        StoreData data = getData();
        if (data == null) {
            return StoreResult.quotaExceeded(0, 0, 0, 0);
        }

        AssetMeta asset = data.assets.get(checksum);
        if (asset == null) {
            return null;
        }

        QuotaCheckResult quota = checkQuota(owner, checksum, asset.sizeBytes);
        if (!quota.allowed) {
            return StoreResult.quotaExceeded(quota.usedBytes, quota.quotaBytes, quota.usedCount, quota.quotaCount);
        }

        data.putOwnerEmoji(owner, checksum, name, namespace, preferredPua);
        data.markDirty();
        return StoreResult.stored(
            data.getUsedBytes(owner),
            ServerConfig.maxStoredClientEmojiBytesPerUser,
            data.getUsedCount(owner),
            ServerConfig.maxStoredClientEmojiCountPerUser,
            checksum,
            asset.extension);
    }

    public static synchronized List<OwnerEmojiEntry> getEntriesForOwner(String owner) {
        StoreData data = getData();
        if (data == null) {
            return Collections.emptyList();
        }
        return data.getOwnerEntries(owner);
    }

    public static synchronized long getUsedBytes(String owner) {
        StoreData data = getData();
        return data == null ? 0L : data.getUsedBytes(owner);
    }

    public static synchronized long getQuotaBytes() {
        return ServerConfig.maxStoredClientEmojiBytesPerUser;
    }

    public static synchronized int getUsedCount(String owner) {
        StoreData data = getData();
        return data == null ? 0 : data.getUsedCount(owner);
    }

    public static synchronized int getQuotaCount() {
        return ServerConfig.maxStoredClientEmojiCountPerUser;
    }

    public static synchronized byte[] readPayload(String checksum) throws IOException {
        StoreData data = getData();
        if (data == null) {
            return null;
        }

        AssetMeta meta = data.assets.get(checksum);
        if (meta == null) {
            return null;
        }

        File storageDir = getStorageDir();
        if (storageDir == null) {
            return null;
        }

        File file = resolveAssetFile(storageDir, checksum, meta.extension);
        return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
    }

    public static synchronized int clearOwner(String owner) {
        StoreData data = getData();
        if (data == null) {
            return 0;
        }

        int removed = data.clearOwner(owner, getStorageDir());
        if (removed > 0) {
            data.markDirty();
        }
        return removed;
    }

    public static synchronized int clearAll() {
        StoreData data = getData();
        if (data == null) {
            return 0;
        }

        int removed = data.clearAll(getStorageDir());
        if (removed > 0) {
            data.markDirty();
        }
        return removed;
    }

    public static synchronized boolean removeOwnerEmoji(String owner, String checksum) {
        StoreData data = getData();
        if (data == null) {
            return false;
        }

        boolean removed = data.removeOwnerEmoji(owner, checksum, getStorageDir());
        if (removed) {
            data.markDirty();
        }
        return removed;
    }

    public static synchronized List<BroadcastAlias> getAllAliases() {
        StoreData data = getData();
        if (data == null) {
            return Collections.emptyList();
        }
        return data.getAllAliases();
    }

    public static synchronized Set<Character> getAssignedPuas() {
        StoreData data = getData();
        if (data == null) {
            return Collections.emptySet();
        }
        return data.getAssignedPuas();
    }

    public static synchronized BroadcastAlias getAliasForPua(char pua) {
        StoreData data = getData();
        if (data == null || !EmojiPua.isPua(pua)) {
            return null;
        }
        return data.getAliasForPua(pua);
    }

    public static synchronized BroadcastAlias getAliasForOwnerChecksum(String owner, String checksum) {
        StoreData data = getData();
        if (data == null || owner == null || owner.isEmpty() || checksum == null || checksum.isEmpty()) {
            return null;
        }
        return data.getAliasForOwnerChecksum(owner, checksum);
    }

    public static synchronized String canonicalizeText(String owner, String text) {
        StoreData data = getData();
        if (data == null || owner == null || owner.isEmpty() || text == null || text.isEmpty()) {
            return text;
        }
        return data.canonicalizeText(owner, text);
    }

    public static synchronized int getAliasCount() {
        StoreData data = getData();
        return data == null ? 0 : data.getAliasCount();
    }

    public static synchronized File findAssetFile(String checksum) {
        AssetMeta meta = getAsset(checksum);
        File dir = getStorageDir();
        if (meta == null || dir == null) {
            return null;
        }
        File file = resolveAssetFile(dir, checksum, meta.extension);
        return file.isFile() ? file : null;
    }

    private static StoreData getData() {
        WorldServer world = getWorld();
        if (world == null) {
            return null;
        }

        StoreData data = (StoreData) world.loadItemData(StoreData.class, DATA_NAME);
        if (data == null) {
            data = new StoreData(DATA_NAME);
            world.setItemData(DATA_NAME, data);
        }
        return data;
    }

    private static WorldServer getWorld() {
        MinecraftServer server = MinecraftServer.getServer();
        return server == null ? null : server.worldServerForDimension(0);
    }

    private static File getStorageDir() {
        WorldServer world = getWorld();
        if (world == null || world.getSaveHandler() == null) {
            return null;
        }
        return new File(
            new File(
                world.getSaveHandler()
                    .getWorldDirectory(),
                "data"),
            "minemoticon_emotes");
    }

    private static File resolveAssetFile(File dir, String checksum, String extension) {
        return new File(dir, checksum + extension);
    }

    public static final class AssetMeta {

        public final String checksum;
        public final String extension;
        public final int sizeBytes;
        public final char pua;

        public AssetMeta(String checksum, String extension, int sizeBytes, char pua) {
            this.checksum = checksum;
            this.extension = extension != null ? extension : ".png";
            this.sizeBytes = sizeBytes;
            this.pua = pua;
        }

        private AssetMeta copy() {
            return new AssetMeta(checksum, extension, sizeBytes, pua);
        }
    }

    public static final class OwnerEmojiEntry {

        public final String checksum;
        public final String name;
        public final String namespace;
        public final int sizeBytes;

        public OwnerEmojiEntry(String checksum, String name, String namespace, int sizeBytes) {
            this.checksum = checksum;
            this.name = name;
            this.namespace = namespace != null ? namespace : "";
            this.sizeBytes = sizeBytes;
        }
    }

    public static final class BroadcastAlias {

        public final String owner;
        public final String name;
        public final String namespace;
        public final String checksum;
        public final String pua;

        public BroadcastAlias(String owner, String name, String namespace, String checksum, String pua) {
            this.owner = owner;
            this.name = name;
            this.namespace = namespace != null ? namespace : "";
            this.checksum = checksum;
            this.pua = pua != null ? pua : "";
        }
    }

    public static final class QuotaCheckResult {

        public final boolean allowed;
        public final long usedBytes;
        public final long quotaBytes;
        public final int usedCount;
        public final int quotaCount;

        private QuotaCheckResult(boolean allowed, long usedBytes, long quotaBytes, int usedCount, int quotaCount) {
            this.allowed = allowed;
            this.usedBytes = usedBytes;
            this.quotaBytes = quotaBytes;
            this.usedCount = usedCount;
            this.quotaCount = quotaCount;
        }

        public static QuotaCheckResult allowed(long usedBytes, long quotaBytes, int usedCount, int quotaCount) {
            return new QuotaCheckResult(true, usedBytes, quotaBytes, usedCount, quotaCount);
        }

        public static QuotaCheckResult denied(long usedBytes, long quotaBytes, int usedCount, int quotaCount) {
            return new QuotaCheckResult(false, usedBytes, quotaBytes, usedCount, quotaCount);
        }
    }

    public static final class StoreResult {

        public final boolean stored;
        public final boolean quotaExceeded;
        public final long usedBytes;
        public final long quotaBytes;
        public final int usedCount;
        public final int quotaCount;
        public final String checksum;
        public final String extension;

        private StoreResult(boolean stored, boolean quotaExceeded, long usedBytes, long quotaBytes, int usedCount,
            int quotaCount, String checksum, String extension) {
            this.stored = stored;
            this.quotaExceeded = quotaExceeded;
            this.usedBytes = usedBytes;
            this.quotaBytes = quotaBytes;
            this.usedCount = usedCount;
            this.quotaCount = quotaCount;
            this.checksum = checksum;
            this.extension = extension;
        }

        public static StoreResult stored(long usedBytes, long quotaBytes, int usedCount, int quotaCount,
            String checksum, String extension) {
            return new StoreResult(true, false, usedBytes, quotaBytes, usedCount, quotaCount, checksum, extension);
        }

        public static StoreResult quotaExceeded(long usedBytes, long quotaBytes, int usedCount, int quotaCount) {
            return new StoreResult(false, true, usedBytes, quotaBytes, usedCount, quotaCount, "", "");
        }
    }

    public static class StoreData extends WorldSavedData {

        private final Map<String, AssetMeta> assets = new LinkedHashMap<>();
        private final Map<String, LinkedHashMap<String, OwnerAlias>> owners = new LinkedHashMap<>();

        public StoreData(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound compound) {
            assets.clear();
            owners.clear();

            NBTTagList assetList = compound.getTagList("Assets", 10);
            for (int i = 0; i < assetList.tagCount(); i++) {
                NBTTagCompound entry = assetList.getCompoundTagAt(i);
                String checksum = entry.getString("Checksum");
                if (checksum == null || checksum.isEmpty()) {
                    continue;
                }
                assets.put(
                    checksum,
                    new AssetMeta(
                        checksum,
                        entry.getString("Extension"),
                        entry.getInteger("SizeBytes"),
                        (char) entry.getInteger("Pua")));
            }

            NBTTagList ownerList = compound.getTagList("Owners", 10);
            for (int i = 0; i < ownerList.tagCount(); i++) {
                NBTTagCompound ownerTag = ownerList.getCompoundTagAt(i);
                String owner = ownerTag.getString("Owner");
                if (owner == null || owner.isEmpty()) {
                    continue;
                }

                LinkedHashMap<String, OwnerAlias> aliases = new LinkedHashMap<>();
                NBTTagList aliasList = ownerTag.getTagList("Entries", 10);
                for (int j = 0; j < aliasList.tagCount(); j++) {
                    NBTTagCompound aliasTag = aliasList.getCompoundTagAt(j);
                    String checksum = aliasTag.getString("Checksum");
                    if (!assets.containsKey(checksum)) {
                        continue;
                    }

                    char aliasPua = (char) aliasTag.getInteger("Pua");
                    if (!EmojiPua.isPua(aliasPua)) {
                        AssetMeta asset = assets.get(checksum);
                        aliasPua = asset != null ? asset.pua : '\0';
                    }

                    aliases.put(
                        checksum,
                        new OwnerAlias(
                            checksum,
                            aliasTag.getString("Name"),
                            aliasTag.getString("Namespace"),
                            aliasPua));
                }

                if (!aliases.isEmpty()) {
                    owners.put(owner, aliases);
                }
            }
        }

        @Override
        public void writeToNBT(NBTTagCompound compound) {
            NBTTagList assetList = new NBTTagList();
            for (AssetMeta asset : assets.values()) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setString("Checksum", asset.checksum);
                entry.setString("Extension", asset.extension);
                entry.setInteger("SizeBytes", asset.sizeBytes);
                entry.setInteger("Pua", asset.pua);
                assetList.appendTag(entry);
            }
            compound.setTag("Assets", assetList);

            NBTTagList ownerList = new NBTTagList();
            for (Map.Entry<String, LinkedHashMap<String, OwnerAlias>> ownerEntry : owners.entrySet()) {
                if (ownerEntry.getValue()
                    .isEmpty()) {
                    continue;
                }

                NBTTagCompound ownerTag = new NBTTagCompound();
                ownerTag.setString("Owner", ownerEntry.getKey());

                NBTTagList entryList = new NBTTagList();
                for (OwnerAlias alias : ownerEntry.getValue()
                    .values()) {
                    NBTTagCompound aliasTag = new NBTTagCompound();
                    aliasTag.setString("Checksum", alias.checksum);
                    aliasTag.setString("Name", alias.name);
                    aliasTag.setString("Namespace", alias.namespace);
                    aliasTag.setInteger("Pua", alias.pua);
                    entryList.appendTag(aliasTag);
                }
                ownerTag.setTag("Entries", entryList);
                ownerList.appendTag(ownerTag);
            }
            compound.setTag("Owners", ownerList);
        }

        private boolean ownerHasChecksum(String owner, String checksum) {
            LinkedHashMap<String, OwnerAlias> aliases = owners.get(owner);
            return aliases != null && aliases.containsKey(checksum);
        }

        private long getUsedBytes(String owner) {
            LinkedHashMap<String, OwnerAlias> aliases = owners.get(owner);
            if (aliases == null || aliases.isEmpty()) {
                return 0L;
            }

            long used = 0L;
            for (String checksum : aliases.keySet()) {
                AssetMeta asset = assets.get(checksum);
                if (asset != null) {
                    used += asset.sizeBytes;
                }
            }
            return used;
        }

        private int getUsedCount(String owner) {
            LinkedHashMap<String, OwnerAlias> aliases = owners.get(owner);
            return aliases == null ? 0 : aliases.size();
        }

        private void putOwnerEmoji(String owner, String checksum, String name, String namespace, char preferredPua) {
            LinkedHashMap<String, OwnerAlias> aliases = owners.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
            OwnerAlias existingAlias = aliases.get(checksum);
            AssetMeta asset = assets.get(checksum);
            char aliasPua = normalizeOwnerPua(
                existingAlias != null ? existingAlias.pua : (asset != null ? asset.pua : '\0'),
                preferredPua,
                owner,
                checksum);
            aliases.put(checksum, new OwnerAlias(checksum, name, namespace, aliasPua));
        }

        private List<OwnerEmojiEntry> getOwnerEntries(String owner) {
            LinkedHashMap<String, OwnerAlias> aliases = owners.get(owner);
            if (aliases == null || aliases.isEmpty()) {
                return Collections.emptyList();
            }

            List<OwnerEmojiEntry> entries = new ArrayList<>();
            for (OwnerAlias alias : aliases.values()) {
                AssetMeta asset = assets.get(alias.checksum);
                if (asset == null) {
                    continue;
                }
                entries.add(new OwnerEmojiEntry(alias.checksum, alias.name, alias.namespace, asset.sizeBytes));
            }
            entries.sort(
                Comparator
                    .comparing(
                        (OwnerEmojiEntry entry) -> entry.name == null ? "" : entry.name,
                        String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(entry -> entry.checksum));
            return entries;
        }

        private int clearOwner(String owner, File storageDir) {
            LinkedHashMap<String, OwnerAlias> aliases = owners.remove(owner);
            if (aliases == null || aliases.isEmpty()) {
                return 0;
            }

            cleanupUnownedAssets(storageDir);
            return aliases.size();
        }

        private int clearAll(File storageDir) {
            int removed = 0;
            for (LinkedHashMap<String, OwnerAlias> aliases : owners.values()) {
                removed += aliases.size();
            }
            owners.clear();
            assets.clear();
            if (storageDir != null && storageDir.isDirectory()) {
                File[] files = storageDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile()) {
                            file.delete();
                        }
                    }
                }
            }
            return removed;
        }

        private boolean removeOwnerEmoji(String owner, String checksum, File storageDir) {
            LinkedHashMap<String, OwnerAlias> aliases = owners.get(owner);
            if (aliases == null || aliases.remove(checksum) == null) {
                return false;
            }
            if (aliases.isEmpty()) {
                owners.remove(owner);
            }
            cleanupUnownedAssets(storageDir);
            return true;
        }

        private void cleanupUnownedAssets(File storageDir) {
            Set<String> ownedChecksums = new LinkedHashSet<>();
            for (Map<String, OwnerAlias> aliases : owners.values()) {
                ownedChecksums.addAll(aliases.keySet());
            }

            List<String> removed = new ArrayList<>();
            for (AssetMeta asset : assets.values()) {
                if (ownedChecksums.contains(asset.checksum)) {
                    continue;
                }
                removed.add(asset.checksum);
                if (storageDir != null) {
                    resolveAssetFile(storageDir, asset.checksum, asset.extension).delete();
                }
            }

            for (String checksum : removed) {
                assets.remove(checksum);
            }
        }

        private List<BroadcastAlias> getAllAliases() {
            List<BroadcastAlias> aliases = new ArrayList<>();
            for (Map.Entry<String, LinkedHashMap<String, OwnerAlias>> ownerEntry : owners.entrySet()) {
                for (OwnerAlias alias : ownerEntry.getValue()
                    .values()) {
                    AssetMeta asset = assets.get(alias.checksum);
                    if (asset == null) {
                        continue;
                    }
                    aliases.add(
                        new BroadcastAlias(
                            ownerEntry.getKey(),
                            alias.name,
                            alias.namespace,
                            alias.checksum,
                            EmojiPua.isPua(resolveAliasPua(alias, asset))
                                ? EmojiPua.toString(resolveAliasPua(alias, asset))
                                : ""));
                }
            }
            aliases.sort(
                Comparator.comparing((BroadcastAlias alias) -> alias.owner, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(alias -> alias.name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(alias -> alias.checksum));
            return aliases;
        }

        private int getAliasCount() {
            int count = 0;
            for (Map<String, OwnerAlias> aliases : owners.values()) {
                count += aliases.size();
            }
            return count;
        }

        private int pruneMissingAssets(File storageDir) {
            if (storageDir == null) {
                return 0;
            }

            int removed = 0;
            List<String> missing = new ArrayList<>();
            for (AssetMeta asset : assets.values()) {
                if (!resolveAssetFile(storageDir, asset.checksum, asset.extension).isFile()) {
                    missing.add(asset.checksum);
                }
            }

            if (missing.isEmpty()) {
                return 0;
            }

            for (String checksum : missing) {
                assets.remove(checksum);
                removed++;
            }

            for (LinkedHashMap<String, OwnerAlias> aliases : owners.values()) {
                aliases.keySet()
                    .removeIf(missing::contains);
            }
            owners.values()
                .removeIf(Map::isEmpty);
            return removed;
        }

        private boolean ensurePuaAssignments() {
            boolean changed = false;
            for (Map.Entry<String, LinkedHashMap<String, OwnerAlias>> ownerEntry : new ArrayList<>(owners.entrySet())) {
                LinkedHashMap<String, OwnerAlias> aliases = ownerEntry.getValue();
                for (Map.Entry<String, OwnerAlias> aliasEntry : new ArrayList<>(aliases.entrySet())) {
                    OwnerAlias alias = aliasEntry.getValue();
                    AssetMeta asset = assets.get(alias.checksum);
                    char fallback = asset != null ? asset.pua : '\0';
                    if (EmojiPua.isPua(alias.pua)) {
                        continue;
                    }
                    aliasEntry.setValue(
                        new OwnerAlias(
                            alias.checksum,
                            alias.name,
                            alias.namespace,
                            normalizeOwnerPua(fallback, '\0', ownerEntry.getKey(), alias.checksum)));
                    changed = true;
                }
            }

            for (Map.Entry<String, AssetMeta> entry : new ArrayList<>(assets.entrySet())) {
                AssetMeta asset = entry.getValue();
                if (EmojiPua.isPua(asset.pua)) {
                    continue;
                }
                char fallback = findAnyAliasPua(asset.checksum);
                if (!EmojiPua.isPua(fallback)) {
                    fallback = allocateNextPua();
                }
                entry.setValue(new AssetMeta(asset.checksum, asset.extension, asset.sizeBytes, fallback));
                changed = true;
            }
            return changed;
        }

        private Set<Character> getAssignedPuas() {
            Set<Character> assigned = new LinkedHashSet<>();
            for (LinkedHashMap<String, OwnerAlias> aliases : owners.values()) {
                for (OwnerAlias alias : aliases.values()) {
                    if (EmojiPua.isPua(alias.pua)) {
                        assigned.add(alias.pua);
                    }
                }
            }
            if (assigned.isEmpty()) {
                for (AssetMeta asset : assets.values()) {
                    if (EmojiPua.isPua(asset.pua)) {
                        assigned.add(asset.pua);
                    }
                }
            }
            return assigned;
        }

        private char allocateNextPua() {
            boolean[] used = new boolean[EmojiPua.COUNT];
            for (char pua : getAssignedPuas()) {
                used[EmojiPua.toIndex(pua)] = true;
            }
            for (int i = 0; i < used.length; i++) {
                if (!used[i]) {
                    return EmojiPua.fromIndex(i);
                }
            }
            throw new IllegalStateException("Ran out of private-use emoji references");
        }

        private String canonicalizeText(String owner, String text) {
            LinkedHashMap<String, OwnerAlias> aliases = owners.get(owner);
            if (aliases == null || aliases.isEmpty()) {
                return text;
            }

            StringBuilder rewritten = null;
            int lastCopied = 0;

            for (int i = 0; i < text.length();) {
                if (text.charAt(i) != ':') {
                    i++;
                    continue;
                }

                int end = text.indexOf(':', i + 1);
                if (end == -1) {
                    break;
                }

                String replacement = findReplacementForToken(aliases, text.substring(i + 1, end));
                if (replacement != null) {
                    if (rewritten == null) {
                        rewritten = new StringBuilder(text.length());
                    }
                    if (i > lastCopied) {
                        rewritten.append(text, lastCopied, i);
                    }
                    rewritten.append(replacement);
                    lastCopied = end + 1;
                    i = lastCopied;
                    continue;
                }

                i = end + 1;
            }

            if (rewritten == null) {
                return text;
            }
            if (lastCopied < text.length()) {
                rewritten.append(text, lastCopied, text.length());
            }
            return rewritten.toString();
        }

        private String findReplacementForToken(LinkedHashMap<String, OwnerAlias> aliases, String token) {
            if (token == null || token.isEmpty()) {
                return null;
            }

            if (token.indexOf('/') >= 0) {
                for (OwnerAlias alias : aliases.values()) {
                    if (token.equals(alias.namespace + "/" + alias.name)) {
                        AssetMeta asset = assets.get(alias.checksum);
                        char pua = resolveAliasPua(alias, asset);
                        return EmojiPua.isPua(pua) ? EmojiPua.toString(pua) : null;
                    }
                }
                return null;
            }

            OwnerAlias match = null;
            for (OwnerAlias alias : aliases.values()) {
                if (!token.equals(alias.name)) {
                    continue;
                }
                if (match != null) {
                    return null;
                }
                match = alias;
            }

            if (match == null) {
                return null;
            }

            AssetMeta asset = assets.get(match.checksum);
            char pua = resolveAliasPua(match, asset);
            return EmojiPua.isPua(pua) ? EmojiPua.toString(pua) : null;
        }

        private BroadcastAlias getAliasForPua(char pua) {
            for (Map.Entry<String, LinkedHashMap<String, OwnerAlias>> ownerEntry : owners.entrySet()) {
                for (OwnerAlias alias : ownerEntry.getValue()
                    .values()) {
                    AssetMeta asset = assets.get(alias.checksum);
                    char aliasPua = resolveAliasPua(alias, asset);
                    if (aliasPua == pua) {
                        return new BroadcastAlias(
                            ownerEntry.getKey(),
                            alias.name,
                            alias.namespace,
                            alias.checksum,
                            EmojiPua.toString(aliasPua));
                    }
                }
            }
            return null;
        }

        private BroadcastAlias getAliasForOwnerChecksum(String owner, String checksum) {
            LinkedHashMap<String, OwnerAlias> aliases = owners.get(owner);
            if (aliases == null) {
                return null;
            }
            OwnerAlias alias = aliases.get(checksum);
            if (alias == null) {
                return null;
            }
            AssetMeta asset = assets.get(checksum);
            char aliasPua = resolveAliasPua(alias, asset);
            return new BroadcastAlias(
                owner,
                alias.name,
                alias.namespace,
                alias.checksum,
                EmojiPua.isPua(aliasPua) ? EmojiPua.toString(aliasPua) : "");
        }

        private char normalizeOwnerPua(char fallbackPua, char preferredPua, String owner, String checksum) {
            if (EmojiPua.isPua(fallbackPua) && isPuaAvailableForOwner(fallbackPua, owner, checksum)) {
                return fallbackPua;
            }
            if (EmojiPua.isPua(preferredPua) && isPuaAvailableForOwner(preferredPua, owner, checksum)) {
                return preferredPua;
            }
            return allocateNextPua();
        }

        private boolean isPuaAvailableForOwner(char pua, String owner, String checksum) {
            for (Map.Entry<String, LinkedHashMap<String, OwnerAlias>> ownerEntry : owners.entrySet()) {
                for (OwnerAlias alias : ownerEntry.getValue()
                    .values()) {
                    if (alias.pua != pua) {
                        continue;
                    }
                    if (ownerEntry.getKey()
                        .equals(owner) && alias.checksum.equals(checksum)) {
                        return true;
                    }
                    return false;
                }
            }
            return true;
        }

        private char resolveAliasPua(OwnerAlias alias, AssetMeta asset) {
            if (alias != null && EmojiPua.isPua(alias.pua)) {
                return alias.pua;
            }
            return asset != null ? asset.pua : '\0';
        }

        private char findAnyAliasPua(String checksum) {
            for (LinkedHashMap<String, OwnerAlias> aliases : owners.values()) {
                OwnerAlias alias = aliases.get(checksum);
                if (alias != null && EmojiPua.isPua(alias.pua)) {
                    return alias.pua;
                }
            }
            return '\0';
        }
    }

    private static final class OwnerAlias {

        private final String checksum;
        private final String name;
        private final String namespace;
        private final char pua;

        private OwnerAlias(String checksum, String name, String namespace, char pua) {
            this.checksum = checksum;
            this.name = name != null ? name : "";
            this.namespace = namespace != null ? namespace : "";
            this.pua = pua;
        }
    }
}
