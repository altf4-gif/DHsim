package com.example.engine;

import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class Engine {

    private static final String VERSION = "v20 (Fixed Tree Spawning)";

    private static final int TEEN_INDEX = 12;

    private static final String SPRITE_VERT = """
            #version 330 core
            layout (location = 0) in vec2 aPos;
            layout (location = 1) in vec2 aTexCoord;

            uniform mat4 uModel;
            uniform mat4 uView;
            uniform mat4 uProjection;
            uniform vec2 uUvOffset;
            uniform vec2 uUvScale;

            out vec2 vTexCoord;

            void main() {
                gl_Position = uProjection * uView * uModel * vec4(aPos, 0.0, 1.0);
                vTexCoord = uUvOffset + aTexCoord * uUvScale;
            }
            """;

    private static final String SPRITE_FRAG = """
            #version 330 core
            in vec2 vTexCoord;
            out vec4 FragColor;
            uniform sampler2D uTexture;

            void main() {
                vec4 c = texture(uTexture, vTexCoord);
                if (c.a < 0.1) discard;
                if (c.r > 0.92 && c.g > 0.92 && c.b > 0.92) discard;
                FragColor = c;
            }
            """;

    private static final String HOUSE_VERT = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;
            uniform mat4 uModel;
            uniform mat4 uView;
            uniform mat4 uProjection;
            out vec2 vTexCoord;
            void main() {
                gl_Position = uProjection * uView * uModel * vec4(aPos, 1.0);
                vTexCoord = aTexCoord;
            }
            """;

    private static final String HOUSE_FRAG = """
            #version 330 core
            in vec2 vTexCoord;
            out vec4 FragColor;
            uniform sampler2D uTexture;
            void main() {
                vec4 c = texture(uTexture, vTexCoord);
                if (c.a < 0.1) discard;
                if (c.r > 0.92 && c.g > 0.92 && c.b > 0.92) discard;
                FragColor = c;
            }
            """;

    private static final String[] PEOPLE_IMAGES = {
            "black_male_tpose.png", "child_boy_tpose.png", "elderly_male_tpose.png", "female_slim_tpose.png",
            "hazmat_suit_yellow_tpose.png", "leather_coat_military_tpose.png", "male_bent_over_pose.png", "male_police_costume_tpose.png",
            "military_uniform_torn_tpose.png", "muscular_male_tpose.png", "overweight_male_tpose.png", "pregnant_female_tpose.png",
            "teenager_male_tpose.png", "teenager_sign_mars.png", "teenager_sign_muyakat.png", "teenager_sign_simulation.png",
            "young_boy_tpose.png"
    };

    private static final String[] PEOPLE_DESCRIPTIONS = new String[PEOPLE_IMAGES.length];
    static {
        for (int i = 0; i < PEOPLE_DESCRIPTIONS.length; i++) {
            PEOPLE_DESCRIPTIONS[i] = "Описание пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.";
        }
    }

    private static final String[] PEOPLE_NAMES = {
            "Человек в чёрном", "Ребёнок", "Пожилой мужчина", "Стройная женщина",
            "Хазмат", "Военная шинель", "Согнувшийся", "Полицейский",
            "Рваная форма", "Качок", "Толстяк", "Беременная",
            "Подросток", "Плакат 'Марс'", "Плакат 'Муякать'", "Плакат 'Симуляция'",
            "Мальчик"
    };

    private static ObjModel tryLoad(String folder, String base) {
        String[] suffixes = {"", "1", "2"};
        for (String s : suffixes) {
            String obj = folder + "/" + base + s + ".obj";
            String mtl = folder + "/" + base + s + ".mtl";
            if (new File(obj).exists()) {
                try {
                    return new ObjModel(obj, mtl, null);
                } catch (Exception e) {
                    System.err.println("[ERROR] Failed to load " + obj + ": " + e.getMessage());
                }
            }
        }
        return null;
    }

    public void run() {
        System.out.println("=== Engine " + VERSION + " ===");

        Window window = new Window(1280, 720, "LWJGL 3D Engine " + VERSION);
        Renderer renderer = new Renderer();
        renderer.init();
        window.setCursorDisabled(true);

        Camera camera = new Camera(window.aspectRatio());
        ShaderProgram spriteShader = new ShaderProgram(SPRITE_VERT, SPRITE_FRAG);
        ShaderProgram houseShader  = new ShaderProgram(HOUSE_VERT, HOUSE_FRAG);
        Sky sky = new Sky();
        Ground ground = new Ground();
        UiRenderer ui = new UiRenderer();

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        Texture[] peopleTex = new Texture[PEOPLE_IMAGES.length];
        for (int i = 0; i < PEOPLE_IMAGES.length; i++) {
            try {
                peopleTex[i] = new Texture("img/P/" + PEOPLE_IMAGES[i]);
            } catch (Exception e) {
                peopleTex[i] = null;
            }
        }

        Texture[] typeTex = {
                peopleTex[12], peopleTex[10], peopleTex[3], peopleTex[11],
                peopleTex[1], peopleTex[16], peopleTex[9],
        };
        Eater.initTextures(typeTex);

        List<Sprite> sprites = new ArrayList<>();
        List<Person> peopleList = new ArrayList<>();
        final float startX = -4.0f, spacingX = 1.2f, spriteHeight = 1.5f;

        for (int i = 0; i < PEOPLE_IMAGES.length; i++) {
            if (peopleTex[i] == null) continue;
            peopleList.add(new Person(PEOPLE_NAMES[i], PEOPLE_DESCRIPTIONS[i], peopleTex[i]));
            if (i == TEEN_INDEX) continue;
            int row = i / 9, col = i % 9;
            float x = startX + col * spacingX + (row == 1 ? spacingX * 0.5f : 0f);
            float z = (row == 0) ? -2.0f : 0.0f;
            sprites.add(new Sprite(peopleTex[i], new Vector3f(x, spriteHeight * 0.5f, z), spriteHeight));
        }

        Encyclopedia encyclopedia = new Encyclopedia(peopleList);

        ObjModel treeModel = tryLoad("mesh/TreeRL", "Tree");
        if (treeModel != null) treeModel.applyFallbackTexture(Texture.generateLeaves());

        ObjModel vWalls = tryLoad("mesh/VillageHouse", "walls");
        ObjModel vRoof  = tryLoad("mesh/VillageHouse", "roof");
        ObjModel vDoor  = tryLoad("mesh/VillageHouse", "door");
        ObjModel vWindows = tryLoad("mesh/VillageHouse", "windows");

        ObjModel v2Walls = tryLoad("mesh/VillageHouse2Story", "walls");
        ObjModel v2Roof  = tryLoad("mesh/VillageHouse2Story", "roof");
        ObjModel v2Door  = tryLoad("mesh/VillageHouse2Story", "door");
        ObjModel v2Windows = tryLoad("mesh/VillageHouse2Story", "windows");

        ObjModel dWalls = tryLoad("mesh/DugoutHouse", "walls");
        ObjModel dRoof  = tryLoad("mesh/DugoutHouse", "roof");
        ObjModel dDoor  = tryLoad("mesh/DugoutHouse", "door");
        ObjModel dWindows = tryLoad("mesh/DugoutHouse", "windows");

        List<HouseInstance> houses = new ArrayList<>();
        List<HouseInstance> trees = new ArrayList<>();
        List<Vector3f> houseCenters = new ArrayList<>();
        List<Vector3f> occupied = new ArrayList<>();
        Random rng = new Random(42);

        // --- Spawn VillageHouse ---
        if (vWalls != null) {
            float scale = 6.0f / Math.max(vWalls.getHeight(), vRoof != null ? vRoof.getHeight() : 1f);
            int placed = 0, attempts = 0;
            while (placed < 3 + rng.nextInt(3) && attempts++ < 500) {
                float x = (rng.nextFloat() - 0.5f) * 150f, z = (rng.nextFloat() - 0.5f) * 150f;
                if (Math.abs(x) < 20f && Math.abs(z) < 15f) continue;
                boolean close = false;
                for (Vector3f p : occupied) if (p.distanceSquared(x, 0, z) < 400f) { close = true; break; }
                if (close) continue;
                houseCenters.add(new Vector3f(x, 0, z));
                occupied.add(new Vector3f(x, 0, z));
                houses.add(new HouseInstance(vWalls, vRoof, vDoor, vWindows, x, 0, z, (float) Math.atan2(-x, -z), scale));
                placed++;
            }
        }

        // --- Spawn VillageHouse2Story ---
        if (v2Walls != null) {
            float scale = 8.5f / Math.max(v2Walls.getHeight(), v2Roof != null ? v2Roof.getHeight() : 1f);
            int placed = 0, attempts = 0;
            while (placed < 3 + rng.nextInt(5) && attempts++ < 500) {
                float x = (rng.nextFloat() - 0.5f) * 150f, z = (rng.nextFloat() - 0.5f) * 150f;
                if (Math.abs(x) < 20f && Math.abs(z) < 15f) continue;
                boolean close = false;
                for (Vector3f p : occupied) if (p.distanceSquared(x, 0, z) < 400f) { close = true; break; }
                if (close) continue;
                houseCenters.add(new Vector3f(x, 0, z));
                occupied.add(new Vector3f(x, 0, z));
                houses.add(new HouseInstance(v2Walls, v2Roof, v2Door, v2Windows, x, 0, z, (float) Math.atan2(-x, -z), scale));
                placed++;
            }
        }

        // --- Spawn DugoutHouse ---
        if (dWalls != null) {
            float scale = 3.5f / Math.max(dWalls.getHeight(), dRoof != null ? dRoof.getHeight() : 1f);
            int placed = 0, attempts = 0;
            while (placed < 10 + rng.nextInt(6) && attempts++ < 1000) {
                float x = (rng.nextFloat() - 0.5f) * 180f, z = (rng.nextFloat() - 0.5f) * 180f;
                if (Math.abs(x) < 20f && Math.abs(z) < 15f) continue;
                boolean close = false;
                for (Vector3f p : occupied) if (p.distanceSquared(x, 0, z) < 150f) { close = true; break; }
                if (close) continue;
                houseCenters.add(new Vector3f(x, 0, z));
                occupied.add(new Vector3f(x, 0, z));
                houses.add(new HouseInstance(dWalls, dRoof, dDoor, dWindows, x, 0, z, (float) Math.atan2(-x, -z), scale));
                placed++;
            }
        }

        // --- Spawn Trees (placed much further away from houses) ---
        if (treeModel != null) {
            float scale = 6.5f / treeModel.getHeight();
            int placed = 0, attempts = 0;
            while (placed < 100 && attempts++ < 2000) {
                float x = (rng.nextFloat() - 0.5f) * 220f, z = (rng.nextFloat() - 0.5f) * 220f;
                if (Math.abs(x) < 15f && Math.abs(z) < 15f) continue;
                boolean close = false;
                // Very large exclusion radius (25m) from houses to prevent clipping
                for (Vector3f hc : houseCenters) if (hc.distanceSquared(x, 0, z) < 625f) { close = true; break; }
                // 10m exclusion from other trees
                for (Vector3f p : occupied) if (p.distanceSquared(x, 0, z) < 100f) { close = true; break; }
                if (close) continue;
                occupied.add(new Vector3f(x, 0, z));
                trees.add(new HouseInstance(treeModel, null, null, null, x, -0.6f, z, rng.nextFloat() * (float)Math.PI * 2f, scale));
                placed++;
            }
            System.out.println("  -> " + placed + " Trees placed.");
        }

        Poop.init();
        List<Poop> poops = new ArrayList<>();
        Random worldRng = new Random(2024);
        int poopAttempts = 0;
        while (poops.size() < 90 && poopAttempts++ < 1200) {
            float x = (worldRng.nextFloat() - 0.5f) * 180f, z = (worldRng.nextFloat() - 0.5f) * 180f;
            if (Math.abs(x) < 14f && Math.abs(z) < 8f) continue;
            boolean blocked = false;
            for (Vector3f hc : occupied) if (hc.distanceSquared(x, 0f, z) < 36f) { blocked = true; break; }
            if (!blocked) poops.add(Poop.random(x, z, worldRng));
        }

        UiTexture poopIcon = PoopPreview.render(256, false);
        GL11.glViewport(0, 0, window.width(), window.height());
        UiTexture brightPoopIcon = PoopPreview.render(256, true);
        GL11.glViewport(0, 0, window.width(), window.height());

        List<Eater> eaters = new ArrayList<>();
        Random npcRng = new Random(777);
        if (typeTex[Eater.Type.TEEN.ordinal()] != null) {
            eaters.add(new Eater(Eater.Type.TEEN, startX + 3 * spacingX + 0.6f, -2.0f, npcRng));
        }

        SpawnMenu spawnMenu = new SpawnMenu(typeTex, poopIcon, brightPoopIcon);

        float lastTime = (float) GLFW.glfwGetTime();
        boolean tabWasPressed = false, qWasPressed = false, escWasPressed = false;
        boolean upWasPressed = false, downWasPressed = false, leftWasPressed = false;

        try {
            while (!window.shouldClose()) {
                float now = (float) GLFW.glfwGetTime();
                float dt = Math.min(now - lastTime, 0.1f);
                lastTime = now;

                window.pollEvents();

                if (window.isKeyPressed(GLFW.GLFW_KEY_ESCAPE) && !escWasPressed) {
                    if (spawnMenu.isOpen()) {
                        spawnMenu.close(); window.setCursorDisabled(true); camera.resetMouseLook(); leftWasPressed = true;
                    } else if (encyclopedia.isOpen()) {
                        encyclopedia.toggle(); window.setCursorDisabled(true); camera.resetMouseLook();
                    } else { return; }
                }
                escWasPressed = window.isKeyPressed(GLFW.GLFW_KEY_ESCAPE);

                if (window.isKeyPressed(GLFW.GLFW_KEY_Q) && !qWasPressed) {
                    if (spawnMenu.isOpen()) {
                        spawnMenu.close(); window.setCursorDisabled(true); camera.resetMouseLook();
                    } else {
                        if (encyclopedia.isOpen()) encyclopedia.toggle();
                        spawnMenu.open(); window.setCursorDisabled(false); camera.resetMouseLook(); leftWasPressed = true;
                    }
                }
                qWasPressed = window.isKeyPressed(GLFW.GLFW_KEY_Q);

                if (window.isKeyPressed(GLFW.GLFW_KEY_TAB) && !tabWasPressed) {
                    if (spawnMenu.isOpen()) spawnMenu.close();
                    encyclopedia.toggle(); window.setCursorDisabled(!encyclopedia.isOpen()); camera.resetMouseLook(); leftWasPressed = true;
                }
                tabWasPressed = window.isKeyPressed(GLFW.GLFW_KEY_TAB);

                if (spawnMenu.isOpen()) {
                    double[] cp = window.cursorPos();
                    int idx = spawnMenu.hitTest(window.width(), window.height(), cp[0], cp[1]);
                    spawnMenu.setHovered(idx);
                    boolean left = window.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    if (left && !leftWasPressed && idx >= 0) {
                        SpawnMenu.Action a = spawnMenu.actionAt(idx);
                        if (a != null) spawnFromMenu(a, camera, poops, eaters, npcRng);
                    }
                    leftWasPressed = left;
                } else {
                    leftWasPressed = false;
                }

                if (encyclopedia.isOpen()) {
                    if (window.isKeyPressed(GLFW.GLFW_KEY_UP) && !upWasPressed) encyclopedia.prev();
                    upWasPressed = window.isKeyPressed(GLFW.GLFW_KEY_UP);
                    if (window.isKeyPressed(GLFW.GLFW_KEY_DOWN) && !downWasPressed) encyclopedia.next();
                    downWasPressed = window.isKeyPressed(GLFW.GLFW_KEY_DOWN);
                } else { upWasPressed = false; downWasPressed = false; }

                camera.setAspect(window.aspectRatio());
                if (!spawnMenu.isOpen() && !encyclopedia.isOpen()) camera.update(window, dt);

                List<Eater> newborns = new ArrayList<>();
                for (Eater e : eaters) e.update(dt, poops, eaters, newborns, camera.position());
                if (!newborns.isEmpty()) eaters.addAll(newborns);
                poops.removeIf(p -> p.eaten);

                renderer.clear();
                sky.render();
                ground.render(camera.view(), camera.projection());

                // --- 1. Opaque objects first (Houses) ---
                houseShader.bind();
                for (HouseInstance h : houses) h.render(houseShader, camera.view(), camera.projection());
                
                // --- 2. Transparent objects next (Trees) ---
                // Disable depth writing so transparent leaves don't occlude other objects behind them
                GL11.glDepthMask(false);
                for (HouseInstance t : trees) t.render(houseShader, camera.view(), camera.projection());
                GL11.glDepthMask(true);
                houseShader.unbind();

                Poop.begin(camera.view(), camera.projection());
                for (Poop p : poops) p.render();
                Poop.end();

                spriteShader.bind();
                for (Sprite s : sprites) s.render(spriteShader, camera.view(), camera.projection(), camera.position(), now);
                for (Eater e : eaters) e.render(spriteShader, camera.view(), camera.projection(), camera.position(), now);
                spriteShader.unbind();

                if (encyclopedia.isOpen() || spawnMenu.isOpen()) {
                    ui.begin(window.width(), window.height());
                    if (encyclopedia.isOpen()) encyclopedia.render(ui, window.width(), window.height());
                    if (spawnMenu.isOpen())    spawnMenu.render(ui, window.width(), window.height());
                    ui.end();
                }

                window.swapBuffers();
            }
        } finally {
            for (Texture t : peopleTex) if (t != null) t.destroy();
            encyclopedia.destroy(); spawnMenu.destroy();
            if (poopIcon != null) poopIcon.destroy(); if (brightPoopIcon != null) brightPoopIcon.destroy();
            if (treeModel != null) treeModel.destroy();
            if (vWalls != null) vWalls.destroy(); if (vRoof != null) vRoof.destroy(); if (vDoor != null) vDoor.destroy(); if (vWindows != null) vWindows.destroy();
            if (v2Walls != null) v2Walls.destroy(); if (v2Roof != null) v2Roof.destroy(); if (v2Door != null) v2Door.destroy(); if (v2Windows != null) v2Windows.destroy();
            if (dWalls != null) dWalls.destroy(); if (dRoof != null) dRoof.destroy(); if (dDoor != null) dDoor.destroy(); if (dWindows != null) dWindows.destroy();
            Poop.destroyAll(); ui.destroy(); spriteShader.destroy(); houseShader.destroy(); sky.destroy(); ground.destroy(); window.destroy();
        }
    }

    private static float[] spawnPoint(Camera camera) {
        Vector3f front = camera.frontVector();
        float yaw = (float) Math.atan2(front.z, front.x);
        Vector3f cam = camera.position();
        return new float[]{ cam.x + (float) Math.cos(yaw) * 10f, cam.z + (float) Math.sin(yaw) * 10f };
    }

    private static void spawnFromMenu(SpawnMenu.Action action, Camera camera, List<Poop> poops, List<Eater> eaters, Random rng) {
        float[] sp = spawnPoint(camera);
        float sx = sp[0], sz = sp[1];
        switch (action) {
            case SPAWN_TEEN        -> eaters.add(new Eater(Eater.Type.TEEN, sx, sz, rng));
            case SPAWN_FAT         -> eaters.add(new Eater(Eater.Type.FAT, sx, sz, rng));
            case SPAWN_MUSCULAR    -> eaters.add(new Eater(Eater.Type.MUSCULAR, sx, sz, rng));
            case SPAWN_FEMALE      -> eaters.add(new Eater(Eater.Type.FEMALE, sx, sz, rng));
            case SPAWN_PREGNANT    -> eaters.add(new Eater(Eater.Type.PREGNANT, sx, sz, rng));
            case SPAWN_BABY        -> eaters.add(new Eater(Eater.Type.BABY, sx, sz, rng));
            case SPAWN_YOUNG_BOY   -> eaters.add(new Eater(Eater.Type.YOUNG_BOY, sx, sz, rng));
            case SPAWN_POOP        -> poops.add(Poop.random(sx, sz, rng));
            case SPAWN_BRIGHT_POOP -> poops.add(Poop.bright(sx, sz, rng));
            case SPAWN_POOP_CLUSTER -> {
                for (int i = 0; i < 5; i++) {
                    float a = rng.nextFloat() * (float) (Math.PI * 2);
                    float r = 0.5f + rng.nextFloat() * 3f;
                    poops.add(Poop.random(sx + (float) Math.cos(a) * r, sz + (float) Math.sin(a) * r, rng));
                }
            }
            case SPAWN_COMBO -> {
                eaters.add(new Eater(Eater.Type.TEEN, sx, sz, rng));
                for (int i = 0; i < 5; i++) {
                    float a = rng.nextFloat() * (float) (Math.PI * 2);
                    float r = 1.5f + rng.nextFloat() * 5f;
                    poops.add(Poop.random(sx + (float) Math.cos(a) * r, sz + (float) Math.sin(a) * r, rng));
                }
            }
            case SPAWN_TEEN_CROWD -> {
                for (int i = 0; i < 5; i++) {
                    float a = rng.nextFloat() * (float) (Math.PI * 2);
                    float r = 0.5f + rng.nextFloat() * 3f;
                    eaters.add(new Eater(Eater.Type.TEEN, sx + (float) Math.cos(a) * r, sz + (float) Math.sin(a) * r, rng));
                }
            }
        }
    }
}
