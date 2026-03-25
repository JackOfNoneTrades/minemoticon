package org.fentanylsolutions.minemoticon.image;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

public final class EmojiImageData {

    private static final int DEFAULT_DELAY_MS = 100;
    private static final int MIN_DELAY_MS = 20;
    private static final int MAX_DELAY_MS = 60_000;

    private final BufferedImage atlasImage;
    private final int frameWidth;
    private final int frameHeight;
    private final int frameCount;
    private final int framesPerRow;
    private final int[] frameDurationsMs;
    private final int[] frameEndTimesMs;
    private final int totalDurationMs;
    private final float[][] frameUvs;

    private EmojiImageData(BufferedImage atlasImage, int frameWidth, int frameHeight, int frameCount, int framesPerRow,
        int[] frameDurationsMs) {
        this.atlasImage = atlasImage;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.frameCount = frameCount;
        this.framesPerRow = framesPerRow;
        this.frameDurationsMs = frameDurationsMs;
        this.frameEndTimesMs = buildFrameEndTimes(frameDurationsMs);
        this.totalDurationMs = frameEndTimesMs[frameEndTimesMs.length - 1];
        this.frameUvs = buildFrameUvs(atlasImage, frameWidth, frameHeight, frameCount, framesPerRow);
    }

    public static EmojiImageData fromStatic(BufferedImage image) {
        BufferedImage argb = copyToArgb(image);
        return new EmojiImageData(argb, argb.getWidth(), argb.getHeight(), 1, 1, new int[] { DEFAULT_DELAY_MS });
    }

    public static EmojiImageData fromAnimation(BufferedImage atlasImage, int frameWidth, int frameHeight,
        int frameCount, int framesPerRow, int[] frameDurationsMs) {
        if (atlasImage == null) {
            throw new IllegalArgumentException("atlasImage cannot be null");
        }
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw new IllegalArgumentException("Frame dimensions must be positive");
        }
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be positive");
        }
        if (framesPerRow <= 0) {
            throw new IllegalArgumentException("framesPerRow must be positive");
        }
        if (frameDurationsMs == null || frameDurationsMs.length != frameCount) {
            throw new IllegalArgumentException("frameDurationsMs must match frameCount");
        }
        int rowCount = (frameCount + framesPerRow - 1) / framesPerRow;
        if (atlasImage.getWidth() < framesPerRow * frameWidth || atlasImage.getHeight() < rowCount * frameHeight) {
            throw new IllegalArgumentException("atlasImage is too small for the declared frame layout");
        }

        return new EmojiImageData(
            copyToArgb(atlasImage),
            frameWidth,
            frameHeight,
            frameCount,
            framesPerRow,
            normalizeDelays(frameDurationsMs));
    }

    public BufferedImage getAtlasImage() {
        return atlasImage;
    }

    public int getFrameWidth() {
        return frameWidth;
    }

    public int getFrameHeight() {
        return frameHeight;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public int getFramesPerRow() {
        return framesPerRow;
    }

    public int[] getFrameDurationsMs() {
        return Arrays.copyOf(frameDurationsMs, frameDurationsMs.length);
    }

    public boolean isAnimated() {
        return frameCount > 1;
    }

    public float[] getUvForTime(long timeMs) {
        if (!isAnimated()) {
            return null;
        }
        return frameUvs[getFrameIndexForTime(timeMs)];
    }

    private int getFrameIndexForTime(long timeMs) {
        if (frameCount <= 1 || totalDurationMs <= 0) {
            return 0;
        }

        int cyclePos = (int) Math.floorMod(timeMs, (long) totalDurationMs);
        for (int i = 0; i < frameEndTimesMs.length; i++) {
            if (cyclePos < frameEndTimesMs[i]) {
                return i;
            }
        }
        return frameEndTimesMs.length - 1;
    }

    private static BufferedImage copyToArgb(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image cannot be null");
        }
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }

        BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = argb.createGraphics();
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return argb;
    }

    private static int[] normalizeDelays(int[] delaysMs) {
        int[] normalized = Arrays.copyOf(delaysMs, delaysMs.length);
        for (int i = 0; i < normalized.length; i++) {
            int delay = normalized[i];
            if (delay <= 0) {
                delay = DEFAULT_DELAY_MS;
            }
            delay = Math.max(delay, MIN_DELAY_MS);
            delay = Math.min(delay, MAX_DELAY_MS);
            normalized[i] = delay;
        }
        return normalized;
    }

    private static int[] buildFrameEndTimes(int[] delaysMs) {
        int[] frameEnds = new int[delaysMs.length];
        int total = 0;
        for (int i = 0; i < delaysMs.length; i++) {
            total += delaysMs[i];
            frameEnds[i] = total;
        }
        return frameEnds;
    }

    private static float[][] buildFrameUvs(BufferedImage atlasImage, int frameWidth, int frameHeight, int frameCount,
        int framesPerRow) {
        float atlasWidth = atlasImage.getWidth();
        float atlasHeight = atlasImage.getHeight();
        float[][] uvs = new float[frameCount][4];

        for (int i = 0; i < frameCount; i++) {
            int col = i % framesPerRow;
            int row = i / framesPerRow;
            float x = col * frameWidth;
            float y = row * frameHeight;
            uvs[i] = new float[] { x / atlasWidth, y / atlasHeight, (x + frameWidth) / atlasWidth,
                (y + frameHeight) / atlasHeight };
        }

        return uvs;
    }
}
