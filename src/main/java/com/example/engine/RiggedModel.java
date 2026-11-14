package com.example.engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Процедурный риг для Meshy-моделей в T-позе.
 * - руки опускаются поворотом вокруг Z от плечевого пивота;
 * - длина руки автоматически тянется так, чтобы кисти были на середине бедра;
 * - ходьба: ноги/руки в противофазе, частота от скорости, покачивание в правильной фазе, idle.
 * Настройки читаются из mesh/MrH/rig.txt (если файла нет — значения по умолчанию).
 */
public final class RiggedModel {

    private static final String CFG_PATH = "mesh/MrH/rig.txt";
    private static final String STEP_SFX = "mp3/steps-10.mp3";
    private static final String[] NAMES = {"Head", "Torso", "ArmL", "ArmR", "LegL", "LegR"};

    // ---- конфиг (доли РОСТА, кроме arm_x / leg_x — это доли ШИРИНЫ) ----
    private static float NECK_FRAC      = 0.85f;
    private static float HIP_FRAC       = 0.45f;
    private static float SHOULDER_FRAC  = 0.80f;
    private static float ARM_CUT_FRAC   = 0.11f;
    private static float LEG_PIVOT_FRAC = 0.05f;
    private static float FINGERTIP_FRAC = 0.38f;  // 0.38 от стоп = середина бедра
    private static float ARM_STRETCH_CFG = 0f;    // 0 = авто
    private static float LEG_SWING      = 0.45f;  // рад, ~26°
    private static float ARM_SWING      = 0.28f;  // рад, ~16°
    private static float BOB_FRAC       = 0.018f;
    private static float STRIDE_FRAC    = 0.45f;
    private static float WALK_SPEED_CFG = 1.5f;
    private static float IDLE_MIN       = 1.0f;
    private static float IDLE_MAX       = 3.5f;
    private static boolean STEP_SOUND   = true;

    private static boolean cfgLoaded = false;
    private static long lastStepMs = 0L;

    private static final class Part {
        int vao, vbo, count;
        String name;
        Vector3f pivot;
        final Matrix4f mat = new Matrix4f();
    }

    private final List<Part> parts = new ArrayList<>();
    private final Texture texture;
    private final Vector3f pos;
    private final float targetHeight;
    private final float armCutFrac;
    private final float armDropAngle;
    private float armStretch = 1f;
    private float scale = 1f;

    private final Random rng = new Random();
    private final Vector3f target = new Vector3f();
    private final Matrix4f world = new Matrix4f();
    private float currentYaw = 0f;
    private float walkSpeed;
    private float walkTimer = 0f;
    private float time = 0f;
    private float idleTimer = 0f;
    private float swingAmp = 0f;
    private int lastStepIdx = 0;

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight) {
        this(objPath, texPath, x, z, targetHeight, -1f, 1.57f);
    }

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight, float armCutFrac) {
        this(objPath, texPath, x, z, targetHeight, armCutFrac, 1.57f);
    }

    public RiggedModel(String objPath, String texPath, float x, float z,
                       float targetHeight, float armCutFrac, float armDropAngle) {
        loadConfig();
        this.targetHeight = targetHeight;
        this.armCutFrac   = armCutFrac < 0f ? ARM_CUT_FRAC : armCutFrac;
        this.armDropAngle = armDropAngle;
        this.walkSpeed    = WALK_SPEED_CFG;
        this.pos = new Vector3f(x, Ground.getHeightAt(x, z), z);
        this.texture = texPath != null ? new Texture(texPath, false, false) : null;

        float modelHeight = loadObj(objPath);
        this.scale = modelHeight > 0.01f ? targetHeight / modelHeight : 1f;

        System.out.println("[RIG] " + new File(objPath).getName()
                + " h=" + modelHeight + " scale=" + scale
                + " cut=" + this.armCutFrac + " drop=" + armDropAngle
                + " armStretch=" + armStretch);

        pickNewTarget();
        updateMatrices();
    }

    // ============================ КОНФИГ ============================

    private static void loadConfig() {
        if (cfgLoaded) return;
        cfgLoaded = true;
        File f = new File(CFG_PATH);
        if (!f.exists()) {
            System.out.println("[RIG] " + CFG_PATH + " нет — использую defaults");
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim();
                float v;
                try { v = Float.parseFloat(line.substring(eq + 1).trim()); }
                catch (NumberFormatException e) { continue; }
                switch (k) {
                    case "neck_y"      -> NECK_FRAC = v;
                    case "hip_y"       -> HIP_FRAC = v;
                    case "shoulder_y"  -> SHOULDER_FRAC = v;
                    case "arm_x"       -> ARM_CUT_FRAC = v;
                    case "leg_x"       -> LEG_PIVOT_FRAC = v;
                    case "fingertip_y" -> FINGERTIP_FRAC = v;
                    case "arm_stretch" -> ARM_STRETCH_CFG = v;
                    case "leg_swing"   -> LEG_SWING = v;
                    case "arm_swing"   -> ARM_SWING = v;
                    case "bob"         -> BOB_FRAC = v;
                    case "stride"      -> STRIDE_FRAC = v;
                    case "walk_speed"  -> WALK_SPEED_CFG = v;
                    case "idle_min"    -> IDLE_MIN = v;
                    case "idle_max"    -> IDLE_MAX = v;
                    case "step_sound"  -> STEP_SOUND = v > 0.5f;
                    default -> { }
                }
            }
            System.out.println("[RIG] конфиг загружен: fingertip_y=" + FINGERTIP_FRAC
                    + " arm_x=" + ARM_CUT_FRAC + " leg_swing=" + LEG_SWING);
        } catch (Exception e) {
            System.err.println("[RIG] ошибка чтения " + CFG_PATH + ": " + e.getMessage());
        }
    }

    // ============================ ЗАГРУЗКА ============================

    private static int idx(String s, int total) {
        int i = Integer.parseInt(s);
        return i < 0 ? total + i : i - 1;
    }

    private float loadObj(String path) {
        List<Float> verts = new ArrayList<>();
        List<Float> uvs   = new ArrayList<>();
        Map<String, List<Integer>> pv = new LinkedHashMap<>();
        Map<String, List<Integer>> pu = new LinkedHashMap<>();
        for (String n : NAMES) { pv.put(n, new ArrayList<>()); pu.put(n, new ArrayList<>()); }

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        // --- проход 1: вершины + bbox ---
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] t = line.split("\\s+");
                if (t[0].equals("v") && t.length >= 4) {
                    float vx = Float.parseFloat(t[1]);
                    float vy = Float.parseFloat(t[2]);
                    float vz = Float.parseFloat(t[3]);
                    verts.add(vx); verts.add(vy); verts.add(vz);
                    minX = Math.min(minX, vx); maxX = Math.max(maxX, vx);
                    minY = Math.min(minY, vy); maxY = Math.max(maxY, vy);
                    minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
                } else if (t[0].equals("vt") && t.length >= 3) {
                    uvs.add(Float.parseFloat(t[1]));
                    uvs.add(Float.parseFloat(t[2]));
                }
            }
        } catch (Exception e) {
            System.err.println("[RIG] не смог прочитать OBJ: " + e.getMessage());
            return 1f;
        }
        if (verts.isEmpty()) return 1f;

        float height = Math.max(maxY - minY, 0.01f);
        float width  = Math.max(maxX - minX, 0.01f);
        float centerX = (minX + maxX) / 2f;
        float centerZ = (minZ + maxZ) / 2f;

        float worldNeckY     = minY + NECK_FRAC     * height;
        float worldHipY      = minY + HIP_FRAC      * height;
        float localNeckY     = NECK_FRAC     * height;
        float localHipY      = HIP_FRAC      * height;
        float localShoulderY = SHOULDER_FRAC * height;

        float armCutX   = width * armCutFrac;
        float legPivotX = width * LEG_PIVOT_FRAC;

        // --- проход 2: раскидываем фейсы по частям ---
        float tipR = -Float.MAX_VALUE, tipL = Float.MAX_VALUE;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] t = line.split("\\s+");
                if (!t[0].equals("f") || t.length < 4) continue;

                List<Integer> fv = new ArrayList<>();
                List<Integer> fu = new ArrayList<>();
                for (int i = 1; i < t.length; i++) {
                    String[] sub = t[i].split("/");
                    fv.add(idx(sub[0], verts.size() / 3));
                    fu.add(sub.length > 1 && !sub[1].isEmpty() ? idx(sub[1], uvs.size() / 2) : 0);
                }

                float cx = 0, cy = 0;
                for (int v : fv) { cx += verts.get(v * 3); cy += verts.get(v * 3 + 1); }
                cx /= fv.size(); cy /= fv.size();

                String part;
                if (cy > worldNeckY)      part = "Head";
                else if (cy > worldHipY)  part = cx < -armCutX ? "ArmL" : (cx > armCutX ? "ArmR" : "Torso");
                else                      part = cx < 0 ? "LegL" : "LegR";

                if (part.equals("ArmR")) for (int v : fv) tipR = Math.max(tipR, verts.get(v * 3));
                if (part.equals("ArmL")) for (int v : fv) tipL = Math.min(tipL, verts.get(v * 3));

                List<Integer> lv = pv.get(part), lu = pu.get(part);
                for (int i = 1; i < fv.size() - 1; i++) {
                    lv.add(fv.get(0));   lu.add(fu.get(0));
                    lv.add(fv.get(i));   lu.add(fu.get(i));
                    lv.add(fv.get(i+1)); lu.add(fu.get(i+1));
                }
            }
        } catch (Exception e) {
            System.err.println("[RIG] ошибка разбора фейсов: " + e.getMessage());
        }

        // --- авто-растяжка рук до середины бедра ---
        float lenR = tipR > -Float.MAX_VALUE / 2 ? (tipR - centerX) - armCutX : 0f;
        float lenL = tipL <  Float.MAX_VALUE / 2 ? (centerX - tipL) - armCutX : 0f;
        float armLen = Math.max(lenR, lenL);
        if (armLen < 1e-4f) armLen = (maxX - centerX) - armCutX;

        float dropSin = (float) Math.abs(Math.sin(armDropAngle));
        if (dropSin < 0.2f) dropSin = 1f;
        float needLen = (localShoulderY - FINGERTIP_FRAC * height) / dropSin;

        if (ARM_STRETCH_CFG > 0.01f) armStretch = ARM_STRETCH_CFG;
        else armStretch = armLen > 1e-4f ? needLen / armLen : 1f;
        armStretch = Math.max(0.5f, Math.min(2.5f, armStretch));

        System.out.println("[RIG] armLen=" + armLen + " need=" + needLen
                + " -> stretch=" + armStretch + " (кисти на " + FINGERTIP_FRAC + " роста)");

        // --- сборка буферов ---
        for (String name : NAMES) {
            List<Integer> vi = pv.get(name);
            List<Integer> ui = pu.get(name);
            if (vi.isEmpty()) { System.out.println("[RIG] WARNING: part " + name + " EMPTY"); continue; }

            Part p = new Part();
            p.name = name;
            p.count = vi.size();
            p.pivot = switch (name) {
                case "Head"  -> new Vector3f(0,          localNeckY,     0);
                case "Torso" -> new Vector3f(0,          localHipY,      0);
                case "ArmL"  -> new Vector3f(-armCutX,   localShoulderY, 0);
                case "ArmR"  -> new Vector3f( armCutX,   localShoulderY, 0);
                case "LegL"  -> new Vector3f(-legPivotX, localHipY,      0);
                case "LegR"  -> new Vector3f( legPivotX, localHipY,      0);
                default      -> new Vector3f(0, 0, 0);
            };

            boolean isArm = name.equals("ArmL") || name.equals("ArmR");
            float[] data = new float[p.count * 5];
            for (int i = 0; i < p.count; i++) {
                int v  = vi.get(i) * 3;
                int uv = ui.get(i) * 2;

                float x = verts.get(v)     - centerX;
                float y = verts.get(v + 1) - minY;
                float z = verts.get(v + 2) - centerZ;

                // тянем руку ТОЛЬКО наружу от плеча — плечевой колпак остаётся на месте
                if (isArm) {
                    float d = x - p.pivot.x;
                    boolean outward = name.equals("ArmR") ? d > 0f : d < 0f;
                    if (outward) x = p.pivot.x + d * armStretch;
                }

                data[i*5]     = x;
                data[i*5 + 1] = y;
                data[i*5 + 2] = z;
                data[i*5 + 3] = uv + 1 < uvs.size() ? uvs.get(uv)     : 0f;
                data[i*5 + 4] = uv + 1 < uvs.size() ? uvs.get(uv + 1) : 0f;
            }

            p.vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(p.vao);
            p.vbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, p.vbo);
            FloatBuffer buf = MemoryUtil.memAllocFloat(data.length);
            try {
                buf.put(data).flip();
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
            } finally {
                MemoryUtil.memFree(buf);
            }
            int stride = 5 * Float.BYTES;
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL30.glBindVertexArray(0);

            parts.add(p);
            System.out.println("[RIG]   " + name + ": " + p.count + " вершин");
        }
        return height;
    }

    // ============================ ЛОГИКА ============================

    private void pickNewTarget() {
        float a = rng.nextFloat() * (float) (Math.PI * 2);
        float r = 5f + rng.nextFloat() * 10f;
        target.set(pos.x + (float) Math.cos(a) * r, 0f, pos.z + (float) Math.sin(a) * r);
    }

    public void update(float dt) {
        time += dt;

        if (idleTimer > 0f) {                       // стоим, анимация гаснет
            idleTimer -= dt;
            swingAmp += (0f - swingAmp) * Math.min(1f, dt * 6f);
            pos.y = Ground.getHeightAt(pos.x, pos.z);
            updateMatrices();
            return;
        }

        float dx = target.x - pos.x, dz = target.z - pos.z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);

        if (dist < 0.5f) {
            idleTimer = IDLE_MIN + rng.nextFloat() * Math.max(0f, IDLE_MAX - IDLE_MIN);
            pickNewTarget();
        } else {
            float step = Math.min(walkSpeed * dt, dist);
            pos.x += dx / dist * step;
            pos.z += dz / dist * step;

            float wantYaw = (float) Math.atan2(dz, dx) - (float) (Math.PI / 2);
            float diff = wantYaw - currentYaw;
            while (diff >  Math.PI) diff -= (float) (Math.PI * 2);
            while (diff < -Math.PI) diff += (float) (Math.PI * 2);
            currentYaw += diff * Math.min(1f, dt * 5f);

            swingAmp += (1f - swingAmp) * Math.min(1f, dt * 6f);

            float stride = Math.max(0.2f, STRIDE_FRAC * targetHeight);
            walkTimer += dt * (float) (Math.PI * walkSpeed / stride);

            // звук шага в момент опоры (пик |sin|)
            int stepIdx = (int) Math.floor((walkTimer - Math.PI / 2) / Math.PI);
            if (stepIdx != lastStepIdx) {
                lastStepIdx = stepIdx;
                long now = System.currentTimeMillis();
                if (STEP_SOUND && swingAmp > 0.5f && now - lastStepMs > 140L) {
                    lastStepMs = now;
                    Audio.playOnce(STEP_SFX, 0.3f);
                }
            }
        }

        pos.y = Ground.getHeightAt(pos.x, pos.z);
        updateMatrices();
    }

    private static void pivotRotate(Part p, float rz, float rx) {
        p.mat.translate(p.pivot);
        if (rz != 0f) p.mat.rotateZ(rz);
        if (rx != 0f) p.mat.rotateX(rx);
        p.mat.translate(-p.pivot.x, -p.pivot.y, -p.pivot.z);
    }

    private void updateMatrices() {
        float s        = (float) Math.sin(walkTimer);
        float legSwing = s * LEG_SWING * swingAmp;
        float armSwing = s * ARM_SWING * swingAmp;
        // cos, а не sin: тело ниже всего при опоре на две ноги
        float bob = Math.abs((float) Math.cos(walkTimer)) * BOB_FRAC * targetHeight * swingAmp;

        world.identity()
             .translate(pos.x, pos.y + bob, pos.z)
             .rotateY(currentYaw)
             .scale(scale);

        for (Part p : parts) {
            p.mat.set(world);
            switch (p.name) {
                case "ArmL" -> pivotRotate(p,  armDropAngle,  armSwing);
                case "ArmR" -> pivotRotate(p, -armDropAngle, -armSwing);
                case "LegL" -> pivotRotate(p, 0f, -legSwing);
                case "LegR" -> pivotRotate(p, 0f,  legSwing);
                case "Head" -> {
                    p.mat.translate(p.pivot)
                         .rotateY((float) Math.sin(time * 1.5f) * 0.25f)
                         .rotateX((float) Math.cos(time * 1.2f) * 0.08f)
                         .translate(-p.pivot.x, -p.pivot.y, -p.pivot.z);
                }
                default -> { }
            }
        }
    }

    // ============================ РЕНДЕР ============================

    public void render(ShaderProgram shader, Matrix4f view, Matrix4f proj) {
        shader.setUniform("uView", view);
        shader.setUniform("uProjection", proj);
        if (texture != null) texture.bind();
        for (Part p : parts) {
            shader.setUniform("uModel", p.mat);
            GL30.glBindVertexArray(p.vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, p.count);
        }
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    /** Вызывать внутри shadow-прохода с уже забинденным shadowShader. */
    public void renderShadow(ShaderProgram shadowShader, Matrix4f lightVP) {
        shadowShader.setUniform("uLightVP", lightVP);
        if (texture != null) texture.bind();
        for (Part p : parts) {
            shadowShader.setUniform("uModel", p.mat);
            GL30.glBindVertexArray(p.vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, p.count);
        }
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    public Vector3f position()   { return pos; }
    public float height()        { return targetHeight; }
    public void setWalkSpeed(float v) { walkSpeed = v; }

    public void destroy() {
        for (Part p : parts) {
            GL15.glDeleteBuffers(p.vbo);
            GL30.glDeleteVertexArrays(p.vao);
        }
        parts.clear();
        if (texture != null) texture.destroy();
    }
}