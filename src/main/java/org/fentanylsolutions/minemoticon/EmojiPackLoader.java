package org.fentanylsolutions.minemoticon;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.fentanylsolutions.fentlib.util.FileUtil;

import com.google.gson.JsonParser;

public class EmojiPackLoader {

    public static final File PACKS_FOLDER = new File("config/minemoticon/packs");
    public static final File SERVER_PACKS_FOLDER = new File("config/minemoticon/server_packs");

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

    private static PackData loadPackFolder(File dir) {
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

        File[] files = dir.listFiles((d, name) -> {
            var lower = name.toLowerCase();
            return lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".qoi")
                || lower.endsWith(".webp");
        });
        if (files == null || files.length == 0) return null;

        Arrays.sort(files);

        var entries = new ArrayList<PackEntry>();
        String folderName = dir.getName()
            .toLowerCase()
            .replaceAll("[^a-z0-9_-]", "");
        for (File f : files) {
            String emojiName = f.getName()
                .replaceFirst("\\.[^.]+$", "");
            entries.add(new PackEntry(emojiName, f));
        }

        Minemoticon.debug("Pack '{}': {} emojis, icon={}", displayName, entries.size(), iconName);
        return new PackData(displayName, folderName, iconName, entries);
    }

    public static File getPacksFolder() {
        return PACKS_FOLDER;
    }
}
