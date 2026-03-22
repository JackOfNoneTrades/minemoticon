package org.fentanylsolutions.minemoticon.api;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;

import org.apache.commons.io.FileUtils;
import org.fentanylsolutions.minemoticon.EmojiConfig;
import org.fentanylsolutions.minemoticon.Minemoticon;

// Downloads an image from a URL, caches to disk, and uploads to GL on next bind.
public class DownloadedTexture extends AbstractTexture {

    private static final ExecutorService DOWNLOAD_POOL = createDownloadPool();

    private final File cacheFile;
    private final String imageUrl;
    private final AtomicReference<BufferedImage> pendingImage = new AtomicReference<>();
    private volatile boolean submitted;
    private volatile boolean uploaded;

    private static ExecutorService createDownloadPool() {
        var threadCounter = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            var t = new Thread(r, "Minemoticon Emoji Download #" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
            EmojiConfig.maxDownloadThreads,
            EmojiConfig.maxDownloadThreads,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            factory);
    }

    public DownloadedTexture(File cacheFile, String imageUrl) {
        this.cacheFile = cacheFile;
        this.imageUrl = imageUrl;
    }

    public boolean isUploaded() {
        return uploaded;
    }

    @Override
    public void loadTexture(IResourceManager resourceManager) throws IOException {
        if (cacheFile != null && cacheFile.isFile()) {
            try {
                pendingImage.set(ImageIO.read(cacheFile));
            } catch (IOException e) {
                Minemoticon.LOG.warn("Failed to read cached emoji {}, re-downloading", cacheFile.getName());
                submitDownload();
            }
        } else {
            submitDownload();
        }
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

    private void submitDownload() {
        if (submitted) return;
        submitted = true;
        DOWNLOAD_POOL.execute(this::download);
    }

    private void download() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(imageUrl).openConnection(
                Minecraft.getMinecraft()
                    .getProxy());
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Minemoticon)");
            conn.setDoInput(true);
            conn.setDoOutput(false);
            conn.connect();
            if (conn.getResponseCode() / 100 == 2) {
                FileUtils.copyInputStreamToFile(conn.getInputStream(), cacheFile);
                pendingImage.set(ImageIO.read(cacheFile));
            } else {
                Minemoticon.LOG.warn("Failed to download emoji from {}: HTTP {}", imageUrl, conn.getResponseCode());
            }
        } catch (Exception e) {
            Minemoticon.LOG.error("Failed to download emoji from {}", imageUrl, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
