package org.fentanylsolutions.minemoticon.api;

import net.minecraft.util.ResourceLocation;

public interface RenderableEmoji {

    ResourceLocation getResourceLocation();

    boolean isLoaded();

    // Override for atlas-based emojis. Returns [u0, v0, u1, v1] or null for full-texture.
    default float[] getUV() {
        return null;
    }
}
