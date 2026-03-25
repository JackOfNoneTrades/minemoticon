package org.fentanylsolutions.minemoticon.api;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.minemoticon.Minemoticon;

// Emoji received from another player or server. Texture loaded from local cache file.
public class EmojiFromRemote extends Emoji implements RenderableEmoji {

    private final File cacheFile;
    private final String checksum;
    private final boolean usable; // if true, appears in picker
    private FileTexture texture;
    private ResourceLocation resourceLocation;

    public EmojiFromRemote(String name, String checksum, File cacheFile, String category, boolean usable) {
        this.name = name;
        this.checksum = checksum;
        this.cacheFile = cacheFile;
        this.category = category;
        this.usable = usable;
        this.strings.add(":" + name + ":");
    }

    private void checkLoad() {
        if (texture != null) return;

        texture = new FileTexture(cacheFile);
        resourceLocation = new ResourceLocation(Minemoticon.MODID, "textures/remote/" + checksum);
        Minecraft.getMinecraft()
            .getTextureManager()
            .loadTexture(resourceLocation, texture);
    }

    @Override
    public ResourceLocation getResourceLocation() {
        checkLoad();
        return resourceLocation;
    }

    @Override
    public float[] getUV() {
        checkLoad();
        return texture != null ? texture.getCurrentUV() : null;
    }

    @Override
    public boolean isLoaded() {
        return texture != null && texture.isUploaded();
    }

    public String getChecksum() {
        return checksum;
    }

    public boolean isUsable() {
        return usable;
    }

    public void destroy() {
        if (resourceLocation != null) {
            Minecraft.getMinecraft()
                .getTextureManager()
                .deleteTexture(resourceLocation);
        }
        texture = null;
        resourceLocation = null;
    }
}
