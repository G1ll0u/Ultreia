package com.jubitus.ultreia.gen.world;

import com.jubitus.ultreia.gen.MPBWorldData;
import com.jubitus.ultreia.gen.RoadType;
import com.jubitus.ultreia.gen.bridge.BridgeGeometry;
import com.jubitus.ultreia.gen.bridge.BridgeSpan;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Persistent road network graph for Ultreïa.
 *
 * Stores all generated road and bridge segments as ordered waypoint lists.
 * Used for:
 * - Bridge reuse detection during A* pathfinding
 * - Map rendering (segment polylines, bridge markers)
 * - Future entity navigation API
 */
public final class MPBRoadNetwork extends WorldSavedData {

    private static final String NAME = "ultreia_roadnetwork";

    /** Waypoint subsampling: one waypoint every N blocks along the centerline. */
    private static final int SAMPLE_STEP = 4;

    /** Exposed for entity navigation and map rendering. */
    public static final class BridgeCrossing {
        public final int startX, startZ;
        public final int endX, endZ;
        public final int segmentId;
        public final int lengthBlocks;
        /** Deck surface block Y at the entrance. */
        public final int startY;

        BridgeCrossing(int startX, int startZ, int startY, int endX, int endZ, int segmentId, int lengthBlocks) {
            this.startX = startX; this.startZ = startZ;
            this.startY = startY;
            this.endX = endX; this.endZ = endZ;
            this.segmentId = segmentId;
            this.lengthBlocks = lengthBlocks;
        }
    }

    /** Global so a reloaded network never repeats a revision seen before. */
    private static final AtomicLong REVISION_SEQ = new AtomicLong();

    private final List<RoadSegment> segments = new ArrayList<>();
    private int nextId = 0;
    private long revision = REVISION_SEQ.incrementAndGet();
    /** True when segments loaded without a saved type/width await inference from the logical network. */
    private boolean legacyPending = false;

    // Spatial index: chunk key → set of segment IDs whose waypoints cross that chunk
    private final Long2ObjectOpenHashMap<IntArrayList> chunkIndex = new Long2ObjectOpenHashMap<>();

    // Bridge deck XZ key → segment ID for fast bridge lookup
    private final Long2IntOpenHashMap bridgeDeckIndex = new Long2IntOpenHashMap();

    public MPBRoadNetwork() {
        super(NAME);
        bridgeDeckIndex.defaultReturnValue(-1);
    }

    public MPBRoadNetwork(String name) {
        super(name);
        bridgeDeckIndex.defaultReturnValue(-1);
    }

    // --- Static factory ---

    public static MPBRoadNetwork get(WorldServer world) {
        MapStorage storage = world.getPerWorldStorage();
        MPBRoadNetwork data = (MPBRoadNetwork) storage.getOrLoadData(MPBRoadNetwork.class, NAME);
        if (data == null) {
            data = new MPBRoadNetwork();
            storage.setData(NAME, data);
        }
        if (data.legacyPending) data.inferLegacySegments(MPBWorldData.get(world));
        return data;
    }

    /** Changes whenever the set of segments changes. Not persisted. */
    public long getRevision() {
        return revision;
    }

    @Override
    public void markDirty() {
        revision = REVISION_SEQ.incrementAndGet();
        super.markDirty();
    }

    // --- Segment registration ---

    /**
     * Register a road segment (non-bridge) from a coarse path.
     * Subsamples waypoints every SAMPLE_STEP blocks.
     * Also marks road blocks in roadIndex with their Y coordinate.
     */
    public int addRoadSegment(List<BlockPos> path,
                              MPBRoadIndexData roadIndex,
                              @Nullable BlockPos fromVillage,
                              @Nullable BlockPos toVillage,
                              RoadType roadType,
                              int widthBlocks) {
        if (path == null || path.isEmpty()) return -1;

        List<RoadWaypoint> waypoints = new ArrayList<>();
        for (int i = 0; i < path.size(); i += SAMPLE_STEP) {
            BlockPos p = path.get(i);
            waypoints.add(new RoadWaypoint(p.getX(), p.getY(), p.getZ(), false));
            if (roadIndex != null) roadIndex.markRoad(p.getX(), p.getY(), p.getZ());
        }
        // Always include the last point
        BlockPos last = path.get(path.size() - 1);
        if (waypoints.isEmpty() || waypoints.get(waypoints.size() - 1).x != last.getX()
                || waypoints.get(waypoints.size() - 1).z != last.getZ()) {
            waypoints.add(new RoadWaypoint(last.getX(), last.getY(), last.getZ(), false));
            if (roadIndex != null) roadIndex.markRoad(last.getX(), last.getY(), last.getZ());
        }

        return registerSegment(waypoints, false, fromVillage, toVillage, roadType, widthBlocks);
    }

    /**
     * Register a bridge segment from a BridgeSpan.
     * Marks all centerline XZ columns as bridge deck in roadIndex.
     */
    public int addBridgeSegment(BridgeSpan span,
                                int[] deckHalfY,
                                MPBRoadIndexData roadIndex,
                                @Nullable BlockPos fromVillage,
                                @Nullable BlockPos toVillage,
                                RoadType roadType) {
        if (span == null || span.centerline == null || span.centerline.isEmpty()) return -1;

        List<RoadWaypoint> waypoints = new ArrayList<>();
        for (int i = 0; i < span.centerline.size(); i++) {
            BlockPos c = span.centerline.get(i);
            int deckY = (deckHalfY != null && i < deckHalfY.length)
                    ? BridgeGeometry.surfaceYFromHalf(deckHalfY[i])
                    : c.getY();

            // Bridges are stored at full resolution (every centerline block), unlike regular road
            // segments: reused-bridge stitching (AStarPathfinder) needs the exact per-block deck
            // profile so it can splice this segment back into a reconstructed path without drifting
            // off the deck centerline via straight-line rasterization between sparse waypoints.
            waypoints.add(new RoadWaypoint(c.getX(), deckY, c.getZ(), true));

            if (roadIndex != null) {
                roadIndex.markBridgeDeck(c.getX(), deckY, c.getZ());
                // Mark lateral ±1 as bridge deck too (standard bridge width)
                // Direction perpendicular to travel
                if (i > 0) {
                    BlockPos prev = span.centerline.get(i - 1);
                    int tdx = c.getX() - prev.getX(), tdz = c.getZ() - prev.getZ();
                    int px = -tdz, pz = tdx; // perpendicular
                    if (px != 0 || pz != 0) {
                        double len = Math.sqrt(px * px + pz * pz);
                        int uPx = (int) Math.round(px / len), uPz = (int) Math.round(pz / len);
                        roadIndex.markBridgeDeck(c.getX() + uPx, deckY, c.getZ() + uPz);
                        roadIndex.markBridgeDeck(c.getX() - uPx, deckY, c.getZ() - uPz);
                    }
                }
            }
        }

        int segId = registerSegment(waypoints, true, fromVillage, toVillage, roadType, BridgeGeometry.DECK_WIDTH_BLOCKS);

        // Index all centerline XZ columns for fast bridge lookup
        for (BlockPos c : span.centerline) {
            bridgeDeckIndex.put(RoadWaypoint.packXZ(c.getX(), c.getZ()), segId);
        }
        markDirty();
        return segId;
    }

    // --- Bridge crossing queries (for A* reuse) ---

    /**
     * Returns all bridge crossings that start within {@code radius} blocks of (x, z).
     * Both start→end and end→start directions are returned.
     */
    public List<BridgeCrossing> getBridgeCrossingsNear(int x, int z, int radius) {
        int radiusSq = radius * radius;
        List<BridgeCrossing> result = new ArrayList<>();

        for (RoadSegment seg : getSegmentsNearFast(x, z, radius)) {
            if (!seg.isBridge || seg.waypoints.size() < 2) continue;

            int sx = seg.startX(), sz = seg.startZ();
            int ex = seg.endX(), ez = seg.endZ();
            int len = seg.lengthBlocks();

            int dsx = sx - x, dsz = sz - z;
            int dex = ex - x, dez = ez - z;

            if (dsx * dsx + dsz * dsz <= radiusSq) {
                result.add(new BridgeCrossing(sx, sz, seg.waypoints.get(0).y, ex, ez, seg.id, len));
            }
            if (dex * dex + dez * dez <= radiusSq) {
                result.add(new BridgeCrossing(ex, ez, seg.waypoints.get(seg.waypoints.size() - 1).y, sx, sz, seg.id, len));
            }
        }
        return result;
    }

    // --- Spatial queries (for map rendering, entity nav) ---

    public List<RoadSegment> getSegmentsNear(int x, int z, int radius) {
        return getSegmentsNearFast(x, z, radius);
    }

    @Nullable
    public RoadWaypoint findNearestWaypoint(int x, int z, int maxRadius) {
        RoadWaypoint best = null;
        long bestDistSq = (long) maxRadius * maxRadius + 1L;

        for (RoadSegment seg : getSegmentsNearFast(x, z, maxRadius)) {
            for (RoadWaypoint wp : seg.waypoints) {
                long dx = wp.x - x, dz = wp.z - z;
                long distSq = dx * dx + dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = wp;
                }
            }
        }
        return best;
    }

    // --- Entity navigation ---

    /**
     * Find a route through the road network from near (fromX,fromZ) to near (toX,toZ).
     * Returns an ordered list of waypoints to follow, or empty list if no route found.
     *
     * This is a lightweight A* over the segment graph.
     * Junction detection: segments whose endpoints are within JUNCTION_RADIUS blocks of each
     * other are considered connected.
     */
    public List<RoadWaypoint> findRoute(int fromX, int fromZ, int toX, int toZ, int maxSearchRadius) {
        return MPBRoadNavigator.findRoute(this, fromX, fromZ, toX, toZ, maxSearchRadius);
    }

    /** Read-only view of all segments — for navigation and external API. */
    public List<RoadSegment> allSegments() {
        return Collections.unmodifiableList(segments);
    }

    /** Fetch a segment by id, e.g. from a {@link BridgeCrossing#segmentId}. Null if not found. */
    @Nullable
    public RoadSegment getSegment(int id) {
        if (id < 0 || id >= segments.size()) return null;
        return segments.get(id);
    }

    // --- NBT serialization ---

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        segments.clear();
        chunkIndex.clear();
        bridgeDeckIndex.clear();
        nextId = 0;
        legacyPending = false;
        revision = REVISION_SEQ.incrementAndGet();

        if (!nbt.hasKey("segs")) return;

        NBTTagList list = nbt.getTagList("segs", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound sc = list.getCompoundTagAt(i);
            int id = sc.getInteger("id");
            boolean isBridge = sc.getBoolean("br");

            BlockPos fromV = null, toV = null;
            if (sc.hasKey("fvx")) fromV = new BlockPos(sc.getInteger("fvx"), 0, sc.getInteger("fvz"));
            if (sc.hasKey("tvx")) toV   = new BlockPos(sc.getInteger("tvx"), 0, sc.getInteger("tvz"));

            int[] wData = sc.getIntArray("wp");
            List<RoadWaypoint> waypoints = new ArrayList<>(wData.length / 4);
            for (int j = 0; j + 3 < wData.length; j += 4) {
                waypoints.add(new RoadWaypoint(wData[j], wData[j + 1], wData[j + 2], wData[j + 3] != 0));
            }
            if (waypoints.isEmpty()) continue;

            // Saves from before type/width were stored lack these keys; inferred on first get(world).
            boolean hasMeta = sc.hasKey("rt") && sc.hasKey("wd");
            if (!hasMeta) legacyPending = true;
            RoadType type = (hasMeta && sc.getByte("rt") == 1) ? RoadType.LONE_TRAIL : RoadType.VILLAGE_MAIN;
            int width = hasMeta ? sc.getInteger("wd") : 0;

            RoadSegment seg = new RoadSegment(id, waypoints, isBridge, fromV, toV, type, width);
            while (segments.size() <= id) segments.add(null);
            segments.set(id, seg);
            if (id >= nextId) nextId = id + 1;

            indexSegment(seg);

            if (isBridge) {
                for (RoadWaypoint wp : waypoints) {
                    bridgeDeckIndex.put(RoadWaypoint.packXZ(wp.x, wp.z), id);
                }
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (RoadSegment seg : segments) {
            if (seg == null || seg.waypoints.isEmpty()) continue;

            NBTTagCompound sc = new NBTTagCompound();
            sc.setInteger("id", seg.id);
            sc.setBoolean("br", seg.isBridge);
            sc.setByte("rt", (byte) (seg.roadType == RoadType.LONE_TRAIL ? 1 : 0));
            sc.setInteger("wd", seg.widthBlocks);
            if (seg.fromVillage != null) {
                sc.setInteger("fvx", seg.fromVillage.getX());
                sc.setInteger("fvz", seg.fromVillage.getZ());
            }
            if (seg.toVillage != null) {
                sc.setInteger("tvx", seg.toVillage.getX());
                sc.setInteger("tvz", seg.toVillage.getZ());
            }

            int[] wData = new int[seg.waypoints.size() * 4];
            int idx = 0;
            for (RoadWaypoint wp : seg.waypoints) {
                wData[idx++] = wp.x;
                wData[idx++] = wp.y;
                wData[idx++] = wp.z;
                wData[idx++] = wp.isBridge ? 1 : 0;
            }
            sc.setTag("wp", new NBTTagIntArray(wData));
            list.appendTag(sc);
        }
        nbt.setTag("segs", list);
        return nbt;
    }

    // --- Internal helpers ---

    /**
     * Fills type and width of segments saved before they were stored. The planned network keeps
     * the same village pair as each segment's from/to, so a segment matches its edge by endpoints
     * (either order). No match (manual paths, missing data) means a village road.
     */
    private void inferLegacySegments(MPBWorldData worldData) {
        legacyPending = false;
        List<LogicalEdge> edges = worldData.getLogicalNetwork();
        for (int i = 0; i < segments.size(); i++) {
            RoadSegment seg = segments.get(i);
            if (seg == null || seg.widthBlocks > 0) continue;

            RoadType type = RoadType.VILLAGE_MAIN;
            if (seg.fromVillage != null && seg.toVillage != null) {
                for (LogicalEdge e : edges) {
                    if (matchesEdge(seg, e)) {
                        if (e.isLoneTrail) type = RoadType.LONE_TRAIL;
                        break;
                    }
                }
            }
            int width = seg.isBridge ? BridgeGeometry.DECK_WIDTH_BLOCKS : type.roadWidthBlocks();
            segments.set(i, new RoadSegment(seg.id, seg.waypoints, seg.isBridge, seg.fromVillage, seg.toVillage, type, width));
        }
        markDirty();
    }

    private static boolean matchesEdge(RoadSegment seg, LogicalEdge e) {
        int fx = seg.fromVillage.getX(), fz = seg.fromVillage.getZ();
        int tx = seg.toVillage.getX(), tz = seg.toVillage.getZ();
        return (fx == e.x1 && fz == e.z1 && tx == e.x2 && tz == e.z2)
                || (fx == e.x2 && fz == e.z2 && tx == e.x1 && tz == e.z1);
    }

    private int registerSegment(List<RoadWaypoint> waypoints,
                                boolean isBridge,
                                @Nullable BlockPos fromVillage,
                                @Nullable BlockPos toVillage,
                                RoadType roadType,
                                int widthBlocks) {
        int id = nextId++;
        RoadSegment seg = new RoadSegment(id, Collections.unmodifiableList(waypoints), isBridge, fromVillage, toVillage,
                roadType != null ? roadType : RoadType.VILLAGE_MAIN, widthBlocks);
        while (segments.size() <= id) segments.add(null);
        segments.set(id, seg);
        indexSegment(seg);
        markDirty();
        return id;
    }

    private void indexSegment(RoadSegment seg) {
        for (RoadWaypoint wp : seg.waypoints) {
            long ck = chunkKey(wp.x >> 4, wp.z >> 4);
            IntArrayList ids = chunkIndex.get(ck);
            if (ids == null) {
                ids = new IntArrayList(4);
                chunkIndex.put(ck, ids);
            }
            if (!ids.contains(seg.id)) ids.add(seg.id);
        }
    }

    private List<RoadSegment> getSegmentsNearFast(int x, int z, int radius) {
        int cminX = (x - radius) >> 4, cmaxX = (x + radius) >> 4;
        int cminZ = (z - radius) >> 4, cmaxZ = (z + radius) >> 4;

        it.unimi.dsi.fastutil.ints.IntOpenHashSet seen = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        List<RoadSegment> result = new ArrayList<>();

        for (int cx = cminX; cx <= cmaxX; cx++) {
            for (int cz = cminZ; cz <= cmaxZ; cz++) {
                IntArrayList ids = chunkIndex.get(chunkKey(cx, cz));
                if (ids == null) continue;
                for (int i = 0; i < ids.size(); i++) {
                    int sid = ids.getInt(i);
                    if (!seen.add(sid)) continue;
                    if (sid < 0 || sid >= segments.size()) continue;
                    RoadSegment seg = segments.get(sid);
                    if (seg != null) result.add(seg);
                }
            }
        }
        return result;
    }

    private static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
