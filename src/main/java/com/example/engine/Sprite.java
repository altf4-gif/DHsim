package com.example.engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A textured billboard cropped to the visible content of its texture and
 * gently bobbing up and down.
 *
 * Facing mode: Y-axis billboard. The sprite always turns toward the camera
 * only in the horizontal (XZ) plane, so its "up" is pinned to world +Y.
 * That means when you look down at the world from above, the characters
 * stay upright on the ground instead of lying flat / tilting back.
 *
 * The position is mutable so NPCs (see {@link Eater}) can walk around.
 */
public final class Sprite {
    private static int sharedVao = -1;

    private final Texture texture;
    private final Vector3f position;
    private final float width;
    private final float height;
    private final float bobPhase;

    /** Uniform scale, used for the chewing animation. */
    private float scale = 1f;

    public Sprite(Texture texture, Vector3f position, float targetHeight) {
        this.texture = texture;
        this.position = new Vector3f(position);
        this.height = targetHeight;
        this.width = targetHeight * texture.contentAspect();
        this.bobPhase = ThreadLocalRandom.current().nextFloat() * (float) (Math.PI * 2);
        if (sharedVao == -1) {
            createSharedQuad();
        }
    }

    private static void createSharedQuad() {
        // Unit quad in XY, UVs 0..1 — UV offset/scale applied in shader.
        float[] vertices = {
                // x,   y,    u,  v
                -0.5f, -0.5f, 0f, 0f,
                 0.5f, -0.5f, 1f, 0f,
                 0.5f,  0.5f, 1f, 1f,
                 0.5f,  0.5f, 1f, 1f,
                -0.5f,  0.5f, 0f, 1f,
                -0.5f, -0.5f, 0f, 0f,
        };

        sharedVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(sharedVao);

        int vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        FloatBuffer buf = MemoryUtil.memAllocFloat(vertices.length);
        try {
            buf.put(vertices).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(buf);
        }

        final int stride = 4 * Float.BYTES;
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0L);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2L * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    public void setPosition(float x, float y, float z) {
        position.set(x, y, z);
    }

    public Vector3f position() {
        return position;
    }

    public void setScale(float s) {
        this.scale = s;
    }

    public void render(ShaderProgram shader, Matrix4f view, Matrix4f projection,
                       Vector3f cameraPos, float time) {
        float bob = (float) Math.sin(time * 1.5f + bobPhase) * 0.06f;
        Vector3f base = new Vector3f(position.x, position.y + bob, position.z);

        // ---- Y-axis billboard ----------------------------------------
        // Only the horizontal direction to the camera is used, so the
        // sprite never tilts up or down no matter where the camera is.
        float dx = cameraPos.x - base.x;
        float dz = cameraPos.z - base.z;

        Vector3f forward = new Vector3f(dx, 0f, dz);
        if (forward.lengthSquared() < 1e-6f) {
            // Camera is (almost) directly above / below: any horizontal
            // facing works visually, just avoid a zero-length vector.
            forward.set(0f, 0f, 1f);
        } else {
            forward.normalize();
        }

        Vector3f up    = new Vector3f(0f, 1f, 0f);
        Vector3f right = new Vector3f(up).cross(forward).normalize();

        float w = width * scale;
        float h = height * scale;

        Matrix4f model = new Matrix4f().identity();
        model.setColumn(0, new Vector4f(right.x * w, right.y * w, right.z * w, 0f));
        model.setColumn(1, new Vector4f(up.x    * h, up.y    * h, up.z    * h, 0f));
        model.setColumn(2, new Vector4f(forward.x,   forward.y,   forward.z,   0f));
        model.setColumn(3, new Vector4f(base.x,      base.y,      base.z,      1f));

        shader.setUniform("uModel", model);
        shader.setUniform("uView", view);
        shader.setUniform("uProjection", projection);
        shader.setUniform2f("uUvOffset", texture.uMin, texture.vMin);
        shader.setUniform2f("uUvScale", texture.uMax - texture.uMin, texture.vMax - texture.vMin);

        texture.bind();
        GL30.glBindVertexArray(sharedVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }
}
