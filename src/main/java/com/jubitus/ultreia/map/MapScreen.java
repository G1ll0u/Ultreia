package com.jubitus.ultreia.map;

import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.net.NetworkHandler;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The road map screen: built roads and bridges around the player, optional network preview, hover details, export. */
@SideOnly(Side.CLIENT)
public final class MapScreen extends GuiScreen {

    private static final int PANEL_H  = 38;

    private static final int C_MAP_BG      = 0xFF090B12;
    private static final int C_PANEL_BG    = 0xFF140F08;
    private static final int C_SEPARATOR   = 0xFF443322;
    private static final int C_TEXT        = 0xFFCCAA66;

    private static final int BTN_EXPORT  = 100;
    private static final int BTN_RESET   = 101;
    private static final int BTN_CLOSE   = 102;
    private static final int BTN_PREVIEW = 103;

    private final int centerX, centerZ, radius;
    private final List<PacketOpenMapGui.SegmentData> segments;
    private final Set<Long> villageKeys = new HashSet<>();
    private final int roadCount, bridgeCount;

    private final MapView view = new MapView();
    private MapRenderer renderer;

    private boolean dragging;

    private int hoveredSegId  = -1;
    private int hoveredWptIdx = -1; // waypoint index within hoveredSegId, -1 = none
    private int hoveredNodeIdx = -1; // index into previewNodes, -1 = none

    private String exportFeedback = null;
    private int exportFeedbackTicks = 0;

    // Preview data — null until received from server
    private List<PacketNetworkPreview.NodeData> previewNodes = null;
    private List<PacketNetworkPreview.EdgeData> previewEdges = null;
    private boolean previewPending = false;

    public MapScreen(int centerX, int centerZ, int radius,
                     List<PacketOpenMapGui.SegmentData> segments) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius  = radius;
        this.segments = segments;

        int roads = 0, bridges = 0;
        for (PacketOpenMapGui.SegmentData s : segments) {
            if (s.isBridge) bridges++; else roads++;
            if (s.fromX != PacketOpenMapGui.ABSENT) villageKeys.add(Pos2D.xzKey(s.fromX, s.fromZ));
            if (s.toX   != PacketOpenMapGui.ABSENT) villageKeys.add(Pos2D.xzKey(s.toX,   s.toZ));
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
            if (btn != null) btn.displayString = I18n.format("ultreia.map.button.preview");
        }
    }

    @Override
    public void initGui() {
        view.reset(centerX, centerZ, radius, width, height - PANEL_H);
        renderer = new MapRenderer(view, fontRenderer);
        buttonList.clear();

        int btnY = height - PANEL_H + 9;
        buttonList.add(new GuiButton(BTN_EXPORT,  6,           btnY,  80, 20, I18n.format("ultreia.map.button.export")));
        buttonList.add(new GuiButton(BTN_RESET,   90,          btnY,  80, 20, I18n.format("ultreia.map.button.reset")));
        buttonList.add(new GuiButton(BTN_PREVIEW, 174,         btnY, 102, 20,
                previewPending ? "§7" + I18n.format("ultreia.map.button.loading") : I18n.format("ultreia.map.button.preview")));
        buttonList.add(new GuiButton(BTN_CLOSE,   width - 90,  btnY,  84, 20, I18n.format("ultreia.map.button.close")));
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

        renderer.drawGrid();
        renderer.drawSegments(segments, hoveredSegId, segById(hoveredSegId));
        if (previewEdges != null) renderer.drawPreviewEdges(previewEdges);
        renderer.drawVillages(villageKeys);
        if (previewNodes != null) hoveredNodeIdx = renderer.drawPreviewNodes(previewNodes, mouseX, mouseY);
        renderer.drawPlayerMarker(centerX, centerZ);
        renderer.drawNorthIndicator(I18n.format("ultreia.map.north"));

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        // HUD — starts after the preview button (174+102=276)
        double wMouseX = view.screenToWorldX(mouseX);
        double wMouseZ = view.screenToWorldZ(mouseY);
        String hud = I18n.format("ultreia.map.hud",
                wMouseX, wMouseZ, view.zoom, segments.size(), roadCount, bridgeCount);
        drawString(fontRenderer, hud, 282, height - PANEL_H + 13, C_TEXT);

        super.drawScreen(mouseX, mouseY, partial);

        updateHover(mouseX, mouseY);
        if (hoveredSegId >= 0) drawSegmentTooltip(mouseX, mouseY);
        if (hoveredNodeIdx >= 0 && previewNodes != null) drawNodeTooltip(mouseX, mouseY);

        if (exportFeedback != null && exportFeedbackTicks > 0) {
            exportFeedbackTicks--;
            int alpha = Math.min(255, exportFeedbackTicks * 5);
            drawString(fontRenderer, exportFeedback, 6, height - PANEL_H - 12, (alpha << 24) | 0x80FF80);
        }
    }

    private void drawSegmentTooltip(int mouseX, int mouseY) {
        PacketOpenMapGui.SegmentData seg = segById(hoveredSegId);
        if (seg == null) return;

        List<String> lines = new ArrayList<>();
        lines.add("§e" + I18n.format(seg.isBridge ? "ultreia.map.segment.bridge" : "ultreia.map.segment.road", seg.id));
        int wc = seg.waypointCount();
        lines.add(I18n.format("ultreia.map.segment.waypoints", wc));
        if (wc >= 2) {
            int dx = seg.wx(wc-1) - seg.wx(0), dz = seg.wz(wc-1) - seg.wz(0);
            lines.add(I18n.format("ultreia.map.segment.length", (int) Math.sqrt((double) dx * dx + (double) dz * dz)));
            lines.add(I18n.format("ultreia.map.segment.start", seg.wx(0), seg.wy(0), seg.wz(0)));
            lines.add(I18n.format("ultreia.map.segment.end", seg.wx(wc-1), seg.wy(wc-1), seg.wz(wc-1)));
        }
        if (hoveredWptIdx >= 0 && hoveredWptIdx < wc) {
            int wi = hoveredWptIdx;
            lines.add("§7" + I18n.format("ultreia.map.segment.waypoint", wi, wc - 1));
            lines.add(I18n.format("ultreia.map.segment.pos", seg.wx(wi), seg.wy(wi), seg.wz(wi)));
        }
        if (seg.fromX != PacketOpenMapGui.ABSENT)
            lines.add(I18n.format("ultreia.map.segment.from", seg.fromX, seg.fromZ));
        if (seg.toX != PacketOpenMapGui.ABSENT)
            lines.add(I18n.format("ultreia.map.segment.to", seg.toX, seg.toZ));

        drawHoveringText(lines, mouseX, mouseY, fontRenderer);
    }

    private void drawNodeTooltip(int mouseX, int mouseY) {
        if (previewNodes == null || hoveredNodeIdx < 0 || hoveredNodeIdx >= previewNodes.size()) return;
        PacketNetworkPreview.NodeData n = previewNodes.get(hoveredNodeIdx);

        List<String> lines = new ArrayList<>();
        String typeLabel;
        switch (n.type) {
            case PacketNetworkPreview.TYPE_HUB:    typeLabel = "§6" + I18n.format("ultreia.map.node.hub");    break;
            case PacketNetworkPreview.TYPE_MEDIUM: typeLabel = "§7" + I18n.format("ultreia.map.node.medium"); break;
            case PacketNetworkPreview.TYPE_LONE:   typeLabel = "§8" + I18n.format("ultreia.map.node.lone");   break;
            default:                               typeLabel = "§8" + I18n.format("ultreia.map.node.small");  break;
        }
        lines.add(typeLabel + " §r" + n.name);
        if (n.type != PacketNetworkPreview.TYPE_LONE) {
            lines.add(n.population > 0
                    ? I18n.format("ultreia.map.node.population", n.population)
                    : I18n.format("ultreia.map.node.population_unknown"));
        }
        lines.add(I18n.format("ultreia.map.node.coords", n.x, n.z));

        drawHoveringText(lines, mouseX, mouseY, fontRenderer);
    }

    /** Segment waypoint nearest the mouse within a few pixels; a hovered preview node takes priority. */
    private void updateHover(int mouseX, int mouseY) {
        int mapH = height - PANEL_H;
        if (mouseX < 0 || mouseY < 0 || mouseX > width || mouseY > mapH) {
            hoveredSegId  = -1;
            hoveredWptIdx = -1;
            return;
        }
        double wMX = view.screenToWorldX(mouseX), wMZ = view.screenToWorldZ(mouseY);
        float threshold = Math.max(12f, 6f / view.zoom);
        float tSq = threshold * threshold;

        int bestSeg  = -1;
        int bestWpt  = -1;
        float bestDist = Float.MAX_VALUE;

        for (PacketOpenMapGui.SegmentData seg : segments) {
            int wc = seg.waypointCount();
            for (int i = 0; i < wc; i++) {
                float dx = seg.wx(i) - (float) wMX;
                float dz = seg.wz(i) - (float) wMZ;
                float d  = dx * dx + dz * dz;
                if (d < tSq && d < bestDist) { bestDist = d; bestSeg = seg.id; bestWpt = i; }
            }
        }

        if (hoveredNodeIdx < 0) {
            hoveredSegId  = bestSeg;
            hoveredWptIdx = bestWpt;
        } else {
            hoveredSegId  = -1;
            hoveredWptIdx = -1;
        }
    }

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
            view.startDrag(x, y);
        }
    }

    @Override
    protected void mouseReleased(int x, int y, int btn) {
        super.mouseReleased(x, y, btn);
        if (btn == 0) dragging = false;
    }

    @Override
    protected void mouseClickMove(int x, int y, int btn, long heldMs) {
        if (dragging && btn == 0) view.dragTo(x, y);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int scroll = Mouse.getEventDWheel();
        if (scroll == 0 || mc.displayWidth == 0 || mc.displayHeight == 0) return;

        int mx = Mouse.getEventX() * width  / mc.displayWidth;
        int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (my >= height - PANEL_H) return;

        view.zoomAt(mx, my, scroll);
    }

    private void resetView() {
        view.reset(centerX, centerZ, radius, width, height - PANEL_H);
    }

    private void requestPreview() {
        if (previewPending) return;
        previewPending = true;
        GuiButton btn = getButton(BTN_PREVIEW);
        if (btn != null) btn.displayString = "§7" + I18n.format("ultreia.map.button.loading");
        NetworkHandler.CHANNEL.sendToServer(new PacketRequestPreview());
    }

    private void exportToFile() {
        try {
            String name = MapExporter.export(mc.gameDir, centerX, centerZ, radius, segments,
                    roadCount, bridgeCount, villageKeys.size());
            exportFeedback = I18n.format("ultreia.map.export.saved", name);
            exportFeedbackTicks = 220;
        } catch (Exception e) {
            exportFeedback = I18n.format("ultreia.map.export.failed", e.getMessage());
            exportFeedbackTicks = 120;
        }
    }

    private PacketOpenMapGui.SegmentData segById(int id) {
        if (id < 0) return null;
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
}
