package com.jubitus.ultreia.mill;

import com.jubitus.ultreia.MillPresence;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The only door to Millénaire village data for code outside {@code mill/}: vanilla types only, and every call
 * answers "nothing" when Millénaire is absent.
 */
public final class Villages {

    /** Radius (blocks) of the protected square around every settlement centre. */
    public static final int CENTER_PROTECT_RADIUS = MillBuildings.SETTLEMENT_CENTER_RADIUS;

    private Villages() {}

    /** A village (town hall) or a lone building. */
    public static final class Settlement {
        public final BlockPos center;
        /** True for a village, false for a lone building. */
        public final boolean isVillage;
        public final String name;

        Settlement(BlockPos center, boolean isVillage, String name) {
            this.center = center;
            this.isVillage = isVillage;
            this.name = name;
        }
    }

    /** Village and lone-building centres (lone ones only when configured), one per XZ. */
    public static List<BlockPos> nodes(World world) {
        if (!MillPresence.isPresent()) return new ArrayList<>();
        return MillSettlements.nodePositions(world);
    }

    public static void logNodeSummary(World world) {
        if (!MillPresence.isPresent()) {
            com.jubitus.ultreia.Ultreia.LOGGER.info("Millenaire not installed — no village nodes available.");
            return;
        }
        MillSettlements.logNodeSummary(world);
    }

    /** The settlement whose centre is exactly this position, or null. */
    @Nullable
    public static Settlement at(World world, BlockPos center) {
        if (!MillPresence.isPresent()) return null;
        return MillSettlements.findExact(world, center);
    }

    /** Gate anchors of the village at (or near) this centre; empty when it has no built gate. */
    public static List<BlockPos> gates(World world, BlockPos villageCenter) {
        if (!MillPresence.isPresent()) return Collections.emptyList();
        return MillBuildings.gates(world, villageCenter);
    }

    public static boolean isNearVillage(World world, BlockPos pos, int radius) {
        return MillPresence.isPresent() && MillSettlements.isNearVillage(world, pos, radius);
    }

    public static boolean isNearAnySettlement(World world, BlockPos pos, int radius) {
        return MillPresence.isPresent() && MillSettlements.isNearAnySettlement(world, pos, radius);
    }

    /** Villagers of the village at this centre (loaded or not), 0 when unknown. */
    public static int population(World world, BlockPos villageCenter) {
        if (!MillPresence.isPresent()) return 0;
        return MillSettlements.population(world, villageCenter);
    }

    /** Adds settlement centres and every building footprint to the areas roads must avoid. */
    public static void addProtectedZones(World world, ProtectedAreas pa) {
        if (MillPresence.isPresent()) MillBuildings.addProtectedZones(world, pa);
    }

    /** Forgets the previous world's Millénaire data (server stop). */
    public static void clear() {
        if (MillPresence.isPresent()) MillSettlements.clear();
    }
}
