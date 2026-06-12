package com.jubitus.millpathbuilder.player;

import com.jubitus.millpathbuilder.MillPathBuilderMod;
import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
import com.jubitus.millpathbuilder.gen.world.MPBRoadIndexData;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MPBSpeedBoostHandler {

    private static final UUID MODIFIER_UUID = UUID.fromString("a3b4c5d6-e7f8-9012-3456-789abcdef012");
    private static final String MODIFIER_NAME = "mpb:road_speed";
    // Operation 2 = multiply total: final_speed = base * (1 + amount)
    private static final int MULTIPLY_TOTAL = 2;

    private static final class State {
        float boost = 0f;  // 0.0 = no boost applied, 1.0 = full config bonus applied
        int linger = 0;
    }

    private final Map<UUID, State> states = new HashMap<>();

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MPBConfig.SpeedBoost cfg = MPBConfig.SPEED_BOOST;

        for (WorldServer world : DimensionManager.getWorlds()) {
            if (world == null) continue;
            MPBRoadIndexData roadIndex = MPBRoadIndexData.get(world);
            List<?> players = new ArrayList<>(world.playerEntities);
            for (Object obj : players) {
                if (obj instanceof EntityPlayerMP) {
                    tickPlayer((EntityPlayerMP) obj, world, roadIndex, cfg);
                }
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        states.remove(event.player.getUniqueID());
    }

    private void tickPlayer(EntityPlayerMP player, WorldServer world, MPBRoadIndexData roadIndex, MPBConfig.SpeedBoost cfg) {
        UUID id = player.getUniqueID();
        State state = states.computeIfAbsent(id, k -> new State());

        boolean onRoad = cfg.enabled && detectOnRoad(player, world, roadIndex, cfg.detectionRadius, cfg.debugLog);

        if (onRoad) {
            state.linger = cfg.lingerTicks;
            state.boost = Math.min(1.0f, state.boost + 1.0f / Math.max(1, cfg.rampUpTicks));
        } else if (state.linger > 0) {
            state.linger--;
            // sustain current boost level through linger period
        } else {
            state.boost = Math.max(0.0f, state.boost - 1.0f / Math.max(1, cfg.rampDownTicks));
        }

        applyModifier(player, state.boost * (float) cfg.speedBonus);

        if (state.boost <= 0f && state.linger <= 0) {
            states.remove(id);
        }
    }

    private boolean detectOnRoad(EntityPlayerMP player, WorldServer world, MPBRoadIndexData roadIndex,
                                  int radius, boolean debug) {
        int px = (int) Math.floor(player.posX);
        int py = (int) Math.floor(player.posY);
        int pz = (int) Math.floor(player.posZ);

        // Fast path: block directly under the player's feet is a path block
        if (MPBMillPathPalette.isMillPath(world.getBlockState(new BlockPos(px, py - 1, pz)).getBlock())) {
            if (debug) MillPathBuilderMod.LOGGER.debug(
                    "[SpeedBoost] {} on direct path block at {},{},{}", player.getName(), px, py - 1, pz);
            return true;
        }

        // Index-based detection with height localization within the search radius
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > r2) continue;
                int x = px + dx, z = pz + dz;
                if (!roadIndex.isRoadXZ(x, z)) continue;

                int roadY = roadIndex.getRoadY(x, z);
                if (roadY < 0) {
                    // No stored Y: verify via direct block check so we don't trigger in caves
                    if (MPBMillPathPalette.isMillPath(world.getBlockState(new BlockPos(x, py - 1, z)).getBlock())) {
                        return true;
                    }
                    continue;
                }

                // Player feet (py) sit one block above the road surface (py == roadY + 1 for full blocks,
                // py == roadY for bottom slabs). Accept within ±1 block to cover both cases cleanly.
                if (Math.abs(py - (roadY + 1)) <= 1) {
                    if (debug) MillPathBuilderMod.LOGGER.debug(
                            "[SpeedBoost] {} near road index ({},{}) roadY={} playerY={}",
                            player.getName(), x, z, roadY, py);
                    return true;
                }
            }
        }
        return false;
    }

    private void applyModifier(EntityPlayerMP player, float amount) {
        IAttributeInstance attr = player.getAttributeMap().getAttributeInstance(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (attr == null) return;

        attr.removeModifier(MODIFIER_UUID);
        if (amount > 0.001f) {
            attr.applyModifier(new AttributeModifier(MODIFIER_UUID, MODIFIER_NAME, amount, MULTIPLY_TOTAL));
        }
    }
}
