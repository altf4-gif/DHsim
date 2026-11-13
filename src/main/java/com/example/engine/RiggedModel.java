package com.example.engine;

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

/**
 * Procedurally rigged model.
 * Arms are brought down from T-pose by `armDropAngle` radians around Z.
 * Legs rotate around X to simulate walking.
 */
public final class RiggedModel {

    private static final float NECK_FRAC     = 0.85f;
    private static final float HIP_FRAC      = 0.48f;
    private static final float SHOULDER_FRAC = 0.80f;
    private static final float LEG_PIVOT_FRAC = 0.08f;

    private final List<Part> parts = new ArrayList<>();
    private final Texture texture;
    private final Vector3f pos;
    private float scale;
    private final float armCutFrac;
    private final float armDropAngle;
    
    private final Random rng;
    private final Vector3f target = new Vector3f();
    private float currentYaw = 0f;
    private final float walkSpeed = 1.5f;
    private float walkTimer = 0f;
    private float time = 0f;

    private static class Part {
        int vao, vbo, count;
        String name;
        Vector3f pivot;
    }

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight) {
        this(objPath, texPath, x, z, targetHeight, 0.15f, 1.5f);
    }

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight, float armCutFrac) {
        this(objPath, texPath, x, z, targetHeight, armCutFrac, 1.5f);
    }

    public RiggedModel(String objPath, String texPath, float x, float z, float targetHeight, float armCutFrac, float armDropAngle) {
        this.pos = new Vector3f(x, Ground.getHeightAt(x, z), z);
        this.rng = new Random();
        this.texture = texPath != null ? new Texture(texPath, false, false) : null;
        this.armCutFrac = armCutFrac;
        this.armDropAngle = armDropAngle;
        
        float modelHeight = loadObj(objPath);
        
        if (modelHeight > 0.01f) {
            this.scale = targetHeight / modelHeight;
        } else {
            this.scale = 1.0f;
        }
        
        System.out.println("[RIG] " + new java.io.File(objPath).getName()
                + " h=" + modelHeight + " scale=" + scale + " cut=" + armCutFrac + " drop=" + armDropAngle);
        
        pickNewTarget();
    }

    private float loadObj(String path) {
        List<Float> verts = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        Map<String, List<Integer>> partVerts = new HashMap<>();
        Map<String, List<Integer>> partUvs = new HashMap<>();
        
        String[] names = {"Head", "Torso", "ArmL", "ArmR", "LegL", "LegR"};
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
        
        float worldNeckY     = minY + NECK_FRAC     * height;
        float worldHipY      = minY + HIP_FRAC      * height;
        float worldShoulderY = minY + SHOULDER_FRAC * height;
        
        float armCutX   = width * armCutFrac;
        float legPivotX = width * LEG_PIVOT_FRAC;
        
        float localNeckY     = NECK_FRAC     * height;
        float localHipY      = HIP_FRAC      * height;
        float localShoulderY = SHOULDER_FRAC * height;

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] tokens = line.split("\\s+");
                if (tokens[0].equals("f")) {
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
                    if (cy > worldNeckY) {
                        part = "Head";
                    } else if (cy > worldHipY) {
                        if (cx < -armCutX) part = "ArmL";
                        else if (cx > armCutX) part = "ArmR";
                        else part = "Torso";
                    } else {
                        if (cx < 0) part = "LegL";
                        else part = "LegR";
                    }
                    
                    for (int i = 1; i < faceV.size() - 1; i++) {
                        addVertex(part, partVerts, partUvs, faceV.get(0), faceUV.get(0));
                        addVertex(part, partVerts, partUvs, faceV.get(i), faceUV.get(i));
                        addVertex(part, partVerts, partUvs, faceV.get(i+1), faceUV.get(i+1));
                    }
                }
            }
        } catch (Exception e) {
            return height;
        }
        
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
                case "Head"  -> new Vector3f(0,          localNeckY,     0);
                case "Torso" -> new Vector3f(0,          localHipY,      0);
                case "ArmL"  -> new Vector3f(-armCutX,   localShoulderY, 0);
                case "ArmR"  -> new Vector3f( armCutX,   localShoulderY, 0);
                case "LegL"  -> new Vector3f(-legPivotX, localHipY,      0);
                case "LegR"  -> new Vector3f( legPivotX, localHipY,      0);
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

        if (dist < 0.5f) {
            pickNewTarget();
            walkTimer += dt * 3f; 
        } else {
            float step = Math.min(walkSpeed * dt, dist);
            pos.x += (dx / dist) * step;
            pos.z += (dz / dist) * step;
            
            float targetYaw = (float) Math.atan2(dz, dx) - (float) (Math.PI / 2);
            float yawDiff = targetYaw - currentYaw;
            while (yawDiff > Math.PI) yawDiff -= Math.PI * 2;
            while (yawDiff < -Math.PI) yawDiff += Math.PI * 2;
            currentYaw += yawDiff * Math.min(1f, dt * 5f);

            walkTimer += dt * 8f;
        }
        pos.y = Ground.getHeightAt(pos.x, pos.z);
    }

    public void render(ShaderProgram shader, Matrix4f view, Matrix4f proj) {
        float bob = (float) Math.abs(Math.sin(walkTimer)) * 0.08f;
        Matrix4f world = new Matrix4f().translate(pos.x, pos.y + bob, pos.z).rotateY(currentYaw).scale(scale);
        
        float swing = (float) Math.sin(walkTimer) * 0.8f;
        
        for (Part p : parts) {
            Matrix4f partMat = new Matrix4f(world);
            
            switch (p.name) {
                case "ArmL" -> {
                    partMat.translate(p.pivot);
                    partMat.rotateZ(armDropAngle);
                    partMat.rotateX(swing);
                    partMat.translate(-p.pivot.x, -p.pivot.y, -p.pivot.z);
                }
                case "ArmR" -> {
                    partMat.translate(p.pivot);
                    partMat.rotateZ(-armDropAngle);
                    partMat.rotateX(-swing);
                    partMat.translate(-p.pivot.x, -p.pivot.y, -p.pivot.z);
                }
                case "LegL" -> {
                    partMat.translate(p.pivot);
                    partMat.rotateX(-swing);
                    partMat.translate(-p.pivot.x, -p.pivot.y, -p.pivot.z);
                }
                case "LegR" -> {
                    partMat.translate(p.pivot);
                    partMat.rotateX(swing);
                    partMat.translate(-p.pivot.x, -p.pivot.y, -p.pivot.z);
                }
                case "Head" -> {
                    partMat.translate(p.pivot);
                    partMat.rotateY((float) Math.sin(time * 1.5f) * 0.3f);
                    partMat.rotateX((float) Math.cos(time * 1.2f) * 0.1f);
                    partMat.translate(-p.pivot.x, -p.pivot.y, -p.pivot.z);
                }
                default -> {}
            }
            
            shader.setUniform("uModel", partMat);
            shader.setUniform("uView", view);
            shader.setUniform("uProjection", proj);
            
            if (texture != null) texture.bind();
            GL30.glBindVertexArray(p.vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, p.count);
            GL30.glBindVertexArray(0);
        }
    }

    public void destroy() {
        for (Part p : parts) {
            GL15.glDeleteBuffers(p.vbo);
            GL30.glDeleteVertexArrays(p.vao);
        }
        if (texture != null) texture.destroy();
    }
}
