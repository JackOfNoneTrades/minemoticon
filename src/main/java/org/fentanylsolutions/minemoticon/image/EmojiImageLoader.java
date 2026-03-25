package org.fentanylsolutions.minemoticon.image;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.fentanylsolutions.fentlib.util.GifUtil;
import org.fentanylsolutions.fentlib.util.QoiUtil;
import org.fentanylsolutions.fentlib.util.WebpUtil;

public final class EmojiImageLoader {

    public static final String STATIC_FILE_EXTENSION = ".png";

    private static final int MAX_ATLAS_SIDE = 4096;

    private EmojiImageLoader() {}

    public static final class SanitizedEmojiData {

        private final byte[] bytes;
        private final String fileExtension;

        public SanitizedEmojiData(byte[] bytes, String fileExtension) {
            this.bytes = bytes;
            this.fileExtension = fileExtension;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public String getFileExtension() {
            return fileExtension;
        }
    }

    public static EmojiImageData read(File file) throws IOException {
        return read(Files.readAllBytes(file.toPath()), file.getName());
    }

    public static EmojiImageData read(byte[] rawBytes, String sourceName) throws IOException {
        if (EmojiAnimationCodec.isEncoded(rawBytes)) {
            return EmojiAnimationCodec.decode(rawBytes);
        }

        if (looksLikeGif(rawBytes, sourceName)) {
            return readGif(rawBytes);
        }

        BufferedImage image = decodeStaticImage(rawBytes, sourceName);
        if (image == null) {
            throw new IOException("Unsupported emoji image format");
        }
        return EmojiImageData.fromStatic(image);
    }

    public static SanitizedEmojiData sanitizeForTransfer(byte[] rawBytes, String sourceName,
        boolean enforceMaxDimension, int maxDimension) throws IOException {
        if (EmojiAnimationCodec.isEncoded(rawBytes)) {
            EmojiImageData imageData = EmojiAnimationCodec.decode(rawBytes);
            validateDimensions(imageData, enforceMaxDimension, maxDimension);
            validateAtlasSize(imageData);
            return new SanitizedEmojiData(rawBytes, EmojiAnimationCodec.FILE_EXTENSION);
        }

        if (looksLikeGif(rawBytes, sourceName)) {
            EmojiImageData imageData = readGif(rawBytes);
            validateDimensions(imageData, enforceMaxDimension, maxDimension);
            validateAtlasSize(imageData);

            if (imageData.isAnimated()) {
                return new SanitizedEmojiData(
                    EmojiAnimationCodec.encode(imageData),
                    EmojiAnimationCodec.FILE_EXTENSION);
            }

            return new SanitizedEmojiData(encodeCleanPng(imageData.getAtlasImage()), STATIC_FILE_EXTENSION);
        }

        BufferedImage image = decodeStaticImage(rawBytes, sourceName);
        if (image == null) {
            throw new IOException("Unsupported emoji image format");
        }

        EmojiImageData imageData = EmojiImageData.fromStatic(image);
        validateDimensions(imageData, enforceMaxDimension, maxDimension);
        return new SanitizedEmojiData(encodeCleanPng(imageData.getAtlasImage()), STATIC_FILE_EXTENSION);
    }

    public static File findCachedFile(File directory, String checksum) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }

        File encoded = new File(directory, checksum + EmojiAnimationCodec.FILE_EXTENSION);
        if (encoded.isFile()) {
            return encoded;
        }

        File png = new File(directory, checksum + STATIC_FILE_EXTENSION);
        if (png.isFile()) {
            return png;
        }

        File[] matches = directory.listFiles(
            file -> file.isFile() && file.getName()
                .startsWith(checksum + "."));
        if (matches == null || matches.length == 0) {
            return null;
        }
        return matches[0];
    }

    public static String fileExtensionForPayload(byte[] rawBytes) {
        return EmojiAnimationCodec.isEncoded(rawBytes) ? EmojiAnimationCodec.FILE_EXTENSION : STATIC_FILE_EXTENSION;
    }

    private static EmojiImageData readGif(byte[] gifBytes) throws IOException {
        GifUtil.GifData gif = GifUtil.readGif(gifBytes);
        int frameCount = gif.getFrameCount();
        if (frameCount <= 0) {
            throw new IOException("GIF has no frames");
        }

        if (frameCount == 1) {
            return EmojiImageData.fromStatic(gif.getFrame(0));
        }

        int frameWidth = 0;
        int frameHeight = 0;
        BufferedImage[] frames = new BufferedImage[frameCount];
        int[] delaysMs = new int[frameCount];

        for (int i = 0; i < frameCount; i++) {
            BufferedImage frame = copyToArgb(gif.getFrame(i));
            frames[i] = frame;
            frameWidth = Math.max(frameWidth, frame.getWidth());
            frameHeight = Math.max(frameHeight, frame.getHeight());
            delaysMs[i] = gif.getDelayMs(i);
        }

        if (frameWidth <= 0 || frameHeight <= 0) {
            throw new IOException("GIF has invalid frame dimensions");
        }

        int framesPerRow = Math.max(1, Math.min(frameCount, MAX_ATLAS_SIDE / frameWidth));
        int rowCount = (frameCount + framesPerRow - 1) / framesPerRow;
        long atlasWidth = (long) framesPerRow * frameWidth;
        long atlasHeight = (long) rowCount * frameHeight;
        if (atlasWidth > MAX_ATLAS_SIDE || atlasHeight > MAX_ATLAS_SIDE) {
            throw new IOException("Animated emoji atlas would exceed " + MAX_ATLAS_SIDE + "px");
        }

        BufferedImage atlas = new BufferedImage((int) atlasWidth, (int) atlasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();
        for (int i = 0; i < frameCount; i++) {
            int col = i % framesPerRow;
            int row = i / framesPerRow;
            graphics.drawImage(frames[i], col * frameWidth, row * frameHeight, null);
        }
        graphics.dispose();

        return EmojiImageData.fromAnimation(atlas, frameWidth, frameHeight, frameCount, framesPerRow, delaysMs);
    }

    private static BufferedImage decodeStaticImage(byte[] rawBytes, String sourceName) throws IOException {
        String lowerName = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);

        if (looksLikeQoi(rawBytes, lowerName)) {
            return QoiUtil.readImage(rawBytes);
        }

        if (looksLikeWebp(rawBytes, lowerName)) {
            return WebpUtil.readImage(rawBytes);
        }

        return ImageIO.read(new ByteArrayInputStream(rawBytes));
    }

    private static void validateDimensions(EmojiImageData imageData, boolean enforceMaxDimension, int maxDimension)
        throws IOException {
        if (!enforceMaxDimension) {
            return;
        }
        if (imageData.getFrameWidth() > maxDimension || imageData.getFrameHeight() > maxDimension) {
            throw new IOException(
                "Emoji dimensions " + imageData
                    .getFrameWidth() + "x" + imageData.getFrameHeight() + " exceed max " + maxDimension);
        }
    }

    private static void validateAtlasSize(EmojiImageData imageData) throws IOException {
        if (imageData.getAtlasImage()
            .getWidth() > MAX_ATLAS_SIDE
            || imageData.getAtlasImage()
                .getHeight() > MAX_ATLAS_SIDE) {
            throw new IOException("Animated emoji atlas exceeds " + MAX_ATLAS_SIDE + "px");
        }
    }

    private static byte[] encodeCleanPng(BufferedImage image) throws IOException {
        BufferedImage clean = copyToArgb(image);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(clean, "png", output)) {
            throw new IOException("Failed to encode PNG");
        }
        return output.toByteArray();
    }

    private static boolean looksLikeGif(byte[] rawBytes, String sourceName) {
        return hasMagic(rawBytes, "GIF87a") || hasMagic(rawBytes, "GIF89a")
            || sourceName != null && sourceName.toLowerCase(Locale.ROOT)
                .endsWith(".gif");
    }

    private static boolean looksLikeQoi(byte[] rawBytes, String sourceName) {
        return hasMagic(rawBytes, "qoif") || sourceName.endsWith(".qoi");
    }

    private static boolean looksLikeWebp(byte[] rawBytes, String sourceName) {
        if (sourceName.endsWith(".webp")) {
            return true;
        }
        return rawBytes != null && rawBytes.length >= 12
            && rawBytes[0] == 'R'
            && rawBytes[1] == 'I'
            && rawBytes[2] == 'F'
            && rawBytes[3] == 'F'
            && rawBytes[8] == 'W'
            && rawBytes[9] == 'E'
            && rawBytes[10] == 'B'
            && rawBytes[11] == 'P';
    }

    private static boolean hasMagic(byte[] rawBytes, String magic) {
        if (rawBytes == null || rawBytes.length < magic.length()) {
            return false;
        }
        for (int i = 0; i < magic.length(); i++) {
            if (rawBytes[i] != (byte) magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static BufferedImage copyToArgb(BufferedImage image) {
        if (image == null) {
            return null;
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
}
