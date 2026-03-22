package org.fentanylsolutions.minemoticon.api;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.minemoticon.Minemoticon;

public class EmojiFromTwitmoji extends Emoji {

    private static final String TWEMOJI_CDN = "https://raw.githubusercontent.com/iamcal/emoji-data/master/img-twitter-64/";

    private DownloadedTexture texture;
    private ResourceLocation resourceLocation;

    // Lazily creates and registers the texture on first call.
    public void checkLoad() {
        if (texture != null) return;

        var cacheFile = new File("minemoticon/cache/" + name + "-" + version);
        var url = TWEMOJI_CDN + location;

        texture = new DownloadedTexture(cacheFile, url);
        resourceLocation = new ResourceLocation(
            Minemoticon.MODID,
            "textures/emoji/" + location.toLowerCase()
                .replaceAll("[^a-z0-9/._-]", "") + "_" + version);
        Minecraft.getMinecraft()
            .getTextureManager()
            .loadTexture(resourceLocation, texture);
    }

    public ResourceLocation getResourceLocation() {
        checkLoad();
        return resourceLocation;
    }

    public boolean isLoaded() {
        return texture != null && texture.isUploaded();
    }
}
