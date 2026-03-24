package org.fentanylsolutions.minemoticon.api;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.IResourceManager;

import org.fentanylsolutions.fentlib.util.QoiUtil;
import org.fentanylsolutions.fentlib.util.WebpUtil;
import org.fentanylsolutions.minemoticon.Minemoticon;
import org.fentanylsolutions.minemoticon.render.EmojiTextureUtil;

// Loads an image from a local file and uploads to GL lazily.
// Supports PNG, JPG, QOI, and WebP.
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
        DownloadedTexture.submitToPool(() -> {
            try {
                pendingImage.set(readImage(imageFile));
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
            EmojiTextureUtil.uploadFilteredTexture(id, img);
            uploaded = true;
        }
        return id;
    }

    public static BufferedImage readImage(File file) throws IOException {
        String name = file.getName()
            .toLowerCase();
        if (name.endsWith(".qoi")) {
            return QoiUtil.readImage(file);
        } else if (name.endsWith(".webp")) {
            return WebpUtil.readImage(file);
        } else {
            return ImageIO.read(file);
        }
    }
}
