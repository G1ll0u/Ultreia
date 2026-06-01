package com.jubitus.jubituschunks.config;

import com.jubitus.jubituschunks.Tags;
import net.minecraftforge.common.config.Config;

@Config(modid = Tags.MOD_ID, name = "jubituschunks")
public class JubitusChunksConfig {

    @Config.Name("general")
    public static final General GENERAL = new General();

    public static class General {

        @Config.Comment({
                "If true, the mod will automatically resume pregeneration after server restart",
                "if a saved pregen state file exists (jubituschunks/pregen_dimX.dat).",
                "This is useful when stopServerOnLowMemory triggers or after crashes."
        })
        public boolean autoResumePregenOnStartup = false;


        @Config.Comment({
                "Maximum spiral STEPS to process per server tick.",
                "A 'step' is one spiral position (NOT always 1 chunk).",
                "If bulkPopulateEnabled=true, one step can populate a whole square of chunks (see bulkRadiusChunks).",
                "Higher = faster, but more lag + more loaded chunks + more RAM usage.",
                "NOTE: If adaptiveStepCap=true, the real steps/tick may be clamped lower automatically."
        })
        @Config.RangeInt(min = 1, max = 64)
        public int stepsPerTick = 16;

        @Config.Comment({
                "Neighbor preload radius (in CHUNKS) around the work chunk before populating.",
                "0 = only the target chunk.",
                "1 = 3x3 loaded, 2 = 5x5 loaded, 3 = 7x7 loaded, etc.",
                "Higher improves compatibility with some mods (they expect neighbor chunks to exist),",
                "but memory usage increases very quickly."
        })
        @Config.RangeInt(min = 0, max = 8)
        public int preloadRadiusChunks = 1;


        @Config.Comment({
                "If loaded chunk count is above this, the pregenerator will pause for this tick",
                "to allow the server to unload chunks (prevents huge memory usage)."
        })
        @Config.RangeInt(min = 64, max = 50000)
        public int maxLoadedChunksSoftLimit = 3800;

        @Config.Comment({
                "If true, queueUnload() is called on the center chunk after it is generated/populated.",
                "This marks it as unloadable so it won't stay in memory forever."
        })
        public boolean queueUnloadCenterChunk = true;

        @Config.Comment({
                "If true, queueUnload() is also called on the preloaded neighbor chunks (after populating the center).",
                "This is the best option for huge radiuses (10k+ blocks) to avoid accumulating loaded chunks."
        })
        public boolean queueUnloadPreloadedNeighbors = true;

        @Config.Comment({
                "How often (in seconds) to send progress messages to the command sender."
        })
        @Config.RangeInt(min = 1, max = 3600)
        public int progressMessageIntervalSeconds = 10;
        @Config.Comment({
                "If true, each spiral step will populate and mark a whole square area as DONE,",
                "instead of only the center chunk.",
                "Example: bulkRadiusChunks=1 -> 3x3 chunks per spiral step.",
                "This makes generation much faster, but is less 'player-like'."
        })
        public boolean bulkPopulateEnabled = true;

        @Config.Comment({
                "Radius (in chunks) of the square to populate per spiral step when bulkPopulateEnabled=true.",
                "1 = 3x3, 2 = 5x5, etc.",
                "Larger values increase memory use sharply because more chunks must be loaded per step."
        })
        @Config.RangeInt(min = 0, max = 8)
        public int bulkRadiusChunks = 2;
        @Config.Comment({
                "If true, dynamically reduce steps/tick so we don't load chunks faster than the server can unload them.",
                "Highly recommended for large preloadRadiusChunks or high stepsPerTick.",
                "In bulk mode, the adaptive cap accounts for the larger effective preload area."
        })
        public boolean adaptiveStepCap = true;

        @Config.Comment({
                "Approx chunks unloaded per tick by ChunkProviderServer.tick(). Vanilla/Forge is ~100.",
                "Used by adaptiveStepCap to estimate safe steps/tick via: budget / (stride * preloadSide).",
                "Raise if you have a mod that increases the unload budget."
        })
        @Config.RangeInt(min = 10, max = 500)
        public int unloadBudgetPerTick = 100;

        @Config.Comment({
                "Extra ChunkProviderServer.tick() calls when loaded > loadedChunksHardLimit.",
                "Each call synchronously saves up to ~100 chunks on the server thread.",
                "0 = rely solely on Minecraft's natural tick (safest, lowest overhead).",
                "1 = recommended balance: one extra save pass per tick when over the hard limit.",
                "Higher = faster drain but proportionally more server thread time spent on chunk IO."
        })
        @Config.RangeInt(min = 0, max = 20)
        public int extraUnloadPassesWhenOverLimit = 1;

        @Config.Comment({
                "Emergency threshold: if loaded chunk count still exceeds this AFTER the aggressive queue pass,",
                "force extra cps.tick() save passes (see extraUnloadPassesWhenOverLimit).",
                "With aggressiveUnloadPressure=true, the queue pass runs every tick regardless of this value.",
                "This threshold only matters if loaded is unexpectedly high despite continuous queuing."
        })
        @Config.RangeInt(min = 128, max = 50000)
        public int loadedChunksHardLimit = 2000;

        @Config.Comment({
                "If true, queueUnload() all loaded chunks outside keepLoadedRadiusChunks every tick.",
                "This catches side-effect loads from mods (e.g. Millénaire calling provideChunk internally)",
                "that would otherwise accumulate silently. Highly recommended."
        })
        public boolean aggressiveUnloadPressure = true;

        @Config.Comment({
                "Chunks within this radius (Chebyshev distance) around the current working chunk are NOT queued for unload",
                "during aggressive unload pressure.",
                "Recommended: keepLoadedRadiusChunks >= (preloadRadiusChunks + bulkRadiusChunks) in bulk mode."
        })
        @Config.RangeInt(min = 0, max = 32)
        public int keepLoadedRadiusChunks = 2;
        @Config.Comment({
                "If true, pregeneration will SKIP chunks that already exist on disk (already generated).",
                "This makes expanding an existing world much faster because it won't load/populate old chunks.",
                "WARNING: If you changed worldgen mods/settings, skipping means old chunks won't get 'updated'."
        })
        public boolean skipAlreadyGeneratedChunks = false;
        @Config.Comment({
                "When skipAlreadyGeneratedChunks=true:",
                "false = SKIP mode: skip chunks purely via isChunkGeneratedAt() (fastest, no loading).",
                "true  = VERIFY mode: load the chunk and only populate if it is NOT populated yet (slower, safer after crashes)."
        })

        public boolean verifyExistingChunks = true;
        @Config.Comment({
                "Population method:",
                "false = use Chunk.populate(provider, generator) (vanilla-style).",
                "true  = use IChunkGenerator.populate(x,z) and then manually mark chunk populated.",
                "Some mods behave better with one or the other."
        })
        public boolean populateViaGenerator = false;
        @Config.Comment({
                "If true, the pregenerator will stop the server cleanly when memory is critically high",
                "and a forced GC does not free enough heap (see minRecoveredAfterGcMB).",
                "This prevents OOM and allows you to /jubituschunks resume after restart."
        })
        public boolean stopServerOnLowMemory = true;

        @Config.Comment({
                "How often (in seconds) to run the memory watchdog check."
        })
        @Config.RangeInt(min = 1, max = 3600)
        public int memoryCheckIntervalSeconds = 10;

        @Config.Comment({
                "Critical heap usage percent (of -Xmx). If used heap >= this percent, the watchdog triggers."
        })
        @Config.RangeInt(min = 50, max = 99)
        public int criticalHeapUsedPercent = 95;

        @Config.Comment({
                "When memory is critical, the watchdog triggers a GC and checks how much was freed.",
                "If less than this many MB were recovered, it counts as a failure."
        })
        @Config.RangeInt(min = 0, max = 8192)
        public int minRecoveredAfterGcMB = 300;

        @Config.Comment({
                "How many consecutive GC failures before the server is stopped."
        })
        @Config.RangeInt(min = 1, max = 20)
        public int consecutiveGcFailuresToStop = 3;
        @Config.Comment({
                "How many ticks to KEEP a populated chunk loaded before allowing unload.",
                "20 ticks = 1 second. Increase if populated chunks get corrupted (partial structures).",
                "For most modpacks, 0-20 is fine since Minecraft saves chunks asynchronously."
        })
        @Config.RangeInt(min = 0, max = 1200)
        public int keepPopulatedChunksLoadedTicks = 0;

        @Config.Comment({
                "How many ticks to KEEP a preloaded neighbor chunk loaded after the step is processed.",
                "0 means neighbor chunks can be unloaded immediately. Usually fine since the next step",
                "reloads overlapping neighbors anyway."
        })
        @Config.RangeInt(min = 0, max = 1200)
        public int keepNeighborChunksLoadedTicks = 0;

    }



}
