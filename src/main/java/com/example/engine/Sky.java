package com.example.engine;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Fullscreen gradient skybox. Renders a vertical gradient by drawing a
 * screen-space quad with depth test disabled, so it always fills the
 * background regardless of camera orientation.
 */
public final class Sky {
    private final int vao;
    private final ShaderProgram shader;

    private static final String VERT = """
            #version 330 core
            layout (location = 0) in vec2 aPos;
            out float vY;
            void main() {
                vY = (aPos.y + 1.0) * 0.5;
                gl_Position = vec4(aPos, 1.0, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 330 core
            in float vY;
            out vec4 FragColor;
            uniform vec3 uTop;
            uniform vec3 uBottom;
            void main() {
                FragColor = vec4(mix(uBottom, uTop, vY), 1.0);
            }
            """;

    public Sky() {
        shader = new ShaderProgram(VERT, FRAG);

        float[] verts = {
                -1f, -1f,
                 1f, -1f,
                 1f,  1f,
                 1f,  1f,
                -1f,  1f,
                -1f, -1f,
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
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0L);
        GL20.glEnableVertexAttribArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    public void render() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);

        shader.bind();
        shader.setUniform3f("uTop",    0.47f, 0.72f, 0.95f);
        shader.setUniform3f("uBottom", 0.86f, 0.94f, 0.99f);

        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);
        shader.unbind();

        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    public void destroy() {
        shader.destroy();
        // VAO/VBO cleanup omitted for brevity; process exit reclaims them.
    }
}
