package com.jubitus.ultreia.mixin;

import com.jubitus.ultreia.integration.MillIntegration;
import net.minecraft.world.World;
import org.millenaire.common.world.MillWorldData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(value = MillWorldData.class, remap = false)
public abstract class MixinMillWorldData_Capture {

    @Shadow
    public World world;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void mpb$afterInit(World world, CallbackInfo ci) {
        MillIntegration.register((MillWorldData) (Object) this);
    }

    @Inject(method = "loadData", at = @At("RETURN"))
    private void mpb$afterLoadData(CallbackInfo ci) {
        MillIntegration.register((MillWorldData) (Object) this);
    }
}