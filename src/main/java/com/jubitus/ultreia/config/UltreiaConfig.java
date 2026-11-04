package com.jubitus.ultreia.config;

import net.minecraftforge.common.config.Config;

// INVARIANT: field and category names are the saved config keys; renaming one resets players' values.
public class UltreiaConfig {

    public static final General GENERAL = new General();
    public static final Network NETWORK = new Network();
    public static final Pathfinding PATHFINDING = new Pathfinding();
    public static final Painting PAINTING = new Painting();
    public static final Bridges BRIDGES = new Bridges();
    public static final ValleyBridging VALLEY_BRIDGING = new ValleyBridging();
    public static final Buildings BUILDINGS = new Buildings();
    public static final RoadHierarchy ROAD_HIERARCHY = new RoadHierarchy();
    public static final SpeedBoost SPEED_BOOST = new SpeedBoost();
    public static final Debug DEBUG = new Debug();

    public static class General {
        @Config.Comment("Time spent building roads on each server tick; higher builds faster but can make the server lag.")
        @Config.RangeInt(min = 1, max = 1000)
        public int tickBudgetMs = 20;

        @Config.Comment("Most chunks that may stay loaded while one road is being built.")
        public int maxForcedChunksPerJob = 4000;

        @Config.Comment("Resume an interrupted road network when the world loads.")
        public boolean autoResumeGeneration = true;

        @Config.Comment("Let road building load and generate the chunks it needs.")
        public boolean allowChunkLoading = true;

        @Config.Comment("Also connect Millénaire lone buildings to the network with small trails.")
        public boolean includeLoneBuildings = true;

        @Config.Comment("Keep roads out of oceans.")
        public boolean forbidOcean = true;

        @Config.Comment("Biomes roads should avoid, e.g. minecraft:desert or biomesoplenty:coral_reef.")
        public String[] biomeBlacklist = new String[]{"minecraft:ocean", "minecraft:deep_ocean"};

        @Config.Comment("Never cross the biomes above (otherwise roads only try hard to avoid them).")
        public boolean hardBlockBlacklistedBiomes = false;
    }

    public static class Network {
        @Config.Comment("Longest distance between two villages that may get a direct road.")
        public int maxEdgeDistance = 8000;

        @Config.Comment("How much of a detour justifies an extra road between two villages; lower gives more roads and loops, 0 keeps them all.")
        @Config.RangeDouble(min = 0.0, max = 5.0)
        public double redundancyRatio = 1.3;

        @Config.Comment("Villagers needed for a village to count as a hub, which gets better connections.")
        @Config.RangeInt(min = 1, max = 500)
        public int hubMinVillagers = 30;

        @Config.Comment("Villagers needed for a village to count as medium rather than small.")
        @Config.RangeInt(min = 1, max = 500)
        public int mediumMinVillagers = 16;
    }

    public static class Pathfinding {
        @Config.Comment("How much roads avoid turning; higher gives straighter roads, lower lets them wind more.")
        @Config.RangeDouble(min = 0.0, max = 50.0)
        public double turnPenalty = 5.0;

        @Config.Comment("How much roads avoid climbing; higher makes them go around hills.")
        public double slopePenalty = 8.0;

        @Config.Comment("Higher finds roads much faster but slightly less well; 1 finds the best road but slowly.")
        @Config.RangeDouble(min = 1.0, max = 10.0)
        public double searchSpeedFactor = 5.0;

        @Config.Comment("When a village cannot be reached, build the road as far as possible instead of giving up.")
        public boolean allowPartialPathFallback = false;

        @Config.Comment("Cost of walking along an existing road compared with building a new one; lower makes roads share the network more, even with detours.")
        @Config.RangeDouble(min = 0.1, max = 1.0)
        public double existingRoadCost = 0.5;

    }

    public static class Painting {
        @Config.Comment("Width of village roads in blocks (even values become the next odd one); trails are narrower.")
        public int roadWidth = 3;

        @Config.Comment("Cut down trees that stand in the way of a road.")
        public boolean clearTrees = true;

        @Config.Comment("Deepest the ground may be dug to keep a road smooth.")
        @Config.RangeInt(min = 0, max = 16)
        public int earthworksMaxCut = 5;

        @Config.Comment("Highest the ground may be raised to keep a road smooth.")
        @Config.RangeInt(min = 0, max = 16)
        public int earthworksMaxFill = 3;

        @Config.Comment("Spread of the banks beside dug or raised roads; higher gives gentler banks.")
        @Config.RangeDouble(min = 0.3, max = 4.0)
        public double earthworksSlopeRatio = 1.0;

        @Config.Comment("Same for trails to lone buildings; -1 uses the road value.")
        @Config.RangeDouble(min = -1.0, max = 4.0)
        public double earthworksSlopeRatioLoneTrail = 0.7;
    }

    public static final class Bridges {
        @Config.Comment("Build bridges at all.")
        public boolean enabled = true;

        @Config.Comment("Longest bridge allowed, in blocks.")
        @Config.RangeInt(min = 8, max = 1024)
        public int maxBridgeLenBlocks = 128;

        @Config.Comment("Bridge template used when no other one fits.")
        public String defaultTemplateName = "default";
    }

    public static final class ValleyBridging {
        @Config.Comment("Also bridge dry valleys and gorges, not only water.")
        public boolean enabled = true;
    }

    public static class Buildings {
        @Config.Comment("Never route roads through building plots (otherwise they only try hard to avoid them).")
        public boolean hardBlockInPathfinding = false;
    }

    public static final class RoadHierarchy {
        @Config.Comment("How much more trails to lone buildings avoid climbing than village roads.")
        @Config.RangeDouble(min = 1.0, max = 10.0)
        public double loneTrailSlopePenaltyMultiplier = 2.5;
    }

    public static final class SpeedBoost {
        @Config.Comment("Players walk faster on roads and bridges.")
        public boolean enabled = true;

        @Config.Comment("Extra speed on roads: 0.35 means 35% faster.")
        @Config.RangeDouble(min = 0.0, max = 2.0)
        public double speedBonus = 0.35;
    }

    public static final class Debug {
        @Config.Comment("Log how the village network is planned.")
        public boolean logNetwork = false;

        @Config.Comment("Log route search progress and why routes fail.")
        public boolean logPathfinding = false;

        @Config.Comment("Log bridge decisions and template choices.")
        public boolean logBridges = false;

        @Config.Comment("Log road painting and tree removal.")
        public boolean logPainting = false;

        @Config.Comment("Log chunk loading and unloading.")
        public boolean logChunks = false;

        @Config.Comment("Log speed boost changes.")
        public boolean logSpeedBoost = false;
    }
}
