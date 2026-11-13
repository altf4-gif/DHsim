package com.example.engine;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tracks trees, tribes and houses. Every house build reserves a flatten pad
 * so the terrain below it becomes flat.
 */
public final class WorldManager {

    public static final class TreeData {
        public final HouseInstance instance;
        public final float x, z;
        public final float originalScale;
        public float currentScale;
        public boolean depleted;
        public boolean reserved = false;
        public float yaw;
        public float yOffset;

        public TreeData(HouseInstance inst, float x, float z, float yaw,
                        float originalScale, float yOffset) {
            this.instance = inst;
            this.x = x; this.z = z;
            this.yaw = yaw;
            this.yOffset = yOffset;
            this.originalScale = originalScale;
            this.currentScale = originalScale;
        }
    }

    public final List<TreeData> trees = new ArrayList<>();
    public final List<Tribe> tribes = new ArrayList<>();

    public ObjModel protoWalls, protoRoof, protoDoor, protoWindows;
    public float protoScale = 1f;

    /** Called by Engine whenever the terrain needs a mesh rebuild. */
    public Runnable onTerrainDirty;

    private final Random rng = new Random(1337);

    public void registerTrees(List<TreeData> list) {
        trees.addAll(list);
    }

    public static float dist2D(float ax, float az, float bx, float bz) {
        float dx = ax - bx, dz = az - bz;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    public TreeData findNearestTree(float fromX, float fromZ, float maxDist) {
        TreeData best = null;
        float bestD = maxDist;
        for (TreeData t : trees) {
            if (t.depleted || t.reserved) continue;
            float d = dist2D(fromX, fromZ, t.x, t.z);
            if (d < bestD) { bestD = d; best = t; }
        }
        return best;
    }

    public void chopTree(TreeData t, float progress) {
        float s = t.originalScale * Math.max(0.05f, 1f - progress * 0.95f);
        t.instance.setTransform(t.x, t.yOffset, t.z, t.yaw, s);
    }

    public int finishChop(TreeData t) {
        if (t.depleted) return 0;
        t.depleted = true;
        t.instance.setTransform(t.x, t.yOffset, t.z, t.yaw, 0.001f);
        int gain = 3 + rng.nextInt(3);
        return gain;
    }

    public HouseInstance buildHouseForTribe(Tribe tribe) {
        if (protoWalls == null || protoRoof == null || protoDoor == null || protoWindows == null) {
            return null;
        }
        if (tribe.wood < 10) return null;
        tribe.wood -= 10;

        float angle = rng.nextFloat() * (float) (Math.PI * 2);
        float r = 10f + tribe.houses.size() * 4f + rng.nextFloat() * 6f;
        float hx = tribe.center.x + (float) Math.cos(angle) * r;
        float hz = tribe.center.z + (float) Math.sin(angle) * r;

        // Reserve a flat pad BEFORE getting the height so the terrain is level
        Ground.addPad(hx, hz, 6f, 4f);
        if (onTerrainDirty != null) onTerrainDirty.run();

        float hy = Ground.getHeightAt(hx, hz);
        float yaw = (float) Math.atan2(-hx, -hz);

        HouseInstance inst = new HouseInstance(protoWalls, protoRoof, protoDoor, protoWindows,
                hx, hy, hz, yaw, protoScale);
        tribe.houses.add(inst);
        return inst;
    }
}
