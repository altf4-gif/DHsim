package com.example.engine;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public final class Texture {
    private final int id;
    private final int pixelWidth;
    private final int pixelHeight;

    public final float uMin, vMin, uMax, vMax;

    public Texture(String filePath) {
        this(filePath, false, true);
    }

    public Texture(String filePath, boolean repeat, boolean cropContent) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            STBImage.stbi_set_flip_vertically_on_load(true);
            ByteBuffer image = STBImage.stbi_load(filePath, w, h, channels, 4);
            if (image == null) {
                throw new RuntimeException("Failed to load texture: " + filePath + "\n"
                        + STBImage.stbi_failure_reason());
            }

            pixelWidth = w.get(0);
            pixelHeight = h.get(0);

            if (cropContent) {
                int minX = pixelWidth, minY = pixelHeight, maxX = -1, maxY = -1;
                for (int y = 0; y < pixelHeight; y++) {
                    for (int x = 0; x < pixelWidth; x++) {
                        int i = (y * pixelWidth + x) * 4;
                        int r = image.get(i) & 0xFF;
                        int g = image.get(i + 1) & 0xFF;
                        int b = image.get(i + 2) & 0xFF;
                        int a = image.get(i + 3) & 0xFF;

                        boolean transparent = a < 16;
                        boolean nearWhite = r > 238 && g > 238 && b > 238;
                        if (transparent || nearWhite) continue;

                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
                if (maxX >= minX && maxY >= minY) {
                    uMin = (float) minX / pixelWidth;
                    uMax = (float) (maxX + 1) / pixelWidth;
                    vMin = (float) minY / pixelHeight;
                    vMax = (float) (maxY + 1) / pixelHeight;
                } else {
                    uMin = 0; vMin = 0; uMax = 1; vMax = 1;
                }
            } else {
                uMin = 0; vMin = 0; uMax = 1; vMax = 1;
            }

            id = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);

            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

            int wrap = repeat ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE;
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);

            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, pixelWidth, pixelHeight, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, image);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);

            STBImage.stbi_image_free(image);
        }
    }

    public int getId() { return id; }

    public float contentAspect() {
        float w = (uMax - uMin) * pixelWidth;
        float h = (vMax - vMin) * pixelHeight;
        if (h <= 0.0001f) return 1f;
        return w / h;
    }

    public float aspect() {
        return (float) pixelWidth / pixelHeight;
    }

    public void bind() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
    }

    public void destroy() {
        GL11.glDeleteTextures(id);
    }
}
