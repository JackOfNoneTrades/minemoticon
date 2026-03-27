package org.fentanylsolutions.minemoticon.mixins.early.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.minemoticon.ClientEmojiHandler;
import org.fentanylsolutions.minemoticon.EmojiConfig;
import org.fentanylsolutions.minemoticon.api.RenderableEmoji;
import org.fentanylsolutions.minemoticon.font.FontSource;
import org.fentanylsolutions.minemoticon.font.FontStack;
import org.fentanylsolutions.minemoticon.font.GlyphCache;
import org.fentanylsolutions.minemoticon.font.MinecraftFontSource;
import org.fentanylsolutions.minemoticon.render.EmojiRenderer;
import org.fentanylsolutions.minemoticon.render.FontRendererEmojiCompat;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FontRenderer.class, priority = 2000)
public abstract class MixinFontRenderer implements FontRendererEmojiCompat {

    @Unique
    private static final float minemoticon$INLINE_EMOJI_Y_OFFSET = -(EmojiRenderer.EMOJI_SIZE - 8.0f) / 2.0f;

    @Shadow
    private float posX;

    @Shadow
    private float posY;

    @Shadow
    private float red;

    @Shadow
    private float green;

    @Shadow
    private float blue;

    @Shadow
    private float alpha;

    @Shadow
    private boolean bidiFlag;

    @Shadow
    private int[] colorCode;

    @Shadow
    private boolean randomStyle;

    @Shadow
    private boolean boldStyle;

    @Shadow
    private boolean italicStyle;

    @Shadow
    private boolean underlineStyle;

    @Shadow
    private boolean strikethroughStyle;

    @Shadow
    public int FONT_HEIGHT;

    @Shadow
    public abstract int getStringWidth(String text);

    @Shadow
    public abstract int getCharWidth(char c);

    @Shadow
    protected abstract void renderStringAtPos(String text, boolean shadow);

    @Shadow
    protected abstract String bidiReorder(String text);

    @Shadow
    protected abstract void resetStyles();

    @Shadow
    protected ResourceLocation locationFontTexture;

    @Unique
    private boolean minemoticon$rendering = false;

    @Unique
    private boolean minemoticon$measuringWidth = false;

    @Unique
    private boolean minemoticon$renderingCompatString = false;

    @Unique
    private int minemoticon$currentRenderColor = 0xFFFFFFFF;

    // --- Width measurement ---

    @Inject(method = "getStringWidth", at = @At("HEAD"), cancellable = true)
    private void minemoticon$fixStringWidth(String text, CallbackInfoReturnable<Integer> cir) {
        if (minemoticon$measuringWidth) return;
        if (ClientEmojiHandler.getFontStack() == null) return;
        if (minemoticon$isSplashFontRenderer()) return;

        minemoticon$measuringWidth = true;
        try {
            cir.setReturnValue((int) Math.ceil(minemoticon$measureCompatWidthExact(text)));
        } finally {
            minemoticon$measuringWidth = false;
        }
    }

    @Inject(method = "trimStringToWidth(Ljava/lang/String;IZ)Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void minemoticon$trimStringToWidthCompat(String text, int width, boolean reverse,
        CallbackInfoReturnable<String> cir) {
        if (ClientEmojiHandler.getFontStack() == null) return;
        if (minemoticon$isSplashFontRenderer()) return;

        cir.setReturnValue(minemoticon$trimStringToWidthCompat(text, width, reverse));
    }

    @Inject(method = "sizeStringToWidth", at = @At("HEAD"), cancellable = true)
    private void minemoticon$sizeStringToWidthCompat(String text, int wrapWidth, CallbackInfoReturnable<Integer> cir) {
        if (ClientEmojiHandler.getFontStack() == null) return;
        if (minemoticon$isSplashFontRenderer()) return;

        cir.setReturnValue(minemoticon$sizeStringToWidthCompat(text, wrapWidth));
    }

    // --- drawString / renderString compat ---

    @Inject(method = "drawString(Ljava/lang/String;IIIZ)I", at = @At("HEAD"), cancellable = true)
    private void minemoticon$drawStringCompat(String text, int x, int y, int color, boolean dropShadow,
        CallbackInfoReturnable<Integer> cir) {
        if (minemoticon$renderingCompatString) return;
        if (minemoticon$isSplashFontRenderer()) return;
        if (!Minecraft.getMinecraft()
            .func_152345_ab()) return;
        if (!minemoticon$shouldUseCompatString(text)) return;

        minemoticon$renderingCompatString = true;
        try {
            cir.setReturnValue(minemoticon$drawStringVanillaCompat(text, x, y, color, dropShadow));
        } finally {
            minemoticon$renderingCompatString = false;
        }
    }

    @Inject(method = "renderString", at = @At("HEAD"), cancellable = true)
    private void minemoticon$renderStringCompat(String text, int x, int y, int color, boolean dropShadow,
        CallbackInfoReturnable<Integer> cir) {
        if (minemoticon$renderingCompatString) return;
        if (minemoticon$isSplashFontRenderer()) return;
        if (!Minecraft.getMinecraft()
            .func_152345_ab()) return;
        if (!minemoticon$shouldUseCompatString(text)) return;

        minemoticon$renderingCompatString = true;
        try {
            cir.setReturnValue(minemoticon$renderStringVanillaCompat(text, x, y, color, dropShadow));
        } finally {
            minemoticon$renderingCompatString = false;
        }
    }

    // --- Core rendering ---

    @Inject(method = "renderStringAtPos", at = @At("HEAD"), cancellable = true)
    private void minemoticon$renderWithEmojis(String text, boolean shadow, CallbackInfo ci) {
        if (minemoticon$rendering) return;
        if (ClientEmojiHandler.getFontStack() == null) return;
        if (minemoticon$isSplashFontRenderer()) return;
        if (!Minecraft.getMinecraft()
            .func_152345_ab()) return;

        ci.cancel();
        minemoticon$rendering = true;
        try {
            this.minemoticon$currentRenderColor = minemoticon$getBaseRenderColor();
            minemoticon$applyRenderColor(this.minemoticon$currentRenderColor);
            var segments = EmojiRenderer.parse(text);
            if (segments != null) {
                for (var seg : segments) {
                    if (seg instanceof RenderableEmoji emoji) {
                        if (!shadow) {
                            EmojiRenderer.renderQuad(emoji, this.posX, this.posY + minemoticon$INLINE_EMOJI_Y_OFFSET);
                            minemoticon$applyRenderColor(this.minemoticon$currentRenderColor);
                        }
                        this.posX += EmojiRenderer.EMOJI_SIZE;
                    } else {
                        minemoticon$renderTextSegment((String) seg, shadow);
                    }
                }
            } else {
                minemoticon$renderTextSegment(text, shadow);
            }
        } finally {
            minemoticon$rendering = false;
        }
    }

    // For each codepoint: resolve via font stack. First font with the glyph wins.
    // MinecraftFontSource or null -> batch to vanilla. Anything else -> textured quad.
    @Unique
    private void minemoticon$renderTextSegment(String text, boolean shadow) {
        FontStack stack = ClientEmojiHandler.getFontStack();

        var vanillaBuf = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c0 = text.charAt(i);
            if (c0 == 167 && i + 1 < text.length()) {
                if (vanillaBuf.length() > 0) {
                    minemoticon$flushVanillaText(vanillaBuf, shadow);
                }
                minemoticon$applyFormattingCode(text.charAt(i + 1), shadow);
                i += 2;
                continue;
            }

            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);

            FontSource source = stack.resolve(cp);
            boolean isVanilla = source == null || source instanceof MinecraftFontSource;

            if (isVanilla) {
                vanillaBuf.append(text, i, i + charCount);
            } else {
                if (vanillaBuf.length() > 0) {
                    minemoticon$flushVanillaText(vanillaBuf, shadow);
                }
                minemoticon$renderFontStackGlyph(source, cp, shadow);
            }
            i += charCount;
        }

        if (vanillaBuf.length() > 0) {
            minemoticon$flushVanillaText(vanillaBuf, shadow);
        }
    }

    @Unique
    private void minemoticon$renderFontStackGlyph(FontSource source, int codepoint, boolean shadow) {
        GlyphCache cache = GlyphCache.forSource(source);
        float[] uv = cache.getGlyphUV(codepoint);
        float width = cache.getGlyphWidth(codepoint);
        float displayHeight = source.preserveTextLineMetrics() ? EmojiConfig.getFontStackTextDisplayHeight() : 8.0f;

        if (uv == null) {
            this.posX += width > 0.0f ? width : 8.0f;
            return;
        }

        if (!shadow || source.usesTextColor()) {
            float x0 = this.posX;
            float y0 = this.posY + (8.0f - displayHeight) * 0.5f;
            float x1 = x0 + width;
            float y1 = y0 + displayHeight;
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(cache.getResourceLocation());

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            if (source.usesTextColor()) {
                minemoticon$applyRenderColor(this.minemoticon$currentRenderColor);
            } else {
                GL11.glColor4f(1.0f, 1.0f, 1.0f, this.alpha);
            }

            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
            tessellator.addVertexWithUV(x0, y0, 0.0, uv[0], uv[1]);
            tessellator.addVertexWithUV(x0, y1, 0.0, uv[0], uv[3]);
            tessellator.addVertexWithUV(x1, y0, 0.0, uv[2], uv[1]);
            tessellator.addVertexWithUV(x1, y1, 0.0, uv[2], uv[3]);
            tessellator.draw();

            if (source.usesTextColor()) {
                minemoticon$applyRenderColor(this.minemoticon$currentRenderColor);
            }
        }

        if (source.usesTextColor() && this.boldStyle) {
            float x0 = this.posX + 1.0f;
            float y0 = this.posY + (8.0f - displayHeight) * 0.5f;
            float x1 = x0 + width;
            float y1 = y0 + displayHeight;

            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
            tessellator.addVertexWithUV(x0, y0, 0.0, uv[0], uv[1]);
            tessellator.addVertexWithUV(x0, y1, 0.0, uv[0], uv[3]);
            tessellator.addVertexWithUV(x1, y0, 0.0, uv[2], uv[1]);
            tessellator.addVertexWithUV(x1, y1, 0.0, uv[2], uv[3]);
            tessellator.draw();
        }

        float advance = width;
        if (this.boldStyle && width > 0.0f) {
            advance += 1.0f;
        }
        minemoticon$drawDecorationLines(advance);
        this.posX += advance;
    }

    @Unique
    private float minemoticon$measureTextSegmentExact(String text) {
        FontStack stack = ClientEmojiHandler.getFontStack();
        if (stack == null) {
            return minemoticon$measureVanillaStringWidth(text);
        }

        float width = 0.0f;
        var vanillaBuf = new StringBuilder();
        boolean bold = false;
        int i = 0;
        while (i < text.length()) {
            char c0 = text.charAt(i);
            if (c0 == 167 && i + 1 < text.length()) {
                if (vanillaBuf.length() > 0) {
                    width += minemoticon$measureVanillaStringWidth(vanillaBuf.toString());
                    vanillaBuf.setLength(0);
                }
                int format = minemoticon$getFormattingIndex(text.charAt(i + 1));
                if (format < 16) {
                    bold = false;
                } else if (format == 17) {
                    bold = true;
                } else if (format == 21) {
                    bold = false;
                }
                i += 2;
                continue;
            }

            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);

            FontSource source = stack.resolve(cp);
            boolean isVanilla = source == null || source instanceof MinecraftFontSource;

            if (isVanilla) {
                vanillaBuf.append(text, i, i + charCount);
            } else {
                if (vanillaBuf.length() > 0) {
                    width += minemoticon$measureVanillaStringWidth(vanillaBuf.toString());
                    vanillaBuf.setLength(0);
                }
                GlyphCache cache = GlyphCache.forSource(source);
                float glyphW = cache.getGlyphWidth(cp);
                float advance = glyphW > 0.0f ? glyphW : 8.0f;
                if (bold && advance > 0.0f) {
                    advance += 1.0f;
                }
                width += advance;
            }
            i += charCount;
        }

        if (vanillaBuf.length() > 0) {
            width += minemoticon$measureVanillaStringWidth(vanillaBuf.toString());
        }

        return width;
    }

    @Unique
    private float minemoticon$measureCompatWidthExact(String text) {
        if (text == null) {
            return 0.0f;
        }

        var segments = EmojiRenderer.parse(text);
        if (segments == null) {
            return minemoticon$measureTextSegmentExact(text);
        }

        float width = 0.0f;
        for (Object seg : segments) {
            if (seg instanceof RenderableEmoji) {
                width += EmojiRenderer.EMOJI_SIZE;
            } else {
                width += minemoticon$measureTextSegmentExact((String) seg);
            }
        }
        return width;
    }

    @Unique
    private int minemoticon$measureVanillaStringWidth(String text) {
        if (text == null) {
            return 0;
        }

        int width = 0;
        boolean bold = false;

        for (int i = 0; i < text.length(); ++i) {
            char c0 = text.charAt(i);
            int charWidth = this.getCharWidth(c0);

            if (charWidth < 0 && i < text.length() - 1) {
                ++i;
                c0 = text.charAt(i);

                if (c0 != 'l' && c0 != 'L') {
                    if (c0 == 'r' || c0 == 'R') {
                        bold = false;
                    }
                } else {
                    bold = true;
                }

                charWidth = 0;
            }

            width += charWidth;

            if (bold && charWidth > 0) {
                ++width;
            }
        }

        return width;
    }

    @Unique
    private String minemoticon$trimStringToWidthCompat(String text, int maxWidth, boolean reverse) {
        if (text == null || maxWidth <= 0) {
            return "";
        }

        if (reverse) {
            int bestStart = text.length();
            for (int index = text.length(); index > 0;) {
                int prev = minemoticon$previousTrimBoundary(text, index);
                if (minemoticon$measureCompatWidthExact(text.substring(prev)) > maxWidth) {
                    break;
                }
                bestStart = prev;
                index = prev;
            }
            return text.substring(bestStart);
        }

        int bestEnd = 0;
        for (int index = 0; index < text.length();) {
            int next = minemoticon$nextTrimBoundary(text, index);
            if (minemoticon$measureCompatWidthExact(text.substring(0, next)) > maxWidth) {
                break;
            }
            bestEnd = next;
            index = next;
        }
        return text.substring(0, bestEnd);
    }

    @Unique
    private int minemoticon$sizeStringToWidthCompat(String text, int wrapWidth) {
        if (text == null || text.isEmpty() || wrapWidth <= 0) {
            return 0;
        }

        int bestEnd = 0;
        int lastSpace = -1;

        for (int index = 0; index < text.length();) {
            char c0 = text.charAt(index);
            if (c0 == '\n') {
                return index;
            }
            if (c0 == ' ') {
                lastSpace = index;
            }

            int next = minemoticon$nextTrimBoundary(text, index);
            if (minemoticon$measureCompatWidthExact(text.substring(0, next)) > wrapWidth) {
                break;
            }

            bestEnd = next;
            index = next;
        }

        if (bestEnd < text.length() && lastSpace >= 0) {
            return lastSpace;
        }

        return bestEnd;
    }

    @Unique
    private int minemoticon$nextTrimBoundary(String text, int index) {
        if (index >= text.length()) {
            return text.length();
        }
        if (text.charAt(index) == 167 && index + 1 < text.length()) {
            return index + 2;
        }
        int codepoint = text.codePointAt(index);
        return index + Character.charCount(codepoint);
    }

    @Unique
    private int minemoticon$previousTrimBoundary(String text, int index) {
        if (index <= 0) {
            return 0;
        }

        int prev = index - 1;
        if (prev > 0 && Character.isLowSurrogate(text.charAt(prev))
            && Character.isHighSurrogate(text.charAt(prev - 1))) {
            prev--;
        }
        if (prev > 0 && text.charAt(prev - 1) == 167) {
            prev--;
        }
        return prev;
    }

    @Unique
    private boolean minemoticon$shouldUseCompatString(String text) {
        if (text == null || ClientEmojiHandler.getFontStack() == null) {
            return false;
        }
        if (EmojiRenderer.parse(text) != null) {
            return true;
        }

        FontStack stack = ClientEmojiHandler.getFontStack();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            FontSource source = stack.resolve(cp);
            if (source != null && !(source instanceof MinecraftFontSource)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    @Unique
    private boolean minemoticon$isSplashFontRenderer() {
        return this.getClass()
            .getName()
            .endsWith("SplashFontRenderer");
    }

    @Unique
    private void minemoticon$flushVanillaText(StringBuilder vanillaBuf, boolean shadow) {
        minemoticon$bindVanillaFontTexture();
        minemoticon$applyRenderColor(this.minemoticon$currentRenderColor);
        this.renderStringAtPos(vanillaBuf.toString(), shadow);
        vanillaBuf.setLength(0);
    }

    @Unique
    private int minemoticon$getBaseRenderColor() {
        int a = Math.round(this.alpha * 255.0f) & 255;
        int r = Math.round(this.red * 255.0f) & 255;
        int g = Math.round(this.blue * 255.0f) & 255;
        int b = Math.round(this.green * 255.0f) & 255;
        return a << 24 | r << 16 | g << 8 | b;
    }

    @Unique
    private void minemoticon$applyRenderColor(int argb) {
        float r = (float) (argb >> 16 & 255) / 255.0f;
        float g = (float) (argb >> 8 & 255) / 255.0f;
        float b = (float) (argb & 255) / 255.0f;
        GL11.glColor4f(r, g, b, this.alpha);
    }

    @Unique
    private int minemoticon$getFormattingIndex(char formatChar) {
        return "0123456789abcdefklmnor".indexOf(Character.toLowerCase(formatChar));
    }

    @Unique
    private void minemoticon$applyFormattingCode(char formatChar, boolean shadow) {
        int format = minemoticon$getFormattingIndex(formatChar);

        if (format < 16) {
            this.randomStyle = false;
            this.boldStyle = false;
            this.strikethroughStyle = false;
            this.underlineStyle = false;
            this.italicStyle = false;

            if (format < 0 || format > 15) {
                format = 15;
            }

            if (shadow) {
                format += 16;
            }

            this.minemoticon$currentRenderColor = this.colorCode[format];
            minemoticon$applyRenderColor(this.minemoticon$currentRenderColor);
        } else if (format == 16) {
            this.randomStyle = true;
        } else if (format == 17) {
            this.boldStyle = true;
        } else if (format == 18) {
            this.strikethroughStyle = true;
        } else if (format == 19) {
            this.underlineStyle = true;
        } else if (format == 20) {
            this.italicStyle = true;
        } else if (format == 21) {
            this.randomStyle = false;
            this.boldStyle = false;
            this.strikethroughStyle = false;
            this.underlineStyle = false;
            this.italicStyle = false;
            this.minemoticon$currentRenderColor = minemoticon$getBaseRenderColor();
            minemoticon$applyRenderColor(this.minemoticon$currentRenderColor);
        }
    }

    @Unique
    private void minemoticon$drawDecorationLines(float width) {
        if (!this.strikethroughStyle && !this.underlineStyle) {
            return;
        }

        Tessellator tessellator = Tessellator.instance;

        if (this.strikethroughStyle) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            tessellator.startDrawingQuads();
            tessellator.addVertex(this.posX, this.posY + (float) (this.FONT_HEIGHT / 2), 0.0D);
            tessellator.addVertex(this.posX + width, this.posY + (float) (this.FONT_HEIGHT / 2), 0.0D);
            tessellator.addVertex(this.posX + width, this.posY + (float) (this.FONT_HEIGHT / 2) - 1.0F, 0.0D);
            tessellator.addVertex(this.posX, this.posY + (float) (this.FONT_HEIGHT / 2) - 1.0F, 0.0D);
            tessellator.draw();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }

        if (this.underlineStyle) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            tessellator.startDrawingQuads();
            tessellator.addVertex(this.posX - 1.0F, this.posY + (float) this.FONT_HEIGHT, 0.0D);
            tessellator.addVertex(this.posX + width, this.posY + (float) this.FONT_HEIGHT, 0.0D);
            tessellator.addVertex(this.posX + width, this.posY + (float) this.FONT_HEIGHT - 1.0F, 0.0D);
            tessellator.addVertex(this.posX - 1.0F, this.posY + (float) this.FONT_HEIGHT - 1.0F, 0.0D);
            tessellator.draw();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }

        minemoticon$applyRenderColor(this.minemoticon$currentRenderColor);
    }

    @Unique
    private void minemoticon$bindVanillaFontTexture() {
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(this.locationFontTexture);
    }

    // --- Compat helpers ---

    @Unique
    private int minemoticon$drawStringVanillaCompat(String text, int x, int y, int color, boolean dropShadow) {
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        this.resetStyles();

        if (dropShadow) {
            int shadowWidth = minemoticon$renderStringVanillaCompat(text, x + 1, y + 1, color, true);
            int normalWidth = minemoticon$renderStringVanillaCompat(text, x, y, color, false);
            return Math.max(shadowWidth, normalWidth);
        }

        return minemoticon$renderStringVanillaCompat(text, x, y, color, false);
    }

    @Override
    public int minemoticon$drawStringCompatDirect(String text, int x, int y, int color, boolean dropShadow) {
        return minemoticon$drawStringVanillaCompat(text, x, y, color, dropShadow);
    }

    @Unique
    private int minemoticon$renderStringVanillaCompat(String text, int x, int y, int color, boolean dropShadow) {
        if (text == null) {
            return 0;
        }

        if (this.bidiFlag) {
            text = this.bidiReorder(text);
        }

        if ((color & 0xFC000000) == 0) {
            color |= 0xFF000000;
        }

        if (dropShadow) {
            color = (color & 0xFCFCFC) >> 2 | color & 0xFF000000;
        }

        this.red = (float) (color >> 16 & 255) / 255.0F;
        this.blue = (float) (color >> 8 & 255) / 255.0F;
        this.green = (float) (color & 255) / 255.0F;
        this.alpha = (float) (color >> 24 & 255) / 255.0F;
        GL11.glColor4f(this.red, this.blue, this.green, this.alpha);
        this.posX = x;
        this.posY = y;
        this.renderStringAtPos(text, dropShadow);
        return (int) this.posX;
    }
}
