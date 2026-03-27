package org.fentanylsolutions.minemoticon.api;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.io.IOUtils;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.image.EmojiImageData;
import org.fentanylsolutions.minemoticon.image.EmojiImageLoader;
import org.fentanylsolutions.minemoticon.render.EmojiTextureUtil;

public class ResourceTexture extends AbstractTexture {

    private final ResourceLocation sourceLocation;
    private final AtomicReference<EmojiImageData> pendingImage = new AtomicReference<>();
    private volatile boolean uploaded;
    private volatile EmojiImageData imageData;

    public ResourceTexture(ResourceLocation sourceLocation) {
        this.sourceLocation = sourceLocation;
    }

    public boolean isUploaded() {
        return uploaded;
    }

    @Override
    public void loadTexture(IResourceManager resourceManager) throws IOException {
        DownloadedTexture.submitToPool(() -> {
            try {
                IResource resource = resourceManager.getResource(sourceLocation);
                if (resource == null) {
                    return;
                }
                try (InputStream stream = resource.getInputStream()) {
                    byte[] bytes = IOUtils.toByteArray(stream);
                    pendingImage.set(EmojiImageLoader.read(bytes, sourceLocation.getResourcePath()));
                }
            } catch (IOException e) {
                Minemoticon.LOG.error("Failed to load resource emoji from {}", sourceLocation, e);
            }
        });
    }

    @Override
    public int getGlTextureId() {
        int id = super.getGlTextureId();
        EmojiImageData newImageData = pendingImage.getAndSet(null);
        if (newImageData != null) {
            BufferedImage atlasImage = newImageData.getAtlasImage();
            EmojiTextureUtil.uploadFilteredTexture(id, atlasImage);
            imageData = newImageData;
            uploaded = true;
        }
        return id;
    }

    public float[] getCurrentUV() {
        EmojiImageData currentImageData = imageData;
        if (currentImageData == null || !currentImageData.isAnimated()) {
            return null;
        }
        return currentImageData.getUvForTime(System.currentTimeMillis());
    }
}
