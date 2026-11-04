package com.jubitus.ultreia.mill;

import net.minecraft.world.World;
import org.millenaire.common.world.MillWorldData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Millénaire world data captured when Millénaire creates it (mixin), per dimension: the fallback of {@code MillSettlements.wd}. */
public final class MillWorldDataRegistry {

    private static final Map<Integer, MillWorldData> BY_DIM = new ConcurrentHashMap<>();

    private MillWorldDataRegistry() {}

    /** Forgets the previous world's data, so the next world opened in the same session loads its own. */
    public static void clear() {
        BY_DIM.clear();
    }

    public static void register(MillWorldData data) {
        if (data == null || data.world == null) return;
        int dim = data.world.provider.getDimension();
        BY_DIM.put(dim, data);
    }

    public static MillWorldData get(World world) {
        if (world == null || world.provider == null) return null;
        return BY_DIM.get(world.provider.getDimension());
    }
}
