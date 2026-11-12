package com.example.engine;

import org.joml.Vector3f;

import java.util.List;
import java.util.Random;

/**
 * The teenager from the encyclopedia, brought to life.
 *
 * Behaviour loop (all timings are in seconds):
 *
 *   SEARCH  -> look for the nearest poop within SEARCH_RADIUS
 *   WALK    -> walk to it (or wander somewhere random if there is none)
 *   EAT     -> 8 s of chewing, looping "eating-champ-with-burp.mp3",
 *              the poop disappears when he is done
 *   REST    -> 30..60 s of standing around, then meow ("miau-muzhskoi.mp3")
 *              and start searching again
 */
public final class Eater {

    public enum State { SEARCH, WALK, EAT, REST }

    private static final float WALK_SPEED    = 2.4f;
    private static final float SEARCH_RADIUS = 80f;
    private static final float EAT_DISTANCE  = 1.0f;
    private static final float EAT_TIME      = 8.0f;
    private static final float REST_MIN      = 30.0f;
    private static final float REST_MAX      = 60.0f;

    private static final String SFX_EAT  = "mp3/eating-champ-with-burp.mp3";
    private static final String SFX_MEOW = "mp3/miau-muzhskoi.mp3";

    private final Sprite sprite;
    private final float spriteHeight;
    private final Random rng;
    private final Vector3f pos = new Vector3f();   // ground position, y = 0

    private State state = State.SEARCH;
    private float timer;
    private Poop target;
    private final Vector3f walkTo = new Vector3f();

    private Audio.Handle eatSound;

    public Eater(Texture texture, float x, float z, float spriteHeight, Random rng) {
        this.spriteHeight = spriteHeight;
        this.rng = rng;
        this.sprite = new Sprite(texture, new Vector3f(x, spriteHeight * 0.5f, z), spriteHeight);
        this.pos.set(x, 0f, z);
        this.timer = rng.nextFloat() * 1.5f;   // stagger the very first search
    }

    public Sprite sprite() {
        return sprite;
    }

    public State state() {
        return state;
    }

    public void update(float dt, List<Poop> poops) {
        switch (state) {
            case SEARCH -> updateSearch(dt, poops);
            case WALK   -> updateWalk(dt);
            case EAT    -> updateEat(dt);
            case REST   -> updateRest(dt);
        }
        sprite.setPosition(pos.x, spriteHeight * 0.5f, pos.z);
    }

    // ---------------------------------------------------------------- SEARCH

    private void updateSearch(float dt, List<Poop> poops) {
        timer -= dt;
        if (timer > 0f) return;

        Poop best = null;
        float bestDist2 = SEARCH_RADIUS * SEARCH_RADIUS;

        for (Poop p : poops) {
            if (p.eaten) continue;
            float dx = p.x - pos.x;
            float dz = p.z - pos.z;
            float d2 = dx * dx + dz * dz;
            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = p;
            }
        }

        if (best != null) {
            target = best;
        } else {
            // Nothing edible in range — go for a stroll.
            target = null;
            float a = rng.nextFloat() * (float) (Math.PI * 2);
            float r = 8f + rng.nextFloat() * 25f;
            walkTo.set(pos.x + (float) Math.cos(a) * r, 0f,
                       pos.z + (float) Math.sin(a) * r);
        }
        state = State.WALK;
    }

    // ------------------------------------------------------------------ WALK

    private void updateWalk(float dt) {
        // Someone else ate our dinner while we were on the way.
        if (target != null && target.eaten) {
            target = null;
            state = State.SEARCH;
            timer = 0f;
            return;
        }

        float destX = (target != null) ? target.x : walkTo.x;
        float destZ = (target != null) ? target.z : walkTo.z;

        float dx = destX - pos.x;
        float dz = destZ - pos.z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);

        float stopAt = (target != null) ? EAT_DISTANCE : 0.4f;

        if (dist <= stopAt) {
            if (target != null) {
                startEating();
            } else {
                state = State.SEARCH;
                timer = 0.5f + rng.nextFloat();
            }
            return;
        }

        float step = WALK_SPEED * dt;
        if (step > dist) step = dist;
        pos.x += dx / dist * step;
        pos.z += dz / dist * step;
    }

    // ------------------------------------------------------------------- EAT

    private void startEating() {
        state = State.EAT;
        timer = EAT_TIME;
        eatSound = Audio.playLoop(SFX_EAT, EAT_TIME);
    }

    private void updateEat(float dt) {
        timer -= dt;

        // Chewing wiggle: squash / stretch the billboard a little.
        float progress = 1f - Math.max(timer, 0f) / EAT_TIME;   // 0..1
        float squash = 1f + 0.12f * (float) Math.sin(progress * Math.PI * 16f);
        sprite.setScale(squash);

        if (timer <= 0f) {
            if (eatSound != null) {
                eatSound.stop();
                eatSound = null;
            }
            sprite.setScale(1f);

            if (target != null) {
                target.eaten = true;
                target = null;
            }

            // ...and then he meows.
            Audio.playOnce(SFX_MEOW);

            state = State.REST;
            timer = REST_MIN + rng.nextFloat() * (REST_MAX - REST_MIN);
        }
    }

    // ------------------------------------------------------------------ REST

    private void updateRest(float dt) {
        timer -= dt;
        if (timer <= 0f) {
            state = State.SEARCH;
            timer = 0f;
        }
    }
}
