package com.jubitus.millpathbuilder.gen.bridge;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BridgeAwarePathResult {

    private final List<BlockPos> path;
    private final List<BridgeSpan> bridges;

    public BridgeAwarePathResult(List<BlockPos> path, List<BridgeSpan> bridges) {
        this.path = path == null ? Collections.<BlockPos>emptyList() : Collections.unmodifiableList(new ArrayList<>(path));
        this.bridges = bridges == null ? Collections.<BridgeSpan>emptyList() : Collections.unmodifiableList(new ArrayList<>(bridges));
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