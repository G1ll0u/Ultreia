package com.jubitus.ultreia.gen.bridge.template;

import com.jubitus.ultreia.gen.bridge.BridgeGeometry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class BridgeTemplateDetector {

    private static final BridgeGeometry.Dir2[] DIRS = new BridgeGeometry.Dir2[] {
            new BridgeGeometry.Dir2(1, 0),
            new BridgeGeometry.Dir2(0, 1),
            new BridgeGeometry.Dir2(1, 1),
            new BridgeGeometry.Dir2(1, -1)
    };

    public static final class DetectedDeck {
        public final BridgeGeometry.Dir2 dir;
        public final BridgeGeometry.Dir2 perp;
        public final int deckY;
        public final MPBBlockStateRef deckSlab;
        public final List<BlockPos> centerline;
        public final List<BlockPos> deckBlocks;

        public DetectedDeck(BridgeGeometry.Dir2 dir,
                            BridgeGeometry.Dir2 perp,
                            int deckY,
                            MPBBlockStateRef deckSlab,
                            List<BlockPos> centerline,
                            List<BlockPos> deckBlocks) {
            this.dir = dir;
            this.perp = perp;
            this.deckY = deckY;
            this.deckSlab = deckSlab;
            this.centerline = centerline;
            this.deckBlocks = deckBlocks;
        }
    }

    private BridgeTemplateDetector() {
    }

    @Nullable
    public static DetectedDeck findNearestMatchingDeck(World world,
                                                       BlockPos origin,
                                                       int radius,
                                                       @Nullable MPBBlockStateRef requiredDeckSlab,
                                                       boolean tolerant) {
        DetectedDeck best = null;
        double bestDistSq = Double.MAX_VALUE;

        int yMin = Math.max(1, origin.getY() - radius);
        int yMax = Math.min(255, origin.getY() + radius);

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int y = yMax; y >= yMin; y--) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    p.setPos(origin.getX() + dx, y, origin.getZ() + dz);
                    IBlockState st = world.getBlockState(p);

                    if (!isTemplateDeckSlab(st)) continue;
                    if (requiredDeckSlab != null && !requiredDeckSlab.matches(st)) continue;

                    DetectedDeck deck = tryDetectAt(world, p.toImmutable(), tolerant);
                    if (deck == null) continue;

                    double d2 = origin.distanceSq(deck.centerline.get(0));
                    if (d2 < bestDistSq) {
                        bestDistSq = d2;
                        best = deck;
                    }
                }
            }
        }

        return best;
    }

    @Nullable
    private static DetectedDeck tryDetectAt(World world, BlockPos seed, boolean tolerant) {
        IBlockState seedState = world.getBlockState(seed);
        if (!isTemplateDeckSlab(seedState)) return null;

        MPBBlockStateRef slabRef = MPBBlockStateRef.of(seedState);

        DetectedDeck best = null;
        int bestLen = -1;

        for (BridgeGeometry.Dir2 dir : DIRS) {
            BridgeGeometry.Dir2 perp = dir.perp();

            for (int lane = -1; lane <= 1; lane++) {
                BlockPos center = seed.add(-perp.dx * lane, 0, -perp.dz * lane);
                if (!stripeMatches(world, center, perp, slabRef, tolerant)) continue;

                BlockPos start = center;
                while (stripeMatches(world, start.add(-dir.dx, 0, -dir.dz), perp, slabRef, tolerant)) {
                    start = start.add(-dir.dx, 0, -dir.dz);
                }

                List<BlockPos> centers = new ArrayList<BlockPos>();
                BlockPos cur = start;
                while (stripeMatches(world, cur, perp, slabRef, tolerant)) {
                    centers.add(cur);
                    cur = cur.add(dir.dx, 0, dir.dz);
                }

                if (centers.isEmpty()) continue;
                if (!tolerant && !widthIsExactly3(world, centers, perp, slabRef)) continue;

                List<BlockPos> deckBlocks = new ArrayList<BlockPos>(centers.size() * 3);
                for (BlockPos c : centers) {
                    for (int o = -1; o <= 1; o++) {
                        deckBlocks.add(c.add(perp.dx * o, 0, perp.dz * o));
                    }
                }

                if (centers.size() > bestLen) {
                    bestLen = centers.size();
                    best = new DetectedDeck(dir, perp, seed.getY(), slabRef, centers, deckBlocks);
                }
            }
        }

        return best;
    }

    private static boolean stripeMatches(World world,
                                         BlockPos center,
                                         BridgeGeometry.Dir2 perp,
                                         MPBBlockStateRef slabRef,
                                         boolean tolerant) {
        int matches = 0;
        boolean centerMatches = false;

        for (int o = -1; o <= 1; o++) {
            BlockPos p = center.add(perp.dx * o, 0, perp.dz * o);
            IBlockState st = world.getBlockState(p);
            boolean hit = slabRef.matches(st);
            if (hit) {
                matches++;
                if (o == 0) centerMatches = true;
            }
        }

        if (tolerant) {
            return centerMatches && matches >= 2;
        }

        return matches == 3;
    }

    private static boolean widthIsExactly3(World world,
                                           List<BlockPos> centers,
                                           BridgeGeometry.Dir2 perp,
                                           MPBBlockStateRef slabRef) {
        for (BlockPos c : centers) {
            IBlockState left2 = world.getBlockState(c.add(-perp.dx * 2, 0, -perp.dz * 2));
            IBlockState right2 = world.getBlockState(c.add(perp.dx * 2, 0, perp.dz * 2));
            if (slabRef.matches(left2) || slabRef.matches(right2)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTemplateDeckSlab(IBlockState st) {
        if (st == null) return false;
        Block b = st.getBlock();
        if (!(b instanceof BlockSlab)) return false;
        try {
            return !((BlockSlab) b).isDouble();
        } catch (Throwable ignored) {
            return false;
        }
    }
}