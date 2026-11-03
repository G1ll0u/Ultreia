package com.jubitus.ultreia.gen;

import com.jubitus.ultreia.gen.world.LogicalEdge;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MPBWorldData extends WorldSavedData {

    private static final String NAME            = "ultreia_data";
    private static final String TAG_EDGES       = "edges";
    private static final String TAG_POPULATIONS = "villagePopulations";
    private static final String TAG_NETWORK     = "logicalNetwork";

    private final Set<String>         builtEdges   = new HashSet<>();
    // key = "x,y,z" matching BlockPos of the village TH center
    private final Map<String, Integer> populations  = new HashMap<>();
    private final List<LogicalEdge>    logicalNetwork = new ArrayList<>();

    public MPBWorldData() { super(NAME); }
    public MPBWorldData(String name) { super(name); }

    // -------------------------------------------------------------------------
    // Access
    // -------------------------------------------------------------------------

    public static MPBWorldData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        MPBWorldData data = (MPBWorldData) storage.getOrLoadData(MPBWorldData.class, NAME);
        if (data == null) {
            data = new MPBWorldData();
            storage.setData(NAME, data);
        }
        return data;
    }

    // ---- Built edges --------------------------------------------------------

    public boolean isBuilt(String edgeKey) {
        return edgeKey != null && builtEdges.contains(edgeKey);
    }

    public void markBuilt(String edgeKey) {
        if (edgeKey == null || edgeKey.isEmpty()) return;
        if (builtEdges.add(edgeKey)) markDirty();
    }

    // ---- Village population cache ------------------------------------------

    private static String popKey(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public void storeVillagePopulation(BlockPos center, int population) {
        String key = popKey(center);
        Integer prev = populations.get(key);
        if (prev == null || prev != population) {
            populations.put(key, population);
            markDirty();
        }
    }

    /** Returns the last known villager count, or 0 if never recorded. */
    public int getStoredPopulation(BlockPos center) {
        Integer v = populations.get(popKey(center));
        return v != null ? v : 0;
    }

    // ---- Logical road network (for entity navigation) ----------------------

    /** Replaces the stored logical network with the latest computed edges. */
    public void setLogicalNetwork(List<LogicalEdge> edges) {
        logicalNetwork.clear();
        logicalNetwork.addAll(edges);
        markDirty();
    }

    /** Read-only view of the last stored logical network. */
    public List<LogicalEdge> getLogicalNetwork() {
        return Collections.unmodifiableList(logicalNetwork);
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        builtEdges.clear();
        NBTTagList list = nbt.getTagList(TAG_EDGES, 8);
        for (int i = 0; i < list.tagCount(); i++) {
            String edge = list.getStringTagAt(i);
            if (edge != null && !edge.isEmpty()) builtEdges.add(edge);
        }

        populations.clear();
        NBTTagList popList = nbt.getTagList(TAG_POPULATIONS, 10);
        for (int i = 0; i < popList.tagCount(); i++) {
            NBTTagCompound entry = popList.getCompoundTagAt(i);
            String key = entry.getString("k");
            int val    = entry.getInteger("v");
            if (!key.isEmpty()) populations.put(key, val);
        }

        logicalNetwork.clear();
        NBTTagList netList = nbt.getTagList(TAG_NETWORK, 11); // int-array list
        for (int i = 0; i < netList.tagCount(); i++) {
            int[] d = ((NBTTagIntArray) netList.get(i)).getIntArray();
            if (d.length >= 5) {
                logicalNetwork.add(new LogicalEdge(d[0], d[1], d[2], d[3], d[4] != 0));
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList edgeList = new NBTTagList();
        for (String edge : builtEdges) {
            if (edge != null && !edge.isEmpty())
                edgeList.appendTag(new net.minecraft.nbt.NBTTagString(edge));
        }
        nbt.setTag(TAG_EDGES, edgeList);

        NBTTagList popList = new NBTTagList();
        for (Map.Entry<String, Integer> e : populations.entrySet()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("k", e.getKey());
            entry.setInteger("v", e.getValue());
            popList.appendTag(entry);
        }
        nbt.setTag(TAG_POPULATIONS, popList);

        NBTTagList netList = new NBTTagList();
        for (LogicalEdge e : logicalNetwork) {
            netList.appendTag(new NBTTagIntArray(new int[]{
                    e.x1, e.z1, e.x2, e.z2, e.isLoneTrail ? 1 : 0
            }));
        }
        nbt.setTag(TAG_NETWORK, netList);

        return nbt;
    }
}
