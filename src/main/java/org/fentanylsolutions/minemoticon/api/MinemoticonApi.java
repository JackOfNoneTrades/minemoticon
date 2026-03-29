package org.fentanylsolutions.minemoticon.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;

import cpw.mods.fml.common.FMLCommonHandler;

public final class MinemoticonApi {

    public static final class ResourceEmojiRegistration {

        public final String prefix;
        public final String name;
        public final String category;
        public final ResourceLocation resourceLocation;
        public final boolean hideFromPicker;

        private ResourceEmojiRegistration(String prefix, String name, String category,
            ResourceLocation resourceLocation, boolean hideFromPicker) {
            this.prefix = prefix;
            this.name = name;
            this.category = category;
            this.resourceLocation = resourceLocation;
            this.hideFromPicker = hideFromPicker;
        }

        public String getToken() {
            return ":" + prefix + "/" + name + ":";
        }
    }

    private static final Map<String, ResourceEmojiRegistration> RESOURCE_EMOJIS = new LinkedHashMap<>();

    private MinemoticonApi() {}

    public static synchronized void registerResourceEmoji(String prefix, String name,
        ResourceLocation resourceLocation) {
        registerResourceEmoji(prefix, name, prefix, resourceLocation, false);
    }

    public static synchronized void registerResourceEmoji(String prefix, String name, ResourceLocation resourceLocation,
        boolean hideFromPicker) {
        registerResourceEmoji(prefix, name, prefix, resourceLocation, hideFromPicker);
    }

    public static synchronized void registerResourceEmoji(String prefix, String name, String category,
        ResourceLocation resourceLocation) {
        registerResourceEmoji(prefix, name, category, resourceLocation, false);
    }

    public static synchronized void registerResourceEmoji(String prefix, String name, String category,
        ResourceLocation resourceLocation, boolean hideFromPicker) {
        if (resourceLocation == null) {
            throw new IllegalArgumentException("resourceLocation cannot be null");
        }

        String normalizedPrefix = normalizeTokenPart(prefix, "prefix");
        String normalizedName = normalizeTokenPart(name, "name");
        String normalizedCategory = category == null || category.trim()
            .isEmpty() ? normalizedPrefix : category.trim();

        ResourceEmojiRegistration registration = new ResourceEmojiRegistration(
            normalizedPrefix,
            normalizedName,
            normalizedCategory,
            resourceLocation,
            hideFromPicker);
        RESOURCE_EMOJIS.put(registration.getToken(), registration);

        if (FMLCommonHandler.instance()
            .getSide()
            .isClient() && ClientEmojiHandler.isReady()) {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft != null) {
                minecraft.func_152344_a(ClientEmojiHandler::reloadResourceEmojis);
            }
        }
    }

    public static synchronized List<ResourceEmojiRegistration> getRegisteredResourceEmojis() {
        return new ArrayList<>(RESOURCE_EMOJIS.values());
    }

    private static String normalizeTokenPart(String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " cannot be null");
        }

        String normalized = value.trim()
            .toLowerCase(Locale.ROOT)
            .replace(' ', '_')
            .replace(':', '_')
            .replace('\\', '_')
            .replace('/', '_');
        while (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("_")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be empty");
        }
        return normalized;
    }
}
