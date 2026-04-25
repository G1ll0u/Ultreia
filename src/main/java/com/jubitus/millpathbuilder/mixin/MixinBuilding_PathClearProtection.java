package com.jubitus.millpathbuilder.mixin;

import com.jubitus.millpathbuilder.gen.newp.MPBMillenairePathCompat;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = Building.class, remap = false)
public abstract class MixinBuilding_PathClearProtection {

    @Shadow
    public World world;

    @Shadow
    public int oldPathPointsToClearIndex;

    @Shadow
    private List<Point> oldPathPointsToClear;

    @Inject(method = "readPaths", at = @At("RETURN"))
    private void mpb$afterReadPaths(CallbackInfo ci) {
        mpb$filterStableClearTargets();
    }

    @Inject(method = "clearOldPaths", at = @At("HEAD"))
    private void mpb$beforeClearOldPaths(CallbackInfo ci) {
        mpb$filterStableClearTargets();
    }

    @Inject(method = "getCurrentClearPathPoint", at = @At("HEAD"))
    private void mpb$skipStableCurrentClearPoint(CallbackInfoReturnable<Point> cir) {
        if (oldPathPointsToClear == null) return;

        while (oldPathPointsToClearIndex < oldPathPointsToClear.size()) {
            Point p = oldPathPointsToClear.get(oldPathPointsToClearIndex);
            if (p == null) {
                oldPathPointsToClearIndex++;
                continue;
            }

            BlockPos pos = new BlockPos(p.getiX(), p.getiY(), p.getiZ());
            if (!MPBMillenairePathCompat.hasStablePathInColumn(world, pos, -2, 4)) {
                break;
            }

            oldPathPointsToClearIndex++;
        }
    }

    private void mpb$filterStableClearTargets() {
        if (oldPathPointsToClear == null || oldPathPointsToClear.isEmpty()) return;

        List<Point> filtered = new ArrayList<Point>(oldPathPointsToClear.size());

        for (Point p : oldPathPointsToClear) {
            if (p == null) continue;

            BlockPos pos = new BlockPos(p.getiX(), p.getiY(), p.getiZ());
            if (MPBMillenairePathCompat.hasStablePathInColumn(world, pos, -2, 4)) {
                continue;
            }

            filtered.add(p);
        }

        oldPathPointsToClear = filtered;
        if (oldPathPointsToClearIndex > oldPathPointsToClear.size()) {
            oldPathPointsToClearIndex = oldPathPointsToClear.size();
        }
    }
    @Inject(method = "calculatePathsToClear", at = @At("RETURN"))
    private void mpb$afterCalculatePathsToClear(CallbackInfo ci) {
        mpb$filterStableClearTargets();
    }
}