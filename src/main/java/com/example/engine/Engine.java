package com.example.engine;

import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class Engine {

    private static final String VERSION = "v7 (Person Encyclopedia)";

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
                FragColor = texture(uTexture, vTexCoord);
            }
            """;

    private static final String[] PEOPLE_IMAGES = {
            "black_male_tpose.png", "child_boy_tpose.png", "elderly_male_tpose.png", "female_slim_tpose.png",
            "hazmat_suit_yellow_tpose.png", "leather_coat_military_tpose.png", "male_bent_over_pose.png", "male_police_costume_tpose.png",
            "military_uniform_torn_tpose.png", "muscular_male_tpose.png", "overweight_male_tpose.png", "pregnant_female_tpose.png",
            "teenager_male_tpose.png", "teenager_sign_mars.png", "teenager_sign_muyakat.png", "teenager_sign_simulation.png",
            "young_boy_tpose.png"
    };

    // Впиши сюда свои описания для каждого персонажа!
    private static final String[] PEOPLE_DESCRIPTIONS = {
            "Описание для человека в чёрном пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для ребёнка пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для пожилого мужчины пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для стройной женщины пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для человека в хазмате пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для человека в шинели пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для согнувшегося пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для полицейского пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для человека в рваной форме пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для качка пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для толстяка пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для беременной пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для подростка пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для плаката 'Марс' пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для плаката 'Муякать' пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для плаката 'Симуляция' пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java.",
            "Описание для мальчика пока не добавлено.\n\nОтредактируйте массив PEOPLE_DESCRIPTIONS в Engine.java."
    };

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

        List<Sprite> sprites = new ArrayList<>();
        List<Person> peopleList = new ArrayList<>();
        final float startX = -4.0f, spacingX = 1.2f, spriteHeight = 1.5f;
        
        for (int i = 0; i < PEOPLE_IMAGES.length; i++) {
            if (peopleTex[i] == null) continue;
            
            peopleList.add(new Person(PEOPLE_NAMES[i], PEOPLE_DESCRIPTIONS[i], peopleTex[i]));

            int row = i / 9;
            int col = i % 9;
            float x = startX + col * spacingX;
            if (row == 1) x += spacingX * 0.5f;
            float z = (row == 0) ? -2.0f : 0.0f;
            sprites.add(new Sprite(peopleTex[i], new Vector3f(x, spriteHeight * 0.5f, z), spriteHeight));
        }

        Encyclopedia encyclopedia = new Encyclopedia(peopleList);

        System.out.println("Loading house textures from img/H/ ...");
        Texture texWall = null, texFront = null, texRoof = null;
        try {
            texWall  = new Texture("img/H/Hos_L.png", true, false);
            texFront = new Texture("img/H/Hos_V.png", false, false);
            texRoof  = new Texture("img/H/Hos_K.png", true, false);
        } catch (Exception e) {
            System.err.println("[ERROR] House textures failed: " + e.getMessage());
        }

        List<House> houses = new ArrayList<>();
        if (texWall != null && texFront != null && texRoof != null) {
            Random rng = new Random(42);
            List<Vector3f> placed = new ArrayList<>();
            int attempts = 0;
            while (houses.size() < 24 && attempts < 500) {
                attempts++;
                float x = (rng.nextFloat() - 0.5f) * 150f;
                float z = (rng.nextFloat() - 0.5f) * 150f;
                if (Math.abs(x) < 14f && Math.abs(z) < 12f) continue;

                boolean tooClose = false;
                for (Vector3f p : placed) {
                    if (p.distanceSquared(x, 0f, z) < 100f) { tooClose = true; break; }
                }
                if (tooClose) continue;
                placed.add(new Vector3f(x, 0f, z));

                float w  = 6f + rng.nextFloat() * 4f;
                float d  = 7f + rng.nextFloat() * 4f;
                float h  = 6f + rng.nextFloat() * 6f;
                float rh = 2.5f + rng.nextFloat() * 2f;
                float yaw = (float) Math.atan2(-x, -z);

                houses.add(new House(x, z, yaw, w, d, h, rh, texWall, texFront, texRoof));
            }
        }

        float lastTime = (float) GLFW.glfwGetTime();
        boolean qWasPressed = false;
        boolean upWasPressed = false;
        boolean downWasPressed = false;

        try {
            while (!window.shouldClose()) {
                float now = (float) GLFW.glfwGetTime();
                float dt = Math.min(now - lastTime, 0.1f);
                lastTime = now;

                window.pollEvents();
                if (window.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) break;

                // Toggle encyclopedia
                boolean qPressed = window.isKeyPressed(GLFW.GLFW_KEY_Q);
                if (qPressed && !qWasPressed) encyclopedia.toggle();
                qWasPressed = qPressed;

                // Navigation when open
                if (encyclopedia.isOpen()) {
                    boolean upPressed = window.isKeyPressed(GLFW.GLFW_KEY_UP);
                    if (upPressed && !upWasPressed) encyclopedia.prev();
                    upWasPressed = upPressed;

                    boolean downPressed = window.isKeyPressed(GLFW.GLFW_KEY_DOWN);
                    if (downPressed && !downWasPressed) encyclopedia.next();
                    downWasPressed = downPressed;
                }

                camera.update(window, dt);

                renderer.clear();
                sky.render();
                ground.render(camera.view(), camera.projection());

                if (!houses.isEmpty()) {
                    houseShader.bind();
                    for (House h : houses) h.render(houseShader, camera.view(), camera.projection());
                    houseShader.unbind();
                }

                spriteShader.bind();
                for (Sprite s : sprites) {
                    s.render(spriteShader, camera.view(), camera.projection(), camera.position(), now);
                }
                spriteShader.unbind();

                if (encyclopedia.isOpen()) {
                    ui.begin(window.width(), window.height());
                    encyclopedia.render(ui, window.width(), window.height());
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
            ui.destroy();
            spriteShader.destroy();
            houseShader.destroy();
            sky.destroy();
            ground.destroy();
            window.destroy();
        }
    }
}
