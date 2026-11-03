package com.jubitus.ultreia.boost;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.mill.MillPathPalette;
import com.jubitus.ultreia.roads.RoadIndex;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SpeedBoostHandler {

    // Horizontal search radius (blocks) around the player used to detect nearby road and bridge columns.
    private static final int DETECTION_RADIUS = 2;
    // Server ticks the speed bonus is maintained after the player leaves a road (prevents abrupt stops).
    private static final int LINGER_TICKS = 30;
    // Server ticks over which the bonus ramps up when entering a road.
    private static final int RAMP_UP_TICKS = 20;
    // Server ticks over which the bonus ramps down after the linger period expires.
    private static final int RAMP_DOWN_TICKS = 40;

    private static final UUID MODIFIER_UUID = UUID.fromString("a3b4c5d6-e7f8-9012-3456-789abcdef012");
    private static final String MODIFIER_NAME = "mpb:road_speed";
    // Operation 2 = multiply total: final_speed = base * (1 + amount)
    private static final int MULTIPLY_TOTAL = 2;

    private static final class State {
        float boost = 0f;  // 0.0 = no boost applied, 1.0 = full config bonus applied
        int linger = 0;
        // Entity currently holding the applied AttributeModifier (player, or the mount when riding).
        WeakReference<EntityLivingBase> target = null;
        float lastAppliedAmount = 0f;
    }

    private final Map<UUID, State> states = new HashMap<>();

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        UltreiaConfig.SpeedBoost cfg = UltreiaConfig.SPEED_BOOST;

        for (WorldServer world : DimensionManager.getWorlds()) {
            if (world == null) continue;
            RoadIndex roadIndex = RoadIndex.get(world);
            List<?> players = new ArrayList<>(world.playerEntities);
            for (Object obj : players) {
                if (obj instanceof EntityPlayerMP) {
                    tickPlayer((EntityPlayerMP) obj, world, roadIndex, cfg);
                }
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        // Older versions saved the modifier with the player: drop any leftover.
        removeModifier(event.player);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        State state = states.remove(event.player.getUniqueID());
        if (state != null) removeModifier(currentTarget(state));
    }

    private void tickPlayer(EntityPlayerMP player, WorldServer world, RoadIndex roadIndex, UltreiaConfig.SpeedBoost cfg) {
        UUID id = player.getUniqueID();
        State state = states.computeIfAbsent(id, k -> new State());

        // Effective target: the ridden mount if it exposes MOVEMENT_SPEED, otherwise the player.
        EntityLivingBase target = player;
        if (player.isRiding() && player.getRidingEntity() instanceof EntityLivingBase) {
            EntityLivingBase mount = (EntityLivingBase) player.getRidingEntity();
            if (mount.getAttributeMap().getAttributeInstance(SharedMonsterAttributes.MOVEMENT_SPEED) != null) {
                target = mount;
            }
        }

        boolean onRoad = cfg.enabled && detectOnRoad(target, world, roadIndex, DETECTION_RADIUS);

        if (onRoad) {
            state.linger = LINGER_TICKS;
            state.boost = Math.min(1.0f, state.boost + 1.0f / Math.max(1, RAMP_UP_TICKS));
        } else if (state.linger > 0) {
            state.linger--;
            // sustain current boost level through linger period
        } else {
            state.boost = Math.max(0.0f, state.boost - 1.0f / Math.max(1, RAMP_DOWN_TICKS));
        }

        EntityLivingBase previous = currentTarget(state);
        if (previous != null && previous != target) {
            // Mount/dismount, or the ridden entity changed: clean up the old target's modifier.
            // The boost/linger ramp itself is kept continuous across the switch.
            if (UltreiaConfig.DEBUG.logSpeedBoost) Ultreia.LOGGER.debug(
                    "[SpeedBoost] {} target switched from {} to {}", player.getName(), previous.getName(), target.getName());
            removeModifier(previous);
            state.lastAppliedAmount = 0f;
        }
        state.target = new WeakReference<>(target);

        applyModifier(target, state, state.boost * (float) cfg.speedBonus, UltreiaConfig.DEBUG.logSpeedBoost);

        if (state.boost <= 0f && state.linger <= 0) {
            removeModifier(target);
            states.remove(id);
        }
    }

    private boolean detectOnRoad(EntityLivingBase target, WorldServer world, RoadIndex roadIndex,
                                  int radius) {
        int px = (int) Math.floor(target.posX);
        int py = (int) Math.floor(target.posY);
        int pz = (int) Math.floor(target.posZ);

        // Fast path: block directly under the target's feet is a path block
        if (MillPathPalette.isMillPath(world.getBlockState(new BlockPos(px, py - 1, pz)).getBlock())) {
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
                    if (MillPathPalette.isMillPath(world.getBlockState(new BlockPos(x, py - 1, z)).getBlock())) {
                        return true;
                    }
                    continue;
                }

                // Player feet (py) sit one block above the road surface (py == roadY + 1 for full blocks,
                // py == roadY for bottom slabs). Accept within ±1 block to cover both cases cleanly.
                if (Math.abs(py - (roadY + 1)) <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final float APPLY_THRESHOLD = 0.001f;

    private void applyModifier(EntityLivingBase target, State state, float amount, boolean debug) {
        IAttributeInstance attr = target.getAttributeMap().getAttributeInstance(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (attr == null) return;

        if (Math.abs(amount - state.lastAppliedAmount) <= APPLY_THRESHOLD) return;

        attr.removeModifier(MODIFIER_UUID);
        if (amount > APPLY_THRESHOLD) {
            attr.applyModifier(new AttributeModifier(MODIFIER_UUID, MODIFIER_NAME, amount, MULTIPLY_TOTAL).setSaved(false));
        }
        if (debug) Ultreia.LOGGER.debug(
                "[SpeedBoost] {} apply performed: {} -> {}", target.getName(), state.lastAppliedAmount, amount);
        state.lastAppliedAmount = amount;
    }

    private static EntityLivingBase currentTarget(State state) {
        return state.target != null ? state.target.get() : null;
    }

    private static void removeModifier(EntityLivingBase e) {
        if (e == null) return;
        IAttributeInstance attr = e.getAttributeMap().getAttributeInstance(SharedMonsterAttributes.MOVEMENT_SPEED);
        if (attr != null) attr.removeModifier(MODIFIER_UUID);
    }
}
