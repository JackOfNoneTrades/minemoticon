package org.fentanylsolutions.minemoticon.api;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.minemoticon.Minemoticon;

public class EmojiFromResource extends Emoji implements RenderableEmoji {

    private final String prefix;
    private final ResourceLocation sourceLocation;
    private ResourceTexture texture;
    private ResourceLocation textureLocation;

    public EmojiFromResource(String prefix, String name, String category, ResourceLocation sourceLocation) {
        this.prefix = prefix;
        this.name = name;
        this.category = category;
        this.sourceLocation = sourceLocation;
        this.strings.add(getNamespaced());
    }

    public String getNamespaced() {
        return ":" + prefix + "/" + name + ":";
    }

    @Override
    public String getInsertText() {
        return getNamespaced();
    }

    private void checkLoad() {
        if (texture != null) return;

        texture = new ResourceTexture(sourceLocation);
        textureLocation = new ResourceLocation(Minemoticon.MODID, "textures/resource/" + prefix + "/" + name);
        Minecraft.getMinecraft()
            .getTextureManager()
            .loadTexture(textureLocation, texture);
    }

    @Override
    public ResourceLocation getResourceLocation() {
        checkLoad();
        return textureLocation;
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

    public void destroy() {
        if (textureLocation != null) {
            Minecraft.getMinecraft()
                .getTextureManager()
                .deleteTexture(textureLocation);
        }
        texture = null;
        textureLocation = null;
    }
}
