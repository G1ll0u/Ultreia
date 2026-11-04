package com.jubitus.ultreia.route;

import com.jubitus.ultreia.bridge.BridgeSpan;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PathResult {

    private final List<BlockPos> path;
    private final List<BridgeSpan> bridges;
    private final LongSet reusedBridgeXZ;

    public PathResult(List<BlockPos> path, List<BridgeSpan> bridges) {
        this(path, bridges, null);
    }

    /** {@code reusedBridgeXZ}: xz keys of the deck centerline of built bridges the path crosses (nothing to paint there). */
    public PathResult(List<BlockPos> path, List<BridgeSpan> bridges, LongSet reusedBridgeXZ) {
        this.path = path == null ? Collections.<BlockPos>emptyList() : Collections.unmodifiableList(new ArrayList<>(path));
        this.bridges = bridges == null ? Collections.<BridgeSpan>emptyList() : Collections.unmodifiableList(new ArrayList<>(bridges));
        this.reusedBridgeXZ = reusedBridgeXZ == null || reusedBridgeXZ.isEmpty()
                ? LongSets.EMPTY_SET : LongSets.unmodifiable(new LongOpenHashSet(reusedBridgeXZ));
    }

    public LongSet getReusedBridgeXZ() {
        return reusedBridgeXZ;
    }

    public List<BlockPos> getPath() {
        return path;
    }

    public List<BridgeSpan> getBridges() {
        return bridges;
    }

    public boolean isEmpty() {
        return path.isEmpty();
    }
}