package org.fentanylsolutions.minemoticon.api;

import net.minecraft.util.ResourceLocation;

public interface RenderableEmoji {

    ResourceLocation getResourceLocation();

    boolean isLoaded();
}
