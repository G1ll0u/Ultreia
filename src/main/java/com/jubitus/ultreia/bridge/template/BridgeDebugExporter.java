package com.jubitus.ultreia.bridge.template;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.terrain.GroundRules;
import com.jubitus.ultreia.mill.MillPathPalette;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockWall;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

public final class BridgeDebugExporter {

    public static final class ExportResult {
        public final boolean ok;
        public final String message;
        public final File file;

        private ExportResult(boolean ok, String message, @Nullable File file) {
            this.ok = ok;
            this.message = message;
            this.file = file;
        }

        public static ExportResult ok(File file, String message) {
            return new ExportResult(true, message, file);
        }

        public static ExportResult fail(String message) {
            return new ExportResult(false, message, null);
        }
    }

    private static final class DebugFile {
        String format = "mpb_bridge_debug_v1";
        String activeTemplate;
        String mode;
        int originX;
        int originY;
        int originZ;
        String detectedDeckSlab;
        int detectedDeckLength;
        List<DebugCenterCell> centerline = new ArrayList<DebugCenterCell>();
        List<DebugBlock> blocks = new ArrayList<DebugBlock>();
    }

    private static final class DebugCenterCell {
        int dx;
        int dy;
        int dz;

        DebugCenterCell(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    private static final class DebugBlock {
        int dx;
        int dy;
        int dz;
        BlockStateRef state;

        DebugBlock(int dx, int dy, int dz, BlockStateRef state) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.state = state;
        }
    }

    private static final class LocalCoord {
        final int forward;
        final int lateral;
        final int relY;

        LocalCoord(int forward, int lateral, int relY) {
            this.forward = forward;
            this.lateral = lateral;
            this.relY = relY;
        }
    }

    private BridgeDebugExporter() {
    }

    public static ExportResult exportNearestBridge(WorldServer world, BlockPos origin) {
        BridgeTemplate active = BridgeTemplateRegistry.getDefaultTemplate();
        BlockStateRef required = active != null ? active.deckSlab : null;

        BridgeTemplateDetector.DetectedDeck deck = BridgeTemplateDetector.findNearestMatchingDeck(
                world,
                origin,
                Math.max(8, UltreiaConfig.BRIDGES.debugExportSearchRadius),
                required,
                true
        );

        DebugFile out = new DebugFile();
        out.activeTemplate = UltreiaConfig.BRIDGES.defaultTemplateName == null ? "" : UltreiaConfig.BRIDGES.defaultTemplateName.trim();

        if (deck != null) {
            out.mode = "deck_component";
            out.detectedDeckSlab = deck.deckSlab.toString();
            out.detectedDeckLength = deck.centerline.size();
            out.originX = deck.centerline.get(0).getX();
            out.originY = deck.centerline.get(0).getY();
            out.originZ = deck.centerline.get(0).getZ();

            for (BlockPos p : deck.centerline) {
                out.centerline.add(new DebugCenterCell(
                        p.getX() - out.originX,
                        p.getY() - out.originY,
                        p.getZ() - out.originZ
                ));
            }

            List<BlockPos> structure = collectDeckComponent(world, deck);
            for (BlockPos p : structure) {
                IBlockState st = world.getBlockState(p);
                if (st == null || st.getMaterial() == Material.AIR) continue;

                out.blocks.add(new DebugBlock(
                        p.getX() - out.originX,
                        p.getY() - out.originY,
                        p.getZ() - out.originZ,
                        BlockStateRef.of(st)
                ));
            }
        } else {
            BlockPos seed = findFallbackSeed(world, origin, Math.max(8, UltreiaConfig.BRIDGES.debugExportSearchRadius));
            if (seed == null) {
                return ExportResult.fail("No nearby bridge-like structure was found.");
            }

            out.mode = "fallback_component";
            out.detectedDeckSlab = "";
            out.detectedDeckLength = 0;
            out.originX = seed.getX();
            out.originY = seed.getY();
            out.originZ = seed.getZ();

            List<BlockPos> structure = collectFallbackComponent(world, seed);
            for (BlockPos p : structure) {
                IBlockState st = world.getBlockState(p);
                if (st == null || st.getMaterial() == Material.AIR) continue;

                out.blocks.add(new DebugBlock(
                        p.getX() - out.originX,
                        p.getY() - out.originY,
                        p.getZ() - out.originZ,
                        BlockStateRef.of(st)
                ));
            }
        }

        Collections.sort(out.blocks, new Comparator<DebugBlock>() {
            @Override
            public int compare(DebugBlock a, DebugBlock b) {
                if (a.dy != b.dy) return Integer.compare(a.dy, b.dy);
                if (a.dx != b.dx) return Integer.compare(a.dx, b.dx);
                return Integer.compare(a.dz, b.dz);
            }
        });

        try {
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File file = new File(BridgeTemplateRegistry.getDebugDir(), "bridge-debug-" + ts + ".json");
            BridgeTemplateIO.saveJson(file, out);
            return ExportResult.ok(file, "Bridge debug exported: " + file.getName());
        } catch (Throwable t) {
            return ExportResult.fail("Failed to export bridge debug: " + t.getMessage());
        }
    }

    private static List<BlockPos> collectDeckComponent(WorldServer world, BridgeTemplateDetector.DetectedDeck deck) {
        int maxLateral = Math.max(3, UltreiaConfig.BRIDGES.templateMaxLateral) + 2;
        int maxAbove = Math.max(2, UltreiaConfig.BRIDGES.templateMaxAboveDeck) + 4;
        int maxBelow = Math.max(2, UltreiaConfig.BRIDGES.templateMaxBelowDeck) + 8;
        int maxBlocks = Math.max(64, UltreiaConfig.BRIDGES.templateMaxBlocks);

        ArrayDeque<BlockPos> queue = new ArrayDeque<BlockPos>();
        HashSet<Long> visited = new HashSet<Long>();
        ArrayList<BlockPos> out = new ArrayList<BlockPos>();

        for (BlockPos p : deck.deckBlocks) {
            queue.add(p);
            visited.add(p.toLong());
            out.add(p);
        }

        while (!queue.isEmpty() && out.size() < maxBlocks) {
            BlockPos cur = queue.poll();

            for (EnumFacing f : EnumFacing.values()) {
                BlockPos next = cur.offset(f);
                long key = next.toLong();
                if (!visited.add(key)) continue;

                IBlockState st = world.getBlockState(next);
                if (st == null || st.getMaterial() == Material.AIR) continue;

                LocalCoord lc = toLocal(deck, next);
                if (lc == null) continue;
                if (lc.forward < -1 || lc.forward > deck.centerline.size()) continue;
                if (Math.abs(lc.lateral) > maxLateral) continue;
                if (lc.relY > maxAbove || lc.relY < -maxBelow) continue;

                if (GroundRules.isRoadReplaceableGround(st) && lc.relY <= -2) {
                    continue;
                }

                queue.add(next);
                out.add(next);
            }
        }

        return out;
    }

    private static BlockPos findFallbackSeed(WorldServer world, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        int yMin = Math.max(1, origin.getY() - radius);
        int yMax = Math.min(255, origin.getY() + radius);

        for (int y = yMax; y >= yMin; y--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    p.setPos(origin.getX() + dx, y, origin.getZ() + dz);
                    IBlockState st = world.getBlockState(p);
                    if (!isBridgeLikeSeed(st)) continue;

                    double d2 = origin.distanceSq(p);
                    if (d2 < bestDistSq) {
                        bestDistSq = d2;
                        best = p.toImmutable();
                    }
                }
            }
        }

        return best;
    }

    private static List<BlockPos> collectFallbackComponent(WorldServer world, BlockPos seed) {
        int maxBlocks = Math.max(64, UltreiaConfig.BRIDGES.templateMaxBlocks);
        ArrayDeque<BlockPos> queue = new ArrayDeque<BlockPos>();
        HashSet<Long> visited = new HashSet<Long>();
        ArrayList<BlockPos> out = new ArrayList<BlockPos>();

        queue.add(seed);
        visited.add(seed.toLong());

        while (!queue.isEmpty() && out.size() < maxBlocks) {
            BlockPos cur = queue.poll();
            IBlockState st = world.getBlockState(cur);
            if (st == null || st.getMaterial() == Material.AIR) continue;

            out.add(cur);

            for (EnumFacing f : EnumFacing.values()) {
                BlockPos next = cur.offset(f);
                long key = next.toLong();
                if (!visited.add(key)) continue;

                if (Math.abs(next.getX() - seed.getX()) > 16) continue;
                if (Math.abs(next.getZ() - seed.getZ()) > 16) continue;
                if (Math.abs(next.getY() - seed.getY()) > 24) continue;

                IBlockState ns = world.getBlockState(next);
                if (ns == null || ns.getMaterial() == Material.AIR) continue;

                if (GroundRules.isRoadReplaceableGround(ns) && !isBridgeLikeSeed(ns)) continue;

                queue.add(next);
            }
        }

        return out;
    }

    private static boolean isBridgeLikeSeed(IBlockState st) {
        if (st == null) return false;
        Block b = st.getBlock();
        if (MillPathPalette.isMillPath(b)) return false;
        if (b instanceof BlockSlab) return true;
        if (b instanceof BlockStairs) return true;
        if (b instanceof BlockFence) return true;
        if (b instanceof BlockFenceGate) return true;
        if (b instanceof BlockWall) return true;
        if (b instanceof BlockLog) return true;

        String id = b.getRegistryName() != null ? b.getRegistryName().toString() : "";
        return id.contains("plank") || id.contains("bridge") || id.contains("stonebrick") || id.contains("cobble");
    }

    @Nullable
    private static LocalCoord toLocal(BridgeTemplateDetector.DetectedDeck deck, BlockPos pos) {
        BlockPos origin = deck.centerline.get(0);

        int relX = pos.getX() - origin.getX();
        int relZ = pos.getZ() - origin.getZ();

        Integer forward = project(relX, relZ, deck.dir.dx, deck.dir.dz);
        Integer lateral = project(relX, relZ, deck.perp.dx, deck.perp.dz);

        if (forward == null || lateral == null) return null;
        return new LocalCoord(forward.intValue(), lateral.intValue(), pos.getY() - deck.deckY);
    }

    @Nullable
    private static Integer project(int relX, int relZ, int bx, int bz) {
        int den = bx * bx + bz * bz;
        int num = relX * bx + relZ * bz;
        if (den == 0) return null;
        if ((num % den) != 0) return null;
        return Integer.valueOf(num / den);
    }
}