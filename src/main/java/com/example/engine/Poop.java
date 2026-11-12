package com.example.engine;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Procedural 3D pile of poop — a loving nod to The Binding of Isaac.
 *
 * No texture files are required: the mesh is generated from a parametric
 * surface (spiral-ridged cone that tapers to a lean tip) and the shading
 * is baked into per-vertex colours with a simple directional light.
 *
 * Five unique variants are generated once at startup. Every scattered
 * instance picks a random variant, yaw and (occasionally huge) scale.
 */
public final class Poop {

    private static final String VERT = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aColor;

            uniform mat4 uModel;
            uniform mat4 uView;
            uniform mat4 uProjection;

            out vec3 vColor;

            void main() {
                vColor = aColor;
                gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 330 core
            in vec3 vColor;
            out vec4 FragColor;
            void main() {
                FragColor = vec4(vColor, 1.0);
            }
            """;

    private static final int NUM_VARIANTS = 5;

    private static ShaderProgram shader;
    private static Variant[] variants;

    /** Ground position — used by the AI to find and eat it. */
    public final float x;
    public final float z;

    /** Set to true once a teenager has finished munching on this pile. */
    public boolean eaten;

    private final Variant variant;
    private final Matrix4f model;

    private Poop(Variant variant, Matrix4f model, float x, float z) {
        this.variant = variant;
        this.model = model;
        this.x = x;
        this.z = z;
    }

    /** Builds the shared shader and the pool of unique poop meshes. Call once. */
    public static void init() {
        if (shader != null) return;
        shader = new ShaderProgram(VERT, FRAG);
        variants = new Variant[NUM_VARIANTS];
        for (int i = 0; i < NUM_VARIANTS; i++) {
            variants[i] = Variant.build(new Random(0xC0FFEE + i * 7919L));
        }
    }

    /** Creates one instance with a random variant, yaw, and scale. */
    public static Poop random(float x, float z, Random rng) {
        Variant v = variants[rng.nextInt(NUM_VARIANTS)];
        float yaw = rng.nextFloat() * (float) (Math.PI * 2);
        float scale = 0.6f + rng.nextFloat() * 0.7f;
        // Rare "boss poop" so it is visible from far away
        if (rng.nextFloat() < 0.08f) scale *= 2.1f;
        Matrix4f m = new Matrix4f().translate(x, 0f, z).rotateY(yaw).scale(scale);
        return new Poop(v, m, x, z);
    }

    public static void begin(Matrix4f view, Matrix4f projection) {
        shader.bind();
        shader.setUniform("uView", view);
        shader.setUniform("uProjection", projection);
    }

    public void render() {
        shader.setUniform("uModel", model);
        GL30.glBindVertexArray(variant.vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, variant.count);
        GL30.glBindVertexArray(0);
    }

    public static void end() {
        shader.unbind();
    }

    public static void destroyAll() {
        if (shader != null) {
            shader.destroy();
            shader = null;
        }
        if (variants != null) {
            for (Variant v : variants) v.destroy();
            variants = null;
        }
    }

    // ------------------------------------------------------------------
    //  One generated mesh (VAO + VBO) shared by many instances.
    // ------------------------------------------------------------------
    private static final class Variant {
        final int vao;
        final int vbo;
        final int count;

        private Variant(int vao, int vbo, int count) {
            this.vao = vao;
            this.vbo = vbo;
            this.count = count;
        }

        void destroy() {
            GL15.glDeleteBuffers(vbo);
            GL30.glDeleteVertexArrays(vao);
        }

        static Variant build(Random rng) {
            final int RINGS = 28;
            final int SIDES = 32;

            // ---- shape parameters (randomized per variant) ----
            float height     = 1.0f;
            float baseRadius = 0.42f + rng.nextFloat() * 0.08f;
            float lean       = 0.06f + rng.nextFloat() * 0.07f;
            float leanAngle  = rng.nextFloat() * (float) (Math.PI * 2);
            float twist      = (float) (Math.PI * (1.0f + rng.nextFloat() * 0.7f));
            float ridgeAmp   = 0.10f + rng.nextFloat() * 0.07f;
            int   ridgeCount = 5 + rng.nextInt(3);
            float phase      = rng.nextFloat() * (float) (Math.PI * 2);
            float tipPinch   = 0.55f + rng.nextFloat() * 0.20f;

            // ---- base colour (brown with slight variation) ----
            float cr = 0.32f + rng.nextFloat() * 0.06f;
            float cg = 0.20f + rng.nextFloat() * 0.05f;
            float cb = 0.10f + rng.nextFloat() * 0.03f;

            // ---- 1. sample the surface into a grid ----
            float[][] px = new float[RINGS + 1][SIDES];
            float[][] py = new float[RINGS + 1][SIDES];
            float[][] pz = new float[RINGS + 1][SIDES];

            for (int i = 0; i <= RINGS; i++) {
                float t = (float) i / RINGS;

                float rBase = baseRadius * (float) Math.pow(1.0f - t, 0.72f);
                rBase *= (1.0f - t * tipPinch);   // squeeze the tip to a point

                float ox = (float) Math.cos(leanAngle) * lean * t * t;
                float oz = (float) Math.sin(leanAngle) * lean * t * t;
                float y  = t * height;

                for (int j = 0; j < SIDES; j++) {
                    float angle  = (float) (2.0 * Math.PI * j / SIDES);
                    float spiral = angle * ridgeCount + twist * t + phase;
                    float r = rBase * (1.0f + ridgeAmp * (float) Math.sin(spiral));

                    px[i][j] = ox + r * (float) Math.cos(angle);
                    py[i][j] = y;
                    pz[i][j] = oz + r * (float) Math.sin(angle);
                }
            }

            // ---- 2. smooth per-vertex normals (finite differences) ----
            float[][] vnx = new float[RINGS + 1][SIDES];
            float[][] vny = new float[RINGS + 1][SIDES];
            float[][] vnz = new float[RINGS + 1][SIDES];

            for (int i = 0; i <= RINGS; i++) {
                int im = Math.max(i - 1, 0);
                int ip = Math.min(i + 1, RINGS);
                for (int j = 0; j < SIDES; j++) {
                    int jm = (j - 1 + SIDES) % SIDES;
                    int jp = (j + 1) % SIDES;

                    float ttx = px[ip][j] - px[im][j];
                    float tty = py[ip][j] - py[im][j];
                    float ttz = pz[ip][j] - pz[im][j];

                    float atx = px[i][jp] - px[i][jm];
                    float aty = py[i][jp] - py[i][jm];
                    float atz = pz[i][jp] - pz[i][jm];

                    float nx = tty * atz - ttz * aty;
                    float ny = ttz * atx - ttx * atz;
                    float nz = ttx * aty - tty * atx;

                    // Make sure the normal points outward (away from the Y axis)
                    if (nx * px[i][j] + nz * pz[i][j] < 0f) {
                        nx = -nx; ny = -ny; nz = -nz;
                    }

                    float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (nl < 1e-6f) { nx = 0f; ny = 1f; nz = 0f; nl = 1f; }

                    vnx[i][j] = nx / nl;
                    vny[i][j] = ny / nl;
                    vnz[i][j] = nz / nl;
                }
            }

            // ---- 3. bake a simple directional light into vertex colours ----
            float lx = -0.35f, ly = 0.85f, lz = 0.35f;
            float ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
            lx /= ll; ly /= ll; lz /= ll;

            List<Float> out = new ArrayList<>(RINGS * SIDES * 36);

            // side quads
            for (int i = 0; i < RINGS; i++) {
                for (int j = 0; j < SIDES; j++) {
                    int j2 = (j + 1) % SIDES;
                    addShaded(out, i, j,  px, py, pz, vnx, vny, vnz, cr, cg, cb, lx, ly, lz);
                    addShaded(out, i, j2, px, py, pz, vnx, vny, vnz, cr, cg, cb, lx, ly, lz);
                    addShaded(out, i + 1, j, px, py, pz, vnx, vny, vnz, cr, cg, cb, lx, ly, lz);

                    addShaded(out, i, j2, px, py, pz, vnx, vny, vnz, cr, cg, cb, lx, ly, lz);
                    addShaded(out, i + 1, j2, px, py, pz, vnx, vny, vnz, cr, cg, cb, lx, ly, lz);
                    addShaded(out, i + 1, j, px, py, pz, vnx, vny, vnz, cr, cg, cb, lx, ly, lz);
                }
            }

            // bottom cap — dark, just so you cannot see inside from below
            float capShade = 0.35f;
            for (int j = 0; j < SIDES; j++) {
                int j2 = (j + 1) % SIDES;
                addFlat(out, 0f, 0f, 0f,
                        cr * capShade, cg * capShade, cb * capShade);
                addFlat(out, px[0][j2], py[0][j2], pz[0][j2],
                        cr * capShade, cg * capShade, cb * capShade);
                addFlat(out, px[0][j],  py[0][j],  pz[0][j],
                        cr * capShade, cg * capShade, cb * capShade);
            }

            // ---- 4. upload to the GPU ----
            float[] data = new float[out.size()];
            for (int i = 0; i < data.length; i++) data[i] = out.get(i);

            int vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            int vbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

            FloatBuffer buf = MemoryUtil.memAllocFloat(data.length);
            try {
                buf.put(data).flip();
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
            } finally {
                MemoryUtil.memFree(buf);
            }

            final int stride = 6 * Float.BYTES;
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL30.glBindVertexArray(0);

            return new Variant(vao, vbo, data.length / 6);
        }

        private static void addShaded(List<Float> out, int i, int j,
                                      float[][] px, float[][] py, float[][] pz,
                                      float[][] vnx, float[][] vny, float[][] vnz,
                                      float cr, float cg, float cb,
                                      float lx, float ly, float lz) {
            float x = px[i][j], y = py[i][j], z = pz[i][j];
            float nx = vnx[i][j], ny = vny[i][j], nz = vnz[i][j];

            float lambert = Math.max(0f, nx * lx + ny * ly + nz * lz);
            float shade = 0.40f + 0.75f * lambert;
            float tint  = 1.0f + 0.25f * y;   // slightly lighter near the tip

            out.add(x); out.add(y); out.add(z);
            out.add(clamp(cr * shade * tint));
            out.add(clamp(cg * shade * tint));
            out.add(clamp(cb * shade * tint));
        }

        private static void addFlat(List<Float> out,
                                    float x, float y, float z,
                                    float r, float g, float b) {
            out.add(x); out.add(y); out.add(z);
            out.add(r); out.add(g); out.add(b);
        }

        private static float clamp(float v) {
            return v < 0f ? 0f : (v > 1f ? 1f : v);
        }
    }
}
