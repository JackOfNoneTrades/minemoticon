package org.fentanylsolutions.minemoticon.api;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.minemoticon.Minemoticon;

public class EmojiFromPack extends Emoji implements RenderableEmoji {

    private final File imageFile;
    private final String packFolder;
    private FileTexture texture;
    private ResourceLocation resourceLocation;

    public EmojiFromPack(String name, String category, String packFolder, File imageFile) {
        this.name = name;
        this.category = category;
        this.packFolder = packFolder;
        this.imageFile = imageFile;
        this.strings.add(":" + name + ":");
    }

    private void checkLoad() {
        if (texture != null) return;

        texture = new FileTexture(imageFile);
        resourceLocation = new ResourceLocation(Minemoticon.MODID, "textures/pack/" + packFolder + "/" + name);
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
    public boolean isLoaded() {
        return texture != null && texture.isUploaded();
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
