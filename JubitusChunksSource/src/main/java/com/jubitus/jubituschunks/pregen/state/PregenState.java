package com.jubitus.jubituschunks.pregen.state;

import com.jubitus.jubituschunks.JubitusChunksMod;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.world.WorldServer;

import java.io.File;

public class PregenState {

    public int dim;
    public int centerChunkX;
    public int centerChunkZ;
    public int chunkRadius;
    public long spiralSteps;

    public boolean skipExisting;
    public boolean verifyExisting;        // NEW
    public boolean populateViaGenerator;  // NEW

    public long generatedChunks;
    public long skippedExisting;

    public int radiusSteps;     // NEW
    public int strideChunks;    // NEW

    public NBTTagCompound toNBT() {
        NBTTagCompound n = new NBTTagCompound();
        n.setInteger("dim", dim);
        n.setInteger("cx", centerChunkX);
        n.setInteger("cz", centerChunkZ);
        n.setInteger("r", chunkRadius);
        n.setLong("steps", spiralSteps);

        n.setBoolean("skipExisting", skipExisting);
        n.setBoolean("verifyExisting", verifyExisting);               // NEW
        n.setBoolean("populateViaGenerator", populateViaGenerator);   // NEW

        n.setLong("generated", generatedChunks);
        n.setLong("skippedExisting", skippedExisting);

        n.setInteger("radiusSteps", radiusSteps);
        n.setInteger("strideChunks", strideChunks);
        return n;
    }

    public static PregenState fromNBT(NBTTagCompound n) {
        PregenState s = new PregenState();
        s.dim = n.getInteger("dim");
        s.centerChunkX = n.getInteger("cx");
        s.centerChunkZ = n.getInteger("cz");
        s.chunkRadius = n.getInteger("r");
        s.spiralSteps = n.getLong("steps");

        s.skipExisting = n.getBoolean("skipExisting");

        // Backward-compatible defaults if fields don't exist in older save files:
        s.verifyExisting = n.hasKey("verifyExisting") ? n.getBoolean("verifyExisting") : false;
        s.populateViaGenerator = n.hasKey("populateViaGenerator") ? n.getBoolean("populateViaGenerator") : false;

        s.generatedChunks = n.getLong("generated");
        s.skippedExisting = n.getLong("skippedExisting");
        s.radiusSteps  = n.hasKey("radiusSteps")  ? n.getInteger("radiusSteps")  : 0;
        s.strideChunks = n.hasKey("strideChunks") ? n.getInteger("strideChunks") : 1;

        return s;
    }

    public static File fileFor(WorldServer world) {
        File dir = new File(world.getSaveHandler().getWorldDirectory(), "jubituschunks");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        int dim = world.provider.getDimension();
        return new File(dir, "pregen_dim" + dim + ".dat");
    }

    public static void save(WorldServer world, PregenState state) {
        try {
            File f = fileFor(world);
            CompressedStreamTools.write(state.toNBT(), f);
        } catch (Exception e) {
            JubitusChunksMod.LOGGER.error("Failed to save pregen state", e);
        }
    }

    public static PregenState load(WorldServer world) {
        try {
            File f = fileFor(world);
            if (!f.exists()) return null;
            NBTTagCompound n = CompressedStreamTools.read(f);
            return fromNBT(n);
        } catch (Exception e) {
            JubitusChunksMod.LOGGER.error("Failed to load pregen state", e);
            return null;
        }
    }

    public static void delete(WorldServer world) {
        try {
            File f = fileFor(world);
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
        } catch (Exception e) {
            JubitusChunksMod.LOGGER.error("Failed to delete pregen state", e);
        }
    }

}
