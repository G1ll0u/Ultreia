package com.jubitus.millpathbuilder.gen;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashSet;
import java.util.Set;

public class MPBWorldData extends WorldSavedData {

    private static final String NAME = "millpathbuilder_data";
    private static final String TAG_EDGES = "edges";

    private final Set<String> builtEdges = new HashSet<>();

    public MPBWorldData() {
        super(NAME);
    }

    public MPBWorldData(String name) {
        super(name);
    }

    public static MPBWorldData get(World world) {
        MapStorage storage = world.getPerWorldStorage();
        MPBWorldData data = (MPBWorldData) storage.getOrLoadData(MPBWorldData.class, NAME);
        if (data == null) {
            data = new MPBWorldData();
            storage.setData(NAME, data);
        }
        return data;
    }

    public int getBuiltEdgeCount() {
        return builtEdges.size();
    }

    public boolean isBuilt(String edgeKey) {
        return edgeKey != null && builtEdges.contains(edgeKey);
    }

    public void markBuilt(String edgeKey) {
        if (edgeKey == null || edgeKey.isEmpty()) {
            return;
        }
        if (builtEdges.add(edgeKey)) {
            markDirty();
        }
    }

    public void clear() {
        if (!builtEdges.isEmpty()) {
            builtEdges.clear();
            markDirty();
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        builtEdges.clear();

        NBTTagList list = nbt.getTagList(TAG_EDGES, 8);
        for (int i = 0; i < list.tagCount(); i++) {
            String edge = list.getStringTagAt(i);
            if (edge != null && !edge.isEmpty()) {
                builtEdges.add(edge);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (String edge : builtEdges) {
            if (edge != null && !edge.isEmpty()) {
                list.appendTag(new net.minecraft.nbt.NBTTagString(edge));
            }
        }
        nbt.setTag(TAG_EDGES, list);
        return nbt;
    }
}