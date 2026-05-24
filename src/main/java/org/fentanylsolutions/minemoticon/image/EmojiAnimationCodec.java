package org.fentanylsolutions.minemoticon.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

public final class EmojiAnimationCodec {

    public static final String FILE_EXTENSION = ".mnea";

    private static final byte[] MAGIC = new byte[] { 'M', 'N', 'E', 'A', '1' };
    private static final int MAX_FRAME_COUNT = 4096;

    private EmojiAnimationCodec() {}

    public static boolean isEncoded(byte[] data) {
        if (data == null || data.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    public static byte[] encode(EmojiImageData imageData) throws IOException {
        if (imageData == null || !imageData.isAnimated()) {
            throw new IOException("Animated emoji data required");
        }

        byte[] atlasPng = encodePng(imageData.getAtlasImage());

        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(byteStream);
        output.write(MAGIC);
        output.writeInt(imageData.getFrameWidth());
        output.writeInt(imageData.getFrameHeight());
        output.writeInt(imageData.getFrameCount());
        output.writeInt(imageData.getFramesPerRow());

        int[] delaysMs = imageData.getFrameDurationsMs();
        output.writeInt(delaysMs.length);
        for (int delayMs : delaysMs) {
            output.writeInt(delayMs);
        }

        output.writeInt(atlasPng.length);
        output.write(atlasPng);
        output.flush();
        return byteStream.toByteArray();
    }

    public static EmojiImageData decode(byte[] data) throws IOException {
        if (!isEncoded(data)) {
            throw new IOException("Not a Minemoticon animated emoji payload");
        }

        DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
        byte[] magic = new byte[MAGIC.length];
        input.readFully(magic);

        int frameWidth = input.readInt();
        int frameHeight = input.readInt();
        int frameCount = input.readInt();
        int framesPerRow = input.readInt();
        if (frameWidth <= 0 || frameHeight <= 0
            || frameCount <= 0
            || frameCount > MAX_FRAME_COUNT
            || framesPerRow <= 0
            || framesPerRow > frameCount) {
            throw new IOException("Animated emoji metadata is invalid");
        }

        int delayCount = input.readInt();
        if (delayCount != frameCount) {
            throw new IOException("Animated emoji delay count does not match frame count");
        }
        int[] delaysMs = new int[delayCount];
        for (int i = 0; i < delayCount; i++) {
            delaysMs[i] = input.readInt();
        }

        int atlasLength = input.readInt();
        if (atlasLength <= 0 || atlasLength > input.available()) {
            throw new IOException("Animated emoji atlas length is invalid");
        }

        byte[] atlasBytes = new byte[atlasLength];
        input.readFully(atlasBytes);

        BufferedImage atlasImage = ImageIO.read(new ByteArrayInputStream(atlasBytes));
        if (atlasImage == null) {
            throw new IOException("Animated emoji atlas PNG is invalid");
        }

        return EmojiImageData.fromAnimation(atlasImage, frameWidth, frameHeight, frameCount, framesPerRow, delaysMs);
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("Failed to encode animated emoji atlas as PNG");
        }
        return output.toByteArray();
    }
}
