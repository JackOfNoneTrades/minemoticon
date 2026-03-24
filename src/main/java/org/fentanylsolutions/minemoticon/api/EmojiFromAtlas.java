package org.fentanylsolutions.minemoticon.api;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.colorfont.EmojiAtlas;

// Stock emoji rendered from a shared atlas texture with UV coordinates.
public class EmojiFromAtlas extends Emoji implements RenderableEmoji {

    private static final ResourceLocation ATLAS_LOCATION = new ResourceLocation(
        Minemoticon.MODID,
        "textures/atlas/emoji");
    private static boolean atlasRegistered;

    private final EmojiAtlas atlas;
    private final String unified;

    public EmojiFromAtlas(EmojiAtlas atlas, String unified) {
        this.atlas = atlas;
        this.unified = unified;
    }

    private void ensureRegistered() {
        if (atlasRegistered) return;
        if (!atlas.isReady()) return;

        Minecraft.getMinecraft()
            .getTextureManager()
            .loadTexture(ATLAS_LOCATION, atlas.getTexture());
        atlasRegistered = true;
    }

    @Override
    public ResourceLocation getResourceLocation() {
        ensureRegistered();
        if (!atlasRegistered) return null;
        return ATLAS_LOCATION;
    }

    @Override
    public boolean isLoaded() {
        return atlas.isReady() && atlas.getTexture()
            .isUploaded();
    }

    @Override
    public float[] getUV() {
        return atlas.getUV(unified);
    }

    @Override
    public String getInsertText() {
        return unicodeString != null ? unicodeString : super.getInsertText();
    }
}
