package org.fentanylsolutions.minemoticon.render;

import java.awt.image.BufferedImage;

import net.minecraft.client.renderer.texture.TextureUtil;

import org.lwjgl.opengl.GL11;

public final class EmojiTextureUtil {

    private EmojiTextureUtil() {}

    public static void uploadFilteredTexture(int textureId, BufferedImage image) {
        TextureUtil.uploadTextureImageAllocate(textureId, image, true, false);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    }
}
