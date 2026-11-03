package com.jubitus.ultreia.job;

import com.jubitus.ultreia.planner.NetworkPlanner;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.ArrayList;
import java.util.List;

public class JobCheckpoint extends WorldSavedData {

    private static final String NAME = "ultreia_job_checkpoint";

    private boolean active = false;
    private int edgeIndex = 0;
    private int built = 0;
    private int skipped = 0;
    private int failed = 0;
    private boolean force = false;
    private boolean dryRun = false;
    private final List<BlockPos[]> edges = new ArrayList<>();

    public JobCheckpoint() {
        super(NAME);
    }

    public JobCheckpoint(String name) {
        super(name);
    }

    public static JobCheckpoint get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        JobCheckpoint data = (JobCheckpoint) storage.getOrLoadData(JobCheckpoint.class, NAME);
        if (data == null) {
            data = new JobCheckpoint();
            storage.setData(NAME, data);
        }
        return data;
    }

    public boolean isActive() {
        return active;
    }

    public int getEdgeIndex() {
        return edgeIndex;
    }

    public int getBuilt() {
        return built;
    }

    public int getSkipped() {
        return skipped;
    }

    public int getFailed() {
        return failed;
    }

    public boolean isForce() {
        return force;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public List<BlockPos[]> getEdges() {
        return edges;
    }

    public void save(List<NetworkPlanner.Edge> edgeList, int idx, int built, int skipped, int failed, boolean force, boolean dryRun) {
        this.active = true;
        this.edgeIndex = idx;
        this.built = built;
        this.skipped = skipped;
        this.failed = failed;
        this.force = force;
        this.dryRun = dryRun;
        this.edges.clear();
        for (NetworkPlanner.Edge e : edgeList) {
            this.edges.add(new BlockPos[]{e.a, e.b});
        }
        markDirty();
    }

    public void clear() {
        this.active = false;
        this.edgeIndex = 0;
        this.built = 0;
        this.skipped = 0;
        this.failed = 0;
        this.force = false;
        this.dryRun = false;
        this.edges.clear();
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        active = nbt.getBoolean("active");
        edgeIndex = nbt.getInteger("edgeIndex");
        built = nbt.getInteger("built");
        skipped = nbt.getInteger("skipped");
        failed = nbt.getInteger("failed");
        force = nbt.getBoolean("force");
        dryRun = nbt.getBoolean("dryRun");

        edges.clear();
        NBTTagList list = nbt.getTagList("edges", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound e = list.getCompoundTagAt(i);
            BlockPos a = new BlockPos(e.getInteger("ax"), e.getInteger("ay"), e.getInteger("az"));
            BlockPos b = new BlockPos(e.getInteger("bx"), e.getInteger("by"), e.getInteger("bz"));
            edges.add(new BlockPos[]{a, b});
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setBoolean("active", active);
        nbt.setInteger("edgeIndex", edgeIndex);
        nbt.setInteger("built", built);
        nbt.setInteger("skipped", skipped);
        nbt.setInteger("failed", failed);
        nbt.setBoolean("force", force);
        nbt.setBoolean("dryRun", dryRun);

        NBTTagList list = new NBTTagList();
        for (BlockPos[] pair : edges) {
            NBTTagCompound e = new NBTTagCompound();
            e.setInteger("ax", pair[0].getX());
            e.setInteger("ay", pair[0].getY());
            e.setInteger("az", pair[0].getZ());
            e.setInteger("bx", pair[1].getX());
            e.setInteger("by", pair[1].getY());
            e.setInteger("bz", pair[1].getZ());
            list.appendTag(e);
        }
        nbt.setTag("edges", list);
        return nbt;
    }
}
