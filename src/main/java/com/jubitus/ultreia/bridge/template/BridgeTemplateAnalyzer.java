package com.jubitus.ultreia.bridge.template;

import com.jubitus.ultreia.config.UltreiaConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.*;

public final class BridgeTemplateAnalyzer {

    // Search radius in blocks for /ultreia bridge create.
    private static final int TEMPLATE_CREATE_SEARCH_RADIUS = 24;
    // Maximum lateral reach from the 3-wide deck centerline that template export keeps.
    public static final int TEMPLATE_MAX_LATERAL = 8;
    // Maximum blocks above deck kept in a bridge template.
    public static final int TEMPLATE_MAX_ABOVE_DECK = 8;
    // Maximum blocks below deck kept in a bridge template.
    public static final int TEMPLATE_MAX_BELOW_DECK = 20;
    // Maximum number of saved blocks in one bridge template or bridge debug export.
    public static final int TEMPLATE_MAX_BLOCKS = 4096;

    public static final class CreateResult {
        public final boolean ok;
        public final String message;
        public final BridgeTemplate template;

        private CreateResult(boolean ok, String message, @Nullable BridgeTemplate template) {
            this.ok = ok;
            this.message = message;
            this.template = template;
        }

        public static CreateResult ok(BridgeTemplate template, String message) {
            return new CreateResult(true, message, template);
        }

        public static CreateResult fail(String message) {
            return new CreateResult(false, message, null);
        }
    }


    private BridgeTemplateAnalyzer() {
    }

    public static CreateResult createNearestTemplate(WorldServer world, BlockPos origin, String rawName) {
        String name = BridgeTemplateRegistry.sanitizeName(rawName);
        if (name.isEmpty()) {
            return CreateResult.fail("Invalid bridge template name.");
        }

        BridgeTemplateDetector.DetectedDeck deck = BridgeTemplateDetector.findNearestMatchingDeck(
                world,
                origin,
                Math.max(8, TEMPLATE_CREATE_SEARCH_RADIUS),
                null,
                false
        );

        if (deck == null) {
            return CreateResult.fail("No valid nearby 3-wide slab deck was found.");
        }

        if (deck.centerline.size() < 5) {
            return CreateResult.fail("Template deck is too short. Minimum length is 8.");
        }

        List<BlockPos> structure = collectConnectedStructure(world, deck);
        if (structure.isEmpty()) {
            return CreateResult.fail("Template structure is empty.");
        }

        Set<Long> deckSet = new HashSet<Long>();
        for (BlockPos p : deck.deckBlocks) {
            deckSet.add(p.toLong());
        }

        BridgeTemplate template = new BridgeTemplate();
        template.name = name;
        template.segmentLength = deck.centerline.size();
        template.canonicalForward = BridgeTemplateDirection.toName(
                BridgeTemplateDirection.snapFromDir(deck.dir.dx, deck.dir.dz)
        );
        template.deckSlab = deck.deckSlab;
        template.createdAt = System.currentTimeMillis();
        template.minLateral = Integer.MAX_VALUE;
        template.maxLateral = Integer.MIN_VALUE;
        template.minRelY = Integer.MAX_VALUE;
        template.maxRelY = Integer.MIN_VALUE;

        List<BridgeTemplate.BlockEntry> entries = new ArrayList<BridgeTemplate.BlockEntry>();

        for (BlockPos pos : structure) {
            if (deckSet.contains(pos.toLong())) continue;

            TileEntity te = world.getTileEntity(pos);
            if (te != null) {
                return CreateResult.fail("Template contains a tile entity at " + pos.toString() + ".");
            }

            BridgeTemplateDetector.LocalCoord lc = deck.toLocal(pos);
            if (lc == null) {
                return CreateResult.fail("Template block is not aligned to template grid at " + pos.toString() + ".");
            }

            if (lc.forward < 0 || lc.forward >= template.segmentLength) {
                return CreateResult.fail("Template has blocks outside segment bounds at " + pos.toString() + ".");
            }

            if (Math.abs(lc.lateral) <= 1 && lc.relY >= 1 && lc.relY <= 2) {
                return CreateResult.fail("Template blocks invade walk space above the deck at " + pos.toString() + ".");
            }

            IBlockState st = world.getBlockState(pos);
            BridgeTemplate.BlockEntry be = new BridgeTemplate.BlockEntry(
                    lc.forward,
                    lc.lateral,
                    lc.relY,
                    BlockStateRef.of(st)
            );
            entries.add(be);

            if (lc.lateral < template.minLateral) template.minLateral = lc.lateral;
            if (lc.lateral > template.maxLateral) template.maxLateral = lc.lateral;
            if (lc.relY < template.minRelY) template.minRelY = lc.relY;
            if (lc.relY > template.maxRelY) template.maxRelY = lc.relY;
        }

        Collections.sort(entries, new Comparator<BridgeTemplate.BlockEntry>() {
            @Override
            public int compare(BridgeTemplate.BlockEntry a, BridgeTemplate.BlockEntry b) {
                if (a.forward != b.forward) return Integer.compare(a.forward, b.forward);
                if (a.lateral != b.lateral) return Integer.compare(a.lateral, b.lateral);
                return Integer.compare(a.relY, b.relY);
            }
        });

        template.blocks.addAll(entries);
        buildSupportSeeds(template);

        if (template.supportSeeds.isEmpty()) {
            return CreateResult.fail("Template must contain at least one connected block below the deck so support continuation has a seed.");
        }

        if (template.minLateral == Integer.MAX_VALUE) template.minLateral = -1;
        if (template.maxLateral == Integer.MIN_VALUE) template.maxLateral = 1;
        if (template.minRelY == Integer.MAX_VALUE) template.minRelY = 0;
        if (template.maxRelY == Integer.MIN_VALUE) template.maxRelY = 0;

        return CreateResult.ok(template, "Bridge template created: " + template.name + " len=" + template.segmentLength + " blocks=" + template.blocks.size());
    }

    private static List<BlockPos> collectConnectedStructure(WorldServer world,
                                                            BridgeTemplateDetector.DetectedDeck deck) {
        int maxLateral = Math.max(3, TEMPLATE_MAX_LATERAL);
        int maxAbove = Math.max(2, TEMPLATE_MAX_ABOVE_DECK);
        int maxBelow = Math.max(2, TEMPLATE_MAX_BELOW_DECK);
        int maxBlocks = Math.max(64, TEMPLATE_MAX_BLOCKS);

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
                if (st == null || st.getMaterial().isReplaceable() && st.getMaterial().isLiquid()) {
                    continue;
                }
                if (st.getMaterial() == net.minecraft.block.material.Material.AIR) {
                    continue;
                }

                BridgeTemplateDetector.LocalCoord lc = deck.toLocal(next);
                if (lc == null) continue;
                if (lc.forward < 0 || lc.forward >= deck.centerline.size()) continue;
                if (Math.abs(lc.lateral) > maxLateral) continue;
                if (lc.relY > maxAbove || lc.relY < -maxBelow) continue;

                queue.add(next);
                out.add(next);
            }
        }

        return out;
    }


    private static void buildSupportSeeds(BridgeTemplate template) {
        Map<String, BridgeTemplate.BlockEntry> belowDeck = new HashMap<String, BridgeTemplate.BlockEntry>();

        for (BridgeTemplate.BlockEntry b : template.blocks) {
            if (b == null || b.relY >= 0) continue;
            belowDeck.put(localKey(b.forward, b.lateral, b.relY), b);
        }

        HashSet<String> seen = new HashSet<String>();
        ArrayList<BridgeTemplate.SupportSeed> seeds = new ArrayList<BridgeTemplate.SupportSeed>();

        for (BridgeTemplate.BlockEntry start : belowDeck.values()) {
            String startKey = localKey(start.forward, start.lateral, start.relY);
            if (!seen.add(startKey)) continue;

            ArrayDeque<BridgeTemplate.BlockEntry> queue = new ArrayDeque<BridgeTemplate.BlockEntry>();
            ArrayList<BridgeTemplate.BlockEntry> component = new ArrayList<BridgeTemplate.BlockEntry>();

            queue.add(start);
            component.add(start);

            int minRelY = start.relY;

            while (!queue.isEmpty()) {
                BridgeTemplate.BlockEntry cur = queue.poll();

                if (cur.relY < minRelY) {
                    minRelY = cur.relY;
                }

                enqueueSupportNeighbor(belowDeck, seen, queue, component, cur.forward + 1, cur.lateral, cur.relY);
                enqueueSupportNeighbor(belowDeck, seen, queue, component, cur.forward - 1, cur.lateral, cur.relY);
                enqueueSupportNeighbor(belowDeck, seen, queue, component, cur.forward, cur.lateral + 1, cur.relY);
                enqueueSupportNeighbor(belowDeck, seen, queue, component, cur.forward, cur.lateral - 1, cur.relY);
                enqueueSupportNeighbor(belowDeck, seen, queue, component, cur.forward, cur.lateral, cur.relY + 1);
                enqueueSupportNeighbor(belowDeck, seen, queue, component, cur.forward, cur.lateral, cur.relY - 1);
            }

            for (BridgeTemplate.BlockEntry b : component) {
                if (b.relY == minRelY) {
                    seeds.add(new BridgeTemplate.SupportSeed(b.forward, b.lateral, b.relY, b.state));
                }
            }
        }

        Collections.sort(seeds, new Comparator<BridgeTemplate.SupportSeed>() {
            @Override
            public int compare(BridgeTemplate.SupportSeed a, BridgeTemplate.SupportSeed b) {
                if (a.forward != b.forward) return Integer.compare(a.forward, b.forward);
                if (a.lateral != b.lateral) return Integer.compare(a.lateral, b.lateral);
                return Integer.compare(a.relY, b.relY);
            }
        });

        template.supportSeeds.clear();
        template.supportSeeds.addAll(seeds);
    }
    private static void enqueueSupportNeighbor(Map<String, BridgeTemplate.BlockEntry> belowDeck,
                                               HashSet<String> seen,
                                               ArrayDeque<BridgeTemplate.BlockEntry> queue,
                                               ArrayList<BridgeTemplate.BlockEntry> component,
                                               int forward,
                                               int lateral,
                                               int relY) {
        String key = localKey(forward, lateral, relY);
        BridgeTemplate.BlockEntry next = belowDeck.get(key);
        if (next == null) return;
        if (!seen.add(key)) return;

        queue.add(next);
        component.add(next);
    }
    private static String localKey(int forward, int lateral, int relY) {
        return forward + ":" + lateral + ":" + relY;
    }
}