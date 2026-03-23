package org.fentanylsolutions.minemoticon;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.fentanylsolutions.fentlib.util.FileUtil;
import org.fentanylsolutions.minemoticon.api.EmojiFromPack;

import com.google.gson.JsonParser;

public class EmojiPackLoader {

    public static final File PACKS_FOLDER = new File("config/minemoticon/packs");

    public static class PackData {

        public final String name;
        public final String iconEmojiName; // null = use first emoji
        public final List<EmojiFromPack> emojis;

        public PackData(String name, String iconEmojiName, List<EmojiFromPack> emojis) {
            this.name = name;
            this.iconEmojiName = iconEmojiName;
            this.emojis = emojis;
        }
    }

    public static List<PackData> loadPacks() {
        FileUtil.createFolderIfNotExists(PACKS_FOLDER);

        var result = new ArrayList<PackData>();
        File[] dirs = PACKS_FOLDER.listFiles(File::isDirectory);
        if (dirs == null) return result;

        Arrays.sort(dirs);

        for (File dir : dirs) {
            var pack = loadPackFolder(dir);
            if (pack != null && !pack.emojis.isEmpty()) {
                result.add(pack);
            }
        }

        Minemoticon.debug("Loaded {} emoji packs from {}", result.size(), PACKS_FOLDER.getAbsolutePath());
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
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        });
        if (files == null || files.length == 0) return null;

        Arrays.sort(files);

        var emojis = new ArrayList<EmojiFromPack>();
        String folderName = dir.getName()
            .toLowerCase()
            .replaceAll("[^a-z0-9_-]", "");
        for (File f : files) {
            String emojiName = f.getName()
                .replaceFirst("\\.[^.]+$", "");
            var emoji = new EmojiFromPack(emojiName, displayName, folderName, f);
            emojis.add(emoji);
        }

        Minemoticon.debug("Pack '{}': {} emojis, icon={}", displayName, emojis.size(), iconName);
        return new PackData(displayName, iconName, emojis);
    }

    public static File getPacksFolder() {
        return PACKS_FOLDER;
    }
}
