package org.fentanylsolutions.minemoticon.api;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;

import org.fentanylsolutions.minemoticon.Minemoticon;

// Loads an image from a local file and uploads to GL lazily.
public class FileTexture extends AbstractTexture {

    private final File imageFile;
    private final AtomicReference<BufferedImage> pendingImage = new AtomicReference<>();
    private volatile boolean uploaded;

    public FileTexture(File imageFile) {
        this.imageFile = imageFile;
    }

    public boolean isUploaded() {
        return uploaded;
    }

    @Override
    public void loadTexture(IResourceManager resourceManager) throws IOException {
        // Submit file read to the shared download pool (reuse existing thread pool)
        DownloadedTexture.submitToPool(() -> {
            try {
                pendingImage.set(ImageIO.read(imageFile));
            } catch (IOException e) {
                Minemoticon.LOG.error("Failed to read pack emoji from {}", imageFile.getName(), e);
            }
        });
    }

    @Override
    public int getGlTextureId() {
        int id = super.getGlTextureId();
        BufferedImage img = pendingImage.getAndSet(null);
        if (img != null) {
            TextureUtil.uploadTextureImageAllocate(id, img, false, false);
            uploaded = true;
        }
        return id;
    }
}
