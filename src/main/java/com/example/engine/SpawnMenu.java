package com.example.engine;

import java.awt.Color;

/**
 * Single, mouse-driven spawn menu. Opens with Q.
 *
 * Move the mouse over a row and click it with the left button to spawn that
 * thing 10 m in front of where the camera is looking. Q or Esc closes it.
 *
 * The exact same layout maths is used by {@link #hitTest} and {@link #render},
 * so what you see is exactly what you click.
 */
public final class SpawnMenu {

    public enum Action {
        SPAWN_TEEN,
        SPAWN_POOP,
        SPAWN_POOP_CLUSTER,
        SPAWN_TEEN_CROWD,
        SPAWN_COMBO
    }

    private static final String[] OPTIONS = {
            "Подросток (1 шт.)",
            "Какашка (1 шт.)",
            "Кучка какашек (5 шт.)",
            "Толпа подростков (5 шт.)",
            "Подросток + 5 какашек",
    };

    private static final Action[] ACTIONS = {
            Action.SPAWN_TEEN,
            Action.SPAWN_POOP,
            Action.SPAWN_POOP_CLUSTER,
            Action.SPAWN_TEEN_CROWD,
            Action.SPAWN_COMBO,
    };

    // ---- layout (kept in one place so hit-test and drawing agree) ----
    private static final float PANEL_W   = 680f;
    private static final float PANEL_H   = 580f;
    private static final float BTN_W     = 500f;
    private static final float BTN_H     = 58f;
    private static final float BTN_GAP   = 12f;
    /** Distance from the panel's top edge down to the first button's top edge. */
    private static final float LIST_TOP  = 118f;

    private boolean open = false;
    private int hovered = -1;

    private final UiTexture titleTexture;
    private final UiTexture hintTexture;
    private final UiTexture[] nameTextures;

    public SpawnMenu() {
        titleTexture = TextRenderer.createText(
                "СПАВН", 30, 760, new Color(255, 220, 110));
        hintTexture = TextRenderer.createText(
                "Наведи курсор и кликни.  Q / Esc — закрыть.",
                16, 560, new Color(150, 155, 170));

        nameTextures = new UiTexture[OPTIONS.length];
        for (int i = 0; i < OPTIONS.length; i++) {
            nameTextures[i] = TextRenderer.createText(
                    OPTIONS[i], 22, 520, new Color(230, 232, 238));
        }
    }

    // ------------------------------------------------------------ state

    public void open() {
        open = true;
        hovered = -1;
    }

    public void close() {
        open = false;
        hovered = -1;
    }

    public void toggle() {
        if (open) close(); else open();
    }

    public boolean isOpen() {
        return open;
    }

    public void setHovered(int index) {
        hovered = index;
    }

    // ---------------------------------------------------------- layout

    private static float firstButtonTop(int sh) {
        float panelTop = sh / 2f - PANEL_H / 2f;
        return panelTop + LIST_TOP;
    }

    private static float buttonCenterY(int sh, int i) {
        return firstButtonTop(sh) + BTN_H / 2f + i * (BTN_H + BTN_GAP);
    }

    /** Index of the button under the cursor, or -1. */
    public int hitTest(int sw, int sh, double mx, double my) {
        float panelCx = sw / 2f;
        float left  = panelCx - BTN_W / 2f;
        float right = panelCx + BTN_W / 2f;

        for (int i = 0; i < OPTIONS.length; i++) {
            float cy = buttonCenterY(sh, i);
            float top = cy - BTN_H / 2f;
            float bot = cy + BTN_H / 2f;
            if (mx >= left && mx <= right && my >= top && my <= bot) return i;
        }
        return -1;
    }

    public Action actionAt(int index) {
        if (index < 0 || index >= ACTIONS.length) return null;
        return ACTIONS[index];
    }

    // ------------------------------------------------------------ draw

    public void render(UiRenderer ui, int sw, int sh) {
        float panelCx = sw / 2f;
        float panelCy = sh / 2f;

        // dim + panel
        ui.drawSolid(panelCx, panelCy, sw, sh, 0f, 0f, 0f, 0.62f);
        ui.drawSolid(panelCx, panelCy, PANEL_W, PANEL_H, 0.15f, 0.18f, 0.24f, 1f);
        ui.drawSolid(panelCx, panelCy, PANEL_W - 3, PANEL_H - 3, 0.07f, 0.08f, 0.11f, 1f);

        // title
        float titleH = 40;
        float titleW = titleH * ((float) titleTexture.width / titleTexture.height);
        float titleY = panelCy - PANEL_H / 2f + 46;
        ui.drawTextured(panelCx, titleY, titleW, titleH, titleTexture.id);

        // buttons
        for (int i = 0; i < OPTIONS.length; i++) {
            float cy = buttonCenterY(sh, i);

            if (i == hovered) {
                ui.drawSolid(panelCx, cy, BTN_W, BTN_H, 0.55f, 0.42f, 0.10f, 0.60f);
                ui.drawSolid(panelCx, cy, BTN_W - 4, BTN_H - 4, 0.24f, 0.28f, 0.36f, 1f);
            } else {
                ui.drawSolid(panelCx, cy, BTN_W, BTN_H, 0.16f, 0.20f, 0.27f, 1f);
            }

            UiTexture t = nameTextures[i];
            float tH = 26;
            float tW = tH * ((float) t.width / t.height);
            ui.drawTextured(panelCx, cy, tW, tH, t.id);
        }

        // hint
        float hintH = 20;
        float hintW = hintH * ((float) hintTexture.width / hintTexture.height);
        float hintY = panelCy + PANEL_H / 2f - 32;
        ui.drawTextured(panelCx, hintY, hintW, hintH, hintTexture.id);
    }

    public void destroy() {
        titleTexture.destroy();
        hintTexture.destroy();
        for (UiTexture t : nameTextures) t.destroy();
    }
}
