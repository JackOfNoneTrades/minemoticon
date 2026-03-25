package org.fentanylsolutions.minemoticon;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.fentanylsolutions.fentlib.util.FileUtil;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class EmojiPackLoader {

    public static final File PACKS_FOLDER = new File("config/minemoticon/packs");
    public static final File SERVER_PACKS_FOLDER = new File("config/minemoticon/server_packs");
    private static final String[] SUPPORTED_EXTENSIONS = { ".png", ".jpg", ".jpeg", ".gif", ".qoi", ".webp" };

    public static class PackMeta {

        public final File folder;
        public final String displayName;
        public final String folderName;
        public final String iconEmojiName;

        public PackMeta(File folder, String displayName, String folderName, String iconEmojiName) {
            this.folder = folder;
            this.displayName = displayName;
            this.folderName = folderName;
            this.iconEmojiName = iconEmojiName;
        }
    }

    // Side-safe data class -- no client imports
    public static class PackEntry {

        public final String name;
        public final File imageFile;

        public PackEntry(String name, File imageFile) {
            this.name = name;
            this.imageFile = imageFile;
        }
    }

    public static class PackData {

        public final String displayName;
        public final String folderName;
        public final String iconEmojiName; // null = use first emoji
        public final List<PackEntry> entries;

        public PackData(String displayName, String folderName, String iconEmojiName, List<PackEntry> entries) {
            this.displayName = displayName;
            this.folderName = folderName;
            this.iconEmojiName = iconEmojiName;
            this.entries = entries;
        }
    }

    public static List<PackData> loadPacks() {
        return loadFromFolder(PACKS_FOLDER);
    }

    public static List<PackData> loadServerPacks() {
        return loadFromFolder(SERVER_PACKS_FOLDER);
    }

    private static List<PackData> loadFromFolder(File folder) {
        FileUtil.createFolderIfNotExists(folder);

        var result = new ArrayList<PackData>();
        File[] dirs = folder.listFiles(File::isDirectory);
        if (dirs == null) return result;

        Arrays.sort(dirs);

        for (File dir : dirs) {
            var pack = loadPackFolder(dir);
            if (pack != null && !pack.entries.isEmpty()) {
                result.add(pack);
            }
        }

        Minemoticon.debug("Loaded {} emoji packs from {}", result.size(), folder.getAbsolutePath());
        return result;
    }

    public static PackMeta readPackMeta(File dir) {
        String displayName = dir.getName();
        String iconName = null;

        File meta = new File(dir, "pack.meta");
        if (meta.isFile()) {
            try (var reader = new FileReader(meta)) {
                var obj = new JsonParser().parse(reader)
                    .getAsJsonObject();
                if (obj.has("name")) {
                    displayName = obj.get("name")
                        .getAsString();
                }
                if (obj.has("icon")) {
                    iconName = obj.get("icon")
                        .getAsString();
                }
            } catch (Exception e) {
                Minemoticon.LOG.warn("Failed to read pack.meta in {}", dir.getName(), e);
            }
        }

        return new PackMeta(dir, displayName, normalizeFolderName(dir), iconName);
    }

    public static void writePackMeta(File dir, String displayName, String iconName) throws IOException {
        FileUtil.createFolderIfNotExists(dir);

        JsonObject root = new JsonObject();
        if (displayName != null && !displayName.trim()
            .isEmpty()) {
            root.addProperty("name", displayName.trim());
        }
        if (iconName != null && !iconName.trim()
            .isEmpty()) {
            root.addProperty("icon", iconName.trim());
        }

        File metaFile = new File(dir, "pack.meta");
        if (root.entrySet()
            .isEmpty()) {
            java.nio.file.Files.deleteIfExists(metaFile.toPath());
            return;
        }

        try (var writer = new FileWriter(metaFile)) {
            new GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping()
                .create()
                .toJson(root, writer);
        }
    }

    public static List<File> listPackFolders(File rootFolder) {
        FileUtil.createFolderIfNotExists(rootFolder);

        var result = new ArrayList<File>();
        File[] dirs = rootFolder.listFiles(File::isDirectory);
        if (dirs == null) {
            return result;
        }

        Arrays.sort(dirs);
        result.addAll(Arrays.asList(dirs));
        return result;
    }

    public static boolean isSupportedEmojiFile(File file) {
        return file != null && file.isFile() && isSupportedEmojiFileName(file.getName());
    }

    public static boolean isSupportedEmojiFileName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String extension : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeFolderName(File dir) {
        return dir.getName()
            .toLowerCase()
            .replaceAll("[^a-z0-9_-]", "");
    }

    public static PackData loadPackFolder(File dir) {
        PackMeta meta = readPackMeta(dir);

        File[] files = dir.listFiles((d, name) -> isSupportedEmojiFileName(name));
        if (files == null || files.length == 0) return null;

        Arrays.sort(files);

        var entries = new ArrayList<PackEntry>();
        for (File f : files) {
            String emojiName = f.getName()
                .replaceFirst("\\.[^.]+$", "");
            entries.add(new PackEntry(emojiName, f));
        }

        Minemoticon.debug("Pack '{}': {} emojis, icon={}", meta.displayName, entries.size(), meta.iconEmojiName);
        return new PackData(meta.displayName, meta.folderName, meta.iconEmojiName, entries);
    }

    public static File getPacksFolder() {
        return PACKS_FOLDER;
    }
}
