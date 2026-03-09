package com.jubitus.millpathbuilder.config;

import com.jubitus.millpathbuilder.Tags;
import net.minecraftforge.common.config.Config;

@Config(modid = Tags.MOD_ID)
public class MPBConfig {

    @Config.Name("General")
    public static final General GENERAL = new General();

    @Config.Name("Network")
    public static final Network NETWORK = new Network();

    @Config.Name("Pathfinding")
    public static final Pathfinding PATHFINDING = new Pathfinding();

    @Config.Name("Painting")
    public static final Painting PAINTING = new Painting();

    // Paste inside MPBConfig class (near other config groups)
    @Config.Name("Water")
    public static final WaterAvoid WATER_AVOID = new WaterAvoid();

    public static final class WaterAvoid {
        @net.minecraftforge.common.config.Config.Comment({
                "If true, roads will avoid hugging rivers/lakes when not bridging.",
                "Land cells within the radius (Manhattan distance) of water/ice become forbidden for pathfinding + painting.",
                "Bridges are still allowed: water centerline cells remain allowed."
        })
        public boolean enabled = true;

        @net.minecraftforge.common.config.Config.Comment({
                "Manhattan radius from any water/ice that roads must avoid when on land.",
                "1 = adjacent only. Increase to ~4 for a wider buffer."
        })
        @net.minecraftforge.common.config.Config.RangeInt(min = 1, max = 16)
        public int bufferRadius = 1;
    }

    public static class General {
        @Config.Comment("Include Millenaire lone buildings as nodes.")
        public boolean includeLoneBuildings = true;

        @Config.Comment("Treat ocean/sea as forbidden. Prevents 'roads ending at sea'.")
        public boolean forbidOcean = true;

        @Config.Comment("If true, blacklisted biomes are forbidden (hard block). If false, they are just very expensive.")
        public boolean hardBlockBlacklistedBiomes = true;

        @Config.Comment("Biome registry names to avoid, e.g. minecraft:desert, minecraft:mesa, biomesoplenty:coral_reef")
        public String[] biomeBlacklist = new String[] {
                "minecraft:ocean",
                "minecraft:deep_ocean"
        };
        @Config.Comment("If true, generation is spread across ticks to avoid freezing the server.")
        public boolean incrementalGeneration = true;

        @Config.Comment("Time budget per server tick for generation (ms).")
        public int tickBudgetMs = 8;

        @Config.Comment("Do not paint/clear inside this radius (blocks) around Millenaire village nodes.")
        public int villageNoPaintRadius = 28;

        @Config.Comment("Do not paint/clear inside this radius (blocks) around Millenaire lone building nodes.")
        public int loneBuildingNoPaintRadius = 20;

        @Config.Comment("Print detailed reasons when a route fails.")
        public boolean printFailureBreakdown = true;
        @Config.Comment("Allow MPB to load/generate chunks during pathfinding when needed.")
        public boolean allowChunkLoading = true;

        @Config.Comment("Max number of chunks MPB may force-load per generation job (hard cap).")
        public int maxForcedChunksPerJob = 2500;

        @Config.Comment("If true, unload chunks loaded by MPB after each edge. If false, unload at end of job.")
        public boolean unloadChunksAfterEachEdge = true;

        public int snapToExistingRoadRadius = 48;
    }

    public static class Network {
        @Config.Comment("Build a Minimum Spanning Tree to ensure all nodes are connected when possible.")
        public boolean buildMST = true;

        @Config.Comment("Also connect each node to N nearest neighbors (extra realism / redundancy).")
        public int nearestNeighbors = 2;

        @Config.Comment("Maximum straight distance (blocks) to consider an edge candidate. Prevents absurd connections.")
        public int maxEdgeDistance = 8000;
    }

    public static class Pathfinding {

        @Config.Comment("Grid step in blocks. Higher = faster, less detailed. Recommended: 4..8")
        public int gridStep = 6;

        @Config.Comment("Max iterations for A* expansions per edge.")
        public int maxIterations = 1_200_000;

        @Config.Comment("Max vertical delta allowed between neighbor grid nodes.")
        public int maxYDeltaPerStep = 2;

        @Config.Comment("Cost multiplier for climbing/descending.")
        public double slopePenalty = 8.0;

        @Config.Comment("Additional cost if stepping on water (non-ocean).")
        public double waterPenalty = 40.0;

        @Config.Comment("Additional cost if stepping in a blacklisted biome when hardBlockBlacklistedBiomes=false.")
        public double blacklistedBiomePenalty = 5000.0;

        @Config.Comment("If true, allow the painter to dig/fill to enforce walkability.")
        public boolean allowEarthworks = true;

        @Config.Comment("If true, pathfinding tries to avoid protected zones (village/lone building footprints).")
        public boolean avoidProtectedZones = true;

        @Config.Comment("Extra cost added when a node is inside a protected zone.")
        public double protectedZonePenalty = 15000.0;

        @Config.Comment("Extra margin around the start-goal bounding box (blocks). Prevents infinite wandering.")
        public int searchMargin = 256;

        @Config.Comment("Hard cap radius from start (blocks). 0 disables.")
        public int maxSearchRadius = 0;

        public static boolean avoidParallelToExistingRoads = true;

        // how far from an existing road we start penalizing (in blocks)
        public static int parallelRoadAvoidRadius = 32;

        // scan step for approx distance (2 or 3 is good)
        public static int parallelRoadAvoidScanStep = 2;

        // penalty scale
        public static double parallelRoadPenalty = 1.25;

        // penalty curve power (2.0 = strong repulsion near road)
        public static double parallelRoadPenaltyPower = 2.0;
    }

    public static class Painting {
        @Config.Comment("Road width in blocks (odd numbers recommended). Default 3.")
        public int roadWidth = 3;

        @Config.Comment("Clear 2 blocks above the road to ensure walkability.")
        public boolean clearHeadroom = true;

        @Config.Comment("Build simple wooden bridges across non-ocean water.")
        public boolean buildBridges = true;

        @Config.Comment("Clear trees intersecting the road corridor (leaves + trunks).")
        public boolean clearTrees = true;

        @Config.Comment("Also clear trunk/log blocks when they look like a tree (mod compatible).")
        public boolean clearTreeTrunks = true;

        @Config.Comment("Extra penalty for crossing small water patches (puddles/marsh). Helps avoid tiny bridges.")
        public double smallWaterPenalty = 260.0;

        @Config.Comment("Radius (blocks) used to classify a water cell as 'small water patch'.")
        public int smallWaterScanRadius = 4;

        @Config.Comment("If the number of nearby water cells in scan radius is <= this, it's considered 'small water'.")
        public int smallWaterMaxCells = 22;


    }
    @Config.Name("Bridges")
    public static final Bridges BRIDGES = new Bridges();

    @Config.Name("ValleyBridging")
    public static final ValleyBridging VALLEY_BRIDGING = new ValleyBridging();

    public static final class Bridges {
        @Config.Comment("Master switch for any bridge generation.")
        public boolean enabled = true;

        @Config.Comment("Enable tiny spans (< microBridgeMaxLen) as slabs-only micro-bridges.")
        public boolean microBridges = true;

        @Config.Comment("If true, tiny water bridges are validated and built as simple supportless decks.")
        public boolean simpleWaterMicroBridgesNoSupports = true;

        @Config.Comment("Max length (in blocks) for a micro-bridge (slabs only). < 6 recommended.")
        @Config.RangeInt(min = 2, max = 16)
        public int microBridgeMaxLen = 5;

        @Config.Comment("Hard cap for any bridge length in blocks (supercover points count).")
        @Config.RangeInt(min = 8, max = 1024)
        public int maxBridgeLenBlocks = 180;

        @Config.Comment("Place supports every N blocks (only for full bridges, not micro-bridges).")
        @Config.RangeInt(min = 2, max = 32)
        public int supportSpacing = 6;

        @Config.Comment("Max vertical distance supports may extend down before giving up.")
        @Config.RangeInt(min = 8, max = 128)
        public int maxSupportDepth = 64;

        @Config.Comment("Max total deltaY allowed between bridge start and end deck height.")
        @Config.RangeInt(min = 0, max = 16)
        public int maxBridgeDeltaY = 3;

        @Config.Comment("Number of consecutive safe blocks required to accept a bridge exit. Prevents over-long bridges.")
        @Config.RangeInt(min = 1, max = 8)
        public int exitStableBlocks = 2;

        @Config.Comment("Max blocks allowed after the last obstacle before ending the bridge. Prevents overhang.")
        @Config.RangeInt(min = 0, max = 8)
        public int maxOverhangAfterObstacle = 1;

        @Config.Comment("Max delta in bridge deck surface per block, in HALF blocks. 1 = 0.5 block per step.")
        @Config.RangeInt(min = 0, max = 4)
        public int maxDeckDeltaHalfPerBlock = 1;

        @Config.Comment("How many blocks after a bridge exit we check to ensure the road does not immediately climb.")
        @Config.RangeInt(min = 0, max = 12)
        public int exitLookaheadBlocks = 4;

        @Config.Comment("Max allowed rise (in blocks) within exitLookaheadBlocks after the exit. 0 = must be flat.")
        @Config.RangeInt(min = 0, max = 8)
        public int exitMaxRiseBlocks = 1;

        @Config.Comment("Extra lift applied near the middle of bridges, in HALF blocks. 0 = disabled.")
        @Config.RangeInt(min = 0, max = 12)
        public int bridgeCrownHalfBlocks = 2;

        @Config.Comment("Only apply crown lift on bridges at least this long.")
        @Config.RangeInt(min = 2, max = 512)
        public int bridgeCrownMinLenBlocks = 8;

        @Config.Comment("If true, water bridges get +1 extra HALF block of crown compared to land bridges.")
        public boolean extraWaterBridgeCrown = true;

    }

    public static final class ValleyBridging {
        @Config.Comment("Enable valley/canyon bridging (not only water).")
        public boolean enabled = true;

        @Config.Comment("Max lookahead distance (blocks) to search for a valid exit point for a straight bridge.")
        @Config.RangeInt(min = 16, max = 512)
        public int maxLookahead = 160;

        @Config.Comment("Minimum straight bridge length (blocks) to consider valley bridging (avoids spam).")
        @Config.RangeInt(min = 8, max = 128)
        public int minSpanLen = 14;

        @Config.Comment("Bridge if the terrain drops at least this many blocks below entry/exit baseline.")
        @Config.RangeInt(min = 2, max = 48)
        public int minValleyDepth = 6;

        @Config.Comment("How tolerant we are to path curvature when deciding a straight bridge. Higher = more bridges.")
        @Config.RangeInt(min = 1, max = 16)
        public int maxLateralDeviation = 5;

        @Config.Comment("Cooldown between two valley bridges along the same road (blocks).")
        @Config.RangeInt(min = 0, max = 512)
        public int cooldownBlocks = 64;

        public int ridgeRiseBlocks = 2;
    }
    public static final Buildings BUILDINGS = new Buildings();
    public static final BridgeGeometry BRIDGE_GEOMETRY = new BridgeGeometry();

    public static class Buildings {

        // Master switch for any building avoidance reinforcement.
        public boolean enabled = true;

        // Extra padding added around each Millenaire BuildingLocation footprint (blocks).
        public int footprintPadding = 3;

        // Also add a soft penalty ring around footprints (helps A* prefer going around).
        public boolean addPenaltyRing = true;

        // Radius of the penalty ring outside the hard no-paint footprint (blocks).
        public int penaltyRingRadius = 6;

        // Cost added by the penalty ring (PATHFINDING extra cost).
        public double penaltyRingCost = 15000.0;

        // If true: PATHFINDING treats no-paint building zones as forbidden.
        // If false: only penalty applies (and painting still blocks).
        public boolean hardBlockInPathfinding = true;

        // If true, allow pathfinding to go through a building zone ONLY if a Millenaire path column already exists.
        public boolean allowExistingRoadThroughBuildings = false;

        // Optional additional guard: skip painting if the column "looks like a building" (manmade solids / tiles nearby),
        // even if the zone index missed it. Useful for edge cases/courtyards.
        public boolean extraPaintingGuard = true;

        // Radius for the extra painting guard scan (Manhattan-ish square).
        public int extraPaintingGuardRadius = 2;
    }

    public static class BridgeGeometry {

        // If true: bridges use an 8-connected raster line (recommended). Prevents diagonal widening artifacts.
        public boolean useRaster8ForBridges = true;
        
    }
    @Config.Name("BridgeAwarePathfinding")
    public static final BridgeAwarePathfinding BRIDGE_AWARE_PATHFINDING = new BridgeAwarePathfinding();

    public static final class BridgeAwarePathfinding {
        @Config.Comment("Enable A* bridge-aware pathfinding.")
        public boolean enabled = true;

        @Config.Comment("Minimum bridge length in blocks for A* bridge moves.")
        @Config.RangeInt(min = 2, max = 512)
        public int minBridgeLenBlocks = 10;

        @Config.Comment("Maximum bridge probe length in blocks for A* bridge moves.")
        @Config.RangeInt(min = 8, max = 512)
        public int maxProbeLenBlocks = 160;

        @Config.Comment("Probe step in blocks when searching bridge landing points.")
        @Config.RangeInt(min = 1, max = 32)
        public int probeLenStepBlocks = 6;

        @Config.Comment("Max accepted bridge candidates per expanded A* node.")
        @Config.RangeInt(min = 1, max = 32)
        public int maxBridgeCandidatesPerNode = 4;

        @Config.Comment("Fixed extra cost added to every bridge move.")
        public double bridgeFixedCost = 22.0;

        @Config.Comment("Multiplier applied to bridge geometric length cost.")
        public double bridgeLengthCostMultiplier = 1.18;

        @Config.Comment("Bonus per block of valley depth. Higher = more valley bridges.")
        public double bridgeValleyPreferenceBonusPerBlock = 1.5;

        @Config.Comment("Minimum number of normal path blocks between two bridges.")
        @Config.RangeInt(min = 0, max = 512)
        public int minDistanceBetweenBridgesBlocks = 28;

        @Config.Comment("Minimum air/gap wanted below bridge deck on interior cells.")
        @Config.RangeInt(min = 1, max = 16)
        public int minGroundClearanceBelowDeck = 2;

        @Config.Comment("Minimum consecutive interior cells that really benefit from a bridge.")
        @Config.RangeInt(min = 1, max = 128)
        public int minMeaningfulGapRun = 4;

        @Config.Comment("Clearance above bridge deck checked during feasibility.")
        @Config.RangeInt(min = 2, max = 8)
        public int walkClearanceAboveDeck = 3;

        @Config.Comment("Require bridge direction to be aligned with incoming path direction.")
        public boolean requireAlignedApproach = true;

        @Config.Comment("Minimum normalized dot product between incoming direction and bridge direction.")
        public double minApproachDot = 0.15;

        @Config.Comment("No bridge start/end within this radius of a village.")
        @Config.RangeInt(min = 0, max = 128)
        public int endpointVillageRadius = 36;

        @Config.Comment("No bridge start/end within this radius of a lone building.")
        @Config.RangeInt(min = 0, max = 128)
        public int endpointLoneBuildingRadius = 24;

        @Config.Comment("Bridge centerline must not pass within this radius of a village.")
        @Config.RangeInt(min = 0, max = 128)
        public int crossVillageRadius = 28;

        @Config.Comment("Bridge centerline must not pass within this radius of a lone building.")
        @Config.RangeInt(min = 0, max = 128)
        public int crossLoneBuildingRadius = 18;

        @Config.Comment("Minimum clearance below bridge deck for water cells. 1 allows normal river bridges when bank and water are at the same Y.")
        @Config.RangeInt(min = 1, max = 16)
        public int minWaterClearanceBelowDeck = 1;

        @Config.Comment("If a bridge is at least this long, require stronger elevation above terrain.")
        @Config.RangeInt(min = 2, max = 512)
        public int longBridgeMinLenForExtraClearance = 14;

        @Config.Comment("Minimum average terrain clearance required for long bridges.")
        @Config.RangeInt(min = 1, max = 32)
        public int longBridgeRequiredAverageClearance = 4;

        @Config.Comment("Minimum max terrain clearance required somewhere along a long bridge.")
        @Config.RangeInt(min = 1, max = 32)
        public int longBridgeRequiredMaxClearance = 6;

        @Config.Comment("If true, disables the old opportunistic BridgePlanner fallback and only uses bridge-aware A* bridges.")
        public boolean disableLegacyBridgePlannerFallback = true;
        @Config.Comment("Require this many buildable blocks straight ahead after bridge exit.")
        @Config.RangeInt(min = 1, max = 16)
        public int requiredExitCorridorBlocks = 4;

        @Config.Comment("Require this many forward-aligned blocks after bridge exit.")
        @Config.RangeInt(min = 1, max = 16)
        public int requiredForwardExitBlocks = 3;

        @Config.Comment("Force-paint this many blocks after bridge exit to secure road continuation.")
        @Config.RangeInt(min = 0, max = 16)
        public int forcePaintExitBlocks = 4;

        @Config.Comment("Minimum consecutive interior water cells required before a water crossing counts as a real bridge candidate.")
        @Config.RangeInt(min = 1, max = 128)
        public int minWaterRunBlocks = 3;
        @Config.Comment("For water bridges, max dry interior cells allowed before the first real water run.")
        @Config.RangeInt(min = 0, max = 64)
        public int maxWaterBridgeLeadInBlocks = 2;

        @Config.Comment("For water bridges, max dry interior cells allowed after the last real water run.")
        @Config.RangeInt(min = 0, max = 64)
        public int maxWaterBridgeLeadOutBlocks = 2;

        @Config.Comment("Extra side clearance checked around bridge centerline during feasibility. 0 = centerline only.")
        @Config.RangeInt(min = 0, max = 8)
        public int sideClearanceRadius = 1;

        @Config.Comment("When snapping to an existing road, keep at least this many land blocks before merging into it.")
        @Config.RangeInt(min = 1, max = 32)
        public int bridgeRoadApproachMinBlocks = 4;

        @Config.Comment("Maximum search radius around a snapped road to find a small natural land approach before the road.")
        @Config.RangeInt(min = 1, max = 48)
        public int bridgeRoadApproachMaxBlocks = 10;

        @Config.Comment("Maximum allowed vertical difference with an existing road when merging, in HALF blocks. 1 = 0.5 block.")
        @Config.RangeInt(min = 0, max = 8)
        public int maxRoadJoinDeltaHalfBlocks = 1;

        @Config.Comment("Do not early-merge into an existing road until at least this many blocks after a bridge exit.")
        @Config.RangeInt(min = 0, max = 64)
        public int minBlocksAfterBridgeBeforeMerge = 6;
        @Config.Comment("Minimum terrain clearance required for a bridge cell to count as genuinely elevated.")

        @Config.RangeInt(min = 1, max = 32)
        public int meaningfulGroundClearanceBlocks = 3;

        @Config.Comment("Minimum water clearance required for a bridge cell to count as genuinely elevated above water.")
        @Config.RangeInt(min = 1, max = 32)
        public int meaningfulWaterClearanceBlocks = 2;

        @Config.Comment("Minimum percentage of interior bridge cells that must have meaningful clearance. 0..100.")
        @Config.RangeInt(min = 0, max = 100)
        public int minMeaningfulClearancePercent = 65;

        @Config.Comment("Ignore this many cells near each bridge end when evaluating global meaningful clearance.")
        @Config.RangeInt(min = 0, max = 16)
        public int endpointGraceCells = 2;

        @Config.Comment("Require the final cells before bridge exit to stay clearly above terrain/water. 0 disables.")
        @Config.RangeInt(min = 0, max = 16)
        public int exitMinClearanceBlocks = 2;

        @Config.Comment("How many cells before the bridge end must satisfy exitMinClearanceBlocks. 0 disables.")
        @Config.RangeInt(min = 0, max = 16)
        public int exitMinClearanceCheckCells = 3;

    }
}