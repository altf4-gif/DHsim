package com.example.engine;

/*
 * ============================ ГРАБЛИ — НЕ ПОВТОРЯТЬ ============================
 * 1. РУКИ РЕЖУТСЯ ТОЛЬКО ВЫШЕ ПОДМЫШКИ (armBandLow). Без этого условия пузо
 *    толстяка и грудь качка шире плоскости реза и уезжают в "руки" -> мясо.
 *    НЕ убирать проверку cy > armBandLow.
 * 2. Линии реза (шея, подмышка, промежность) НАХОДЯТСЯ ПО ПРОФИЛЮ МЕША,
 *    а не константой. Одна константа на все тела не работает.
 * 3. ПИВОТ ноги = ТОЧНО линия реза (legCutY). rotateX не меняет x, поэтому
 *    вершины на линии реза не двигаются вообще -> шов не рвётся. НЕ поднимать
 *    пивот обратно на бедро — вернётся бахрома на шортах.
 * 4. Длина руки ЗАПЕКАЕТСЯ В ВЕРШИНЫ по X, только "наружу" от плеча.
 *    НЕ переносить в matrix.scale() — рвёт плечевой колпак.
 * 5. JOML применяет повороты к вершинам в обратном порядке вызовов:
 *    .rotateX(swing).rotateZ(drop) => сначала опустить, потом мах вперёд-назад.
 * 6. Голова режется ПЕРЕД руками (всё выше шеи — голова), иначе причёска
 *    уезжает в руку и падает на 90 градусов.
 * ===========================================================================
 */

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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class RiggedModel {

    private static final String CFG_PATH = "mesh/MrH/rig.txt";
    private static final String STEP_SFX = "mp3/steps-10.mp3";
    private static final String[] NAMES = {"Head", "Torso", "ArmL", "ArmR", "LegL", "LegR"};

    // ---------------- конфиг ----------------
    private static boolean AUTO_RIG      = true;
    private static float NECK_FRAC      = 0.85f;   // fallback
    private static float HIP_FRAC       = 0.45f;   // fallback
    private static float SHOULDER_FRAC  = 0.80f;   // fallback
    private static float ARM_CUT_FRAC   = 0.13f;   // fallback
    private static float LEG_PIVOT_FRAC = 0.05f;
    private static float FINGERTIP_FRAC = 0.38f;   // куда достают кисти (доля роста от стоп)
    private static float ARM_STRETCH_CFG = 0f;     // 0 = авто
    private static float ARM_BAND_RATIO = 0.55f;   // где заканчивается "полоса рук" в профиле
    private static float ARM_MARGIN     = 1.02f;   // рез чуть снаружи торса
    private static float ARM_BAND_PAD   = 0.02f;   // запас вниз от подмышки
    private static float LEG_GAP        = 0.015f;  // щель между ног (доля ширины)
    private static float LEG_AMP        = 0.45f;
    private static float ARM_AMP        = 0.28f;
    private static float BOB_AMP        = 0.018f;
    private static float STRIDE_FRAC    = 0.45f;
    private static float WALK_SPEED_CFG = 1.5f;
    private static float LEAN_WALK      = 0.06f;
    private static float SWAY_AMP       = 0.02f;
    private static float TURN_RATE      = 4.0f;
    private static float IDLE_MIN       = 1.0f;
    private static float IDLE_MAX       = 3.5f;
    private static float PELVIS_MARGIN  = 0.08f;   // fallback для линии реза ног
    private static float FACING_OFFSET  = 0f;
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
    private final float armCutArg;
    private final float armDropAngle;
    private final float shoulderArg;
    private final float armStretchArg;
    private final float heightWorld;
    private final float seed;

    private float scale = 1f;
    private float armStretch = 1f;
    private float walkSpeed;

    private final Random rng = new Random();
    private final Vector3f target = new Vector3f();
    private float currentYaw = 0f;
    private float time = 0f;
    private float phase;
    private float walkAmount = 0f;
    private float idleTimer;
    private int lastStepIdx = 0;

    private final Matrix4f world = new Matrix4f();
    private final Matrix4f upper = new Matrix4f();

    private final Vector3f hipP  = new Vector3f();
    private final Vector3f neckP = new Vector3f();
    private final Vector3f shLP  = new Vector3f();
    private final Vector3f shRP  = new Vector3f();
    private final Vector3f hipLP = new Vector3f();
    private final Vector3f hipRP = new Vector3f();

    // ---------------- конструкторы (сигнатуры не менять) ----------------

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight) {
        this(objPath, texPath, x, z, targetHeight, -1f, 1.57f, 0.80f, 1.0f, false);
    }

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight, float armCutFrac) {
        this(objPath, texPath, x, z, targetHeight, armCutFrac, 1.57f, 0.80f, 1.0f, false);
    }

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight,
                       float armCutFrac, float armDropAngle) {
        this(objPath, texPath, x, z, targetHeight, armCutFrac, armDropAngle, 0.80f, 1.0f, false);
    }

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight,
                       float armCutFrac, float armDropAngle, float shoulderFrac) {
        this(objPath, texPath, x, z, targetHeight, armCutFrac, armDropAngle, shoulderFrac, 1.0f, false);
    }

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight,
                       float armCutFrac, float armDropAngle, float shoulderFrac, float armStretch) {
        this(objPath, texPath, x, z, targetHeight, armCutFrac, armDropAngle, shoulderFrac, armStretch, false);
    }

    /** dropShelf оставлен для совместимости — полки больше не нужны, игнорируется. */
    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight,
                       float armCutFrac, float armDropAngle, float shoulderFrac,
                       float armStretch, boolean dropShelf) {
        loadConfig();
        this.pos = new Vector3f(x, Ground.getHeightAt(x, z), z);
        this.texture = texPath != null ? new Texture(texPath, false, false) : null;
        this.armCutArg     = armCutFrac;
        this.armDropAngle  = armDropAngle;
        this.shoulderArg   = shoulderFrac;
        this.armStretchArg = armStretch;
        this.heightWorld   = targetHeight;
        this.walkSpeed     = WALK_SPEED_CFG;
        this.seed  = rng.nextFloat() * 10f;
        this.phase = rng.nextFloat() * (float) (Math.PI * 2);
        this.idleTimer = 0.5f + rng.nextFloat() * 2f;

        float modelHeight = loadObj(objPath);
        this.scale = modelHeight > 0.01f ? targetHeight / modelHeight : 1f;

        System.out.println("[RIG] " + new File(objPath).getName()
                + " h=" + modelHeight + " scale=" + scale
                + " drop=" + armDropAngle + " armStretch=" + this.armStretch);

        pickNewTarget();
        updateMatrices();
    }

    // ---------------- конфиг ----------------

    private static void loadConfig() {
        if (cfgLoaded) return;
        cfgLoaded = true;
        File f = new File(CFG_PATH);
        if (!f.exists()) { System.out.println("[RIG] " + CFG_PATH + " нет — defaults"); return; }
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
                    case "auto_rig"       -> AUTO_RIG = v > 0.5f;
                    case "neck_y"         -> NECK_FRAC = v;
                    case "hip_y"          -> HIP_FRAC = v;
                    case "shoulder_y"     -> SHOULDER_FRAC = v;
                    case "arm_x"          -> ARM_CUT_FRAC = v;
                    case "leg_x"          -> LEG_PIVOT_FRAC = v;
                    case "fingertip_y"    -> FINGERTIP_FRAC = v;
                    case "arm_stretch"    -> ARM_STRETCH_CFG = v;
                    case "arm_band_ratio" -> ARM_BAND_RATIO = v;
                    case "arm_margin"     -> ARM_MARGIN = v;
                    case "arm_band_pad"   -> ARM_BAND_PAD = v;
                    case "leg_gap"        -> LEG_GAP = v;
                    case "leg_swing"      -> LEG_AMP = v;
                    case "arm_swing"      -> ARM_AMP = v;
                    case "bob"            -> BOB_AMP = v;
                    case "stride"         -> STRIDE_FRAC = v;
                    case "walk_speed"     -> WALK_SPEED_CFG = v;
                    case "lean"           -> LEAN_WALK = v;
                    case "sway"           -> SWAY_AMP = v;
                    case "turn_rate"      -> TURN_RATE = v;
                    case "idle_min"       -> IDLE_MIN = v;
                    case "idle_max"       -> IDLE_MAX = v;
                    case "pelvis_margin"  -> PELVIS_MARGIN = v;
                    case "facing_offset"  -> FACING_OFFSET = v;
                    case "step_sound"     -> STEP_SOUND = v > 0.5f;
                    default -> { }
                }
            }
            System.out.println("[RIG] config: auto_rig=" + AUTO_RIG + " fingertip_y=" + FINGERTIP_FRAC);
        } catch (Exception e) {
            System.err.println("[RIG] ошибка чтения " + CFG_PATH + ": " + e.getMessage());
        }
    }

    // ---------------- загрузка ----------------

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
            System.err.println("[RIG] Failed to load OBJ: " + e.getMessage());
            return 1f;
        }
        if (verts.isEmpty()) return 1f;

        float height  = Math.max(maxY - minY, 0.01f);
        float width   = Math.max(maxX - minX, 0.01f);
        float centerX = (minX + maxX) / 2f;
        float centerZ = (minZ + maxZ) / 2f;

        // ================= ПРОФИЛЬ МЕША =================
        final int NB = 160;
        float[] halfW  = new float[NB];              // max |x| в слое
        float[] minAbs = new float[NB];              // min |x| в слое (щель между ног)
        int[]   cnt    = new int[NB];
        Arrays.fill(minAbs, Float.MAX_VALUE);
        for (int i = 0; i < verts.size(); i += 3) {
            float ax = Math.abs(verts.get(i) - centerX);
            float y  = verts.get(i + 1) - minY;
            int b = Math.max(0, Math.min(NB - 1, (int) (y / height * NB)));
            halfW[b] = Math.max(halfW[b], ax);
            minAbs[b] = Math.min(minAbs[b], ax);
            cnt[b]++;
        }

        float neckY, shoulderY, armpitY, armCutX, legCutY;

        if (AUTO_RIG) {
            // 1) полоса рук = самая широкая часть ВЫШЕ середины роста
            int peak = NB / 2;
            for (int b = NB / 2; b < NB; b++) if (cnt[b] > 0 && halfW[b] > halfW[peak]) peak = b;
            float maxHalf = Math.max(halfW[peak], 1e-4f);
            float thr = ARM_BAND_RATIO * maxHalf;

            int down = peak;
            while (down > 0 && (cnt[down] == 0 || halfW[down] >= thr)) down--;
            int up = peak;
            while (up < NB - 1 && (cnt[up] == 0 || halfW[up] >= thr)) up++;

            armpitY   = (down + 0.5f) / NB * height;
            float armTopY = (up + 0.5f) / NB * height;
            shoulderY = (armpitY + armTopY) * 0.5f;

            // 2) рез плеча = край торса сразу под подмышкой
            float torsoHalf = 0f;
            int from = Math.max(0, down - (int) (0.10f * NB));
            for (int b = from; b <= down; b++) torsoHalf = Math.max(torsoHalf, halfW[b]);
            armCutX = torsoHalf * ARM_MARGIN;
            if (armCutX < 0.04f * width || armCutX > 0.40f * width) {   // детект не удался
                armCutX = width * (armCutArg > 0f ? armCutArg : ARM_CUT_FRAC);
                shoulderY = (shoulderArg > 0f ? shoulderArg : SHOULDER_FRAC) * height;
                armpitY = shoulderY - 0.10f * height;
                System.out.println("[RIG] auto-detect плеча не удался — fallback");
            }

            // 3) шея = самое узкое место между головой и плечами
            int neckBin = -1;
            float best = Float.MAX_VALUE;
            for (int b = (int) (0.72f * NB); b < (int) (0.94f * NB); b++) {
                if (cnt[b] > 0 && halfW[b] < best) { best = halfW[b]; neckBin = b; }
            }
            neckY = neckBin >= 0 ? (neckBin + 0.5f) / NB * height : NECK_FRAC * height;
            if (neckY <= shoulderY) neckY = Math.min(0.93f * height, shoulderY + 0.06f * height);

            // 4) промежность = самый верхний слой с щелью между ног
            int hipBin = (int) (HIP_FRAC * NB);
            int gapBin = -1;
            for (int b = 1; b <= hipBin; b++)
                if (cnt[b] > 0 && minAbs[b] > LEG_GAP * width) gapBin = b;
            legCutY = gapBin >= 0 ? (gapBin + 1.0f) / NB * height
                                  : (HIP_FRAC - PELVIS_MARGIN) * height;
            legCutY = Math.max(0.22f * height, Math.min(legCutY, HIP_FRAC * height));
        } else {
            neckY     = NECK_FRAC * height;
            shoulderY = (shoulderArg > 0f ? shoulderArg : SHOULDER_FRAC) * height;
            armCutX   = width * (armCutArg > 0f ? armCutArg : ARM_CUT_FRAC);
            armpitY   = shoulderY - 0.10f * height;
            legCutY   = (HIP_FRAC - PELVIS_MARGIN) * height;
        }

        float armBandLow  = armpitY - ARM_BAND_PAD * height;
        float legPivotX   = width * LEG_PIVOT_FRAC;
        float worldNeckY  = minY + neckY;
        float worldBandY  = minY + armBandLow;
        float worldLegY   = minY + legCutY;

        System.out.printf("[RIG] auto: neck=%.3f shoulder=%.3f armpit=%.3f armCut=%.3fW legCut=%.3f%n",
                neckY / height, shoulderY / height, armpitY / height, armCutX / width, legCutY / height);

        // ================= РАЗБОР ГРАНЕЙ =================
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
                if (cy > worldNeckY)                                   part = "Head";
                else if (cy > worldBandY && cx < -armCutX)             part = "ArmL";
                else if (cy > worldBandY && cx >  armCutX)             part = "ArmR";
                else if (cy > worldLegY)                               part = "Torso";
                else                                                   part = cx < 0 ? "LegL" : "LegR";

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
            System.err.println("[RIG] ошибка разбора граней: " + e.getMessage());
        }

        // ================= ДЛИНА РУКИ =================
        float lenR = tipR > -Float.MAX_VALUE / 2 ? (tipR - centerX) - armCutX : 0f;
        float lenL = tipL <  Float.MAX_VALUE / 2 ? (centerX - tipL) - armCutX : 0f;
        float armLen = Math.max(lenR, lenL);
        if (armLen < 1e-4f) armLen = (maxX - centerX) - armCutX;

        float dropSin = (float) Math.abs(Math.sin(armDropAngle));
        if (dropSin < 0.2f) dropSin = 1f;
        float needLen = (shoulderY - FINGERTIP_FRAC * height) / dropSin;

        float manual = (armStretchArg > 0.01f && Math.abs(armStretchArg - 1f) > 1e-3f)
                ? armStretchArg : ARM_STRETCH_CFG;
        armStretch = manual > 0.01f ? manual : (armLen > 1e-4f ? needLen / armLen : 1f);
        armStretch = Math.max(0.6f, Math.min(2.2f, armStretch));

        System.out.println("[RIG] armLen=" + armLen + " need=" + needLen + " -> stretch=" + armStretch);

        hipP.set(0, legCutY, 0);
        neckP.set(0, neckY, 0);
        shLP.set(-armCutX, shoulderY, 0);
        shRP.set( armCutX, shoulderY, 0);
        hipLP.set(-legPivotX, legCutY, 0);   // pivotY == линия реза -> шов не рвётся
        hipRP.set( legPivotX, legCutY, 0);

        // ================= БУФЕРЫ =================
        for (String name : NAMES) {
            List<Integer> vi = pv.get(name);
            List<Integer> ui = pu.get(name);
            if (vi.isEmpty()) { System.out.println("[RIG] WARNING: part " + name + " EMPTY"); continue; }

            Part p = new Part();
            p.name = name;
            p.count = vi.size();
            p.pivot = switch (name) {
                case "Head"  -> new Vector3f(neckP);
                case "Torso" -> new Vector3f(hipP);
                case "ArmL"  -> new Vector3f(shLP);
                case "ArmR"  -> new Vector3f(shRP);
                case "LegL"  -> new Vector3f(hipLP);
                case "LegR"  -> new Vector3f(hipRP);
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

    // ---------------- логика ----------------

    private void pickNewTarget() {
        float a = rng.nextFloat() * (float) (Math.PI * 2);
        float r = 5f + rng.nextFloat() * 10f;
        target.set(pos.x + (float) Math.cos(a) * r, 0f, pos.z + (float) Math.sin(a) * r);
    }

    public void update(float dt) {
        time += dt;

        float dx = target.x - pos.x;
        float dz = target.z - pos.z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        boolean moving = false;

        if (idleTimer > 0f) {
            idleTimer -= dt;
            if (idleTimer <= 0f) pickNewTarget();
        } else if (dist < 0.35f) {
            idleTimer = IDLE_MIN + rng.nextFloat() * Math.max(0f, IDLE_MAX - IDLE_MIN);
        } else {
            moving = true;

            float targetYaw = (float) Math.atan2(dx, dz) + FACING_OFFSET;
            float yawDiff = targetYaw - currentYaw;
            while (yawDiff >  Math.PI) yawDiff -= (float) (Math.PI * 2);
            while (yawDiff < -Math.PI) yawDiff += (float) (Math.PI * 2);
            float turn = yawDiff * Math.min(1f, dt * 6f);
            float maxTurn = TURN_RATE * dt;
            turn = Math.max(-maxTurn, Math.min(maxTurn, turn));
            currentYaw += turn;

            float nearSlow = Math.max(0.25f, Math.min(1f, dist / 1.2f));
            float turnSlow = Math.max(0.25f, 1f - Math.abs(yawDiff) / (float) Math.PI);
            float ease = 0.35f + 0.65f * walkAmount;
            float step = Math.min(walkSpeed * nearSlow * turnSlow * ease * dt, dist);

            pos.x += dx / dist * step;
            pos.z += dz / dist * step;

            float cycleLen = Math.max(0.5f, heightWorld * STRIDE_FRAC * 2f);
            phase += step / cycleLen * (float) (Math.PI * 2);

            int stepIdx = (int) Math.floor((phase - Math.PI / 2) / Math.PI);
            if (stepIdx != lastStepIdx) {
                lastStepIdx = stepIdx;
                long now = System.currentTimeMillis();
                if (STEP_SOUND && walkAmount > 0.5f && now - lastStepMs > 140L) {
                    lastStepMs = now;
                    Audio.playOnce(STEP_SFX, 0.3f);
                }
            }
        }

        walkAmount += ((moving ? 1f : 0f) - walkAmount) * Math.min(1f, dt * 5f);

        float gy = Ground.getHeightAt(pos.x, pos.z);
        pos.y += (gy - pos.y) * Math.min(1f, dt * 12f);

        updateMatrices();
    }

    private static void rot(Part p, Matrix4f base, Vector3f pivot, float rx, float ry, float rz) {
        p.mat.set(base).translate(pivot);
        if (ry != 0f) p.mat.rotateY(ry);
        if (rx != 0f) p.mat.rotateX(rx);
        if (rz != 0f) p.mat.rotateZ(rz);
        p.mat.translate(-pivot.x, -pivot.y, -pivot.z);
    }

    private void updateMatrices() {
        float w = walkAmount;
        float s = (float) Math.sin(phase);

        float bob  = (1f + (float) Math.cos(phase * 2f)) * 0.5f * BOB_AMP * heightWorld * w;
        float sway = s * SWAY_AMP * w;
        float lean = LEAN_WALK * w + 0.012f * (float) Math.sin(time * 1.6f + seed);

        world.identity()
             .translate(pos.x, pos.y + bob, pos.z)
             .rotateY(currentYaw)
             .rotateZ(sway)
             .scale(scale);

        upper.set(world)
             .translate(hipP)
             .rotateX(lean)
             .translate(-hipP.x, -hipP.y, -hipP.z);

        float legL =  s * LEG_AMP * w;
        float legR = -s * LEG_AMP * w;
        float armL = -s * ARM_AMP * w;
        float armR =  s * ARM_AMP * w;
        float drop = Math.max(0f, armDropAngle);

        float lookYaw   = (float) Math.sin(time * 0.40f + seed) * 0.45f * (1f - 0.7f * w);
        float lookPitch = (float) Math.sin(time * 0.27f + seed * 1.7f) * 0.08f * (1f - 0.5f * w) - lean * 0.7f;

        for (Part p : parts) {
            switch (p.name) {
                case "Torso" -> p.mat.set(upper);
                case "Head"  -> rot(p, upper, neckP, lookPitch, lookYaw, 0f);
                case "ArmL"  -> rot(p, upper, shLP, -armL, 0f,  drop);
                case "ArmR"  -> rot(p, upper, shRP, -armR, 0f, -drop);
                case "LegL"  -> rot(p, world, hipLP, -legL, 0f, 0f);
                case "LegR"  -> rot(p, world, hipRP, -legR, 0f, 0f);
                default      -> p.mat.set(world);
            }
        }
    }

    // ---------------- рендер ----------------

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

    public Vector3f position() { return pos; }
    public float height()      { return heightWorld; }
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
