package com.jubitus.ultreia.gen.mill;

import com.jubitus.ultreia.gen.MPBProtectedAreas;
import com.jubitus.ultreia.integration.MillIntegration;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.BuildingLocation;
import org.millenaire.common.world.MillWorldData;

import java.util.List;

public final class MillBuildingObstacles2 {
    private MillBuildingObstacles2() {}

    public static void addBuildingsFromLocationsAsNoPaint(
            WorldServer world,
            MPBProtectedAreas out,
            int expandHard,   // safety around bbox (0..2)
            int hardPenalty
    ) {
        MillWorldData wd = MillIntegration.ensureLoaded(world);
        if (wd == null) return;

        // Villages -> list buildings around each TH center
        if (wd.villagesList != null && wd.villagesList.pos != null) {
            for (Point vp : wd.villagesList.pos) {
                if (vp == null) continue;
                BlockPos th = new BlockPos(vp.getiX(), vp.getiY(), vp.getiZ());

                List<MillVillageIndex.BuildingInfo> buildings =
                        MillVillageIndex.listBuildingsAroundVillageCached(world, th, 192, 96);

                for (MillVillageIndex.BuildingInfo bi : buildings) {
                    if (bi == null || bi.location == null) continue;
                    addOne(bi.location, out, expandHard, hardPenalty);
                }
            }
        }

        // Lone buildings -> the building itself exists in wd, but easiest is to query around the point too
        if (wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            for (Point lp : wd.loneBuildingsList.pos) {
                if (lp == null) continue;
                BlockPos p = new BlockPos(lp.getiX(), lp.getiY(), lp.getiZ());

                List<MillVillageIndex.BuildingInfo> buildings =
                        MillVillageIndex.listBuildingsAroundVillage(world, p, 120, 64);

                for (MillVillageIndex.BuildingInfo bi : buildings) {
                    if (bi == null || bi.location == null) continue;
                    addOne(bi.location, out, expandHard, hardPenalty);
                }
            }
        }
    }

    private static void addOne(BuildingLocation loc, MPBProtectedAreas out, int expandHard, int hardPenalty) {
        int minX = Math.min(loc.minx, loc.maxx) - expandHard;
        int maxX = Math.max(loc.minx, loc.maxx) + expandHard;
        int minZ = Math.min(loc.minz, loc.maxz) - expandHard;
        int maxZ = Math.max(loc.minz, loc.maxz) + expandHard;

        // Hard no-paint/no-path zone = actual building bbox (NOT margins)
        out.addNoPaintRect(minX, maxX, minZ, maxZ, hardPenalty);

        // Optional: margins as penalty only (do NOT hard block)
        // If you want this, keep it small so you can still route to entrances easily.
        // out.addRect(loc.minxMargin, loc.maxxMargin, loc.minzMargin, loc.maxzMargin, hardPenalty / 20, false);
    }
}