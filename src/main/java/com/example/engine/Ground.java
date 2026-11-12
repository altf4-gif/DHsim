package com.example.engine;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Infinite-looking checkerboard grass plane in the XZ plane at y = 0.
 */
public final class Ground {
    private final int vao;
    private final ShaderProgram shader;

    private static final String VERT = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            uniform mat4 uView;
            uniform mat4 uProjection;
            out vec3 vWorld;
            void main() {
                vWorld = aPos;
                gl_Position = uProjection * uView * vec4(aPos, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 330 core
            in vec3 vWorld;
            out vec4 FragColor;
            void main() {
                float c = mod(floor(vWorld.x) + floor(vWorld.z), 2.0);
                vec3 a = vec3(0.30, 0.52, 0.27);
                vec3 b = vec3(0.40, 0.63, 0.35);
                FragColor = vec4(mix(a, b, c), 1.0);
            }
            """;

    public Ground() {
        shader = new ShaderProgram(VERT, FRAG);

        float size = 200f;
        float[] verts = {
                -size, 0f, -size,
                 size, 0f, -size,
                 size, 0f,  size,
                 size, 0f,  size,
                -size, 0f,  size,
                -size, 0f, -size,
        };

        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        int vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        FloatBuffer buf = MemoryUtil.memAllocFloat(verts.length);
        try {
            buf.put(verts).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(buf);
        }
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0L);
        GL20.glEnableVertexAttribArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    public void render(Matrix4f view, Matrix4f projection) {
        shader.bind();
        shader.setUniform("uView", view);
        shader.setUniform("uProjection", projection);
        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);
        shader.unbind();
    }

    public void destroy() {
        shader.destroy();
    }
}
