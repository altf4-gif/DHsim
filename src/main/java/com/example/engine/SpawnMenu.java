package com.example.engine;

import java.awt.Color;

public final class SpawnMenu {

    public enum Action {
        SPAWN_TEEN, SPAWN_FAT, SPAWN_MUSCULAR, SPAWN_FEMALE, SPAWN_PREGNANT,
        SPAWN_BABY, SPAWN_YOUNG_BOY,
        SPAWN_POOP, SPAWN_BRIGHT_POOP, SPAWN_POOP_CLUSTER,
        SPAWN_COMBO, SPAWN_TEEN_CROWD,
        SPAWN_MRH_FEMALE, SPAWN_MRH_KACHOK, SPAWN_MRH_TOLSTIY
    }

    private static final String[] LABELS = {
            "Подросток",
            "Толстяк",
            "Качок",
            "Женщина",
            "Беременная",
            "Малыш",
            "Подросток-мальчик",
            "Какашка",
            "Яркая какашка",
            "Кучка какашек",
            "Подросток + 5 какашек",
            "Толпа подростков",
            "Женщина (MrH)",
            "Качок (MrH)",
            "Толстяк (MrH)"
    };

    private static final Action[] ACTIONS = {
            Action.SPAWN_TEEN, Action.SPAWN_FAT, Action.SPAWN_MUSCULAR,
            Action.SPAWN_FEMALE, Action.SPAWN_PREGNANT, Action.SPAWN_BABY,
            Action.SPAWN_YOUNG_BOY,
            Action.SPAWN_POOP, Action.SPAWN_BRIGHT_POOP, Action.SPAWN_POOP_CLUSTER,
            Action.SPAWN_COMBO, Action.SPAWN_TEEN_CROWD,
            Action.SPAWN_MRH_FEMALE, Action.SPAWN_MRH_KACHOK, Action.SPAWN_MRH_TOLSTIY
    };

    /** 0 = creature texture (index in ICON_TEX), 1 = poop icon, 2 = bright poop icon. */
    private static final int[] ICON_KIND = { 0, 0, 0, 0, 0, 0, 0, 1, 2, 1, 0, 0, 0, 0, 0 };
    /** For kind 0, the index inside typeTextures (== Eater.Type.ordinal()). */
    private static final int[] ICON_TEX  = { 0, 1, 6, 2, 3, 4, 5, -1, -1, -1, 0, 0, 2, 6, 1 };

    private static final float PANEL_W_BASE = 720f;
    private static final float HEADER_BASE  = 110f;
    private static final float FOOTER_BASE  = 50f;
    private static final float GAP_BASE     = 6f;
    private static final float ROW_MAX_BASE = 80f;
    private static final float ROW_MIN      = 20f;
    private static final float ICON_BASE    = 64f;
    private static final float ICON_PAD_BASE= 16f;

    private final UiTexture titleTexture;
    private final UiTexture hintTexture;
    private final UiTexture[] labelTextures;
    private final Texture[] typeTextures;
    private final UiTexture poopIcon;
    private final UiTexture brightPoopIcon;

    private boolean open = false;
    private int hovered = -1;

    public SpawnMenu(Texture[] typeTextures, UiTexture poopIcon, UiTexture brightPoopIcon) {
        this.typeTextures = typeTextures;
        this.poopIcon = poopIcon;
        this.brightPoopIcon = brightPoopIcon;

        titleTexture = TextRenderer.createText("СПАВН", 30, 760, new Color(255, 220, 110));
        hintTexture = TextRenderer.createText(
                "Наведи курсор и кликни.  Q / Esc — закрыть.",
                16, 560, new Color(150, 155, 170));

        labelTextures = new UiTexture[LABELS.length];
        for (int i = 0; i < LABELS.length; i++) {
            labelTextures[i] = TextRenderer.createText(LABELS[i], 22, 520, new Color(230, 232, 238));
        }
    }

    public void open()   { open = true; hovered = -1; }
    public void close()  { open = false; hovered = -1; }
    public void toggle() { if (open) close(); else open(); }
    public boolean isOpen() { return open; }
    public void setHovered(int i) { hovered = i; }

    private static float uiScale(int sh) { return Math.min(Math.max(sh / 720f, 1f), 2f); }

    private static float rowHeight(int sh) {
        float s = uiScale(sh);
        float maxH = sh * 0.92f;
        float header = HEADER_BASE * s;
        float footer = FOOTER_BASE * s;
        float gap = GAP_BASE * s;
        int n = LABELS.length;
        float avail = maxH - header - footer - (n - 1) * gap;
        return Math.min(Math.max(avail / n, ROW_MIN), ROW_MAX_BASE * s);
    }

    private static float panelHeight(int sh) {
        float s = uiScale(sh);
        float n = LABELS.length;
        return HEADER_BASE * s + n * rowHeight(sh) + (n - 1) * GAP_BASE * s + FOOTER_BASE * s;
    }

    private static float panelTop(int sh) { return sh / 2f - panelHeight(sh) / 2f; }

    private static float rowCenterY(int sh, int i) {
        float s = uiScale(sh);
        float top = panelTop(sh) + HEADER_BASE * s;
        float rowH = rowHeight(sh);
        float gap  = GAP_BASE * s;
        return top + rowH / 2f + i * (rowH + gap);
    }

    public int hitTest(int sw, int sh, double mx, double my) {
        float s = uiScale(sh);
        float rowW = PANEL_W_BASE * s - 40f * s;
        float rowH = rowHeight(sh);
        float left  = sw / 2f - rowW / 2f;
        float right = sw / 2f + rowW / 2f;
        for (int i = 0; i < LABELS.length; i++) {
            float cy = rowCenterY(sh, i);
            if (mx >= left && mx <= right && my >= cy - rowH / 2f && my <= cy + rowH / 2f) return i;
        }
        return -1;
    }

    public Action actionAt(int i) {
        if (i < 0 || i >= ACTIONS.length) return null;
        return ACTIONS[i];
    }

    public void render(UiRenderer ui, int sw, int sh) {
        float s = uiScale(sh);
        float panelW = PANEL_W_BASE * s;
        float panelH = panelHeight(sh);
        float panelCx = sw / 2f;
        float panelCy = sh / 2f;

        ui.drawSolid(panelCx, panelCy, sw, sh, 0f, 0f, 0f, 0.62f);
        ui.drawSolid(panelCx, panelCy, panelW, panelH, 0.15f, 0.18f, 0.24f, 1f);
        ui.drawSolid(panelCx, panelCy, panelW - 3, panelH - 3, 0.07f, 0.08f, 0.11f, 1f);

        float titleH = 40 * s;
        float titleW = titleH * ((float) titleTexture.width / titleTexture.height);
        float titleY = panelTop(sh) + 30 * s;
        ui.drawTextured(panelCx, titleY, titleW, titleH, titleTexture.id);

        float rowW    = panelW - 40f * s;
        float rowH    = rowHeight(sh);
        float iconPad = ICON_PAD_BASE * s;
        float iconBox = Math.min(ICON_BASE * s, rowH * 0.85f);

        for (int i = 0; i < LABELS.length; i++) {
            float cy = rowCenterY(sh, i);
            if (i == hovered) {
                ui.drawSolid(panelCx, cy, rowW, rowH, 0.55f, 0.42f, 0.10f, 0.60f);
                ui.drawSolid(panelCx, cy, rowW - 4, rowH - 4, 0.24f, 0.28f, 0.36f, 1f);
            } else {
                ui.drawSolid(panelCx, cy, rowW, rowH, 0.16f, 0.20f, 0.27f, 1f);
            }

            float iconCx = panelCx - rowW / 2f + iconPad + iconBox / 2f;
            float iconRight = iconCx + iconBox / 2f;

            int kind = ICON_KIND[i];
            if (kind == 0) {
                int ti = ICON_TEX[i];
                if (ti >= 0 && typeTextures != null && ti < typeTextures.length) {
                    Texture t = typeTextures[ti];
                    if (t != null) drawContentTex(ui, iconCx, cy, iconBox, t);
                }
            } else if (kind == 1) {
                if (poopIcon != null) ui.drawTextured(iconCx, cy, iconBox, iconBox, poopIcon.id);
            } else {
                if (brightPoopIcon != null) ui.drawTextured(iconCx, cy, iconBox, iconBox, brightPoopIcon.id);
            }

            UiTexture lt = labelTextures[i];
            float tH = Math.min(22 * s, rowH * 0.45f);
            float tW = tH * ((float) lt.width / lt.height);
            float labelCx = ((iconRight + iconPad) + (panelCx + rowW / 2f - iconPad)) / 2f;
            ui.drawTextured(labelCx, cy, tW, tH, lt.id);
        }

        float hintH = 20 * s;
        float hintW = hintH * ((float) hintTexture.width / hintTexture.height);
        float hintY = panelTop(sh) + panelH - 30 * s;
        ui.drawTextured(panelCx, hintY, hintW, hintH, hintTexture.id);
    }

    private static void drawContentTex(UiRenderer ui, float cx, float cy,
                                       float boxSize, Texture t) {
        float aspect = t.contentAspect();
        float drawH = boxSize;
        float drawW = drawH * aspect;
        if (drawW > boxSize) { drawW = boxSize; drawH = drawW / aspect; }
        ui.drawTexturedUV(cx, cy, drawW, drawH, t.getId(),
                t.uMin, t.vMin, t.uMax, t.vMax);
    }

    public void destroy() {
        titleTexture.destroy();
        hintTexture.destroy();
        for (UiTexture t : labelTextures) t.destroy();
    }
}
