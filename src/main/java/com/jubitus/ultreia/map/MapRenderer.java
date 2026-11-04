package com.jubitus.ultreia.map;

import com.jubitus.ultreia.geom.Pos2D;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Set;

/** Draws the map area: grid with coordinates, built roads and bridges, preview network, villages, player, north. */
@SideOnly(Side.CLIENT)
final class MapRenderer {

    private static final int C_VILLAGE     = 0xFFFFD060;
    private static final int C_PLAYER      = 0xFFFF4444;
    private static final int C_NORTH       = 0xFFAABBFF;
    private static final int C_PRV_HUB     = 0xFFFFD060; // gold
    private static final int C_PRV_MEDIUM  = 0xFFCCCCCC; // silver
    private static final int C_PRV_SMALL   = 0xFF888888; // grey
    private static final int C_PRV_LONE    = 0xFF8B6040; // brown

    private final MapView view;
    private final FontRenderer font;

    MapRenderer(MapView view, FontRenderer font) {
        this.view = view;
        this.font = font;
    }

    private void drawString(String text, int x, int y, int color) {
        font.drawStringWithShadow(text, (float) x, (float) y, color);
    }

    private static void beginLines() {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
    }

    private static void endLines() {
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    void drawGrid() {
        int width = view.width, mapH = view.mapH;
        float zoom = view.zoom;

        double minWX = view.screenToWorldX(0);
        double maxWX = view.screenToWorldX(width);
        double minWZ = view.screenToWorldZ(0);
        double maxWZ = view.screenToWorldZ(mapH);

        // Grid detail follows the zoom: fine lines and strong lines (blocks, chunks, then larger).
        int minorSpacing;
        int majorSpacing;
        if (zoom >= 8f) {
            minorSpacing = 1;
            majorSpacing = 16;
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

        beginLines();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);

        for (int wx = startX; wx <= (int) maxWX + minorSpacing; wx += minorSpacing) {
            float sx = view.worldToScreenX(wx);
            float[] c = gridColor((wx % majorSpacing) == 0, zoom);
            buf.pos(sx, 0, 0).color(c[0], c[1], c[2], c[3]).endVertex();
            buf.pos(sx, mapH, 0).color(c[0], c[1], c[2], c[3]).endVertex();
        }

        for (int wz = startZ; wz <= (int) maxWZ + minorSpacing; wz += minorSpacing) {
            float sy = view.worldToScreenZ(wz);
            float[] c = gridColor((wz % majorSpacing) == 0, zoom);
            buf.pos(0, sy, 0).color(c[0], c[1], c[2], c[3]).endVertex();
            buf.pos(width, sy, 0).color(c[0], c[1], c[2], c[3]).endVertex();
        }

        tess.draw();
        endLines();

        // Coordinate labels on major lines only (aligned to the major spacing, not the minor one).
        if (zoom >= 0.08f) {
            int majorStartX = (int) Math.floor(minWX / majorSpacing) * majorSpacing;
            int majorStartZ = (int) Math.floor(minWZ / majorSpacing) * majorSpacing;
            for (int wx = majorStartX; wx <= (int) maxWX + majorSpacing; wx += majorSpacing) {
                int sx = (int) view.worldToScreenX(wx);
                if (sx < 2 || sx > width - 20) continue;
                drawString(String.valueOf(wx), sx + 2, 2, 0x55AACCFF);
            }
            for (int wz = majorStartZ; wz <= (int) maxWZ + majorSpacing; wz += majorSpacing) {
                int sy = (int) view.worldToScreenZ(wz);
                if (sy < 10 || sy > mapH - 10) continue;
                drawString(String.valueOf(wz), 2, sy - 4, 0x55AACCFF);
            }
        }
    }

    private static float[] gridColor(boolean major, float zoom) {
        if (major) return new float[]{0.30f, 0.35f, 0.42f, 0.45f};
        return new float[]{0.12f, 0.14f, 0.18f, zoom >= 4f ? 0.30f : 0.16f};
    }

    /** Roads, then bridges on top; the hovered segment last, highlighted. */
    void drawSegments(List<PacketOpenMapGui.SegmentData> segments, int hoveredId, PacketOpenMapGui.SegmentData hovered) {
        if (segments.isEmpty()) return;

        beginLines();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        for (int pass = 0; pass < 2; pass++) {
            boolean wantBridge = (pass == 1);
            buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            boolean any = false;

            for (PacketOpenMapGui.SegmentData seg : segments) {
                if (seg.isBridge != wantBridge) continue;
                if (seg.id == hoveredId) continue;

                float r, g, b;
                if (seg.isBridge) { r = 0.25f; g = 0.75f; b = 1.00f; }
                else              { r = 0.78f; g = 0.63f; b = 0.38f; }

                int wc = seg.waypointCount();
                for (int i = 0; i < wc - 1; i++) {
                    float sx1 = view.worldToScreenX(seg.wx(i)),   sy1 = view.worldToScreenZ(seg.wz(i));
                    float sx2 = view.worldToScreenX(seg.wx(i+1)), sy2 = view.worldToScreenZ(seg.wz(i+1));
                    if (view.bothOffScreen(sx1, sy1, sx2, sy2)) continue;
                    buf.pos(sx1, sy1, 0).color(r, g, b, 0.9f).endVertex();
                    buf.pos(sx2, sy2, 0).color(r, g, b, 0.9f).endVertex();
                    any = true;
                }
                if (wc == 1) {
                    float sx = view.worldToScreenX(seg.wx(0)), sy = view.worldToScreenZ(seg.wz(0));
                    buf.pos(sx-2, sy, 0).color(r, g, b, 0.9f).endVertex();
                    buf.pos(sx+2, sy, 0).color(r, g, b, 0.9f).endVertex();
                    any = true;
                }
            }
            if (any) tess.draw(); else buf.finishDrawing();
        }

        if (hoveredId >= 0 && hovered != null) {
            buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            boolean any = false;
            int wc = hovered.waypointCount();
            for (int i = 0; i < wc - 1; i++) {
                float sx1 = view.worldToScreenX(hovered.wx(i)),   sy1 = view.worldToScreenZ(hovered.wz(i));
                float sx2 = view.worldToScreenX(hovered.wx(i+1)), sy2 = view.worldToScreenZ(hovered.wz(i+1));
                buf.pos(sx1, sy1, 0).color(1f, 1f, 0.2f, 1f).endVertex();
                buf.pos(sx2, sy2, 0).color(1f, 1f, 0.2f, 1f).endVertex();
                any = true;
            }
            if (any) tess.draw(); else buf.finishDrawing();
        }

        endLines();
    }

    void drawPreviewEdges(List<PacketNetworkPreview.EdgeData> edges) {
        if (edges == null || edges.isEmpty()) return;

        beginLines();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        boolean any = false;

        for (PacketNetworkPreview.EdgeData e : edges) {
            float sx1 = view.worldToScreenX(e.x1), sy1 = view.worldToScreenZ(e.z1);
            float sx2 = view.worldToScreenX(e.x2), sy2 = view.worldToScreenZ(e.z2);
            if (view.bothOffScreen(sx1, sy1, sx2, sy2)) continue;

            float r, g, b, a;
            if (e.isLoneTrail) { r = 0.55f; g = 0.38f; b = 0.25f; a = 0.45f; }
            else               { r = 0.85f; g = 0.60f; b = 0.20f; a = 0.60f; }

            buf.pos(sx1, sy1, 0).color(r, g, b, a).endVertex();
            buf.pos(sx2, sy2, 0).color(r, g, b, a).endVertex();
            any = true;
        }
        if (any) tess.draw(); else buf.finishDrawing();

        endLines();
    }

    /** Villages at the ends of built segments. */
    void drawVillages(Set<Long> villageKeys) {
        for (long key : villageKeys) {
            int sx = (int) view.worldToScreenX(Pos2D.xFromKey(key));
            int sy = (int) view.worldToScreenZ(Pos2D.zFromKey(key));
            if (sx < -6 || sx > view.width + 6 || sy < -6 || sy > view.mapH + 6) continue;
            Gui.drawRect(sx - 4, sy,     sx + 4, sy + 1, C_VILLAGE);
            Gui.drawRect(sx,     sy - 4, sx + 1, sy + 4, C_VILLAGE);
            Gui.drawRect(sx - 2, sy - 2, sx + 2, sy + 2, 0xFFFFEE90);
        }
    }

    /** Preview network nodes; returns the index of the node under the mouse, or -1. */
    int drawPreviewNodes(List<PacketNetworkPreview.NodeData> nodes, int mouseX, int mouseY) {
        int hoveredIdx = -1;
        int mapH = view.mapH;

        for (int i = 0; i < nodes.size(); i++) {
            PacketNetworkPreview.NodeData n = nodes.get(i);
            int sx = (int) view.worldToScreenX(n.x);
            int sy = (int) view.worldToScreenZ(n.z);
            if (sx < -8 || sx > view.width + 8 || sy < -8 || sy > mapH + 8) continue;

            boolean hovered = Math.abs(mouseX - sx) <= 6 && Math.abs(mouseY - sy) <= 6;
            if (hovered && sy < mapH) hoveredIdx = i;

            if (n.type == PacketNetworkPreview.TYPE_LONE) {
                // Small brown square
                int col = hovered ? 0xFFD4884A : C_PRV_LONE;
                Gui.drawRect(sx - 2, sy - 2, sx + 2, sy + 2, col);
            } else {
                // Diamond: size depends on type
                int half   = n.type == PacketNetworkPreview.TYPE_HUB   ? 6
                           : n.type == PacketNetworkPreview.TYPE_MEDIUM ? 4 : 3;
                int col    = n.type == PacketNetworkPreview.TYPE_HUB   ? (hovered ? 0xFFFFFF40 : C_PRV_HUB)
                           : n.type == PacketNetworkPreview.TYPE_MEDIUM ? (hovered ? 0xFFFFFFFF : C_PRV_MEDIUM)
                           :                                              (hovered ? 0xFFCCCCCC : C_PRV_SMALL);
                Gui.drawRect(sx - half, sy,      sx + half, sy + 1, col);
                Gui.drawRect(sx,        sy - half, sx + 1,  sy + half, col);
                Gui.drawRect(sx - half / 2, sy - half / 2, sx + half / 2, sy + half / 2, col);
            }

            // Name label — only when zoomed in enough to be readable
            if (view.zoom > 0.08f && n.type != PacketNetworkPreview.TYPE_LONE) {
                String label = n.population > 0 ? n.name + " [" + n.population + "]" : n.name;
                drawString(label, sx + 7, sy - 4, 0xAAFFDD88);
            }
        }
        return hoveredIdx;
    }

    void drawPlayerMarker(int centerX, int centerZ) {
        int sx = (int) view.worldToScreenX(centerX);
        int sy = (int) view.worldToScreenZ(centerZ);
        Gui.drawRect(sx - 5, sy,     sx + 5, sy + 1, 0xFFFFFFFF);
        Gui.drawRect(sx,     sy - 5, sx + 1, sy + 5, 0xFFFFFFFF);
        Gui.drawRect(sx - 2, sy - 2, sx + 2, sy + 2, C_PLAYER);
    }

    void drawNorthIndicator(String label) {
        int nx = view.width / 2, ny = 6;
        drawString(label, nx - 2, ny, C_NORTH);
        Gui.drawRect(nx, ny - 5, nx + 1, ny - 1, C_NORTH);
    }
}
