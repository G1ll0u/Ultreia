package com.jubitus.jubituschunks.client.gui;

import com.jubitus.jubituschunks.client.ClientHooks;
import com.jubitus.jubituschunks.net.msg.PacketViewUpdate;
import net.minecraft.client.gui.GuiScreen;

import java.util.BitSet;

public class GuiChunkViewer extends GuiScreen {

    private final int radiusBlocks;

    private int gridSize;
    private int scaleStepChunks;
    private int originChunkX, originChunkZ;

    private BitSet generated;
    private BitSet loaded;

    private int headMinCellX, headMaxCellX, headMinCellZ, headMaxCellZ;

    private long stepIndex, totalSteps, generatedChunks, skippedExisting;

    public GuiChunkViewer(int radiusBlocks) {
        this.radiusBlocks = radiusBlocks;
    }

    public void accept(PacketViewUpdate u) {
        this.gridSize = u.gridSize;
        this.scaleStepChunks = u.scaleStepChunks;
        this.originChunkX = u.originChunkX;
        this.originChunkZ = u.originChunkZ;

        this.generated = BitSet.valueOf(u.generatedBits);
        this.loaded = BitSet.valueOf(u.loadedBits);

        this.headMinCellX = u.headMinCellX;
        this.headMaxCellX = u.headMaxCellX;
        this.headMinCellZ = u.headMinCellZ;
        this.headMaxCellZ = u.headMaxCellZ;

        this.stepIndex = u.stepIndex;
        this.totalSteps = u.totalSteps;
        this.generatedChunks = u.generatedChunks;
        this.skippedExisting = u.skippedExisting;
    }

    @Override
    public void onGuiClosed() {
        ClientHooks.onViewerClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (this.mc != null && this.mc.world != null) {
            this.drawDefaultBackground();
        } else {
            this.drawGradientRect(0, 0, this.width, this.height, 0xFF101010, 0xFF101010);
        }


        if (gridSize <= 0 || generated == null || loaded == null) {
            drawCenteredString(fontRenderer, "Waiting for server data...", width / 2, height / 2, 0xFFFFFF);
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        int pad = 10;
        int mapSizePx = Math.min(width, height) - pad * 2 - 40;
        int cellPx = Math.max(1, mapSizePx / gridSize);

        int mapW = cellPx * gridSize;
        int mapH = cellPx * gridSize;

        int x0 = (width - mapW) / 2;
        int y0 = (height - mapH) / 2 + 10;

        // draw cells
        for (int cz = 0; cz < gridSize; cz++) {
            for (int cx = 0; cx < gridSize; cx++) {
                int idx = cz * gridSize + cx;

                int col;
                if (loaded.get(idx)) col = 0xFFFFFFFF;          // white
                else if (generated.get(idx)) col = 0xFF888888;   // grey
                else col = 0xFF444444;                           // dark grey

                int px = x0 + cx * cellPx;
                int py = y0 + cz * cellPx;
                drawRect(px, py, px + cellPx, py + cellPx, col);
            }
        }

        // head overlay (red)
        if (headMinCellX >= 0) {
            int minX = x0 + headMinCellX * cellPx;
            int maxX = x0 + (headMaxCellX + 1) * cellPx;
            int minZ = y0 + headMinCellZ * cellPx;
            int maxZ = y0 + (headMaxCellZ + 1) * cellPx;

            // filled transparent overlay + border
            drawRect(minX, minZ, maxX, maxZ, 0x55FF0000);
            drawHorizontalLine(minX, maxX - 1, minZ, 0xFFFF0000);
            drawHorizontalLine(minX, maxX - 1, maxZ - 1, 0xFFFF0000);
            drawVerticalLine(minX, minZ, maxZ - 1, 0xFFFF0000);
            drawVerticalLine(maxX - 1, minZ, maxZ - 1, 0xFFFF0000);
        }

        // center crosshair
        int mid = gridSize / 2;
        int cpx = x0 + mid * cellPx;
        int cpy = y0 + mid * cellPx;
        drawRect(cpx, cpy, cpx + cellPx, cpy + cellPx, 0xFF00FF00);

        // text
        int ty = y0 - 20;
        drawString(fontRenderer,
                "jubituschunks view | radiusBlocks=" + radiusBlocks +
                        " | scale=" + scaleStepChunks + " chunk(s)/cell",
                pad, ty, 0xFFFFFF);

        if (totalSteps > 0) {
            int pct = (int) ((stepIndex * 100L) / Math.max(1L, totalSteps));
            drawString(fontRenderer,
                    "pregen: step " + stepIndex + "/" + totalSteps + " (" + pct + "%)",
                    pad, ty + 10, 0xFFFFFF);
        } else {
            drawString(fontRenderer, "no pregen task running in this dimension", pad, ty + 10, 0xFFFFFF);
        }

        // legend
        int ly = y0 + mapH + 6;
        drawString(fontRenderer, "Legend: dark=not generated | grey=generated | white=loaded | red=head | green=center", pad, ly, 0xFFFFFF);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
