package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.gen.bridge.BridgeSpan;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MPBPlannedPath {

    public enum SegmentType { WALK, BRIDGE }

    public static final class Segment {
        public final SegmentType type;
        public final BlockPos a;
        public final BlockPos b;
        @Nullable public final BridgeSpan bridge;

        public Segment(SegmentType type, BlockPos a, BlockPos b, @Nullable BridgeSpan bridge) {
            this.type = type;
            this.a = a;
            this.b = b;
            this.bridge = bridge;
        }
    }

    private final List<Segment> segments;

    public MPBPlannedPath(List<Segment> segments) {
        this.segments = segments == null ? Collections.<Segment>emptyList() : Collections.unmodifiableList(new ArrayList<>(segments));
    }

    public List<Segment> segments() {
        return segments;
    }

    public boolean hasBridges() {
        for (Segment s : segments) if (s.type == SegmentType.BRIDGE) return true;
        return false;
    }

    public static MPBPlannedPath fromCoarsePoints(List<BlockPos> coarse) {
        if (coarse == null || coarse.size() < 2) return new MPBPlannedPath(Collections.<Segment>emptyList());
        List<Segment> segs = new ArrayList<>(coarse.size());
        for (int i = 1; i < coarse.size(); i++) {
            BlockPos a = coarse.get(i - 1);
            BlockPos b = coarse.get(i);
            segs.add(new Segment(SegmentType.WALK, a, b, null));
        }
        return new MPBPlannedPath(segs);
    }
}