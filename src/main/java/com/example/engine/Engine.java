package com.example.engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class Engine {

    private static final String VERSION = "v28 (Flat Building Pads)";
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
            uniform vec3 uLightTint;
            void main() {
                vec4 c = texture(uTexture, vTexCoord);
                if (c.a < 0.1) discard;
                if (c.r > 0.92 && c.g > 0.92 && c.b > 0.92) discard;
                FragColor = vec4(c.rgb * uLightTint, c.a);
            }
            """;

    private static final String SPRITE_SHADOW_VERT = """
            #version 330 core
            layout (location = 0) in vec2 aPos;
            layout (location = 1) in vec2 aTexCoord;
            uniform mat4 uModel;
            uniform mat4 uLightVP;
            uniform vec2 uUvOffset;
            uniform vec2 uUvScale;
            out vec2 vTexCoord;
            void main() {
                gl_Position = uLightVP * uModel * vec4(aPos, 0.0, 1.0);
                vTexCoord = uUvOffset + aTexCoord * uUvScale;
            }
            """;

    private static final String SPRITE_SHADOW_FRAG = """
            #version 330 core
            in vec2 vTexCoord;
            out vec4 FragColor;
            uniform sampler2D uTexture;
            void main() {
                vec4 c = texture(uTexture, vTexCoord);
                if (c.a < 0.5) discard;
                if (c.r > 0.92 && c.g > 0.92 && c.b > 0.92) discard;
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
            out vec3 vWorld;
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                vWorld = world.xyz;
                vTexCoord = aTexCoord;
                gl_Position = uProjection * uView * world;
            }
            """;

    private static final String HOUSE_FRAG = """
            #version 330 core
            in vec2 vTexCoord;
            in vec3 vWorld;
            out vec4 FragColor;

            uniform sampler2D uTexture;
            uniform sampler2D uShadowMap;
            uniform mat4 uLightVP;
            uniform vec3 uSunDir;
            uniform vec3 uSunColor;
            uniform vec3 uAmbient;
            uniform vec3 uCamPos;
            uniform vec3 uFogColor;
            uniform float uFogDensity;

            float shadowCalc(vec4 lsp) {
                vec3 p = lsp.xyz / lsp.w;
                p = p * 0.5 + 0.5;
                if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0 || p.z > 1.0) return 1.0;
                float current = p.z - 0.0025;
                vec2 ts = 1.0 / textureSize(uShadowMap, 0);
                float sh = 0.0;
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        float d = texture(uShadowMap, p.xy + vec2(x, y) * ts).r;
                        sh += current > d ? 0.0 : 1.0;
                    }
                }
                return sh / 9.0;
            }

            void main() {
                vec4 tex = texture(uTexture, vTexCoord);
                if (tex.a < 0.1) discard;
                if (tex.r > 0.92 && tex.g > 0.92 && tex.b > 0.92) discard;

                vec3 n = normalize(cross(dFdx(vWorld), dFdy(vWorld)));
                float diff = max(dot(n, uSunDir), 0.0);

                float shadow = 1.0;
                if (diff > 0.0) shadow = shadowCalc(uLightVP * vec4(vWorld, 1.0));

                vec3 lit = tex.rgb * (uAmbient + uSunColor * diff * shadow);
                float dist = length(vWorld - uCamPos);
                float fog = 1.0 - exp(-pow(dist * uFogDensity, 2.0));
                lit = mix(lit, uFogColor, fog);
                FragColor = vec4(lit, tex.a);
            }
            """;

    private static final String SHADOW_VERT = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;
            uniform mat4 uModel;
            uniform mat4 uLightVP;
            out vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = uLightVP * uModel * vec4(aPos, 1.0);
            }
            """;

    private static final String SHADOW_FRAG = """
            #version 330 core
            in vec2 vTexCoord;
            out vec4 FragColor;
            uniform sampler2D uTexture;
            void main() {
                vec4 c = texture(uTexture, vTexCoord);
                if (c.a < 0.5) discard;
                if (c.r > 0.92 && c.g > 0.92 && c.b > 0.92) discard;
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
                try { return new ObjModel(obj, mtl, null); }
                catch (Exception e) { System.err.println("[ERROR] " + obj + ": " + e.getMessage()); }
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
        ShaderProgram spriteShader       = new ShaderProgram(SPRITE_VERT, SPRITE_FRAG);
        ShaderProgram spriteShadowShader = new ShaderProgram(SPRITE_SHADOW_VERT, SPRITE_SHADOW_FRAG);
        ShaderProgram houseShader        = new ShaderProgram(HOUSE_VERT, HOUSE_FRAG);
        ShaderProgram shadowShader       = new ShaderProgram(SHADOW_VERT, SHADOW_FRAG);

        Sky sky = new Sky();
        Ground ground = new Ground();
        Water water = new Water();
        LineRenderer lineRenderer = new LineRenderer();
        UiRenderer ui = new UiRenderer();
        ShadowMap shadowMap = new ShadowMap();
        shadowMap.init();
        DayNight dayNight = new DayNight();
        Weather weather = new Weather();
        WorldManager world = new WorldManager();
        TribeMarker.init();

        // Rebuild terrain whenever a house reserves a new pad
        world.onTerrainDirty = ground::rebuild;

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        Texture[] peopleTex = new Texture[PEOPLE_IMAGES.length];
        for (int i = 0; i < PEOPLE_IMAGES.length; i++) {
            try { peopleTex[i] = new Texture("img/P/" + PEOPLE_IMAGES[i]); }
            catch (Exception e) { peopleTex[i] = null; }
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
            float y = Ground.getHeightAt(x, z);
            sprites.add(new Sprite(peopleTex[i], new Vector3f(x, y + spriteHeight * 0.5f, z), spriteHeight));
        }

        Encyclopedia encyclopedia = new Encyclopedia(peopleList);

        // --- Create Tribes ---
        Tribe redTribe = new Tribe(0, new Vector3f(-40, Ground.getHeightAt(-40, -40), -40), new Vector3f(1f, 0.2f, 0.2f));
        Tribe blueTribe = new Tribe(1, new Vector3f(40, Ground.getHeightAt(40, -40), -40), new Vector3f(0.2f, 0.5f, 1f));
        Tribe greenTribe = new Tribe(2, new Vector3f(0, Ground.getHeightAt(0, 50), 50), new Vector3f(0.2f, 1f, 0.2f));
        world.tribes.add(redTribe);
        world.tribes.add(blueTribe);
        world.tribes.add(greenTribe);

        // Flatten pads under initial tribe centers so villages start flat
        Ground.addPad(redTribe.center.x, redTribe.center.z, 14f, 6f);
        Ground.addPad(blueTribe.center.x, blueTribe.center.z, 14f, 6f);
        Ground.addPad(greenTribe.center.x, greenTribe.center.z, 14f, 6f);
        ground.rebuild();

        ObjModel treeModel = tryLoad("mesh/TreeRL", "Tree");
        if (treeModel != null) treeModel.applyFallbackTexture(Texture.generateLeaves());

        ObjModel vWalls = tryLoad("mesh/VillageHouse", "walls");
        ObjModel vRoof  = tryLoad("mesh/VillageHouse", "roof");
        ObjModel vDoor  = tryLoad("mesh/VillageHouse", "door");
        ObjModel vWindows = tryLoad("mesh/VillageHouse", "windows");

        if (vWalls != null && vRoof != null && vDoor != null && vWindows != null) {
            world.protoWalls = vWalls;
            world.protoRoof = vRoof;
            world.protoDoor = vDoor;
            world.protoWindows = vWindows;
            world.protoScale = 6.0f / Math.max(vWalls.getHeight(), vRoof.getHeight());
        }

        List<HouseInstance> staticHouses = new ArrayList<>();
        List<HouseInstance> staticTrees = new ArrayList<>();
        List<Vector3f> occupied = new ArrayList<>();
        Random rng = new Random(42);

        // Trees spawn
        List<WorldManager.TreeData> treeDataList = new ArrayList<>();
        if (treeModel != null) {
            float scale = 6.5f / treeModel.getHeight();
            int placed = 0, attempts = 0;
            while (placed < 150 && attempts++ < 2000) {
                float x = (rng.nextFloat() - 0.5f) * 220f, z = (rng.nextFloat() - 0.5f) * 220f;
                if (Math.abs(x) < 15f && Math.abs(z) < 15f) continue;
                float lakeDist = (float)Math.sqrt((x - Ground.LAKE_X)*(x - Ground.LAKE_X) + (z - Ground.LAKE_Z)*(z - Ground.LAKE_Z));
                if (lakeDist < Ground.LAKE_RADIUS + 5f) continue;

                boolean close = false;
                for (Vector3f p : occupied) if (p.distanceSquared(x, 0, z) < 100f) { close = true; break; }
                if (close) continue;
                occupied.add(new Vector3f(x, 0, z));
                float yaw = rng.nextFloat() * (float)Math.PI * 2f;
                float y = Ground.getHeightAt(x, z) - 0.6f;

                HouseInstance inst = new HouseInstance(treeModel, null, null, null, x, y, z, yaw, scale);
                staticTrees.add(inst);
                treeDataList.add(new WorldManager.TreeData(inst, x, z, yaw, scale, y));
                placed++;
            }
            world.registerTrees(treeDataList);
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
            eaters.add(new Eater(Eater.Type.TEEN, redTribe.center.x + 2f, redTribe.center.z + 2f, redTribe, npcRng));
            eaters.add(new Eater(Eater.Type.TEEN, blueTribe.center.x + 2f, blueTribe.center.z + 2f, blueTribe, npcRng));
            eaters.add(new Eater(Eater.Type.TEEN, greenTribe.center.x + 2f, greenTribe.center.z + 2f, greenTribe, npcRng));
        }

        SpawnMenu spawnMenu = new SpawnMenu(typeTex, poopIcon, brightPoopIcon);

        float lastTime = (float) GLFW.glfwGetTime();
        boolean tabWasPressed = false, qWasPressed = false, escWasPressed = false;
        boolean upWasPressed = false, downWasPressed = false, leftWasPressed = false;
        boolean tWasPressed = false;

        Matrix4f lightView = new Matrix4f();
        Matrix4f lightProj = new Matrix4f().ortho(-140f, 140f, -140f, 140f, 1f, 500f);
        Matrix4f lightVP = new Matrix4f();

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

                if (window.isKeyPressed(GLFW.GLFW_KEY_T) && !tWasPressed) {
                    dayNight.toggleSpeed();
                }
                tWasPressed = window.isKeyPressed(GLFW.GLFW_KEY_T);

                if (spawnMenu.isOpen()) {
                    double[] cp = window.cursorPos();
                    int idx = spawnMenu.hitTest(window.width(), window.height(), cp[0], cp[1]);
                    spawnMenu.setHovered(idx);
                    boolean left = window.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                    if (left && !leftWasPressed && idx >= 0) {
                        SpawnMenu.Action a = spawnMenu.actionAt(idx);
                        if (a != null) spawnFromMenu(a, camera, poops, eaters, npcRng, world.tribes);
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

                dayNight.update(dt);
                weather.update(dt);

                Vector3f sunDir   = dayNight.sunDir();
                Vector3f sunColor = dayNight.sunColor();
                Vector3f ambient  = dayNight.ambient();
                Vector3f fogColor = dayNight.fogColor();
                float fogDensity  = dayNight.fogDensity();

                float sunIntensity = dayNight.sunIntensity() * weather.sunIntensityModifier();
                sunColor.mul(weather.sunIntensityModifier());
                ambient.mul(weather.ambientDampening());

                Vector3f eye = new Vector3f(sunDir).mul(200f);
                if (Math.abs(sunDir.y) > 0.99f) { eye.x += 0.001f; eye.z += 0.001f; }
                lightView.identity().lookAt(eye.x, eye.y, eye.z, 0, 0, 0, 0, 1, 0);
                lightVP.set(lightProj).mul(lightView);

                List<Eater> newborns = new ArrayList<>();
                for (Eater e : eaters) e.update(dt, poops, eaters, newborns, world, camera.position());
                if (!newborns.isEmpty()) eaters.addAll(newborns);
                poops.removeIf(p -> p.eaten);

                if (sunIntensity > 0.05f) {
                    shadowMap.bind();
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);

                    shadowShader.bind();
                    shadowShader.setUniform("uLightVP", lightVP);
                    shadowShader.setUniform1i("uTexture", 0);
                    for (HouseInstance t : staticTrees) t.render(shadowShader, camera.view(), camera.projection());
                    for (Tribe tribe : world.tribes) {
                        for (HouseInstance h : tribe.houses) h.render(shadowShader, camera.view(), camera.projection());
                    }
                    shadowShader.unbind();

                    spriteShadowShader.bind();
                    spriteShadowShader.setUniform("uLightVP", lightVP);
                    spriteShadowShader.setUniform1i("uTexture", 0);
                    for (Sprite s : sprites) s.renderShadow(spriteShadowShader, lightVP, sunDir, now);
                    for (Eater e : eaters)  e.renderShadow(spriteShadowShader, lightVP, sunDir, now);
                    spriteShadowShader.unbind();

                    shadowMap.unbind(window.width(), window.height());
                }

                renderer.clear();
                sky.render(camera, dayNight, weather, now);

                Vector3f camPos = camera.position();
                ground.render(camera.view(), camera.projection(),
                        lightVP, shadowMap.textureId(),
                        sunDir, sunColor, ambient, camPos, fogColor, fogDensity);

                water.render(camera.view(), camera.projection(), dayNight, camPos, now, fogColor, fogDensity);

                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadowMap.textureId());
                GL13.glActiveTexture(GL13.GL_TEXTURE0);

                houseShader.bind();
                houseShader.setUniform("uLightVP", lightVP);
                houseShader.setUniform1i("uShadowMap", 1);
                houseShader.setUniform1i("uTexture", 0);
                houseShader.setUniform3f("uSunDir", sunDir.x, sunDir.y, sunDir.z);
                houseShader.setUniform3f("uSunColor", sunColor.x, sunColor.y, sunColor.z);
                houseShader.setUniform3f("uAmbient", ambient.x, ambient.y, ambient.z);
                houseShader.setUniform3f("uCamPos", camPos.x, camPos.y, camPos.z);
                houseShader.setUniform3f("uFogColor", fogColor.x, fogColor.y, fogColor.z);
                houseShader.setUniform1f("uFogDensity", fogDensity);

                GL11.glDepthMask(false);
                for (HouseInstance t : staticTrees) t.render(houseShader, camera.view(), camera.projection());
                GL11.glDepthMask(true);
                
                for (Tribe tribe : world.tribes) {
                    for (HouseInstance h : tribe.houses) h.render(houseShader, camera.view(), camera.projection());
                }
                houseShader.unbind();

                Poop.begin(camera.view(), camera.projection());
                Vector3f tint = new Vector3f(
                        ambient.x + sunColor.x * 0.4f,
                        ambient.y + sunColor.y * 0.4f,
                        ambient.z + sunColor.z * 0.4f
                );
                Poop.setLightTint(tint.x, tint.y, tint.z);
                for (Poop p : poops) p.render();
                Poop.end();

                spriteShader.bind();
                spriteShader.setUniform3f("uLightTint", tint.x, tint.y, tint.z);
                for (Sprite s : sprites) s.render(spriteShader, camera.view(), camera.projection(), camera.position(), now);
                for (Eater e : eaters) e.render(spriteShader, camera.view(), camera.projection(), camera.position(), now);
                spriteShader.unbind();

                GL11.glEnable(GL11.GL_BLEND);
                GL11.glDepthMask(false);
                for (Eater e : eaters) {
                    if (e.getTribe() != null) {
                        TribeMarker.render(camera.view(), camera.projection(), e.position().x, e.position().y, e.position().z, 1.2f, e.getTribe().color);
                    }
                }
                GL11.glDepthMask(true);
                GL11.glDisable(GL11.GL_BLEND);

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
            if (poopIcon != null) poopIcon.destroy();
            if (brightPoopIcon != null) brightPoopIcon.destroy();
            if (treeModel != null) treeModel.destroy();
            if (vWalls != null) vWalls.destroy(); if (vRoof != null) vRoof.destroy(); if (vDoor != null) vDoor.destroy(); if (vWindows != null) vWindows.destroy();
            shadowMap.destroy();
            TribeMarker.destroy();
            lineRenderer.destroy();
            water.destroy();
            Poop.destroyAll(); ui.destroy(); spriteShader.destroy(); spriteShadowShader.destroy();
            houseShader.destroy(); shadowShader.destroy();
            sky.destroy(); ground.destroy(); window.destroy();
        }
    }

    private static float[] spawnPoint(Camera camera) {
        Vector3f front = camera.frontVector();
        float yaw = (float) Math.atan2(front.z, front.x);
        Vector3f cam = camera.position();
        float x = cam.x + (float) Math.cos(yaw) * 10f;
        float z = cam.z + (float) Math.sin(yaw) * 10f;
        float y = Ground.getHeightAt(x, z);
        return new float[]{ x, y, z };
    }

    private static void spawnFromMenu(SpawnMenu.Action action, Camera camera, List<Poop> poops, List<Eater> eaters, Random rng, List<Tribe> tribes) {
        float[] sp = spawnPoint(camera);
        float sx = sp[0], sy = sp[1], sz = sp[2];
        Tribe randomTribe = tribes.get(rng.nextInt(tribes.size()));

        switch (action) {
            case SPAWN_TEEN        -> eaters.add(new Eater(Eater.Type.TEEN, sx, sz, randomTribe, rng));
            case SPAWN_FAT         -> eaters.add(new Eater(Eater.Type.FAT, sx, sz, randomTribe, rng));
            case SPAWN_MUSCULAR    -> eaters.add(new Eater(Eater.Type.MUSCULAR, sx, sz, randomTribe, rng));
            case SPAWN_FEMALE      -> eaters.add(new Eater(Eater.Type.FEMALE, sx, sz, randomTribe, rng));
            case SPAWN_PREGNANT    -> eaters.add(new Eater(Eater.Type.PREGNANT, sx, sz, randomTribe, rng));
            case SPAWN_BABY        -> eaters.add(new Eater(Eater.Type.BABY, sx, sz, randomTribe, rng));
            case SPAWN_YOUNG_BOY   -> eaters.add(new Eater(Eater.Type.YOUNG_BOY, sx, sz, randomTribe, rng));
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
                eaters.add(new Eater(Eater.Type.TEEN, sx, sz, randomTribe, rng));
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
                    eaters.add(new Eater(Eater.Type.TEEN, sx + (float) Math.cos(a) * r, sz + (float) Math.sin(a) * r, randomTribe, rng));
                }
            }
        }
    }
}
