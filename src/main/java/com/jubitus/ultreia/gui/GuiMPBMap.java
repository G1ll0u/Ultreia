package com.jubitus.ultreia.gui;

import com.jubitus.ultreia.network.MPBNetworkHandler;
import com.jubitus.ultreia.network.PacketNetworkPreview;
import com.jubitus.ultreia.network.PacketOpenMapGui;
import com.jubitus.ultreia.network.PacketRequestPreview;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SideOnly(Side.CLIENT)
public final class GuiMPBMap extends GuiScreen {

    private static final int PANEL_H  = 38;
    private static final float ZOOM_MIN = 0.003f;
    private static final float ZOOM_MAX = 10f;

    // Colors
    private static final int C_MAP_BG      = 0xFF090B12;
    private static final int C_PANEL_BG    = 0xFF140F08;
    private static final int C_SEPARATOR   = 0xFF443322;
    private static final int C_ROAD        = 0xFFC8A060;
    private static final int C_BRIDGE      = 0xFF40C0FF;
    private static final int C_VILLAGE     = 0xFFFFD060;
    private static final int C_PLAYER      = 0xFFFF4444;
    private static final int C_TEXT        = 0xFFCCAA66;
    private static final int C_GRID        = 0x22AABBCC;
    private static final int C_NORTH       = 0xFFAABBFF;
    // Preview colors
    private static final int C_PRV_HUB     = 0xFFFFD060; // gold
    private static final int C_PRV_MEDIUM  = 0xFFCCCCCC; // silver
    private static final int C_PRV_SMALL   = 0xFF888888; // grey
    private static final int C_PRV_LONE    = 0xFF8B6040; // brown

    // Button IDs
    private static final int BTN_EXPORT  = 100;
    private static final int BTN_RESET   = 101;
    private static final int BTN_CLOSE   = 102;
    private static final int BTN_PREVIEW = 103;

    private final int centerX, centerZ, radius;
    private final List<PacketOpenMapGui.SegmentData> segments;
    private final Set<Long> villageKeys = new HashSet<>();
    private final int roadCount, bridgeCount;

    private double viewX, viewZ;
    private float zoom;

    private boolean dragging;
    private int dragMouseX, dragMouseY;
    private double dragViewX, dragViewZ;

    private int hoveredSegId  = -1;
    private int hoveredWptIdx = -1; // waypoint index within hoveredSegId, -1 = none
    private int hoveredNodeIdx = -1; // index into previewNodes, -1 = none

    private String exportFeedback = null;
    private int exportFeedbackTicks = 0;

    // Preview data — null until received from server
    private List<PacketNetworkPreview.NodeData> previewNodes = null;
    private List<PacketNetworkPreview.EdgeData> previewEdges = null;
    private boolean previewPending = false;

    public GuiMPBMap(int centerX, int centerZ, int radius,
                     List<PacketOpenMapGui.SegmentData> segments) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius  = radius;
        this.segments = segments;

        int roads = 0, bridges = 0;
        for (PacketOpenMapGui.SegmentData s : segments) {
            if (s.isBridge) bridges++; else roads++;
            if (s.fromX != PacketOpenMapGui.ABSENT) villageKeys.add(packXZ(s.fromX, s.fromZ));
            if (s.toX   != PacketOpenMapGui.ABSENT) villageKeys.add(packXZ(s.toX,   s.toZ));
        }
        this.roadCount   = roads;
        this.bridgeCount = bridges;
    }

    /** Called by ClientProxy when PacketNetworkPreview is received. */
    public void setPreviewData(List<PacketNetworkPreview.NodeData> nodes,
                               List<PacketNetworkPreview.EdgeData> edges) {
        this.previewNodes   = nodes;
        this.previewEdges   = edges;
        this.previewPending = false;
        if (buttonList != null) {
            GuiButton btn = getButton(BTN_PREVIEW);
            if (btn != null) btn.displayString = "Preview [P]";
        }
    }

    @Override
    public void initGui() {
        viewX = centerX;
        viewZ = centerZ;
        zoom  = defaultZoom();
        buttonList.clear();

        int btnY = height - PANEL_H + 9;
        buttonList.add(new GuiButton(BTN_EXPORT,  6,           btnY,  80, 20, "Export [E]"));
        buttonList.add(new GuiButton(BTN_RESET,   90,          btnY,  80, 20, "Reset [R]"));
        buttonList.add(new GuiButton(BTN_PREVIEW, 174,         btnY, 102, 20,
                previewPending ? "§7Loading..." : "Preview [P]"));
        buttonList.add(new GuiButton(BTN_CLOSE,   width - 90,  btnY,  84, 20, "Close [Esc]"));
    }

    private float defaultZoom() {
        int mapH = height - PANEL_H;
        float minDim = Math.min(width, mapH);
        return Math.max(ZOOM_MIN, minDim / (radius * 2.2f));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partial) {
        drawRect(0, 0, width, height - PANEL_H, C_MAP_BG);
        drawRect(0, height - PANEL_H, width, height, C_PANEL_BG);
        drawRect(0, height - PANEL_H, width, height - PANEL_H + 1, C_SEPARATOR);

        ScaledResolution sr = new ScaledResolution(mc);
        int scale = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, PANEL_H * scale, mc.displayWidth, (height - PANEL_H) * scale);

        drawGrid();
        drawAllSegments();
        if (previewEdges != null) drawPreviewEdges();
        drawVillages();
        if (previewNodes != null) drawPreviewNodes(mouseX, mouseY);
        drawPlayerMarker();
        drawNorthIndicator();

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        // HUD — starts after the preview button (174+102=276)
        double wMouseX = screenToWorldX(mouseX);
        double wMouseZ = screenToWorldZ(mouseY);
        String hud = String.format("X: %.0f  Z: %.0f   Zoom: %.3fx   Segs: %d  Roads: %d  Bridges: %d",
                wMouseX, wMouseZ, zoom, segments.size(), roadCount, bridgeCount);
        drawString(fontRenderer, hud, 282, height - PANEL_H + 13, C_TEXT);

        super.drawScreen(mouseX, mouseY, partial);

        updateHover(mouseX, mouseY);
        if (hoveredSegId >= 0) drawHoverTooltip(mouseX, mouseY);
        if (hoveredNodeIdx >= 0 && previewNodes != null) drawNodeTooltip(mouseX, mouseY);

        if (exportFeedback != null && exportFeedbackTicks > 0) {
            exportFeedbackTicks--;
            int alpha = Math.min(255, exportFeedbackTicks * 5);
            drawString(fontRenderer, exportFeedback, 6, height - PANEL_H - 12, (alpha << 24) | 0x80FF80);
        }
    }

    // -------------------------------------------------------------------------
    // Map rendering
    // -------------------------------------------------------------------------

    private void drawGrid() {
        int mapH = height - PANEL_H;

        double minWX = screenToWorldX(0);
        double maxWX = screenToWorldX(width);
        double minWZ = screenToWorldZ(0);
        double maxWZ = screenToWorldZ(mapH);

        // ---------------------------------------------------------
        // Niveau de détail de grille selon le zoom
        // ---------------------------------------------------------
        int minorSpacing; // grille fine
        int majorSpacing; // grille forte

        if (zoom >= 8f) {
            minorSpacing = 1;     // bloc par bloc
            majorSpacing = 16;    // chunks
        } else if (zoom >= 2f) {
            minorSpacing = 4;
            majorSpacing = 16;
        } else if (zoom >= 0.5f) {
            minorSpacing = 16;
            majorSpacing = 64;
        } else if (zoom >= 0.1f) {
            minorSpacing = 64;
            majorSpacing = 256;
        } else {
            minorSpacing = 256;
            majorSpacing = 1024;
        }

        int startX = (int) Math.floor(minWX / minorSpacing) * minorSpacing;
        int startZ = (int) Math.floor(minWZ / minorSpacing) * minorSpacing;

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO
        );

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);

        // ---------------------------------------------------------
        // Lignes verticales
        // ---------------------------------------------------------
        for (int wx = startX; wx <= (int) maxWX + minorSpacing; wx += minorSpacing) {
            float sx = worldToScreenX(wx);

            boolean major = (wx % majorSpacing) == 0;

            float r, g, b, a;

            if (major) {
                // lignes importantes
                r = 0.30f;
                g = 0.35f;
                b = 0.42f;
                a = 0.45f;
            } else {
                // lignes fines (bloc/chunk)
                r = 0.12f;
                g = 0.14f;
                b = 0.18f;
                a = zoom >= 4f ? 0.30f : 0.16f;
            }

            buf.pos(sx, 0, 0).color(r, g, b, a).endVertex();
            buf.pos(sx, mapH, 0).color(r, g, b, a).endVertex();
        }

        // ---------------------------------------------------------
        // Lignes horizontales
        // ---------------------------------------------------------
        for (int wz = startZ; wz <= (int) maxWZ + minorSpacing; wz += minorSpacing) {
            float sy = worldToScreenZ(wz);

            boolean major = (wz % majorSpacing) == 0;

            float r, g, b, a;

            if (major) {
                r = 0.30f;
                g = 0.35f;
                b = 0.42f;
                a = 0.45f;
            } else {
                r = 0.12f;
                g = 0.14f;
                b = 0.18f;
                a = zoom >= 4f ? 0.30f : 0.16f;
            }

            buf.pos(0, sy, 0).color(r, g, b, a).endVertex();
            buf.pos(width, sy, 0).color(r, g, b, a).endVertex();
        }

        tess.draw();

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();

        // ---------------------------------------------------------
        // Coordonnées sur grosses lignes seulement
        // ---------------------------------------------------------
        if (zoom >= 0.08f) {
            for (int wx = startX; wx <= (int) maxWX + majorSpacing; wx += majorSpacing) {
                int sx = (int) worldToScreenX(wx);
                if (sx < 2 || sx > width - 20) continue;

                drawString(fontRenderer,
                        String.valueOf(wx),
                        sx + 2,
                        2,
                        0x55AACCFF);
            }

            for (int wz = startZ; wz <= (int) maxWZ + majorSpacing; wz += majorSpacing) {
                int sy = (int) worldToScreenZ(wz);
                if (sy < 10 || sy > mapH - 10) continue;

                drawString(fontRenderer,
                        String.valueOf(wz),
                        2,
                        sy - 4,
                        0x55AACCFF);
            }
        }
    }


    private void drawAllSegments() {
        if (segments.isEmpty()) return;
        int mapH = height - PANEL_H;

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        for (int pass = 0; pass < 2; pass++) {
            boolean wantBridge = (pass == 1);
            buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            boolean any = false;

            for (PacketOpenMapGui.SegmentData seg : segments) {
                if (seg.isBridge != wantBridge) continue;
                if (seg.id == hoveredSegId) continue;

                float r, g, b;
                if (seg.isBridge) { r = 0.25f; g = 0.75f; b = 1.00f; }
                else              { r = 0.78f; g = 0.63f; b = 0.38f; }

                int wc = seg.waypointCount();
                for (int i = 0; i < wc - 1; i++) {
                    float sx1 = worldToScreenX(seg.wx(i)),   sy1 = worldToScreenZ(seg.wz(i));
                    float sx2 = worldToScreenX(seg.wx(i+1)), sy2 = worldToScreenZ(seg.wz(i+1));
                    if (bothOffScreen(sx1, sy1, sx2, sy2, mapH)) continue;
                    buf.pos(sx1, sy1, 0).color(r, g, b, 0.9f).endVertex();
                    buf.pos(sx2, sy2, 0).color(r, g, b, 0.9f).endVertex();
                    any = true;
                }
                if (wc == 1) {
                    float sx = worldToScreenX(seg.wx(0)), sy = worldToScreenZ(seg.wz(0));
                    buf.pos(sx-2, sy, 0).color(r, g, b, 0.9f).endVertex();
                    buf.pos(sx+2, sy, 0).color(r, g, b, 0.9f).endVertex();
                    any = true;
                }
            }
            if (any) tess.draw(); else buf.finishDrawing();
        }

        if (hoveredSegId >= 0) {
            PacketOpenMapGui.SegmentData seg = segById(hoveredSegId);
            if (seg != null) {
                buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
                boolean any = false;
                int wc = seg.waypointCount();
                for (int i = 0; i < wc - 1; i++) {
                    float sx1 = worldToScreenX(seg.wx(i)),   sy1 = worldToScreenZ(seg.wz(i));
                    float sx2 = worldToScreenX(seg.wx(i+1)), sy2 = worldToScreenZ(seg.wz(i+1));
                    buf.pos(sx1, sy1, 0).color(1f, 1f, 0.2f, 1f).endVertex();
                    buf.pos(sx2, sy2, 0).color(1f, 1f, 0.2f, 1f).endVertex();
                    any = true;
                }
                if (any) tess.draw(); else buf.finishDrawing();
            }
        }

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    private void drawPreviewEdges() {
        if (previewEdges == null || previewEdges.isEmpty()) return;
        int mapH = height - PANEL_H;

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        boolean any = false;

        for (PacketNetworkPreview.EdgeData e : previewEdges) {
            float sx1 = worldToScreenX(e.x1), sy1 = worldToScreenZ(e.z1);
            float sx2 = worldToScreenX(e.x2), sy2 = worldToScreenZ(e.z2);
            if (bothOffScreen(sx1, sy1, sx2, sy2, mapH)) continue;

            float r, g, b, a;
            if (e.isLoneTrail) { r = 0.55f; g = 0.38f; b = 0.25f; a = 0.45f; }
            else               { r = 0.85f; g = 0.60f; b = 0.20f; a = 0.60f; }

            buf.pos(sx1, sy1, 0).color(r, g, b, a).endVertex();
            buf.pos(sx2, sy2, 0).color(r, g, b, a).endVertex();
            any = true;
        }
        if (any) tess.draw(); else buf.finishDrawing();

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    private void drawVillages() {
        for (long key : villageKeys) {
            int vx = (int)(key >> 32);
            int vz = (int)(key & 0xFFFFFFFFL);
            int sx = (int)worldToScreenX(vx);
            int sy = (int)worldToScreenZ(vz);
            if (sx < -6 || sx > width + 6 || sy < -6 || sy > height - PANEL_H + 6) continue;
            drawRect(sx - 4, sy,     sx + 4, sy + 1, C_VILLAGE);
            drawRect(sx,     sy - 4, sx + 1, sy + 4, C_VILLAGE);
            drawRect(sx - 2, sy - 2, sx + 2, sy + 2, 0xFFFFEE90);
        }
    }

    private void drawPreviewNodes(int mouseX, int mouseY) {
        if (previewNodes == null) return;
        int mapH = height - PANEL_H;
        hoveredNodeIdx = -1;

        for (int i = 0; i < previewNodes.size(); i++) {
            PacketNetworkPreview.NodeData n = previewNodes.get(i);
            int sx = (int) worldToScreenX(n.x);
            int sy = (int) worldToScreenZ(n.z);
            if (sx < -8 || sx > width + 8 || sy < -8 || sy > mapH + 8) continue;

            boolean hovered = Math.abs(mouseX - sx) <= 6 && Math.abs(mouseY - sy) <= 6;
            if (hovered && sy < mapH) hoveredNodeIdx = i;

            if (n.type == PacketNetworkPreview.TYPE_LONE) {
                // Small brown square
                int col = hovered ? 0xFFD4884A : C_PRV_LONE;
                drawRect(sx - 2, sy - 2, sx + 2, sy + 2, col);
            } else {
                // Diamond: size depends on type
                int half   = n.type == PacketNetworkPreview.TYPE_HUB   ? 6
                           : n.type == PacketNetworkPreview.TYPE_MEDIUM ? 4 : 3;
                int col    = n.type == PacketNetworkPreview.TYPE_HUB   ? (hovered ? 0xFFFFFF40 : C_PRV_HUB)
                           : n.type == PacketNetworkPreview.TYPE_MEDIUM ? (hovered ? 0xFFFFFFFF : C_PRV_MEDIUM)
                           :                                              (hovered ? 0xFFCCCCCC : C_PRV_SMALL);
                drawRect(sx - half, sy,      sx + half, sy + 1, col);
                drawRect(sx,        sy - half, sx + 1,  sy + half, col);
                drawRect(sx - half / 2, sy - half / 2, sx + half / 2, sy + half / 2, col);
            }

            // Name label — only when zoomed in enough to be readable
            if (zoom > 0.08f && n.type != PacketNetworkPreview.TYPE_LONE) {
                String label = buildLabel(n);
                drawString(fontRenderer, label, sx + 7, sy - 4, 0xAAFFDD88);
            }
        }
    }

    private static String buildLabel(PacketNetworkPreview.NodeData n) {
        if (n.population > 0) return n.name + " [" + n.population + "]";
        return n.name;
    }

    private void drawPlayerMarker() {
        int sx = (int)worldToScreenX(centerX);
        int sy = (int)worldToScreenZ(centerZ);
        drawRect(sx - 5, sy,     sx + 5, sy + 1, 0xFFFFFFFF);
        drawRect(sx,     sy - 5, sx + 1, sy + 5, 0xFFFFFFFF);
        drawRect(sx - 2, sy - 2, sx + 2, sy + 2, C_PLAYER);
    }

    private void drawNorthIndicator() {
        int nx = width / 2, ny = 6;
        drawString(fontRenderer, "N", nx - 2, ny, C_NORTH);
        drawRect(nx, ny - 5, nx + 1, ny - 1, C_NORTH);
    }

    private void drawHoverTooltip(int mouseX, int mouseY) {
        PacketOpenMapGui.SegmentData seg = segById(hoveredSegId);
        if (seg == null) return;

        List<String> lines = new ArrayList<>();
        lines.add("§e" + (seg.isBridge ? "BRIDGE" : "ROAD") + " #" + seg.id);
        int wc = seg.waypointCount();
        lines.add("Waypoints: " + wc);
        if (wc >= 2) {
            int dx = seg.wx(wc-1) - seg.wx(0), dz = seg.wz(wc-1) - seg.wz(0);
            lines.add("Length ≈ " + (int)Math.sqrt((double)dx*dx + (double)dz*dz) + " m");
            lines.add("Start: (" + seg.wx(0)    + ", " + seg.wy(0)    + ", " + seg.wz(0)    + ")");
            lines.add("End:   (" + seg.wx(wc-1) + ", " + seg.wy(wc-1) + ", " + seg.wz(wc-1) + ")");
        }
        if (hoveredWptIdx >= 0 && hoveredWptIdx < wc) {
            int wi = hoveredWptIdx;
            lines.add("§7--- waypoint #" + wi + " / " + (wc - 1) + " ---");
            lines.add("Pos: (" + seg.wx(wi) + ", " + seg.wy(wi) + ", " + seg.wz(wi) + ")");
            if (seg.hasTerrainY()) {
                int ty = seg.terrainAt(wi);
                int delta = seg.wy(wi) - ty;
                lines.add("Terrain Y: " + ty + "  ΔY: " + (delta >= 0 ? "+" : "") + delta);
            }
        }
        if (seg.fromX != PacketOpenMapGui.ABSENT)
            lines.add("From-village: (" + seg.fromX + ", " + seg.fromZ + ")");
        if (seg.toX != PacketOpenMapGui.ABSENT)
            lines.add("To-village: (" + seg.toX + ", " + seg.toZ + ")");

        drawHoveringText(lines, mouseX, mouseY, fontRenderer);
    }

    private void drawNodeTooltip(int mouseX, int mouseY) {
        if (previewNodes == null || hoveredNodeIdx < 0 || hoveredNodeIdx >= previewNodes.size()) return;
        PacketNetworkPreview.NodeData n = previewNodes.get(hoveredNodeIdx);

        List<String> lines = new ArrayList<>();
        String typeLabel;
        switch (n.type) {
            case PacketNetworkPreview.TYPE_HUB:    typeLabel = "§6HUB";         break;
            case PacketNetworkPreview.TYPE_MEDIUM: typeLabel = "§7MEDIUM";      break;
            case PacketNetworkPreview.TYPE_LONE:   typeLabel = "§8LONE BUILDING"; break;
            default:                               typeLabel = "§8SMALL";       break;
        }
        lines.add(typeLabel + " §r" + n.name);
        if (n.type != PacketNetworkPreview.TYPE_LONE) {
            lines.add("Population: " + (n.population > 0 ? String.valueOf(n.population) : "unknown"));
        }
        lines.add("X: " + n.x + "  Z: " + n.z);

        drawHoveringText(lines, mouseX, mouseY, fontRenderer);
    }

    // -------------------------------------------------------------------------
    // Hover detection
    // -------------------------------------------------------------------------

    private void updateHover(int mouseX, int mouseY) {
        int mapH = height - PANEL_H;
        if (mouseX < 0 || mouseY < 0 || mouseX > width || mouseY > mapH) {
            hoveredSegId  = -1;
            hoveredWptIdx = -1;
            return;
        }
        // Node hover is set in drawPreviewNodes; here we only update segment hover
        double wMX = screenToWorldX(mouseX), wMZ = screenToWorldZ(mouseY);
        float threshold = Math.max(12f, 6f / zoom);
        float tSq = threshold * threshold;

        int bestSeg  = -1;
        int bestWpt  = -1;
        float bestDist = Float.MAX_VALUE;

        for (PacketOpenMapGui.SegmentData seg : segments) {
            int wc = seg.waypointCount();
            for (int i = 0; i < wc; i++) {
                float dx = seg.wx(i) - (float)wMX;
                float dz = seg.wz(i) - (float)wMZ;
                float d  = dx * dx + dz * dz;
                if (d < tSq && d < bestDist) { bestDist = d; bestSeg = seg.id; bestWpt = i; }
            }
        }

        // Node hover takes priority over segment hover when preview is loaded
        if (hoveredNodeIdx < 0) {
            hoveredSegId  = bestSeg;
            hoveredWptIdx = bestWpt;
        } else {
            hoveredSegId  = -1;
            hoveredWptIdx = -1;
        }
    }

    // -------------------------------------------------------------------------
    // Input handling
    // -------------------------------------------------------------------------

    @Override
    protected void actionPerformed(GuiButton btn) {
        switch (btn.id) {
            case BTN_EXPORT:  exportToFile();    break;
            case BTN_RESET:   resetView();       break;
            case BTN_PREVIEW: requestPreview();  break;
            case BTN_CLOSE:   mc.displayGuiScreen(null); break;
        }
    }

    @Override
    protected void keyTyped(char c, int keyCode) throws IOException {
        if      (keyCode == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(null);
        else if (keyCode == Keyboard.KEY_E)      exportToFile();
        else if (keyCode == Keyboard.KEY_R)      resetView();
        else if (keyCode == Keyboard.KEY_P)      requestPreview();
    }

    @Override
    protected void mouseClicked(int x, int y, int btn) throws IOException {
        super.mouseClicked(x, y, btn);
        if (btn == 0 && y < height - PANEL_H) {
            dragging = true;
            dragMouseX = x; dragMouseY = y;
            dragViewX = viewX; dragViewZ = viewZ;
        }
    }

    @Override
    protected void mouseReleased(int x, int y, int btn) {
        super.mouseReleased(x, y, btn);
        if (btn == 0) dragging = false;
    }

    @Override
    protected void mouseClickMove(int x, int y, int btn, long heldMs) {
        if (dragging && btn == 0) {
            viewX = dragViewX - (x - dragMouseX) / (double)zoom;
            viewZ = dragViewZ - (y - dragMouseY) / (double)zoom;
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int scroll = Mouse.getEventDWheel();
        if (scroll == 0 || mc.displayWidth == 0 || mc.displayHeight == 0) return;

        int mx = Mouse.getEventX() * width  / mc.displayWidth;
        int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (my >= height - PANEL_H) return;

        double wMx = screenToWorldX(mx), wMz = screenToWorldZ(my);
        float factor  = scroll > 0 ? 1.25f : 0.8f;
        float newZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom * factor));
        viewX = wMx - (mx - width * 0.5) / newZoom;
        viewZ = wMz - (my - (height - PANEL_H) * 0.5) / newZoom;
        zoom  = newZoom;
    }

    private void resetView() {
        viewX = centerX; viewZ = centerZ;
        zoom  = defaultZoom();
    }

    private void requestPreview() {
        if (previewPending) return;
        previewPending = true;
        GuiButton btn = getButton(BTN_PREVIEW);
        if (btn != null) btn.displayString = "§7Loading...";
        MPBNetworkHandler.CHANNEL.sendToServer(new PacketRequestPreview());
    }

    // -------------------------------------------------------------------------
    // Coordinate helpers
    // -------------------------------------------------------------------------

    private float worldToScreenX(double wx) {
        return (float)(width * 0.5 + (wx - viewX) * zoom);
    }

    private float worldToScreenZ(double wz) {
        return (float)((height - PANEL_H) * 0.5 + (wz - viewZ) * zoom);
    }

    private double screenToWorldX(int sx) {
        return viewX + (sx - width * 0.5) / zoom;
    }

    private double screenToWorldZ(int sy) {
        return viewZ + (sy - (height - PANEL_H) * 0.5) / zoom;
    }

    private boolean bothOffScreen(float x1, float y1, float x2, float y2, int mapH) {
        int p = 24;
        return (x1 < -p || x1 > width+p || y1 < -p || y1 > mapH+p)
            && (x2 < -p || x2 > width+p || y2 < -p || y2 > mapH+p);
    }

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    private void exportToFile() {
        String name = "mpb_export_" + System.currentTimeMillis() + ".txt";
        File file = new File(mc.gameDir, name);

        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            pw.println("# MPB Road Network Export");
            pw.println("# Timestamp: " + System.currentTimeMillis());
            pw.println("# Center: (" + centerX + ", " + centerZ + ")");
            pw.println("# Radius: " + radius + " blocks");
            pw.println("# Segments: " + segments.size() +
                    " total (" + roadCount + " road, " + bridgeCount + " bridge)");
            pw.println("# Villages: " + villageKeys.size());
            pw.println();

            for (PacketOpenMapGui.SegmentData seg : segments) {
                int wc = seg.waypointCount();
                boolean hasT = seg.hasTerrainY();
                pw.println("== SEGMENT " + seg.id + " [" + (seg.isBridge ? "BRIDGE" : "ROAD") + "] ==");
                pw.println("From-village: " + (seg.fromX != PacketOpenMapGui.ABSENT
                        ? "(" + seg.fromX + ", " + seg.fromZ + ")" : "none"));
                pw.println("To-village:   " + (seg.toX != PacketOpenMapGui.ABSENT
                        ? "(" + seg.toX + ", " + seg.toZ + ")" : "none"));
                pw.println("Waypoints: " + wc);
                if (wc > 0) {
                    pw.println("Start: (" + seg.wx(0) + ", " + seg.wy(0) + ", " + seg.wz(0) + ")");
                    pw.println("End:   (" + seg.wx(wc-1) + ", " + seg.wy(wc-1) + ", " + seg.wz(wc-1) + ")");
                }
                if (hasT) {
                    pw.println("# idx |     X |  Ypath | Yterrain |     Z |   dY");
                } else {
                    pw.println("# idx |     X |  Ypath |     Z");
                }
                for (int i = 0; i < wc; i++) {
                    if (hasT) {
                        int delta = seg.wy(i) - seg.terrainAt(i);
                        pw.printf("  %4d | %6d | %6d | %8d | %6d | %+4d%n",
                                i, seg.wx(i), seg.wy(i), seg.terrainAt(i), seg.wz(i), delta);
                    } else {
                        pw.printf("  %4d | %6d | %6d | %6d%n",
                                i, seg.wx(i), seg.wy(i), seg.wz(i));
                    }
                }
                pw.println();
            }
        } catch (Exception e) {
            exportFeedback = "Export failed: " + e.getMessage();
            exportFeedbackTicks = 120;
            return;
        }

        exportFeedback = "Saved: " + name;
        exportFeedbackTicks = 220;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PacketOpenMapGui.SegmentData segById(int id) {
        for (PacketOpenMapGui.SegmentData s : segments) {
            if (s.id == id) return s;
        }
        return null;
    }

    private GuiButton getButton(int id) {
        for (GuiButton btn : buttonList) {
            if (btn.id == id) return btn;
        }
        return null;
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
