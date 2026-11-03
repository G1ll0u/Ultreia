package com.jubitus.ultreia.config;

import com.jubitus.ultreia.bridge.BridgeGeometry;
import net.minecraftforge.common.config.Config;

public class MPBConfig {

    public static final General GENERAL = new General();

    public static final Network NETWORK = new Network();

    public static final Pathfinding PATHFINDING = new Pathfinding();

    public static final Painting PAINTING = new Painting();

    // Paste inside MPBConfig class (near other config groups)
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
        public boolean hardBlockBlacklistedBiomes = false;

        @Config.Comment("Print detailed reasons when a route fails.")
        public boolean printFailureBreakdown = true;
        @Config.Comment("Print per-span bridge debug traces during pathfinding. Very verbose — leave off unless debugging bridge placement.")
        public boolean printBridgeDebug = false;
        @Config.Comment("Allow MPB to load/generate chunks during pathfinding when needed.")
        public boolean allowChunkLoading = true;

        @Config.Comment("Automatically resume an interrupted generation job when the world loads.")
        public boolean autoResumeGeneration = true;


        @Config.Comment("Log chunk load/unload lifecycle events for debugging. Disable in production.")
        public boolean debugLogChunkLifecycle = false;

        @Config.Comment("Biome registry names to avoid, e.g. minecraft:desert, minecraft:mesa, biomesoplenty:coral_reef")
        public String[] biomeBlacklist = new String[]{"minecraft:ocean", "minecraft:deep_ocean"};



        @Config.Comment("Time per server tick spent building roads (ms). Higher builds faster but makes the server lag; 20 keeps it smooth.")
        @Config.RangeInt(min = 1, max = 1000)
        public int tickBudgetMs = 20;

        @Config.Comment("Do not paint/clear inside this radius (blocks) around Millenaire village nodes.")
        public int villageNoPaintRadius = 2;

        @Config.Comment("Do not paint/clear inside this radius (blocks) around Millenaire lone building nodes.")
        public int loneBuildingNoPaintRadius = 2;



        @Config.Comment("Max chunks MPB may force-load per edge (safety cap). Resets between edges.")
        public int maxForcedChunksPerJob = 4000;


        public int snapToExistingRoadRadius = 28;

        @Config.Comment("Radius around village center to search for gate buildings (borderpost_gate, full_wall_gateway, etc.) when resolving road entry points.")
        public int gateSearchRadius = 120;

        @Config.Comment("Radius around each gate building's anchor to scan for Millénaire path blocks.")
        public int gateMillPathSearchRadius = 120;



        @Config.Comment("Delay in server ticks before auto-resuming a job after world load (gives Millenaire time to initialise).")
        @Config.RangeInt(min = 20, max = 600)
        public int autoResumeDelayTicks = 100;

    }

    public static class Network {
        @Config.Comment("Build a Minimum Spanning Tree to ensure all villages are connected when possible.")
        public boolean buildMST = true;

        @Config.Comment({
                "Use Relative Neighborhood Graph instead of K-nearest-neighbors for extra edges.",
                "Produces cleaner, less spaghetti-like networks. Recommended: true.",
                "When false, falls back to nearestNeighbors KNN."
        })
        public boolean useRNG = true;

        @Config.Comment({
                "If true, reject RNG candidate edges that geometrically cross (X-shape) an already-committed edge.",
                "Prevents confusing non-intersection crossings on the map."
        })
        public boolean avoidEdgeCrossings = true;

        @Config.Comment({
                "Only used when useRNG=false. Connect each village to N nearest neighbors.",
        })
        public int nearestNeighbors = 2;

        @Config.Comment("Maximum straight distance (blocks) to consider an edge candidate. Prevents absurd connections.")
        public int maxEdgeDistance = 8000;

        @Config.Comment({
                "Redundancy suppression ratio. A candidate RNG edge (A→B) is dropped if the MST already",
                "contains a path from A to B whose total length is ≤ ratio × direct distance(A,B).",
                "1.0 = only keep edges that are truly shortcuts; 1.5 = generous; 0 = disable check."
        })
        @Config.RangeDouble(min = 0.0, max = 5.0)
        public double redundancyRatio = 1.3;

        @Config.Comment("Minimum villagers for a village to be classified as a HUB.")
        @Config.RangeInt(min = 1, max = 500)
        public int hubMinVillagers = 30;

        @Config.Comment("Minimum villagers for a village to be classified as MEDIUM (below this = SMALL).")
        @Config.RangeInt(min = 1, max = 500)
        public int mediumMinVillagers = 16;

        @Config.Comment({
                "Distance bonus applied when both endpoints of an RNG edge are HUBs.",
                "Hub-hub effective distance = actual_distance × (1 - bonus), making their connections",
                "harder to prune by the RNG lune criterion.",
                "0 = no bonus; 0.25 = 25% discount (recommended); max 0.9."
        })
        @Config.RangeDouble(min = 0.0, max = 0.9)
        public double hubConnectionBonus = 0.25;

        @Config.Comment("Log detailed network-building decisions (RNG, redundancy, crossings). Disable in production.")
        public boolean debugNetwork = false;
    }

    public static class Pathfinding {

        @Config.Comment("Grid step in blocks. Higher = faster, less detailed. Recommended: 4..8. Default 4: 4x fewer states than 2, critical for mountain routes.")
        public int gridStep = 2;

        @Config.Comment("Max iterations for A* expansions per edge.")
        public int maxIterations = 10000000;

        @Config.Comment("Wall-clock timeout in seconds per path. A* gives up and uses best partial path after this time. Mountain routes need more time.")
        @Config.RangeInt(min = 10, max = 600)
        public int maxWallTimeSeconds = 320;

        @Config.Comment("Max vertical delta allowed between neighbor grid nodes.")
        public int maxYDeltaPerStep = 2;

        @Config.Comment("Cost multiplier for climbing/descending.")
        public double slopePenalty = 8.0;

        @Config.Comment("Extra cost per unit of local terrain roughness along a segment.")
        public double surfaceRoughnessPenalty = 3.0;

        @Config.Comment("Hard cap for cumulative roughness along one neighbor segment. Increased for mountain terrain.")
        public int maxSegmentRoughness = 4;

        @Config.Comment("Additional cost if stepping on water (non-ocean).")
        public double waterPenalty = 24.0;

        @Config.Comment({
                "Extra cost when a tree trunk/branch is in the headroom above a grid cell.",
                "Makes the pathfinder prefer routes that go around forests.",
                "Only applied when clearTrees=true. Set to 0 to disable tree avoidance."
        })
        public double treePenalty = 15.0;

        @Config.Comment({
                "Extra pathfinding cost when a cell contains farmland (or modded equivalent).",
                "The road painter will also never place blocks on farmland regardless of this value.",
                "High default value to strongly route paths around cultivated fields.",
                "Set to 0 to allow the pathfinder to ignore farmland (painter still skips it)."
        })
        public double farmlandPenalty = 4000.0;

        @Config.Comment({
                "DynamicTrees branch radius (1-8) above which a tree is considered 'large'.",
                "Large trees receive the dtLargeTreePenaltyMultiplier bonus on top of treePenalty.",
                "Radius 1-2 = saplings/small stubs (soft avoidance), 3+ = real trunks (strong avoidance).",
                "Default: 3."
        })
        @Config.RangeInt(min = 1, max = 8)
        public int dtLargeTreeRadiusThreshold = 3;

        @Config.Comment({
                "Multiplier applied to treePenalty when a large DynamicTrees trunk is nearby.",
                "Total cost = treePenalty * multiplier. Higher values = more aggressive avoidance.",
                "Default: 6.0 (a large DT tree costs 6x a vanilla tree to cut through)."
        })
        @Config.RangeDouble(min = 1.0, max = 50.0)
        public double dtLargeTreePenaltyMultiplier = 6.0;

        @Config.Comment({
                "Horizontal scan radius (blocks) used to detect DynamicTrees trunks near a path cell.",
                "The pathfinder checks nearby positions so the full road width avoids large trunks.",
                "Set to 0 to disable proximity check (only the exact cell is tested).",
                "Default: 2."
        })
        @Config.RangeInt(min = 0, max = 8)
        public int dtTreeProximityCheckRadius = 2;

        @Config.Comment("Additional cost if stepping in a blacklisted biome when hardBlockBlacklistedBiomes=false.")
        public double blacklistedBiomePenalty = 5000.0;

        @Config.Comment("If true, allow the painter to dig/fill to enforce walkability.")
        public boolean allowEarthworks = true;

        @Config.Comment("If true, pathfinding tries to avoid protected zones (village/lone building footprints).")
        public boolean avoidProtectedZones = true;

        @Config.Comment("Extra cost added when a node is inside a protected zone.")
        public double protectedZonePenalty = 15000.0;

        @Config.Comment("Extra margin around the start-goal bounding box (blocks). Prevents infinite wandering.")
        public int searchMargin = 230;

        @Config.Comment("Hard cap radius from start (blocks). 0 disables.")
        public int maxSearchRadius = 0;

        @Config.Comment({
                "Maximum half-width of the A* search corridor (blocks).",
                "The corridor is the perpendicular distance the pathfinder can deviate from",
                "the straight line between start and goal. For mountain routes with large",
                "elevation changes, the auto-computed corridor includes a climb-boost that",
                "can reach 300+ blocks; this cap must be large enough to allow switchbacks.",
                "A 40-block climb needs ~370 blocks of lateral room for switchbacks.",
                "Reduce to 192 for flat/open terrain, keep at 512+ for mountain worlds.",
                "Default: 512."
        })
        @Config.RangeInt(min = 64, max = 1024)
        public int corridorHalfWidthMax = 256;

        @Config.Comment("Minimum total rise within lookahead before a straight uphill is considered 'direct climb'.")
        @Config.RangeInt(min = 1, max = 16)
        public int uphillLookaheadMinRise = 3;

        @Config.Comment("Predicted maximum cut depth pathfinding treats as normal terrain shaping on one move. 4 blocks allows mountain passes without over-penalising.")
        @Config.RangeInt(min = 0, max = 12)
        public int predictedMaxCutDepth = 4;

        @Config.Comment({
                "Penalty per block of predicted cut depth when a move cuts into terrain.",
                "The openHillsideFactor in code applies a 0.3x discount for open hillside cuts,",
                "so the effective rate for natural embankments is ~15/block vs 50/block for enclosed trenches."
        })
        public double predictedCutPenaltyPerBlock = 50.0;

        @Config.Comment({
                "Penalty per block of fill required along a segment. Higher values push the path to prefer",
                "cutting through terrain rather than building fill embankments on the opposite side.",
                "Set to 0 to treat fill same as cut. Default 120 (~2.4× the cut penalty)."
        })
        public double predictedFillPenaltyPerBlock = 120.0;

        @Config.Comment("Lateral scan radius used to detect enclosed mountain cutting.")
        @Config.RangeInt(min = 1, max = 8)
        public int enclosedCutScanRadius = 3;

        @Config.Comment("Minimum wall height on both sides for a cell to count as enclosed cutting.")
        @Config.RangeInt(min = 1, max = 8)
        public int enclosedWallMinHeight = 2;

        @Config.Comment("Penalty added when a move enters enclosed cutting on both sides (trench/saw-through-ridge). Keep high to prevent unnatural trenches.")
        public double enclosedCutPenalty = 150.0;

        @Config.Comment("Extra penalty per additional enclosed cell in a sustained enclosed cut run. Discourages long saw-like trenches through ridges.")
        public double sustainedEnclosedCutPenalty = 100.0;

        @Config.Comment("Forward lookahead used to discourage direct enclosed mountain ascent.")
        @Config.RangeInt(min = 1, max = 16)
        public int mountainAscentLookahead = 4;

        @Config.Comment("Penalty for continuing into a sustained enclosed uphill push. Reduced to allow mountain passes.")
        public double mountainAscentPenalty = 50.0;

        @Config.Comment({
                "Cut cost multiplier applied when the terrain being cut is hard rock (stone, granite, etc.).",
                "Soft terrain (dirt, snow, sand) uses 1.0x. Hard rock (stone+) uses this multiplier.",
                "1.5 still favours softer terrain while not making rock-cutting prohibitively expensive."
        })
        @Config.RangeDouble(min = 1.0, max = 8.0)
        public double hardRockCutMultiplier = 1.5;

        @Config.Comment("If true, when the goal cannot be reached exactly, return a path to the best reached point near the goal instead of failing. Recommended true for mountain worlds to at least partially connect isolated villages.")
        public boolean allowPartialPathFallback = false;

        @Config.Comment({
                "Maximum number of full-path profile rejections before A* gives up.",
                "Each rejection marks the endpoint as closed and A* retries with an alternate path.",
                "Increase for complex mountain terrain where many candidate paths fail the profile check.",
                "Default: 150."
        })
        @Config.RangeInt(min = 8, max = 500)
        public int profileRejectionLimit = 300;

        @Config.Comment({
                "If true, multiply the stagnation-abort threshold when the route has a large elevation change.",
                "Switchbacks require lateral traversal that makes no XZ progress, which normally triggers",
                "the stagnation abort. This boost allows A* more iterations to complete switchbacks.",
                "Default: true."
        })
        public boolean switchbackStagnationBoost = true;

        @Config.Comment({
                "Extra earthworks tolerance (blocks) added at pathfinding time only, on top of earthworksMaxCut/Fill.",
                "Lets A* find routes through steeper terrain without changing what the painter actually does.",
                "The painter still uses earthworksMaxCut/Fill from the Painting section — only the path search is relaxed.",
                "Increase if paths fail in mountainous worlds; decrease to 0 to match painter limits exactly.",
                "Default: 4 — lets A* find routes through mountain terrain that the painter smooths with earthworks."
        })
        @Config.RangeInt(min = 0, max = 8)
        public int pathfindingEarthworksTolerance = 4;

        @Config.Comment({
                "Penalty multiplier for direction changes. Applied quadratically: almost free for gentle curves,",
                "significant for 90° turns, near-prohibitive for U-turns. Prevents zigzagging on flat terrain",
                "while still allowing natural road curves and mountain switchbacks.",
                "Formula: t^2 * turnPenalty * gridStep, where t = (1 - cos(angle)) / 2.",
                "At gridStep=4 with default 5.0: 90°≈5 blocks cost, 180°≈20 blocks cost.",
                "Increase to straighten roads further; decrease for tighter-winding terrain paths. Default: 5.0."
        })
        @Config.RangeDouble(min = 0.0, max = 50.0)
        public double turnPenalty = 5.0;

        @Config.Comment({
                "If true, automatically increases gridStep for very long routes to limit search space.",
                "Routes > 600 blocks use step*2, routes > 1000 blocks use step*3 (capped at 12).",
                "Disable if you notice roads being too coarse on long segments."
        })
        public boolean adaptiveGridStep = true;

        @Config.Comment("If true, give a cost bonus when the A* pathfinder moves onto an existing road block, promoting road sharing over parallel construction.")
        public boolean preferExistingRoads = false;

        @Config.Comment("Cost reduction per step when moving onto an existing road block. Higher values make the pathfinder follow existing roads more aggressively. Recommended: 2.5–4.0 for gridStep=4.")
        @Config.RangeDouble(min = 0.0, max = 15.0)
        public double existingRoadBonus = 3.0;

        @Config.Comment("If true, penalise nodes that run parallel to existing roads but are not on them. Disabled by default since preferExistingRoads now handles road reuse more cleanly.")
        public boolean avoidParallelToExistingRoads = false;

        @Config.Comment("Manhattan radius used to detect nearby existing roads for the parallel-avoidance penalty.")
        public int parallelRoadAvoidRadius = 32;

        @Config.Comment("Step size used when scanning for nearby roads (larger = faster but less accurate).")
        public int parallelRoadAvoidScanStep = 2;

        @Config.Comment("Base penalty multiplier for the parallel-road avoidance system.")
        public double parallelRoadPenalty = 1.25;

        @Config.Comment("Power exponent applied to the parallel-road avoidance penalty (higher = steeper gradient).")
        public double parallelRoadPenaltyPower = 2.0;
    }

    public static class Painting {
        @Config.Comment({
                "Width in blocks of village roads (even values are rounded up to odd). Default 3.",
                "Lone trails are 2 blocks narrower (min 1) when road hierarchy is enabled.",
                "Bridge decks are always 3 wide."
        })
        public int roadWidth = 3;

        @Config.Comment("Clear 2 blocks above the road to ensure walkability.")
        public boolean clearHeadroom = true;

        @Config.Comment("Build simple wooden bridges across non-ocean water.")
        public boolean buildBridges = true;

        @Config.Comment("Clear trees intersecting the road corridor (leaves + trunks).")
        public boolean clearTrees = true;

        @Config.Comment("Also clear trunk/log blocks when they look like a tree (mod compatible).")
        public boolean clearTreeTrunks = true;

        @Config.Comment({
                "When a DynamicTrees trunk with radius >= dtWholeTreeRemovalThreshold is encountered",
                "while clearing the road corridor, remove the entire tree via BFS rather than just the",
                "path column. Leaves are left to decay naturally (no cube of air).",
                "Disable to revert to single-column clearing for DT trees."
        })
        public boolean dtRemoveWholeTree = true;

        @Config.Comment({
                "DynamicTrees branch radius at or above which the entire tree is removed when it",
                "intersects the road corridor (requires dtRemoveWholeTree=true).",
                "Radius 1-2 = small branches (cleared locally), 3+ = real trunk (whole tree removed).",
                "Default: 3."
        })
        @Config.RangeInt(min = 1, max = 8)
        public int dtWholeTreeRemovalThreshold = 1;

        @Config.Comment({
                "Maximum number of blocks trimmed from one edge of the road to create organic width variation.",
                "0 = disabled (uniform width). 1 = trim up to 1 block from one side (road varies between",
                "(roadWidth - 1) and roadWidth wide). Never reduces the road to 1 block.",
                "Has no effect on 1-block-wide roads (LONE_TRAIL with baseR=0)."
        })
        @Config.RangeInt(min = 0, max = 2)
        public int roadEdgeVariation = 1;

        @Config.Comment({
                "Average number of road blocks between edge-variation changes.",
                "Lower values = more frequent and chaotic variation; higher values = longer smooth runs.",
                "Recommended range: 4 (frequent) to 20 (gentle). Default: 8."
        })
        @Config.RangeInt(min = 2, max = 40)
        public int roadEdgeVariationPeriod = 8;

        @Config.Comment({
                "If true, the painter skips waypoints that run within road-width of an already-painted road,",
                "then resumes when the paths diverge. Physical connectors are force-painted at each",
                "entry/exit boundary so the two roads remain physically connected."
        })
        public boolean suppressParallelPainting = true;

        @Config.Comment("Extra penalty for crossing small water patches (puddles/marsh). Helps avoid tiny bridges.")
        public double smallWaterPenalty = 260.0;

        @Config.Comment("Radius (blocks) used to classify a water cell as 'small water patch'.")
        public int smallWaterScanRadius = 4;

        @Config.Comment("If the number of nearby water cells in scan radius is <= this, it's considered 'small water'.")
        public int smallWaterMaxCells = 22;

        @Config.Comment("Smoothing radius for the road centerline height profile.")
        @Config.RangeInt(min = 1, max = 32)
        public int verticalSmoothRadius = 2;

        @Config.Comment("Maximum height change per road block in HALF blocks. 1 = 0.5 block.")
        @Config.RangeInt(min = 1, max = 4)
        public int maxRoadStepHalfPerBlock = 1;

        @Config.Comment("Maximum cut depth for road earthworks. 5 blocks allows mountain-pass talus cuts without looking like a canyon.")
        @Config.RangeInt(min = 0, max = 16)
        public int earthworksMaxCut = 5;

        @Config.Comment("Maximum fill height for road earthworks. 3 blocks for valley approaches.")
        @Config.RangeInt(min = 0, max = 16)
        public int earthworksMaxFill = 3;

        @Config.Comment("Shoulder width used to taper earthworks away from the road surface. 3 blocks produces a wider, more natural-looking talus in mountains.")
        @Config.RangeInt(min = 0, max = 8)
        public int earthworksShoulder = 3;

        @Config.Comment({
                "Talus slope ratio for earthworks side-slopes (blocks of lateral spread per block of height difference).",
                "1.0 = 45° (1 block out per block up/down). 1.5 = gentler (~34°). 0.7 = steeper (~55°).",
                "Controls the side-slopes created outside the road surface after cut or fill."
        })
        @Config.RangeDouble(min = 0.3, max = 4.0)
        public double earthworksSlopeRatio = 1.0;

        @Config.Comment({
                "Talus slope ratio for LONE_TRAIL paths. A narrower trail looks more natural with a slightly",
                "steeper talus. 0.7 = steeper slope (~55°). Set to -1 to use earthworksSlopeRatio for all roads."
        })
        @Config.RangeDouble(min = -1.0, max = 4.0)
        public double earthworksSlopeRatioLoneTrail = 0.7;
    }

    public static final Bridges BRIDGES = new Bridges();

    public static final ValleyBridging VALLEY_BRIDGING = new ValleyBridging();

    public static final class Bridges {

        @Config.Comment("Default bridge template name used when no other template matches the current conditions.")
        public String defaultTemplateName = "default";

        @Config.Comment("Search radius in blocks for /ultreia bridge create.")
        @Config.RangeInt(min = 8, max = 96)
        public int templateCreateSearchRadius = 24;

        @Config.Comment("Search radius in blocks for /ultreia bridge export.")
        @Config.RangeInt(min = 8, max = 128)
        public int debugExportSearchRadius = 48;

        @Config.Comment("Maximum lateral reach from the 3-wide deck centerline that template export keeps.")
        @Config.RangeInt(min = 3, max = 32)
        public int templateMaxLateral = 8;

        @Config.Comment("Maximum blocks above deck kept in a bridge template.")
        @Config.RangeInt(min = 2, max = 32)
        public int templateMaxAboveDeck = 8;

        @Config.Comment("Maximum blocks below deck kept in a bridge template.")
        @Config.RangeInt(min = 2, max = 64)
        public int templateMaxBelowDeck = 20;

        @Config.Comment("Maximum number of saved blocks in one bridge template or bridge debug export.")
        @Config.RangeInt(min = 64, max = 16384)
        public int templateMaxBlocks = 4096;

        @Config.Comment("Master switch for any bridge generation.")
        public boolean enabled = true;

        @Config.Comment("Hard cap for any bridge length in blocks (supercover points count).")
        @Config.RangeInt(min = 8, max = 1024)
        public int maxBridgeLenBlocks = 128;

        @Config.Comment("Max vertical distance supports may extend down before giving up.")
        @Config.RangeInt(min = 8, max = 128)
        public int maxSupportDepth = 128;

        @Config.Comment("How many blocks a support embeds into the ground at its base (realistic abutment anchoring). 0 = stop at surface.")
        @Config.RangeInt(min = 0, max = 4)
        public int supportGroundSinkBlocks = 1;

        @Config.Comment("Maximum slope angle (degrees) between bridge start and end deck heights. Longer bridges are allowed a proportionally larger height difference.")
        public double maxBridgeSlopeDegrees = 10.0;

        @Config.Comment("Max delta in bridge deck surface per block, in HALF blocks. 1 = 0.5 block per step.")
        @Config.RangeInt(min = 0, max = 4)
        public int maxDeckDeltaHalfPerBlock = 1;

        @Config.Comment("Extra lift applied near the middle of bridges, in HALF blocks. 0 = disabled.")
        @Config.RangeInt(min = 0, max = 12)
        public int bridgeCrownHalfBlocks = 4;

        @Config.Comment("Only apply crown lift on bridges at least this long.")
        @Config.RangeInt(min = 2, max = 512)
        public int bridgeCrownMinLenBlocks = 8;

        @Config.Comment("If true, water bridges get +1 extra HALF block of crown compared to land bridges.")
        public boolean extraWaterBridgeCrown = true;

    }

    public static final class ValleyBridging {
        @Config.Comment("Enable valley/canyon bridging (not only water).")
        public boolean enabled = true;

        @Config.Comment("Bridge if the terrain drops at least this many blocks below entry/exit baseline. 6 blocks catches more mountain valleys and gorges.")
        @Config.RangeInt(min = 2, max = 48)
        public int minValleyDepth = 6;

    }
    public static final Buildings BUILDINGS = new Buildings();
    public static final BridgeGeometry BRIDGE_GEOMETRY = new BridgeGeometry();

    public static class Buildings {

        // Master switch for any building avoidance reinforcement.
        public boolean enabled = true;

        // Extra padding added around each Millenaire BuildingLocation footprint (blocks).
        public int footprintPadding = 1;

        // Also add a soft penalty ring around footprints (helps A* prefer going around).
        public boolean addPenaltyRing = true;

        // Radius of the penalty ring outside the hard no-paint footprint (blocks).
        public int penaltyRingRadius = 1;

        // Cost added by the penalty ring (PATHFINDING extra cost).
        public double penaltyRingCost = 15000.0;

        // If true: PATHFINDING treats no-paint building zones as forbidden.
        // If false: only penalty applies (and painting still blocks).
        public boolean hardBlockInPathfinding = false;

        // If true, allow pathfinding to go through a building zone ONLY if a Millenaire path column already exists.
        public boolean allowExistingRoadThroughBuildings = true;

        // Optional additional guard: skip painting if the column "looks like a building" (manmade solids / tiles nearby),
        // even if the zone index missed it. Useful for edge cases/courtyards.
        public boolean extraPaintingGuard = true;

        // Radius for the extra painting guard scan (Manhattan-ish square).
        public int extraPaintingGuardRadius = 1;
    }

    public static class BridgeGeometry {

        // If true: bridges use an 8-connected raster line (recommended). Prevents diagonal widening artifacts.
        public boolean useRaster8ForBridges = true;
        
    }
    public static final BridgeAwarePathfinding BRIDGE_AWARE_PATHFINDING = new BridgeAwarePathfinding();

    public static final class BridgeAwarePathfinding {
        @Config.Comment("Enable A* bridge-aware pathfinding.")
        public boolean enabled = true;

        @Config.Comment("Do not apply road earthworks this many cells before/after a bridge join.")
        @Config.RangeInt(min = 0, max = 8)
        public int bridgeJoinProtectionBlocks = 0;

        @Config.Comment("Minimum bridge length in blocks for A* bridge moves.")
        @Config.RangeInt(min = 2, max = 512)
        public int minBridgeLenBlocks = 8;

        @Config.Comment("Maximum bridge probe length in blocks for A* bridge moves.")
        @Config.RangeInt(min = 8, max = 512)
        public int maxProbeLenBlocks = 128;

        @Config.Comment("Flat overhead penalty applied to all water-dominant bridge moves. Higher discourages water bridges more.")
        public double waterBridgeBasePenalty = 250.0;

        @Config.Comment({
                "Maximum number of water interior cells a water-dominant bridge may span.",
                "Bridges crossing more water cells than this are rejected during feasibility.",
                "Keep low (16-32) to prevent bridges over lakes while still allowing river crossings.",
                "Set higher if you want to allow lake bridges."
        })
        @Config.RangeInt(min = 4, max = 128)
        public int maxWaterBridgeLenBlocks = 24;

        @Config.Comment({
                "Extra cost added per water interior cell for water-dominant bridges.",
                "Stacks with waterBridgeBasePenalty to progressively penalise longer water crossings.",
                "Higher values make A* prefer shorter river crossings over longer lake crossings."
        })
        @Config.RangeDouble(min = 0.0, max = 50.0)
        public double waterBridgeLengthPenaltyPerBlock = 8.0;

        @Config.Comment("Cost added when entering an XZ column that belongs to an existing bridge volume (deck + supports + lateral extent). Prevents paths from cutting through existing bridge structures.")
        public double existingBridgeVolumeAvoidanceCost = 700.0;

        @Config.Comment("Max accepted bridge candidates per expanded A* node.")
        @Config.RangeInt(min = 1, max = 32)
        public int maxBridgeCandidatesPerNode = 4;

        @Config.Comment("Fixed extra cost added to every bridge move.")
        public double bridgeFixedCost = 230;

        @Config.Comment("Multiplier applied to bridge geometric length cost.")
        public double bridgeLengthCostMultiplier = 1.10;

        @Config.Comment("Bonus per block of valley depth. Higher = more valley bridges.")
        public double bridgeValleyPreferenceBonusPerBlock = 1.23;

        @Config.Comment("Minimum number of normal path blocks between two bridges.")
        @Config.RangeInt(min = 0, max = 512)
        public int minDistanceBetweenBridgesBlocks = 28;

        @Config.Comment("Minimum air/gap wanted below bridge deck on interior cells.")
        @Config.RangeInt(min = 1, max = 16)
        public int minGroundClearanceBelowDeck = 4;

        @Config.Comment("Minimum consecutive interior cells that really benefit from a bridge.")
        @Config.RangeInt(min = 1, max = 128)
        public int minMeaningfulGapRun = 5;

        @Config.Comment("Clearance above bridge deck checked during feasibility.")
        @Config.RangeInt(min = 2, max = 8)
        public int walkClearanceAboveDeck = 3;

        @Config.Comment({
                "Safety rail only, not the main alignment mechanism: rejects bridge directions that would",
                "require a near-reversal from the incoming path direction. Normal steering toward a well-",
                "aligned bridge is handled by the same turnPenalty cost applied to ordinary road turns, so",
                "this only needs to catch the pathological case, not enforce a tight angle."
        })
        public boolean requireAlignedApproach = true;

        @Config.Comment("Minimum normalized dot product between incoming direction and bridge direction (safety rail, see requireAlignedApproach).")
        @Config.RangeDouble(min = -1.0, max = 1.0)
        public double minApproachDot = -0.3;

        @Config.Comment("No bridge start/end within this radius of a village.")
        @Config.RangeInt(min = 0, max = 128)
        public int endpointVillageRadius = 10;

        @Config.Comment("No bridge start/end within this radius of a lone building.")
        @Config.RangeInt(min = 0, max = 128)
        public int endpointLoneBuildingRadius = 10;

        @Config.Comment("Bridge centerline must not pass within this radius of a village.")
        @Config.RangeInt(min = 0, max = 128)
        public int crossVillageRadius = 10;

        @Config.Comment("Bridge centerline must not pass within this radius of a lone building.")
        @Config.RangeInt(min = 0, max = 128)
        public int crossLoneBuildingRadius = 10;

        @Config.Comment("Minimum clearance below bridge deck for water cells. 1 allows normal river bridges when bank and water are at the same Y.")
        @Config.RangeInt(min = 1, max = 16)
        public int minWaterClearanceBelowDeck = 1;

        @Config.Comment("If a bridge is at least this long, require stronger elevation above terrain.")
        @Config.RangeInt(min = 2, max = 512)
        public int longBridgeMinLenForExtraClearance = 14;

        @Config.Comment("Minimum average terrain clearance required for long bridges.")
        @Config.RangeInt(min = 1, max = 32)
        public int longBridgeRequiredAverageClearance = 5;

        @Config.Comment("Minimum max terrain clearance required somewhere along a long bridge.")
        @Config.RangeInt(min = 1, max = 32)
        public int longBridgeRequiredMaxClearance = 6;

        @Config.Comment("Require this many buildable blocks straight ahead after bridge exit.")
        @Config.RangeInt(min = 1, max = 16)
        public int requiredExitCorridorBlocks = 1;

        @Config.Comment("Require this many forward-aligned blocks after bridge exit.")
        @Config.RangeInt(min = 1, max = 16)
        public int requiredForwardExitBlocks = 1;

        @Config.Comment("Force-paint this many blocks after bridge exit to secure road continuation.")
        @Config.RangeInt(min = 0, max = 16)
        public int forcePaintExitBlocks = 2;

        @Config.Comment("Minimum consecutive interior water cells required before a water crossing counts as a real bridge candidate.")
        @Config.RangeInt(min = 1, max = 128)
        public int minWaterRunBlocks = 7;
        @Config.Comment("For water bridges, max dry interior cells allowed before the first real water run. Keep low (1-2) so bridges start at water, not at adjacent ponds.")
        @Config.RangeInt(min = 0, max = 64)
        public int maxWaterBridgeLeadInBlocks = 1;

        @Config.Comment("For water bridges, max dry interior cells allowed after the last real water run.")
        @Config.RangeInt(min = 0, max = 64)
        public int maxWaterBridgeLeadOutBlocks = 1;

        @Config.Comment("Extra side clearance checked around bridge centerline during feasibility. 0 = centerline only.")
        @Config.RangeInt(min = 0, max = 8)
        public int sideClearanceRadius = 1;

        @Config.Comment("When snapping to an existing road, keep at least this many land blocks before merging into it.")
        @Config.RangeInt(min = 1, max = 32)
        public int bridgeRoadApproachMinBlocks = 8;

        @Config.Comment("Maximum search radius around a snapped road to find a small natural land approach before the road.")
        @Config.RangeInt(min = 1, max = 48)
        public int bridgeRoadApproachMaxBlocks = 8;

        @Config.Comment("Maximum allowed vertical difference with an existing road when merging, in HALF blocks. 1 = 0.5 block.")
        @Config.RangeInt(min = 0, max = 8)
        public int maxRoadJoinDeltaHalfBlocks = 1;

        @Config.Comment("Minimum terrain clearance required for a bridge cell to count as genuinely elevated.")

        @Config.RangeInt(min = 1, max = 32)
        public int meaningfulGroundClearanceBlocks = 3;

        @Config.Comment("Minimum water clearance required for a bridge cell to count as genuinely elevated above water.")
        @Config.RangeInt(min = 1, max = 32)
        public int meaningfulWaterClearanceBlocks = 1;

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

        @Config.Comment("Minimum full air blocks required between an existing road below and the bridge deck underside.")
        @Config.RangeInt(min = 0, max = 8)
        public int underBridgeRoadMinClearanceBlocks = 2;

        @Config.Comment("Minimum number of land blocks on each bridge side that must be connectable with strict 0.5-block road steps.")
        @Config.RangeInt(min = 1, max = 32)
        public int bridgeApproachRampBlocks = 3;

        @Config.Comment({
                "Radius (blocks) around the current A* position to search for existing bridge crossings.",
                "If a previously built bridge starts within this radius, A* will prefer to reuse it",
                "instead of building a parallel one. Increase if double-bridges still appear."
        })
        @Config.RangeInt(min = 0, max = 128)
        public int bridgeReuseSearchRadius = 36;

        @Config.Comment("Cost bonus applied when reusing an existing bridge (higher = stronger preference for reuse).")
        @Config.RangeDouble(min = 0.0, max = 400.0)
        public double bridgeReuseBonus = 120.0;

        @Config.Comment({
                "Cost discount applied to normal A* moves that step closer to a known existing bridge",
                "crossing's entrance, within bridgeReuseSearchRadius blocks. Unlike bridgeReuseBonus (which",
                "discounts the reuse transition itself), this pulls the search toward the exact entrance",
                "from a distance so A* actually walks there cell-by-cell instead of drifting past it.",
                "0 disables this attraction."
        })
        @Config.RangeDouble(min = 0.0, max = 80.0)
        public double bridgeEndpointMagnetismBonus = 12.0;
    }

    public static final Debug DEBUG = new Debug();

    public static final class Debug {
        @Config.Comment("Log which bridge template is selected for each bridge placement.")
        public boolean logBridgeTemplateSelection = false;

        @Config.Comment("Log DynamicTrees whole-tree removal events (position + trunk radius).")
        public boolean logDTTreeRemoval = false;

        @Config.Comment("Log road painting decisions (edge variation profile, plan stats).")
        public boolean debugRoadPainting = false;
    }

    public static final RoadHierarchy ROAD_HIERARCHY = new RoadHierarchy();

    public static final class RoadHierarchy {

        @Config.Comment({
                "Enable road type differentiation.",
                "When true, routes to lone buildings use LONE_TRAIL settings (narrow, terrain-following, bridges costly).",
                "Routes between villages use VILLAGE_MAIN settings (wider, earthworks allowed, normal bridge costs)."
        })
        public boolean enabled = true;

        // --- Magnetism ---

        @Config.Comment({
                "Enable the road magnetism system.",
                "Within magnetismRadius blocks of an existing road, moving TOWARD it is cheaper",
                "and moving AWAY from it is penalised. This funnels new paths onto existing ones",
                "instead of building parallel tracks.",
                "Complements preferExistingRoads: magnetism attracts from a distance,",
                "existingRoadBonus rewards being on the road."
        })
        public boolean magnetismEnabled = false;

        @Config.Comment({
                "Radius (blocks) within which road magnetism is active.",
                "Larger values create a wider attraction funnel around existing roads."
        })
        @Config.RangeInt(min = 2, max = 64)
        public int magnetismRadius = 12;

        @Config.Comment({
                "Cost discount per step when moving toward an existing road within magnetismRadius.",
                "Scales from full bonus when at 0 blocks to 0 at magnetismRadius."
        })
        @Config.RangeDouble(min = 0.0, max = 80.0)
        public double magnetismAttractionBonus = 10.0;

        @Config.Comment({
                "Cost penalty per step when moving away from an existing road while inside the magnetism zone.",
                "Prevents the pathfinder from escaping the road corridor once inside it."
        })
        @Config.RangeDouble(min = 0.0, max = 80.0)
        public double magnetismRepulsionPenalty = 6.0;

        // --- LONE_TRAIL tuning ---

        @Config.Comment({
                "Bridge cost multiplier for LONE_TRAIL routes (connections to lone buildings).",
                "Default 5.0 makes bridges ~5x more expensive, strongly preferring long land detours.",
                "Also multiplies existing-bridge reuse costs for consistency."
        })
        @Config.RangeDouble(min = 1.0, max = 20.0)
        public double loneTrailBridgeCostMultiplier = 5.0;

        @Config.Comment({
                "Slope penalty multiplier for LONE_TRAIL routes.",
                "Default 2.5 forces the trail to wind around hills rather than cut through them."
        })
        @Config.RangeDouble(min = 1.0, max = 10.0)
        public double loneTrailSlopePenaltyMultiplier = 2.5;

        @Config.Comment({
                "Allow earthworks (cut/fill) for LONE_TRAIL routes.",
                "VILLAGE_MAIN always uses the global allowEarthworks setting."
        })
        public boolean loneTrailEarthworksEnabled = true;

        @Config.Comment({
                "Search radius (blocks) used to detect a nearby existing road when deciding to merge a LONE_TRAIL.",
                "Larger values make lone trails stop earlier when they come close to an existing road.",
                "Default 5: a lone trail within 5 blocks of a road will terminate and connect to it."
        })
        @Config.RangeInt(min = 2, max = 12)
        public int loneTrailMergeSearchRadius = 5;

        @Config.Comment("Log road type selection decisions.")
        public boolean debugLogRoadType = false;

    }

    public static final SpeedBoost SPEED_BOOST = new SpeedBoost();

    public static final class SpeedBoost {
        @Config.Comment("Enable road speed bonus for players walking on paths and bridges.")
        public boolean enabled = true;

        @Config.Comment({
                "Speed bonus applied to player movement speed (fraction of base speed).",
                "0.3 = 30% faster. Applied as a multiplicative modifier on top of the base speed."
        })
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double speedBonus = 0.35;

        @Config.Comment("Horizontal search radius (blocks) around the player used to detect nearby road and bridge columns.")
        @Config.RangeInt(min = 1, max = 8)
        public int detectionRadius = 2;

        @Config.Comment("Server ticks the speed bonus is maintained after the player leaves a road (prevents abrupt stops).")
        @Config.RangeInt(min = 0, max = 200)
        public int lingerTicks = 30;

        @Config.Comment("Server ticks over which the bonus ramps up when entering a road.")
        @Config.RangeInt(min = 1, max = 100)
        public int rampUpTicks = 20;

        @Config.Comment("Server ticks over which the bonus ramps down after the linger period expires.")
        @Config.RangeInt(min = 1, max = 100)
        public int rampDownTicks = 40;

        @Config.Comment("Log speed boost decisions for debugging.")
        public boolean debugLog = false;
    }
}