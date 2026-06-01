package com.jubitus.jubituschunks.mixin;

import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

/**
 * Patches WorldServer.getPendingBlockUpdates(Chunk, boolean) to run in O(1) instead of O(N).
 *
 * Vanilla iterates the global pendingTickListEntriesTreeSet (all scheduled block ticks in the
 * world) on every chunk save. With Millénaire's pregen this set can grow to tens of thousands
 * of entries and dominates server-thread time (~32 % of ticks).
 *
 * Fix: maintain a per-chunk spatial index (HashMap<Long, List<NextTickListEntry>>) in parallel
 * with the TreeSet. Each entry goes into the bucket for its chunk at the time it is scheduled.
 * On getPendingBlockUpdates we look up only the 4 chunks whose blocks can overlap the ±2-block
 * extended bounding-box and validate each entry against pendingTickListEntriesHashSet (stale-
 * entry defence against removals we did not intercept, e.g. via iterator.remove() in tickUpdates).
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    // The duplicate-prevention HashSet — used as the canonical "is this entry still live?" check.
    @Shadow @Final
    private Set<NextTickListEntry> pendingTickListEntriesHashSet;

    // The sorted TreeSet that vanilla iterates on every save — this is what we skip.
    @Shadow @Final
    private TreeSet<NextTickListEntry> pendingTickListEntriesTreeSet;

    // In-flight ticks being processed this tick; usually empty during chunk saves.
    @Shadow @Final
    private List<NextTickListEntry> pendingTickListEntriesThisTick;

    // Our spatial index: packed ChunkPos → entries whose block falls in that chunk.
    @Unique
    private HashMap<Long, List<NextTickListEntry>> jubituschunks_chunkIndex;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void jubituschunks_initIndex(CallbackInfo ci) {
        jubituschunks_chunkIndex = new HashMap<>();
    }

    @Unique
    private static long jubituschunks_packKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    @Unique
    private void jubituschunks_addToIndex(NextTickListEntry entry) {
        BlockPos pos = entry.position;
        long key = jubituschunks_packKey(pos.getX() >> 4, pos.getZ() >> 4);
        jubituschunks_chunkIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
    }

    // Intercept both scheduling paths so every entry added to the TreeSet also lands in our index.

    @Redirect(
        method = "scheduleBlockUpdate",
        at = @At(value = "INVOKE", target = "Ljava/util/TreeSet;add(Ljava/lang/Object;)Z")
    )
    private boolean jubituschunks_scheduleAdd(TreeSet<NextTickListEntry> set, Object entry) {
        boolean added = set.add((NextTickListEntry) entry);
        if (added && jubituschunks_chunkIndex != null) jubituschunks_addToIndex((NextTickListEntry) entry);
        return added;
    }

    @Redirect(
        method = "updateBlockTick",
        at = @At(value = "INVOKE", target = "Ljava/util/TreeSet;add(Ljava/lang/Object;)Z")
    )
    private boolean jubituschunks_updateTickAdd(TreeSet<NextTickListEntry> set, Object entry) {
        boolean added = set.add((NextTickListEntry) entry);
        if (added && jubituschunks_chunkIndex != null) jubituschunks_addToIndex((NextTickListEntry) entry);
        return added;
    }

    /**
     * Replace the O(N) TreeSet scan with an O(1) index lookup.
     *
     * The vanilla bbox for chunk (cx, cz) is [cx*16-2, cx*16+16) × [cz*16-2, cz*16+16).
     * That covers blocks from up to 2 positions into the adjacent chunk on the negative sides,
     * so we must check 4 chunk buckets: (cx-1,cz-1), (cx,cz-1), (cx-1,cz), (cx,cz).
     *
     * Entries removed by tickUpdates or a prior save are no longer in pendingTickListEntriesHashSet;
     * we detect and purge them lazily (stale-entry defence, no removal interception needed).
     *
     * We still iterate pendingTickListEntriesThisTick linearly because it is almost always empty
     * during chunk saves (it is populated and cleared within a single tickUpdates call).
     */
    @Inject(
        method = "getPendingBlockUpdates(Lnet/minecraft/world/chunk/Chunk;Z)Ljava/util/List;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void jubituschunks_fastGetPendingUpdates(Chunk chunk, boolean remove,
                                                      CallbackInfoReturnable<List<NextTickListEntry>> cir) {
        if (jubituschunks_chunkIndex == null) return;

        int cx = chunk.x;
        int cz = chunk.z;
        // Extended bbox: [cx*16-2, cx*16+16) in X, same in Z (exclusive upper bound).
        int bxMin = (cx << 4) - 2;
        int bxMax = (cx << 4) + 16;
        int bzMin = (cz << 4) - 2;
        int bzMax = (cz << 4) + 16;

        List<NextTickListEntry> result = null;

        for (int dcx = -1; dcx <= 0; dcx++) {
            for (int dcz = -1; dcz <= 0; dcz++) {
                long key = jubituschunks_packKey(cx + dcx, cz + dcz);
                List<NextTickListEntry> bucket = jubituschunks_chunkIndex.get(key);
                if (bucket == null || bucket.isEmpty()) continue;

                Iterator<NextTickListEntry> it = bucket.iterator();
                while (it.hasNext()) {
                    NextTickListEntry entry = it.next();

                    // Stale entry: already consumed by tickUpdates or a prior chunk save.
                    if (!pendingTickListEntriesHashSet.contains(entry)) {
                        it.remove();
                        continue;
                    }

                    int bx = entry.position.getX();
                    int bz = entry.position.getZ();
                    if (bx < bxMin || bx >= bxMax || bz < bzMin || bz >= bzMax) continue;

                    if (result == null) result = new ArrayList<>();
                    result.add(entry);

                    if (remove) {
                        it.remove();
                        pendingTickListEntriesTreeSet.remove(entry);
                        pendingTickListEntriesHashSet.remove(entry);
                    }
                }
            }
        }

        // pendingTickListEntriesThisTick: entries being processed this very tick.
        // In practice empty during chunk saves, but we handle it for correctness.
        if (!pendingTickListEntriesThisTick.isEmpty()) {
            Iterator<NextTickListEntry> it = pendingTickListEntriesThisTick.iterator();
            while (it.hasNext()) {
                NextTickListEntry entry = it.next();
                int bx = entry.position.getX();
                int bz = entry.position.getZ();
                if (bx >= bxMin && bx < bxMax && bz >= bzMin && bz < bzMax) {
                    if (result == null) result = new ArrayList<>();
                    result.add(entry);
                    if (remove) it.remove();
                }
            }
        }

        cir.setReturnValue(result);
    }
}
