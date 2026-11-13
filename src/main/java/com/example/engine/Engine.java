package com.example.engine;

import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class Engine {

    private static final String VERSION = "v14 (Lifecycle + Sound)";

    private static final int TEEN_INDEX = 12;
    private static final boolean SPAWN_HOUSES = false;

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
            void main() { FragColor = texture(uTexture, vTexCoord); }
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

    public void run() {
        System.out.println("=== Engine " + VERSION + " ===");

        Window window = new Window(1280, 720, "LWJGL 3D Engine " + VERSION);
        Renderer renderer = new Renderer();
        renderer.init();
        window.setCursorDisabled(true);

        System.out.println("Window: " + window.width() + "x" + window.height() + " (fullscreen)");

        Camera camera = new Camera(window.aspectRatio());
        ShaderProgram spriteShader = new ShaderProgram(SPRITE_VERT, SPRITE_FRAG);
        ShaderProgram houseShader  = new ShaderProgram(HOUSE_VERT, HOUSE_FRAG);
        Sky sky = new Sky();
        Ground ground = new Ground();
        UiRenderer ui = new UiRenderer();

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        System.out.println("Loading people from img/P/ ...");
        Texture[] peopleTex = new Texture[PEOPLE_IMAGES.length];
        for (int i = 0; i < PEOPLE_IMAGES.length; i++) {
            try {
                peopleTex[i] = new Texture("img/P/" + PEOPLE_IMAGES[i]);
            } catch (Exception e) {
                System.err.println("  [MISS] img/P/" + PEOPLE_IMAGES[i] + " -- " + e.getMessage());
                peopleTex[i] = null;
            }
        }

        Texture[] typeTex = {
                peopleTex[12],  // TEEN
                peopleTex[10],  // FAT
                peopleTex[3],   // FEMALE
                peopleTex[11],  // PREGNANT
                peopleTex[1],   // BABY
                peopleTex[16],  // YOUNG_BOY
                peopleTex[9],   // MUSCULAR
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

        // Houses (disabled for now)
        List<House> houses = new ArrayList<>();
        List<Vector3f> houseCenters = new ArrayList<>();
        Texture texWall = null, texFront = null, texRoof = null;
        if (SPAWN_HOUSES) {
            try {
                texWall  = new Texture("img/H/Hos_L.png", true, false);
                texFront = new Texture("img/H/Hos_V.png", false, false);
                texRoof  = new Texture("img/H/Hos_K.png", true, false);
            } catch (Exception e) { System.err.println("[ERROR] House textures: " + e.getMessage()); }
            // ... (spawn logic omitted, disabled)
        }

        System.out.println("Scattering poop across the map...");
        Poop.init();
        List<Poop> poops = new ArrayList<>();
        Random worldRng = new Random(2024);
        int attempts = 0;
        while (poops.size() < 90 && attempts < 1200) {
            attempts++;
            float x = (worldRng.nextFloat() - 0.5f) * 180f;
            float z = (worldRng.nextFloat() - 0.5f) * 180f;
            if (Math.abs(x) < 14f && Math.abs(z) < 8f) continue;
            boolean blocked = false;
            for (Vector3f hc : houseCenters) if (hc.distanceSquared(x, 0f, z) < 36f) { blocked = true; break; }
            if (blocked) continue;
            poops.add(Poop.random(x, z, worldRng));
        }
        int bigCount = 0;
        for (Poop p : poops) if (p.big) bigCount++;
        System.out.println("  -> " + poops.size() + " poops placed (" + bigCount + " big).");

        System.out.println("Rendering poop preview icons...");
        UiTexture poopIcon = PoopPreview.render(256, false);
        GL11.glViewport(0, 0, window.width(), window.height());
        UiTexture brightPoopIcon = PoopPreview.render(256, true);
        GL11.glViewport(0, 0, window.width(), window.height());

        // Initial NPC
        List<Eater> eaters = new ArrayList<>();
        Random npcRng = new Random(777);
        if (typeTex[Eater.Type.TEEN.ordinal()] != null) {
            int row = TEEN_INDEX / 9, col = TEEN_INDEX % 9;
            float x = startX + col * spacingX + (row == 1 ? spacingX * 0.5f : 0f);
            float z = (row == 0) ? -2.0f : 0.0f;
            eaters.add(new Eater(Eater.Type.TEEN, x, z, npcRng));
            System.out.println("  -> 1 teenager awake at (" + x + ", " + z + ")");
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

                boolean escPressed = window.isKeyPressed(GLFW.GLFW_KEY_ESCAPE);
                if (escPressed && !escWasPressed) {
                    if (spawnMenu.isOpen()) {
                        spawnMenu.close(); window.setCursorDisabled(true);
                        camera.resetMouseLook(); leftWasPressed = true;
                    } else if (encyclopedia.isOpen()) {
                        encyclopedia.toggle(); window.setCursorDisabled(true);
                        camera.resetMouseLook();
                    } else { return; }
                }
                escWasPressed = escPressed;

                boolean qPressed = window.isKeyPressed(GLFW.GLFW_KEY_Q);
                if (qPressed && !qWasPressed) {
                    if (spawnMenu.isOpen()) {
                        spawnMenu.close(); window.setCursorDisabled(true);
                        camera.resetMouseLook();
                    } else {
                        if (encyclopedia.isOpen()) encyclopedia.toggle();
                        spawnMenu.open(); window.setCursorDisabled(false);
                        camera.resetMouseLook(); leftWasPressed = true;
                    }
                }
                qWasPressed = qPressed;

                boolean tabPressed = window.isKeyPressed(GLFW.GLFW_KEY_TAB);
                if (tabPressed && !tabWasPressed) {
                    if (spawnMenu.isOpen()) spawnMenu.close();
                    encyclopedia.toggle();
                    window.setCursorDisabled(!encyclopedia.isOpen());
                    camera.resetMouseLook(); leftWasPressed = true;
                }
                tabWasPressed = tabPressed;

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
                    boolean upPressed = window.isKeyPressed(GLFW.GLFW_KEY_UP);
                    if (upPressed && !upWasPressed) encyclopedia.prev();
                    upWasPressed = upPressed;
                    boolean downPressed = window.isKeyPressed(GLFW.GLFW_KEY_DOWN);
                    if (downPressed && !downWasPressed) encyclopedia.next();
                    downWasPressed = downPressed;
                } else { upWasPressed = false; downWasPressed = false; }

                camera.setAspect(window.aspectRatio());
                if (!spawnMenu.isOpen() && !encyclopedia.isOpen()) camera.update(window, dt);

                List<Eater> newborns = new ArrayList<>();
                for (Eater e : eaters) {
                    e.update(dt, poops, eaters, newborns, camera.position());
                }
                if (!newborns.isEmpty()) eaters.addAll(newborns);
                poops.removeIf(p -> p.eaten);

                renderer.clear();
                sky.render();
                ground.render(camera.view(), camera.projection());

                if (!houses.isEmpty()) {
                    houseShader.bind();
                    for (House h : houses) h.render(houseShader, camera.view(), camera.projection());
                    houseShader.unbind();
                }

                Poop.begin(camera.view(), camera.projection());
                for (Poop p : poops) p.render();
                Poop.end();

                spriteShader.bind();
                for (Sprite s : sprites) {
                    s.render(spriteShader, camera.view(), camera.projection(), camera.position(), now);
                }
                for (Eater e : eaters) {
                    e.render(spriteShader, camera.view(), camera.projection(), camera.position(), now);
                }
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
            if (texWall  != null) texWall.destroy();
            if (texFront != null) texFront.destroy();
            if (texRoof  != null) texRoof.destroy();
            encyclopedia.destroy();
            spawnMenu.destroy();
            if (poopIcon != null) poopIcon.destroy();
            if (brightPoopIcon != null) brightPoopIcon.destroy();
            Poop.destroyAll();
            ui.destroy();
            spriteShader.destroy();
            houseShader.destroy();
            sky.destroy();
            ground.destroy();
            window.destroy();
        }
    }

    private static float[] spawnPoint(Camera camera) {
        Vector3f front = camera.frontVector();
        float yaw = (float) Math.atan2(front.z, front.x);
        Vector3f cam = camera.position();
        return new float[]{
                cam.x + (float) Math.cos(yaw) * 10f,
                cam.z + (float) Math.sin(yaw) * 10f,
        };
    }

    private static void spawnFromMenu(SpawnMenu.Action action, Camera camera,
                                      List<Poop> poops, List<Eater> eaters, Random rng) {
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
                    poops.add(Poop.random(sx + (float) Math.cos(a) * r,
                                          sz + (float) Math.sin(a) * r, rng));
                }
            }
            case SPAWN_COMBO -> {
                eaters.add(new Eater(Eater.Type.TEEN, sx, sz, rng));
                for (int i = 0; i < 5; i++) {
                    float a = rng.nextFloat() * (float) (Math.PI * 2);
                    float r = 1.5f + rng.nextFloat() * 5f;
                    poops.add(Poop.random(sx + (float) Math.cos(a) * r,
                                          sz + (float) Math.sin(a) * r, rng));
                }
            }
            case SPAWN_TEEN_CROWD -> {
                for (int i = 0; i < 5; i++) {
                    float a = rng.nextFloat() * (float) (Math.PI * 2);
                    float r = 0.5f + rng.nextFloat() * 3f;
                    eaters.add(new Eater(Eater.Type.TEEN,
                            sx + (float) Math.cos(a) * r,
                            sz + (float) Math.sin(a) * r, rng));
                }
            }
        }
        System.out.println("[SPAWN] " + action + " @ (" + sx + ", " + sz + ")");
    }
}
