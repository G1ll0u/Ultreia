package com.jubitus.millpathbuilder.gen.mill;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.MPBProtectedAreas;
import com.jubitus.millpathbuilder.integration.MillIntegration;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.BuildingLocation;
import org.millenaire.common.world.MillWorldData;

import java.util.List;

public final class MPBBuildingProtection {

    private MPBBuildingProtection() {}

    public static void addMillenaireBuildingFootprints(WorldServer world, MPBProtectedAreas out) {
        if (!MPBConfig.BUILDINGS.enabled) return;

        MillWorldData wd = MillIntegration.ensureLoaded(world);
        if (wd == null) return;

        int pad = Math.max(0, MPBConfig.BUILDINGS.footprintPadding);

        // Villages
        if (wd.villagesList != null && wd.villagesList.pos != null) {
            for (Point vp : wd.villagesList.pos) {
                if (vp == null) continue;

                BlockPos th = new BlockPos(vp.getiX(), vp.getiY(), vp.getiZ());
                List<MillVillageIndex.BuildingInfo> buildings =
                        MillVillageIndex.listBuildingsAroundVillageCached(world, th, 192, 96);

                addLocations(buildings, out, pad);
            }
        }

        // Lone buildings
        if (wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            for (Point lp : wd.loneBuildingsList.pos) {
                if (lp == null) continue;

                BlockPos p = new BlockPos(lp.getiX(), lp.getiY(), lp.getiZ());
                List<MillVillageIndex.BuildingInfo> buildings =
                        MillVillageIndex.listBuildingsAroundVillage(world, p, 120, 64);

                addLocations(buildings, out, pad);
            }
        }

        // Optional penalty ring around hard footprints
        if (MPBConfig.BUILDINGS.addPenaltyRing) {
            addPenaltyRingFromNoPaintZones(out);
        }
    }

    private static void addLocations(List<MillVillageIndex.BuildingInfo> buildings, MPBProtectedAreas out, int pad) {
        if (buildings == null) return;

        int hardPenalty = computeHardPenalty();

        for (MillVillageIndex.BuildingInfo bi : buildings) {
            if (bi == null || bi.location == null) continue;
            addOneLocation(bi.location, out, pad, hardPenalty);
        }
    }

    private static void addOneLocation(BuildingLocation loc, MPBProtectedAreas out, int pad, int hardPenalty) {
        int minX = Math.min(loc.minx, loc.maxx) - pad;
        int maxX = Math.max(loc.minx, loc.maxx) + pad;
        int minZ = Math.min(loc.minz, loc.maxz) - pad;
        int maxZ = Math.max(loc.minz, loc.maxz) + pad;

        out.addNoPaintRect(minX, maxX, minZ, maxZ, hardPenalty);
    }

    private static int computeHardPenalty() {
        // Use existing PATHFINDING protectedZonePenalty as a baseline, but make hard zones clearly dominant.
        int base = (int) Math.max(0.0, MPBConfig.PATHFINDING.protectedZonePenalty);
        return Math.max(5000, base + 5000);
    }

    private static void addPenaltyRingFromNoPaintZones(MPBProtectedAreas out) {
        int ring = Math.max(0, MPBConfig.BUILDINGS.penaltyRingRadius);
        if (ring <= 0) return;

        int p = (int) Math.max(0.0, MPBConfig.BUILDINGS.penaltyRingCost);
        if (p <= 0) return;

        // We don’t have direct access to internal zone list, so we re-add penalty squares
        // only around the node centers is not enough.
        // Instead: rely on MPBProtectedAreas.penaltyAt via existing squares + building zones,
        // and add an outer penalty in TerrainSampler (see below) from "containsNearNoPaint".
        //
        // Intentionally left empty: ring is implemented in TerrainSampler to avoid duplicating zone enumeration.
    }
}