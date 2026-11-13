package com.example.engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Random;

/**
 * Full lifecycle of the world's creatures.
 *
 *   TEEN      — normal male. Eats poop. 50% are gluttonous (5-10 s rest);
 *               after 5-10 meals becomes FAT. Eats bright poop -> FEMALE.
 *   FAT       — fat male. Only seeks BIG poop piles. Still can mature /
 *               impregnate / become MUSCULAR.
 *   MUSCULAR  — the "chad": made by a male that kissed 4-5 times.
 *   FEMALE    — wanders. After being kissed: 60 s gestation, 30 s pregnant,
 *               then gives birth to 1-8 BABY and reverts to FEMALE.
 *   BABY      — slow walker, grows into YOUNG_BOY after 2 min. Cries
 *               2-10 s after birth. Returns to mother to suckle every
 *               30-130 s.
 *   YOUNG_BOY — 8-12 year old. Eats poop, but during breaks forms little
 *               packs and chases each other around with a crowd sound.
 *               Grows into TEEN after 3 min. 1-in-50 keeps the suckle habit.
 */
public final class Eater {

    public enum Type { TEEN, FAT, FEMALE, PREGNANT, BABY, YOUNG_BOY, MUSCULAR }

    private enum State {
        SEARCH, WALK, EAT, REST,
        WANDER, SEEK_FEMALE, SATISFY,
        GESTATING, PREGNANT_WAIT,
        SEEK_MOM, SUCKLE
    }

    private static Texture[] typeTextures;
    private static final float[] TYPE_HEIGHTS = {
            1.50f,   // TEEN
            1.65f,   // FAT
            1.50f,   // FEMALE
            1.50f,   // PREGNANT
            0.85f,   // BABY
            1.15f,   // YOUNG_BOY
            1.75f    // MUSCULAR
    };

    public static void initTextures(Texture[] textures) {
        typeTextures = textures;
    }

    private static final String SFX_EAT           = "mp3/eating-champ-with-burp.mp3";
    private static final String SFX_MEOW          = "mp3/miau-muzhskoi.mp3";
    private static final String SFX_KISS          = "mp3/zvuk-poceluya.mp3";
    private static final String SFX_BIRTH         = "mp3/scream_female_giving_birth-c.mp3";
    private static final String SFX_BABY_CRY      = "mp3/baby-cry_fyco-2nu.mp3";
    private static final String SFX_CROWD         = "mp3/032635556b5d512.mp3";
    private static final String SFX_FIGHT         = "mp3/fight-hit-moan-shouts_my4oxhv_.mp3";
    private static final String SFX_SUCKLE        = "mp3/a9c8eebae9d0e23.mp3";
    private static final String SFX_MAN_SCREAM    = "mp3/man-screaming-screaming-man.mp3";
    private static final String SFX_FEMALE_SCREAM = "mp3/female-scream-of-horror.mp3";

    private static final float EAT_TIME            = 8.0f;
    private static final float REST_MIN            = 30.0f;
    private static final float REST_MAX            = 60.0f;
    private static final float GLUTTON_REST_MIN    = 5.0f;
    private static final float GLUTTON_REST_MAX    = 10.0f;
    private static final float YB_REST_MIN         = 15.0f;
    private static final float YB_REST_MAX         = 30.0f;
    private static final float SEARCH_RADIUS       = 80.0f;
    private static final float EAT_DISTANCE        = 1.0f;
    private static final float MALE_WALK_SPEED     = 2.4f;
    private static final float CHASE_SPEED         = 3.5f;
    private static final float FEMALE_WALK_SPEED   = 1.6f;
    private static final float BABY_WALK_SPEED     = 0.9f;
    private static final float SEEK_FEMALE_RADIUS  = 18.0f;
    private static final float SATISFY_DISTANCE    = 1.3f;
    private static final float SATISFY_TIME        = 3.0f;
    private static final float GESTATION_TIME      = 60.0f;
    private static final float PREGNANT_TIME       = 30.0f;
    private static final float BABY_GROW_TIME      = 120.0f;
    private static final float YOUNG_BOY_GROW_TIME = 180.0f;
    private static final int   MATURITY_MEALS      = 3;
    private static final float GLUTTON_CHANCE      = 0.5f;
    private static final float SUCKLE_TIME         = 5.0f;
    private static final float SUCKLE_RANGE        = 1.5f;
    private static final int   SUCKLE_YB_ODDS      = 50;
    private static final long  CROWD_COOLDOWN_MS   = 10_000L;

    private static long lastCrowdSoundMs = 0L;

    private Type type;
    private State state;
    private Sprite sprite;
    private float currentHeight = 1.5f;

    private final Random rng;
    private final Vector3f pos = new Vector3f();

    private float timer;
    private float stateTimer;
    private float growTimer;
    private float pregnancyTimer;

    private Poop target;
    private Eater targetFemale;
    private final Vector3f walkTo = new Vector3f();

    private int mealsEaten;
    private boolean gluttonous;
    private int fatThreshold;
    private boolean mature;

    private int kissCount;
    private int muscleThreshold;

    private Eater mother;
    private float suckleTimer;
    private boolean suckleAllowed;
    private float cryTimer = -1f;

    private Audio.Handle eatSound;

    // camera position, set each frame for distance-based volume
    private Vector3f camPos = new Vector3f();

    public Eater(Type type, float x, float z, Random rng) {
        this.type = type;
        this.rng = rng;
        this.pos.set(x, 0f, z);
        this.gluttonous = rng.nextFloat() < GLUTTON_CHANCE;
        this.fatThreshold = 5 + rng.nextInt(6);
        this.muscleThreshold = 4 + rng.nextInt(2);
        initStateForType();
        rebuildSprite();
        syncSpritePosition();
    }

    public Type type() { return type; }
    public Sprite sprite() { return sprite; }
    public Vector3f position() { return pos; }

    /** Called by the mother right after the baby is created. */
    public void setMother(Eater m, boolean keepSuckleInYoungBoy) {
        this.mother = m;
        this.suckleAllowed = keepSuckleInYoungBoy;
        if (m != null) {
            this.suckleTimer = 30f + rng.nextFloat() * 100f;
            this.cryTimer    = 2f  + rng.nextFloat() * 8f;
        }
    }

    public void render(ShaderProgram shader, Matrix4f view, Matrix4f projection,
                       Vector3f cameraPos, float time) {
        if (sprite != null) sprite.render(shader, view, projection, cameraPos, time);
    }

    // ================================================================
    public void update(float dt, List<Poop> poops, List<Eater> allEaters,
                       List<Eater> newbornsOut, Vector3f cameraPos) {
        this.camPos = cameraPos;

        if (cryTimer > 0f) {
            cryTimer -= dt;
            if (cryTimer <= 0f) {
                Audio.playOnce(SFX_BABY_CRY, volumeFor());
                cryTimer = -1f;
            }
        }

        switch (type) {
            case TEEN, FAT -> updateMale(dt, poops, allEaters);
            case MUSCULAR  -> updateMuscular(dt);
            case FEMALE, PREGNANT -> updateFemale(dt, newbornsOut);
            case BABY      -> updateBaby(dt);
            case YOUNG_BOY -> updateYoungBoy(dt, poops, allEaters);
        }
        syncSpritePosition();
    }

    private void syncSpritePosition() {
        if (sprite != null) sprite.setPosition(pos.x, currentHeight * 0.5f, pos.z);
    }

    private float volumeFor() {
        float dx = camPos.x - pos.x;
        float dy = camPos.y - pos.y;
        float dz = camPos.z - pos.z;
        float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.max(0.05f, Math.min(1f, 10f / (10f + d)));
    }

    // ================================================================
    private void updateMale(float dt, List<Poop> poops, List<Eater> allEaters) {
        switch (state) {
            case SEARCH -> {
                timer -= dt;
                if (timer > 0f) break;

                boolean fatOnly = (type == Type.FAT);
                Poop best = null;
                float bestD2 = SEARCH_RADIUS * SEARCH_RADIUS;
                for (Poop p : poops) {
                    if (p.eaten) continue;
                    if (fatOnly && !p.big) continue;
                    float dx = p.x - pos.x, dz = p.z - pos.z;
                    float d2 = dx * dx + dz * dz;
                    if (d2 < bestD2) { bestD2 = d2; best = p; }
                }
                if (best != null) target = best;
                else { target = null; pickWalkTarget(); }
                state = State.WALK;
            }
            case WALK -> {
                if (target != null && target.eaten) {
                    target = null;
                    state = State.SEARCH;
                    timer = 0f;
                    break;
                }
                float dx, dz, stopAt;
                if (target != null) { dx = target.x - pos.x; dz = target.z - pos.z; stopAt = EAT_DISTANCE; }
                else                { dx = walkTo.x - pos.x; dz = walkTo.z - pos.z; stopAt = 0.4f; }
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist <= stopAt) {
                    if (target != null) startEating();
                    else { state = State.SEARCH; timer = 0.5f + rng.nextFloat(); }
                    break;
                }
                float step = Math.min(MALE_WALK_SPEED * dt, dist);
                pos.x += dx / dist * step;
                pos.z += dz / dist * step;
            }
            case EAT -> updateEating(dt);
            case REST -> updateRest(dt, allEaters);
            case SEEK_FEMALE -> updateSeekFemale(dt);
            case SATISFY -> updateSatisfy(dt);
            default -> { state = State.SEARCH; timer = 0f; }
        }
    }

    private void startEating() {
        state = State.EAT;
        timer = EAT_TIME;
        eatSound = Audio.playLoop(SFX_EAT, EAT_TIME, volumeFor());
    }

    private void updateEating(float dt) {
        timer -= dt;

        if (sprite != null) {
            float progress = 1f - Math.max(timer, 0f) / EAT_TIME;
            float squash = 1f + 0.12f * (float) Math.sin(progress * Math.PI * 16f);
            sprite.setScale(squash);
        }
        if (timer > 0f) return;

        if (eatSound != null) { eatSound.stop(); eatSound = null; }
        if (sprite != null) sprite.setScale(1f);

        boolean brightEaten = false;
        if (target != null) { brightEaten = target.bright; target.eaten = true; target = null; }

        mealsEaten++;

        if (brightEaten) {
            float vol = volumeFor();
            Audio.playOnce(SFX_MAN_SCREAM, vol);
            Audio.playOnce(SFX_FEMALE_SCREAM, vol);
            System.out.println("[EATER] Male ate bright poop -> becomes FEMALE (with screams)");
            becomeType(Type.FEMALE);
            return;
        }

        if (type == Type.TEEN && gluttonous && mealsEaten >= fatThreshold) {
            System.out.println("[EATER] Gluttonous teen ate " + mealsEaten + " meals -> FAT");
            becomeType(Type.FAT);
        }

        if (mealsEaten >= MATURITY_MEALS) mature = true;

        Audio.playOnce(SFX_MEOW, volumeFor());
        state = State.REST;
        timer = gluttonous
                ? GLUTTON_REST_MIN + rng.nextFloat() * (GLUTTON_REST_MAX - GLUTTON_REST_MIN)
                : REST_MIN + rng.nextFloat() * (REST_MAX - REST_MIN);
    }

    private void updateRest(float dt, List<Eater> allEaters) {
        timer -= dt;

        if (mature && rng.nextFloat() < 0.3f * dt) {
            Eater best = null;
            float bestD2 = SEEK_FEMALE_RADIUS * SEEK_FEMALE_RADIUS;
            for (Eater e : allEaters) {
                if (e == this) continue;
                if (e.type != Type.FEMALE) continue;
                if (e.isPregnantOrGestating()) continue;
                float dx = e.pos.x - pos.x, dz = e.pos.z - pos.z;
                float d2 = dx * dx + dz * dz;
                if (d2 < bestD2) { bestD2 = d2; best = e; }
            }
            if (best != null) { targetFemale = best; state = State.SEEK_FEMALE; return; }
        }

        if (timer <= 0f) { state = State.SEARCH; timer = 0f; }
    }

    private void updateSeekFemale(float dt) {
        if (targetFemale == null || targetFemale.type != Type.FEMALE
                || targetFemale.isPregnantOrGestating()) {
            targetFemale = null;
            state = State.REST;
            timer = 5f + rng.nextFloat() * 5f;
            return;
        }
        float dx = targetFemale.pos.x - pos.x;
        float dz = targetFemale.pos.z - pos.z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);

        if (dist > SEEK_FEMALE_RADIUS * 1.6f) {
            targetFemale = null;
            state = State.REST;
            timer = 5f + rng.nextFloat() * 5f;
            return;
        }
        if (dist <= SATISFY_DISTANCE) {
            state = State.SATISFY;
            stateTimer = SATISFY_TIME;
            return;
        }
        float step = Math.min(MALE_WALK_SPEED * dt, dist);
        pos.x += dx / dist * step;
        pos.z += dz / dist * step;
    }

    private void updateSatisfy(float dt) {
        stateTimer -= dt;
        if (stateTimer > 0f) return;

        if (targetFemale != null) {
            Audio.playOnce(SFX_KISS, volumeFor());
            targetFemale.impregnate();
            kissCount++;
            System.out.println("[EATER] Male kissed female (kiss #" + kissCount + ")");

            if (kissCount >= muscleThreshold) {
                Audio.playOnce(SFX_FIGHT, volumeFor());
                System.out.println("[EATER] " + kissCount + " kisses -> becomes MUSCULAR");
                targetFemale = null;
                becomeType(Type.MUSCULAR);
                return;
            }
        }
        targetFemale = null;
        state = State.REST;
        timer = 8f + rng.nextFloat() * 8f;
    }

    // ================================================================
    private void updateMuscular(float dt) {
        if (state != State.WANDER) { state = State.WANDER; pickWalkTarget(); }
        float dx = walkTo.x - pos.x;
        float dz = walkTo.z - pos.z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.5f) pickWalkTarget();
        else {
            float step = Math.min(FEMALE_WALK_SPEED * dt, dist);
            pos.x += dx / dist * step;
            pos.z += dz / dist * step;
        }
    }

    // ================================================================
    private void updateFemale(float dt, List<Eater> newbornsOut) {
        switch (state) {
            case WANDER -> {
                float dx = walkTo.x - pos.x;
                float dz = walkTo.z - pos.z;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist < 0.5f) pickWalkTarget();
                else {
                    float step = Math.min(FEMALE_WALK_SPEED * dt, dist);
                    pos.x += dx / dist * step;
                    pos.z += dz / dist * step;
                }
            }
            case GESTATING -> {
                pregnancyTimer -= dt;
                if (pregnancyTimer <= 0f) {
                    System.out.println("[EATER] Female is now PREGNANT");
                    becomeType(Type.PREGNANT);
                }
            }
            case PREGNANT_WAIT -> {
                pregnancyTimer -= dt;
                if (pregnancyTimer <= 0f) {
                    Audio.playOnce(SFX_BIRTH, volumeFor());
                    int n = 1 + rng.nextInt(8);
                    System.out.println("[EATER] Birth: " + n + " babies");

                    Eater mom = this;
                    for (int i = 0; i < n; i++) {
                        float a = rng.nextFloat() * (float) (Math.PI * 2);
                        float r = 0.5f + rng.nextFloat() * 1.5f;
                        Eater baby = new Eater(Type.BABY,
                                pos.x + (float) Math.cos(a) * r,
                                pos.z + (float) Math.sin(a) * r, rng);
                        // 1-in-50 keeps the suckle habit when growing up
                        boolean keepSuckle = rng.nextInt(SUCKLE_YB_ODDS) == 0;
                        baby.setMother(mom, keepSuckle);
                        newbornsOut.add(baby);
                    }
                    becomeType(Type.FEMALE);
                }
            }
            default -> { state = State.WANDER; pickWalkTarget(); }
        }
    }

    private void impregnate() {
        if (type != Type.FEMALE) return;
        if (isPregnantOrGestating()) return;
        pregnancyTimer = GESTATION_TIME;
        state = State.GESTATING;
    }

    private boolean isPregnantOrGestating() {
        return type == Type.PREGNANT
                || state == State.GESTATING
                || state == State.PREGNANT_WAIT;
    }

    // ================================================================
    private void updateBaby(float dt) {
        growTimer -= dt;
        if (growTimer <= 0f) {
            System.out.println("[EATER] BABY grew into YOUNG_BOY");
            // If suckle not allowed, drop mother
            boolean keep = suckleAllowed;
            becomeType(Type.YOUNG_BOY);
            if (!keep) { mother = null; suckleAllowed = false; }
            return;
        }

        if (mother != null && suckleAllowed) {
            suckleTimer -= dt;
            if (suckleTimer <= 0f && state == State.WANDER) {
                state = State.SEEK_MOM;
            }
        }

        switch (state) {
            case WANDER -> {
                float dx = walkTo.x - pos.x;
                float dz = walkTo.z - pos.z;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist < 0.5f) pickWalkTarget();
                else {
                    float step = Math.min(BABY_WALK_SPEED * dt, dist);
                    pos.x += dx / dist * step;
                    pos.z += dz / dist * step;
                }
            }
            case SEEK_MOM -> {
                if (mother == null) { state = State.WANDER; break; }
                float dx = mother.pos.x - pos.x;
                float dz = mother.pos.z - pos.z;
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d <= SUCKLE_RANGE) {
                    state = State.SUCKLE;
                    stateTimer = SUCKLE_TIME;
                    Audio.playLoop(SFX_SUCKLE, SUCKLE_TIME, volumeFor());
                } else {
                    float step = Math.min(FEMALE_WALK_SPEED * dt, d);
                    pos.x += dx / d * step;
                    pos.z += dz / d * step;
                }
            }
            case SUCKLE -> {
                stateTimer -= dt;
                if (stateTimer <= 0f) {
                    state = State.WANDER;
                    suckleTimer = 30f + rng.nextFloat() * 100f;
                    pickWalkTarget();
                }
            }
            default -> { state = State.WANDER; pickWalkTarget(); }
        }
    }

    // ================================================================
    private void updateYoungBoy(float dt, List<Poop> poops, List<Eater> allEaters) {
        growTimer -= dt;
        if (growTimer <= 0f) {
            System.out.println("[EATER] YOUNG_BOY grew into TEEN");
            boolean keepSuckle = suckleAllowed && mother != null;
            becomeType(Type.TEEN);
            if (!keepSuckle) { mother = null; suckleAllowed = false; }
            return;
        }

        if (mother != null && suckleAllowed) {
            suckleTimer -= dt;
            if (suckleTimer <= 0f && (state == State.REST || state == State.WANDER)) {
                state = State.SEEK_MOM;
            }
        }

        switch (state) {
            case SEARCH -> {
                timer -= dt;
                if (timer > 0f) break;
                Poop best = null;
                float bestD2 = SEARCH_RADIUS * SEARCH_RADIUS;
                for (Poop p : poops) {
                    if (p.eaten) continue;
                    float dx = p.x - pos.x, dz = p.z - pos.z;
                    float d2 = dx * dx + dz * dz;
                    if (d2 < bestD2) { bestD2 = d2; best = p; }
                }
                if (best != null) target = best;
                else { target = null; pickWalkTarget(); }
                state = State.WALK;
            }
            case WALK -> {
                if (target != null && target.eaten) { target = null; state = State.SEARCH; timer = 0f; break; }
                float dx, dz, stopAt;
                if (target != null) { dx = target.x - pos.x; dz = target.z - pos.z; stopAt = EAT_DISTANCE; }
                else                { dx = walkTo.x - pos.x; dz = walkTo.z - pos.z; stopAt = 0.4f; }
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist <= stopAt) {
                    if (target != null) {
                        state = State.EAT;
                        timer = EAT_TIME;
                        eatSound = Audio.playLoop(SFX_EAT, EAT_TIME, volumeFor());
                    } else { state = State.SEARCH; timer = 0.5f + rng.nextFloat(); }
                    break;
                }
                float step = Math.min(MALE_WALK_SPEED * dt, dist);
                pos.x += dx / dist * step;
                pos.z += dz / dist * step;
            }
            case EAT -> {
                timer -= dt;
                if (sprite != null) {
                    float progress = 1f - Math.max(timer, 0f) / EAT_TIME;
                    sprite.setScale(1f + 0.12f * (float) Math.sin(progress * Math.PI * 16f));
                }
                if (timer <= 0f) {
                    if (eatSound != null) { eatSound.stop(); eatSound = null; }
                    if (sprite != null) sprite.setScale(1f);
                    if (target != null) { target.eaten = true; target = null; }
                    state = State.REST;
                    timer = YB_REST_MIN + rng.nextFloat() * (YB_REST_MAX - YB_REST_MIN);
                }
            }
            case REST -> {
                timer -= dt;
                if (timer <= 0f) { state = State.SEARCH; timer = 0f; break; }

                // Chase other young boys (reservoir sample one within 20 units)
                Eater chaseTarget = null;
                int count = 0;
                for (Eater other : allEaters) {
                    if (other == this || other.type != Type.YOUNG_BOY) continue;
                    float dx = other.pos.x - pos.x, dz = other.pos.z - pos.z;
                    float d2 = dx * dx + dz * dz;
                    if (d2 < 400f) {
                        count++;
                        if (rng.nextInt(count) == 0) chaseTarget = other;
                    }
                }
                if (chaseTarget != null) {
                    float dx = chaseTarget.pos.x - pos.x;
                    float dz = chaseTarget.pos.z - pos.z;
                    float d = (float) Math.sqrt(dx * dx + dz * dz);
                    if (d > 2f) {
                        float step = Math.min(CHASE_SPEED * dt, d);
                        pos.x += dx / d * step;
                        pos.z += dz / d * step;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastCrowdSoundMs > CROWD_COOLDOWN_MS) {
                        lastCrowdSoundMs = now;
                        Audio.playOnce(SFX_CROWD, volumeFor());
                    }
                }
            }
            case SEEK_MOM -> {
                if (mother == null) { state = State.WANDER; break; }
                float dx = mother.pos.x - pos.x;
                float dz = mother.pos.z - pos.z;
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d <= SUCKLE_RANGE) {
                    state = State.SUCKLE;
                    stateTimer = SUCKLE_TIME;
                    Audio.playLoop(SFX_SUCKLE, SUCKLE_TIME, volumeFor());
                } else {
                    float step = Math.min(FEMALE_WALK_SPEED * dt, d);
                    pos.x += dx / d * step;
                    pos.z += dz / d * step;
                }
            }
            case SUCKLE -> {
                stateTimer -= dt;
                if (stateTimer <= 0f) {
                    state = State.REST;
                    timer = YB_REST_MIN + rng.nextFloat() * (YB_REST_MAX - YB_REST_MIN);
                    suckleTimer = 30f + rng.nextFloat() * 100f;
                }
            }
            default -> { state = State.SEARCH; timer = 0f; }
        }
    }

    // ================================================================
    private void becomeType(Type newType) {
        this.type = newType;

        switch (newType) {
            case FEMALE -> {
                mealsEaten = 0; mature = false; kissCount = 0;
                gluttonous = rng.nextFloat() < GLUTTON_CHANCE;
                fatThreshold = 5 + rng.nextInt(6);
                state = State.WANDER;
                pickWalkTarget();
            }
            case PREGNANT -> {
                state = State.PREGNANT_WAIT;
                pregnancyTimer = PREGNANT_TIME;
            }
            case BABY -> {
                growTimer = BABY_GROW_TIME;
                state = State.WANDER;
                pickWalkTarget();
            }
            case YOUNG_BOY -> {
                growTimer = YOUNG_BOY_GROW_TIME;
                state = State.WANDER;
                pickWalkTarget();
            }
            case TEEN -> {
                mealsEaten = 0; mature = false;
                gluttonous = rng.nextFloat() < GLUTTON_CHANCE;
                fatThreshold = 5 + rng.nextInt(6);
                state = State.SEARCH;
                timer = 0.5f;
            }
            case MUSCULAR -> {
                state = State.WANDER;
                pickWalkTarget();
            }
            case FAT -> { /* state handled by caller */ }
        }
        rebuildSprite();
        syncSpritePosition();
    }

    private void initStateForType() {
        switch (type) {
            case TEEN, FAT, MUSCULAR -> { state = State.SEARCH; timer = rng.nextFloat() * 1.5f; }
            case FEMALE -> { state = State.WANDER; pickWalkTarget(); }
            case PREGNANT -> { state = State.PREGNANT_WAIT; pregnancyTimer = PREGNANT_TIME; }
            case BABY -> { state = State.WANDER; growTimer = BABY_GROW_TIME; pickWalkTarget(); }
            case YOUNG_BOY -> { state = State.WANDER; growTimer = YOUNG_BOY_GROW_TIME; pickWalkTarget(); }
        }
    }

    private void rebuildSprite() {
        if (typeTextures == null) { sprite = null; return; }
        Texture tex = typeTextures[type.ordinal()];
        currentHeight = TYPE_HEIGHTS[type.ordinal()];
        if (tex == null) { sprite = null; return; }
        sprite = new Sprite(tex, new Vector3f(pos.x, currentHeight * 0.5f, pos.z), currentHeight);
    }

    private void pickWalkTarget() {
        float a = rng.nextFloat() * (float) (Math.PI * 2);
        float r = 5f + rng.nextFloat() * 15f;
        walkTo.set(pos.x + (float) Math.cos(a) * r, 0f, pos.z + (float) Math.sin(a) * r);
    }
}
