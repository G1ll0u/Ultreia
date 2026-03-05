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
        return builtEdges.contains(edgeKey);
    }

    public void markBuilt(String edgeKey) {
        builtEdges.add(edgeKey);
        markDirty();
    }

    public void clear() {
        builtEdges.clear();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        builtEdges.clear();
        NBTTagList list = nbt.getTagList("edges", 8); // string
        for (int i = 0; i < list.tagCount(); i++) {
            builtEdges.add(list.getStringTagAt(i));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (String s : builtEdges) {
            list.appendTag(new net.minecraft.nbt.NBTTagString(s));
        }
        nbt.setTag("edges", list);
        return nbt;
    }
}