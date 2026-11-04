package com.jubitus.ultreia.map;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Pan and zoom of the map, and world/screen coordinate conversion over a map area of width x mapH pixels. */
@SideOnly(Side.CLIENT)
final class MapView {

    private static final float ZOOM_MIN = 0.003f;
    private static final float ZOOM_MAX = 10f;
    // Segments with both ends this far outside the map area (pixels) are not drawn.
    private static final int OFFSCREEN_PAD = 24;

    double viewX, viewZ;
    float zoom;
    int width, mapH;

    private double dragViewX, dragViewZ;
    private int dragMouseX, dragMouseY;

    /** Centres the view on (cx, cz) with the whole radius in sight. */
    void reset(int cx, int cz, int radius, int width, int mapH) {
        this.width = width;
        this.mapH = mapH;
        viewX = cx;
        viewZ = cz;
        float minDim = Math.min(width, mapH);
        zoom = Math.max(ZOOM_MIN, minDim / (radius * 2.2f));
    }

    float worldToScreenX(double wx) {
        return (float) (width * 0.5 + (wx - viewX) * zoom);
    }

    float worldToScreenZ(double wz) {
        return (float) (mapH * 0.5 + (wz - viewZ) * zoom);
    }

    double screenToWorldX(int sx) {
        return viewX + (sx - width * 0.5) / zoom;
    }

    double screenToWorldZ(int sy) {
        return viewZ + (sy - mapH * 0.5) / zoom;
    }

    boolean bothOffScreen(float x1, float y1, float x2, float y2) {
        int p = OFFSCREEN_PAD;
        return (x1 < -p || x1 > width + p || y1 < -p || y1 > mapH + p)
            && (x2 < -p || x2 > width + p || y2 < -p || y2 > mapH + p);
    }

    void startDrag(int mouseX, int mouseY) {
        dragMouseX = mouseX;
        dragMouseY = mouseY;
        dragViewX = viewX;
        dragViewZ = viewZ;
    }

    void dragTo(int mouseX, int mouseY) {
        viewX = dragViewX - (mouseX - dragMouseX) / (double) zoom;
        viewZ = dragViewZ - (mouseY - dragMouseY) / (double) zoom;
    }

    /** Zooms in (scroll > 0) or out around the screen point (mx, my), which keeps its world position. */
    void zoomAt(int mx, int my, int scroll) {
        double wMx = screenToWorldX(mx), wMz = screenToWorldZ(my);
        float factor = scroll > 0 ? 1.25f : 0.8f;
        float newZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom * factor));
        viewX = wMx - (mx - width * 0.5) / newZoom;
        viewZ = wMz - (my - mapH * 0.5) / newZoom;
        zoom = newZoom;
    }
}
