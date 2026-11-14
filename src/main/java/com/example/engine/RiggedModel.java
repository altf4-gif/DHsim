package com.example.engine;

/*
 * ================= ЗАМЕТКИ ДЛЯ СЕБЯ / ГРАБЛИ (не повторять!) =================
 * 1. JOML: повороты к вершинам в ОБРАТНОМ порядке вызовов.
 *    rotateX(swing).rotateZ(drop) = сначала опустить, потом мах ВПЕРЁД-НАЗАД.
 * 2. НЕ резать конечности на сегменты — дырки. Только цельные.
 * 3. НЕ авто-детект подмышки, НЕ дубли граней — призраки и бабочки.
 * 4. Руки по X ПЕРЕД головой по Y (дельты выше шеи).
 * 5. Шов таза ПРЯЧЕМ под шорты: PELVIS_MARGIN=0.08, ширина 2.6.
 * 6. armDropAngle: >1.57 к телу, <1.57 в стороны. ARM_CLEARANCE=0.
 * 7. ГЛАВНОЕ: горизонтальный кусок плеча (полка), оставшийся в торсе, надо
 *    ОПУСТИТЬ, а не растягивать висящую часть. Режим dropShelf: грани полки
 *    (выше 0.70h, |cx| между grabX и armCutX) уходят в части ShL/ShR и
 *    опускаются тем же поворотом, что и рука, вокруг пивота у шеи (grabX).
 *    Пивот полки = grabX (внутренний край), иначе полка уедет ВВЕРХ за голову.
 *    Для женщины/нормиса dropShelf=false — их не трогать.
 * 8. yaw = atan2(dx,dz); спиной — FACING_OFFSET=PI.
 * 9. Фаза шага от расстояния, иначе скользят.
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
import java.io.FileReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class RiggedModel {

    private static final float NECK_FRAC      = 0.85f;
    private static final float HIP_FRAC       = 0.48f;
    private static final float LEG_PIVOT_FRAC = 0.08f;

    private static final float WALK_SPEED    = 1.5f;
    private static final float LEG_AMP       = 0.45f;
    private static final float ARM_AMP       = 0.35f;
    private static final float BOB_AMP       = 0.025f;
    private static final float LEAN_WALK     = 0.06f;
    private static final float TURN_RATE     = 4.0f;
    private static final float ARM_CLEARANCE = 0.0f;
    private static final float PELVIS_MARGIN = 0.08f;

    /** Если модель идёт спиной вперёд — поставь (float) Math.PI. */
    private static final float FACING_OFFSET = 0f;

    private final List<Part> parts = new ArrayList<>();
    private final Texture texture;
    private final Vector3f pos;
    private float scale;
    private final float armCutFrac;
    private final float armDropAngle;
    
    private final float shoulderFrac;
    private final float armStretch;
    private final boolean dropShelf;   // опускать горизонтальную полку плеча (качок/толстяк)

    private final Random rng;
    private final Vector3f target = new Vector3f();
    private float currentYaw = 0f;
    private float time = 0f;
    private float phase;
    private float walkAmount = 0f;
    private float idleTimer;
    private final float seed;
    private float heightWorld;

    private final Vector3f hipP  = new Vector3f();
    private final Vector3f neckP = new Vector3f();
    private final Vector3f shLP  = new Vector3f();
    private final Vector3f shRP  = new Vector3f();
    private final Vector3f hipLP = new Vector3f();
    private final Vector3f hipRP = new Vector3f();
    private final Vector3f shelfLP = new Vector3f();
    private final Vector3f shelfRP = new Vector3f();

    private static class Part {
        int vao, vbo, count;
        String name;
        Vector3f pivot;
    }

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight) {
        this(objPath, texPath, x, z, targetHeight, 0.15f, 1.5f, 0.80f, 1.0f, false);
    }

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight, float armCutFrac) {
        this(objPath, texPath, x, z, targetHeight, armCutFrac, 1.5f, 0.80f, 1.0f, false);
    }

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight, float armCutFrac, float armDropAngle) {
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

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight, 
                       float armCutFrac, float armDropAngle, float shoulderFrac, float armStretch, boolean dropShelf) {
        this.pos = new Vector3f(x, Ground.getHeightAt(x, z), z);
        this.rng = new Random();
        this.texture = texPath != null ? new Texture(texPath, false, false) : null;
        this.armCutFrac = armCutFrac;
        this.armDropAngle = armDropAngle;
        
        this.shoulderFrac = shoulderFrac;
        this.armStretch = armStretch;
        this.dropShelf = dropShelf;
        this.heightWorld = targetHeight;
        this.seed = rng.nextFloat() * 10f;
        this.phase = rng.nextFloat() * (float) (Math.PI * 2);
        this.idleTimer = 0.5f + rng.nextFloat() * 2f;

        float modelHeight = loadObj(objPath);
        
        if (modelHeight > 0.01f) {
            this.scale = targetHeight / modelHeight;
        } else {
            this.scale = 1.0f;
        }
        
        System.out.println("[RIG] " + new java.io.File(objPath).getName() 
                + " h=" + modelHeight + " scale=" + scale + " cut=" + armCutFrac 
                + " drop=" + armDropAngle + " shelf=" + dropShelf);
        
        pickNewTarget();
    }

    private float loadObj(String path) {
        List<Float> verts = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        Map<String, List<Integer>> partVerts = new HashMap<>();
        Map<String, List<Integer>> partUvs = new HashMap<>();
        
        String[] names = {"Head", "Torso", "ArmL", "ArmR", "LegL", "LegR", "ShL", "ShR"};
        for (String n : names) {
            partVerts.put(n, new ArrayList<>());
            partUvs.put(n, new ArrayList<>());
        }

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] tokens = line.split("\\s+");
                if (tokens[0].equals("v")) {
                    float vx = Float.parseFloat(tokens[1]);
                    float vy = Float.parseFloat(tokens[2]);
                    float vz = Float.parseFloat(tokens[3]);
                    verts.add(vx); verts.add(vy); verts.add(vz);
                    
                    minX = Math.min(minX, vx); maxX = Math.max(maxX, vx);
                    minY = Math.min(minY, vy); maxY = Math.max(maxY, vy);
                    minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
                } else if (tokens[0].equals("vt")) {
                    uvs.add(Float.parseFloat(tokens[1]));
                    uvs.add(Float.parseFloat(tokens[2]));
                }
            }
        } catch (Exception e) {
            System.err.println("[RIG] Failed to load OBJ: " + e.getMessage());
            return 1.0f;
        }

        float height = maxY - minY;
        float width = maxX - minX;
        if (height <= 0.01f) height = 1.0f;
        if (width <= 0.01f) width = height;
        
        float centerX = (minX + maxX) / 2f;
        float centerZ = (minZ + maxZ) / 2f;
        
        float worldNeckY = minY + NECK_FRAC * height;
        float worldHipY  = minY + HIP_FRAC * height;
        float worldShelfY = minY + 0.70f * height;   // ниже этой линии полку не трогаем

        float armCutX   = width * armCutFrac;
        float grabX     = armCutX * 0.45f;           // внутренний край полки (пивот у шеи)
        float legPivotX = width * LEG_PIVOT_FRAC;
        
        float localNeckY     = NECK_FRAC * height;
        float localHipY      = HIP_FRAC * height;
        float localShoulderY = shoulderFrac * height;

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] tokens = line.split("\\s+");
                if (tokens[0].equals("f") && tokens.length >= 4) {
                    List<Integer> faceV = new ArrayList<>();
                    List<Integer> faceUV = new ArrayList<>();
                    for (int i = 1; i < tokens.length; i++) {
                        String[] sub = tokens[i].split("/");
                        int vIdx = Integer.parseInt(sub[0]) - 1;
                        int uvIdx = sub.length > 1 && !sub[1].isEmpty() ? Integer.parseInt(sub[1]) - 1 : 0;
                        faceV.add(vIdx);
                        faceUV.add(uvIdx);
                    }
                    
                    float cx = 0, cy = 0;
                    for (int v : faceV) {
                        cx += verts.get(v * 3);
                        cy += verts.get(v * 3 + 1);
                    }
                    cx /= faceV.size(); cy /= faceV.size();

                    String part;
                    if (cy > worldHipY) {
                        if (cx < -armCutX)     part = "ArmL";
                        else if (cx > armCutX) part = "ArmR";
                        else if (dropShelf && cy > worldShelfY && cx < -grabX) part = "ShL";
                        else if (dropShelf && cy > worldShelfY && cx >  grabX) part = "ShR";
                        else if (cy > worldNeckY) part = "Head";
                        else                      part = "Torso";
                    } else {
                        if (cy > worldHipY - PELVIS_MARGIN * height && Math.abs(cx) < legPivotX * 2.6f) {
                            part = "Torso";
                        } else if (cx < 0) {
                            part = "LegL";
                        } else {
                            part = "LegR";
                        }
                    }

                    for (int i = 1; i < faceV.size() - 1; i++) {
                        addVertex(part, partVerts, partUvs, faceV.get(0), faceUV.get(0));
                        addVertex(part, partVerts, partUvs, faceV.get(i), faceUV.get(i));
                        addVertex(part, partVerts, partUvs, faceV.get(i + 1), faceUV.get(i + 1));
                    }
                }
            }
        } catch (Exception e) {
            return height;
        }
        
        hipP.set(0, localHipY, 0);
        neckP.set(0, localNeckY, 0);
        shLP.set(-armCutX, localShoulderY, 0);
        shRP.set( armCutX, localShoulderY, 0);
        hipLP.set(-legPivotX, localHipY, 0);
        hipRP.set( legPivotX, localHipY, 0);
        shelfLP.set(-grabX, localShoulderY, 0);
        shelfRP.set( grabX, localShoulderY, 0);

        for (String name : names) {
            List<Integer> vIdx = partVerts.get(name);
            List<Integer> uvIdx = partUvs.get(name);
            if (vIdx.isEmpty()) {
                System.out.println("[RIG] WARNING: part " + name + " is EMPTY.");
                continue;
            }
            
            Part p = new Part();
            p.name = name;
            p.count = vIdx.size();
            
            p.pivot = switch (name) {
                case "Head"  -> new Vector3f(neckP);
                case "Torso" -> new Vector3f(hipP);
                case "ArmL"  -> new Vector3f(shLP);
                case "ArmR"  -> new Vector3f(shRP);
                case "LegL"  -> new Vector3f(hipLP);
                case "LegR"  -> new Vector3f(hipRP);
                case "ShL"   -> new Vector3f(shelfLP);
                case "ShR"   -> new Vector3f(shelfRP);
                default      -> new Vector3f(0, 0, 0);
            };
            
            float[] data = new float[p.count * 5];
            for (int i = 0; i < p.count; i++) {
                int v = vIdx.get(i) * 3;
                int uv = uvIdx.get(i) * 2;
                data[i*5]   = verts.get(v)     - centerX;
                data[i*5+1] = verts.get(v+1)   - minY;
                data[i*5+2] = verts.get(v+2)   - centerZ;
                data[i*5+3] = uvs.get(uv);
                data[i*5+4] = uvs.get(uv+1);
            }
            
            p.vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(p.vao);
            p.vbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, p.vbo);
            FloatBuffer buf = MemoryUtil.memAllocFloat(data.length);
            buf.put(data).flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
            MemoryUtil.memFree(buf);
            
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 5*Float.BYTES, 0L);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 5*Float.BYTES, 3*Float.BYTES);
            GL20.glEnableVertexAttribArray(1);
            
            parts.add(p);
        }
        
        GL30.glBindVertexArray(0);
        return height;
    }

    private void addVertex(String part, Map<String, List<Integer>> pv, Map<String, List<Integer>> pu, int v, int uv) {
        pv.get(part).add(v);
        pu.get(part).add(uv);
    }

    private void pickNewTarget() {
        float a = rng.nextFloat() * (float) (Math.PI * 2);
        float r = 5f + rng.nextFloat() * 10f;
        target.set(pos.x + (float) Math.cos(a) * r, 0, pos.z + (float) Math.sin(a) * r);
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
            idleTimer = 1.5f + rng.nextFloat() * 3.5f;
        } else {
            moving = true;

            float targetYaw = (float) Math.atan2(dx, dz) + FACING_OFFSET;
            float yawDiff = targetYaw - currentYaw;
            while (yawDiff > Math.PI)  yawDiff -= (float) (Math.PI * 2);
            while (yawDiff < -Math.PI) yawDiff += (float) (Math.PI * 2);
            float turn = yawDiff * Math.min(1f, dt * 6f);
            float maxTurn = TURN_RATE * dt;
            turn = Math.max(-maxTurn, Math.min(maxTurn, turn));
            currentYaw += turn;

            float nearSlow = Math.max(0.25f, Math.min(1f, dist / 1.2f));
            float turnSlow = Math.max(0.25f, 1f - Math.abs(yawDiff) / (float) Math.PI);
            float ease = 0.35f + 0.65f * walkAmount;
            float step = Math.min(WALK_SPEED * nearSlow * turnSlow * ease * dt, dist);

            pos.x += dx / dist * step;
            pos.z += dz / dist * step;

            float cycleLen = Math.max(0.5f, heightWorld * 0.9f);
            phase += step / cycleLen * (float) (Math.PI * 2);
        }

        walkAmount += ((moving ? 1f : 0f) - walkAmount) * Math.min(1f, dt * 5f);

        float gy = Ground.getHeightAt(pos.x, pos.z);
        pos.y += (gy - pos.y) * Math.min(1f, dt * 12f);
    }

    public void render(ShaderProgram shader, Matrix4f view, Matrix4f proj) {
        float w = walkAmount;
        float s = (float) Math.sin(phase);

        float bob  = (1f + (float) Math.cos(phase * 2f)) * 0.5f * BOB_AMP * heightWorld * w;
        float sway = s * 0.02f * w;
        float lean = LEAN_WALK * w + 0.012f * (float) Math.sin(time * 1.6f + seed);

        Matrix4f world = new Matrix4f()
                .translate(pos.x, pos.y + bob, pos.z)
                .rotateY(currentYaw)
                .rotateZ(sway)
                .scale(scale);

        Matrix4f upper = new Matrix4f(world)
                .translate(hipP.x, hipP.y, hipP.z)
                .rotateX(lean)
                .translate(-hipP.x, -hipP.y, -hipP.z);

        float legL =  s * LEG_AMP * w;
        float legR = -s * LEG_AMP * w;
        float armL = -s * ARM_AMP * w;
        float armR =  s * ARM_AMP * w;

        float drop = Math.max(0f, armDropAngle - ARM_CLEARANCE);

        Matrix4f armLMat = new Matrix4f(upper)
                .translate(shLP.x, shLP.y, shLP.z)
                .rotateX(-armL)
                .scale(1f, armStretch, 1f)
                .rotateZ(drop)
                .translate(-shLP.x, -shLP.y, -shLP.z);
        Matrix4f armRMat = new Matrix4f(upper)
                .translate(shRP.x, shRP.y, shRP.z)
                .rotateX(-armR)
                .scale(1f, armStretch, 1f)
                .rotateZ(-drop)
                .translate(-shRP.x, -shRP.y, -shRP.z);

        // ПОЛКА ПЛЕЧА: опускается тем же поворотом, что и рука, вокруг пивота у шеи.
        // Без растяжения — просто опускаем то, что торчало горизонтально.
        Matrix4f shelfLMat = new Matrix4f(upper)
                .translate(shelfLP.x, shelfLP.y, shelfLP.z)
                .rotateX(-armL)
                .rotateZ(drop)
                .translate(-shelfLP.x, -shelfLP.y, -shelfLP.z);
        Matrix4f shelfRMat = new Matrix4f(upper)
                .translate(shelfRP.x, shelfRP.y, shelfRP.z)
                .rotateX(-armR)
                .rotateZ(-drop)
                .translate(-shelfRP.x, -shelfRP.y, -shelfRP.z);

        Matrix4f legLMat = new Matrix4f(world)
                .translate(hipLP.x, hipLP.y, hipLP.z)
                .rotateX(-legL)
                .translate(-hipLP.x, -hipLP.y, -hipLP.z);
        Matrix4f legRMat = new Matrix4f(world)
                .translate(hipRP.x, hipRP.y, hipRP.z)
                .rotateX(-legR)
                .translate(-hipRP.x, -hipRP.y, -hipRP.z);

        float lookYaw   = (float) Math.sin(time * 0.40f + seed) * 0.45f * (1f - 0.7f * w);
        float lookPitch = (float) Math.sin(time * 0.27f + seed * 1.7f) * 0.08f * (1f - 0.5f * w) - lean * 0.7f;
        Matrix4f headMat = new Matrix4f(upper)
                .translate(neckP.x, neckP.y, neckP.z)
                .rotateY(lookYaw)
                .rotateX(lookPitch)
                .translate(-neckP.x, -neckP.y, -neckP.z);

        for (Part p : parts) {
            Matrix4f partMat = switch (p.name) {
                case "Torso" -> upper;
                case "Head"  -> headMat;
                case "ArmL"  -> armLMat;
                case "ArmR"  -> armRMat;
                case "LegL"  -> legLMat;
                case "LegR"  -> legRMat;
                case "ShL"   -> shelfLMat;
                case "ShR"   -> shelfRMat;
                default      -> world;
            };

            shader.setUniform("uModel", partMat);
            shader.setUniform("uView", view);
            shader.setUniform("uProjection", proj);

            if (texture != null) texture.bind();
            GL30.glBindVertexArray(p.vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, p.count);
            GL30.glBindVertexArray(0);
        }
    }

    /** Вызывать внутри shadow-прохода с уже забинденным shadowShader. */
    public void renderShadow(ShaderProgram shadowShader, Matrix4f lightVP) {
        shadowShader.setUniform("uLightVP", lightVP);

        float w = walkAmount;
        float s = (float) Math.sin(phase);

        float bob  = (1f + (float) Math.cos(phase * 2f)) * 0.5f * BOB_AMP * heightWorld * w;
        float sway = s * 0.02f * w;
        float lean = LEAN_WALK * w + 0.012f * (float) Math.sin(time * 1.6f + seed);

        Matrix4f world = new Matrix4f()
                .translate(pos.x, pos.y + bob, pos.z)
                .rotateY(currentYaw)
                .rotateZ(sway)
                .scale(scale);

        Matrix4f upper = new Matrix4f(world)
                .translate(hipP.x, hipP.y, hipP.z)
                .rotateX(lean)
                .translate(-hipP.x, -hipP.y, -hipP.z);

        float legL =  s * LEG_AMP * w;
        float legR = -s * LEG_AMP * w;
        float armL = -s * ARM_AMP * w;
        float armR =  s * ARM_AMP * w;

        float drop = Math.max(0f, armDropAngle - ARM_CLEARANCE);

        Matrix4f armLMat = new Matrix4f(upper)
                .translate(shLP.x, shLP.y, shLP.z)
                .rotateX(-armL)
                .scale(1f, armStretch, 1f)
                .rotateZ(drop)
                .translate(-shLP.x, -shLP.y, -shLP.z);
        Matrix4f armRMat = new Matrix4f(upper)
                .translate(shRP.x, shRP.y, shRP.z)
                .rotateX(-armR)
                .scale(1f, armStretch, 1f)
                .rotateZ(-drop)
                .translate(-shRP.x, -shRP.y, -shRP.z);

        Matrix4f shelfLMat = new Matrix4f(upper)
                .translate(shelfLP.x, shelfLP.y, shelfLP.z)
                .rotateX(-armL)
                .rotateZ(drop)
                .translate(-shelfLP.x, -shelfLP.y, -shelfLP.z);
        Matrix4f shelfRMat = new Matrix4f(upper)
                .translate(shelfRP.x, shelfRP.y, shelfRP.z)
                .rotateX(-armR)
                .rotateZ(-drop)
                .translate(-shelfRP.x, -shelfRP.y, -shelfRP.z);

        Matrix4f legLMat = new Matrix4f(world)
                .translate(hipLP.x, hipLP.y, hipLP.z)
                .rotateX(-legL)
                .translate(-hipLP.x, -hipLP.y, -hipLP.z);
        Matrix4f legRMat = new Matrix4f(world)
                .translate(hipRP.x, hipRP.y, hipRP.z)
                .rotateX(-legR)
                .translate(-hipRP.x, -hipRP.y, -hipRP.z);

        float lookYaw   = (float) Math.sin(time * 0.40f + seed) * 0.45f * (1f - 0.7f * w);
        float lookPitch = (float) Math.sin(time * 0.27f + seed * 1.7f) * 0.08f * (1f - 0.5f * w) - lean * 0.7f;
        Matrix4f headMat = new Matrix4f(upper)
                .translate(neckP.x, neckP.y, neckP.z)
                .rotateY(lookYaw)
                .rotateX(lookPitch)
                .translate(-neckP.x, -neckP.y, -neckP.z);

        if (texture != null) texture.bind();
        for (Part p : parts) {
            Matrix4f partMat = switch (p.name) {
                case "Torso" -> upper;
                case "Head"  -> headMat;
                case "ArmL"  -> armLMat;
                case "ArmR"  -> armRMat;
                case "LegL"  -> legLMat;
                case "LegR"  -> legRMat;
                case "ShL"   -> shelfLMat;
                case "ShR"   -> shelfRMat;
                default      -> world;
            };

            shadowShader.setUniform("uModel", partMat);
            GL30.glBindVertexArray(p.vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, p.count);
        }
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    public void destroy() {
        for (Part p : parts) {
            GL15.glDeleteBuffers(p.vbo);
            GL30.glDeleteVertexArrays(p.vao);
        }
        if (texture != null) texture.destroy();
    }
}
