package org.fentanylsolutions.minemoticon.api;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.IResourceManager;

import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.image.EmojiImageData;
import org.fentanylsolutions.minemoticon.image.EmojiImageLoader;
import org.fentanylsolutions.minemoticon.render.EmojiTextureUtil;

// Loads an emoji image from a local file and uploads to GL lazily.
public class FileTexture extends AbstractTexture {

    private final File imageFile;
    private final AtomicReference<EmojiImageData> pendingImage = new AtomicReference<>();
    private volatile boolean uploaded;
    private volatile EmojiImageData imageData;

    public FileTexture(File imageFile) {
        this.imageFile = imageFile;
    }

    public boolean isUploaded() {
        return uploaded;
    }

    @Override
    public void loadTexture(IResourceManager resourceManager) throws IOException {
        DownloadedTexture.submitToPool(() -> {
            try {
                pendingImage.set(EmojiImageLoader.read(imageFile));
            } catch (IOException e) {
                Minemoticon.LOG.error("Failed to read pack emoji from {}", imageFile.getName(), e);
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
