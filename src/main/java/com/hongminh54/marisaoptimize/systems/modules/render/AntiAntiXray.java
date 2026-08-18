/*
 * This file is part of the Marisa Optimize distribution (https://github.com/TheBlueSkyARL/marisa-optimize).
 * Copyright (c) Meteor Development.
 */

package com.hongminh54.marisaoptimize.systems.modules.render;

import com.hongminh54.marisaoptimize.events.world.TickEvent;
import com.hongminh54.marisaoptimize.settings.*;
import com.hongminh54.marisaoptimize.systems.modules.Categories;
import com.hongminh54.marisaoptimize.systems.modules.Module;
import com.hongminh54.marisaoptimize.systems.modules.Modules;
import com.hongminh54.marisaoptimize.utils.player.Rotations;
import com.hongminh54.marisaoptimize.utils.world.AntiXrayServerType;
import com.hongminh54.marisaoptimize.utils.world.BlockUtils;
import com.hongminh54.marisaoptimize.utils.world.XrayMemory;
import com.hongminh54.marisaoptimize.utils.world.XrayNoiseFilter;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class AntiAntiXray extends Module {
    public enum RevealMode {
        Off,
        Safe,
        Aggressive
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<RevealMode> revealMode = sgGeneral.add(new EnumSetting.Builder<RevealMode>()
        .name("reveal-mode")
        .description("How blocks are punched to force the server to reveal real ores. Safe only punches the block you are looking at.")
        .defaultValue(RevealMode.Safe)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Minimum delay between punch attempts in ticks.")
        .defaultValue(2)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Only reveals blocks below this Y level. Most servers only obfuscate up to 64 (128 in the nether).")
        .defaultValue(64)
        .range(0, 320)
        .sliderMax(128)
        .build()
    );

    private final Setting<Boolean> fakeLook = sgGeneral.add(new BoolSetting.Builder()
        .name("fake-look")
        .description("Sends fake look packets to exploit plugins that deobfuscate blocks the player is looking at (e.g. Orebfuscator ray cast checking). Risky, anti-cheats may flag it.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> fakeLookDistance = sgGeneral.add(new IntSetting.Builder()
        .name("fake-look-distance")
        .description("How far away the fake look targets.")
        .defaultValue(32)
        .range(1, 64)
        .sliderMax(64)
        .build()
    );

    private final Setting<Boolean> cascade = sgGeneral.add(new BoolSetting.Builder()
        .name("cascade")
        .description("Punches remembered ores in reach to reveal deeper layers of the vein (the server reveals blocks around punched positions). Looks like normal mining behavior.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cascadeCooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cascade-cooldown")
        .description("How often each ore is punched for cascading, in seconds.")
        .defaultValue(30)
        .range(10, 300)
        .sliderMax(120)
        .build()
    );

    private final Setting<Boolean> chunkSweep = sgGeneral.add(new BoolSetting.Builder()
        .name("chunk-sweep")
        .description("Punches reachable blocks inside chunks classified as fully obfuscated by the noise filter to build up real data.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseWhenMoving = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-when-moving")
        .description("Only punches while standing still. Many anti-cheats flag digging while moving.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseNearPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-near-players")
        .description("Stops punching when other players are within 8 blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxPunchesPerSecond = sgGeneral.add(new IntSetting.Builder()
        .name("max-punches-per-second")
        .description("Maximum amount of punch packets per second.")
        .defaultValue(5)
        .range(1, 20)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> adaptive = sgGeneral.add(new BoolSetting.Builder()
        .name("adaptive")
        .description("Automatically stops punching on servers that reveal blocks by look direction themselves (detected via re-hide block updates). Punching can make them stop revealing those blocks.")
        .defaultValue(true)
        .build()
    );

    private int fakeLookIndex;
    private final Long2LongOpenHashMap lastPunched = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap lastCascade = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap lastSweep = new Long2LongOpenHashMap();
    private long nextPunchTime;
    private long secondStart;
    private int punchesThisSecond;
    private boolean warnedCreative;

    public AntiAntiXray() {
        super(Categories.Render, "anti-antixray", "Reveals real blocks hidden by server-side anti-xray. Works by remembering ores revealed through block updates and punching blocks to force the server to send real data.");
    }

    @Override
    public void onActivate() {
        lastPunched.clear();
        lastCascade.clear();
        lastSweep.clear();
        nextPunchTime = 0;
        secondStart = 0;
        punchesThisSecond = 0;
        warnedCreative = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Modules.get().get(Xray.class).isActive() || mc.world == null || mc.player == null || mc.getNetworkHandler() == null) return;

        if (adaptive.get() && AntiXrayServerType.isLookRevealServer()) return;

        if (fakeLook.get()) fakeLook();

        if (mc.player.isCreative() || mc.player.isSpectator()) {
            if (mc.player.isCreative() && !warnedCreative) {
                warnedCreative = true;
                warning("Creative mode breaks blocks instantly on dig packets, revealing is disabled.");
            }
            return;
        }

        if (mc.interactionManager.isBreakingBlock() || BlockUtils.breaking) return;

        if (pauseWhenMoving.get() && isMoving()) return;
        if (pauseNearPlayers.get() && playerNearby()) return;
        if (!punchSlot()) return;

        boolean punched = false;

        switch (revealMode.get()) {
            case Safe -> punched = trySafePunch();
            case Aggressive -> {}
        }

        if (!punched && cascade.get()) punched = tryCascadePunch();
        if (!punched && chunkSweep.get()) punched = trySweepPunch();
        if (!punched && revealMode.get() == RevealMode.Aggressive) tryAggressivePunch();
    }

    private boolean isMoving() {
        Vec3d v = mc.player.getVelocity();
        return Math.sqrt(v.x * v.x + v.z * v.z) > 0.1;
    }

    private boolean playerNearby() {
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player && player.squaredDistanceTo(mc.player) < 64) return true;
        }
        return false;
    }

    private boolean punchSlot() {
        long now = System.currentTimeMillis();

        if (now - secondStart >= 1000) {
            secondStart = now;
            punchesThisSecond = 0;
        }

        if (punchesThisSecond >= maxPunchesPerSecond.get()) return false;
        if (now < nextPunchTime) return false;

        punchesThisSecond++;
        nextPunchTime = now + (long) (Math.max(delay.get() * 50.0, 1000.0 / maxPunchesPerSecond.get()) * (0.8 + Math.random() * 0.4));
        return true;
    }

    private boolean trySafePunch() {
        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) return false;

        BlockPos pos = bhr.getBlockPos();
        if (!canReveal(pos)) return false;

        punch(pos, bhr.getSide());
        return true;
    }

    private boolean tryCascadePunch() {
        Vec3d eye = mc.player.getEyePos();
        double bestDist = Double.MAX_VALUE;
        BlockPos best = null;
        Direction bestSide = null;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    pos.set(mc.player.getBlockX() + dx, mc.player.getBlockY() + dy, mc.player.getBlockZ() + dz);

                    if (eye.distanceTo(pos.toCenterPos()) > 4.5) continue;
                    if (!XrayMemory.isRemembered(pos)) continue;
                    if (mc.world.getBlockState(pos).isAir()) continue;
                    if (pos.getY() > maxY.get()) continue;

                    long now = System.currentTimeMillis();
                    if (now - lastCascade.getOrDefault(pos.asLong(), 0) < cascadeCooldown.get() * 1000L) continue;

                    double dist = eye.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ());
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos.toImmutable();
                        bestSide = BlockUtils.getDirection(best);
                    }
                }
            }
        }

        if (best == null) return false;

        lastCascade.put(best.asLong(), System.currentTimeMillis());
        punch(best, bestSide);
        return true;
    }

    private boolean trySweepPunch() {
        Vec3d eye = mc.player.getEyePos();
        double bestDist = Double.MAX_VALUE;
        BlockPos best = null;
        Direction bestSide = null;
        ChunkPos bestChunk = null;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        long now = System.currentTimeMillis();

        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                ChunkPos chunkPos = new ChunkPos(mc.player.getChunkPos().x + cx, mc.player.getChunkPos().z + cz);
                if (!XrayNoiseFilter.isSuspect(chunkPos)) continue;
                if (now - lastSweep.getOrDefault(chunkPos.toLong(), 0) < 30000) continue;

                for (int dx = -4; dx <= 4; dx++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dz = -4; dz <= 4; dz++) {
                            pos.set(mc.player.getBlockX() + dx, mc.player.getBlockY() + dy, mc.player.getBlockZ() + dz);

                            if (pos.getX() >> 4 != chunkPos.x || pos.getZ() >> 4 != chunkPos.z) continue;
                            if (eye.distanceTo(pos.toCenterPos()) > 4.5) continue;
                            if (!canReveal(pos)) continue;

                            double dist = eye.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ());
                            if (dist < bestDist) {
                                bestDist = dist;
                                best = pos.toImmutable();
                                bestSide = BlockUtils.getDirection(best);
                                bestChunk = chunkPos;
                            }
                        }
                    }
                }
            }
        }

        if (best == null || bestChunk == null) return false;

        lastSweep.put(bestChunk.toLong(), now);
        punch(best, bestSide);
        return true;
    }

    private void tryAggressivePunch() {
        Vec3d eye = mc.player.getEyePos();
        double bestDist = Double.MAX_VALUE;
        BlockPos best = null;
        Direction bestSide = null;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    pos.set(mc.player.getBlockX() + dx, mc.player.getBlockY() + dy, mc.player.getBlockZ() + dz);

                    if (eye.distanceTo(pos.toCenterPos()) > 4.5) continue;
                    if (!canReveal(pos)) continue;
                    if (!hasLineOfSight(pos)) continue;

                    double dist = eye.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ());
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos.toImmutable();
                        bestSide = BlockUtils.getDirection(best);
                    }
                }
            }
        }

        if (best != null) punch(best, bestSide);
    }

    private void fakeLook() {
        fakeLookIndex++;
        double yaw = Math.toRadians(mc.player.getYaw() + fakeLookIndex * 30.0);
        double pitch = Math.toRadians(-15);

        Vec3d dir = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        BlockPos.Mutable pos = new BlockPos.Mutable();
        Vec3d start = mc.player.getEyePos();
        BlockPos target = null;

        for (int i = 1; i <= fakeLookDistance.get(); i++) {
            Vec3d point = start.add(dir.x * i, dir.y * i, dir.z * i);
            pos.set(point.x, point.y, point.z);

            if (!mc.world.isInBuildLimit(pos)) break;

            if (!mc.world.getBlockState(pos).isAir()) {
                target = pos.toImmutable();
                break;
            }
        }

        if (target == null) return;

        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround((float) Rotations.getYaw(target), (float) Rotations.getPitch(target), mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    private boolean canReveal(BlockPos pos) {
        if (mc.world.isOutOfHeightLimit(pos)) return false;
        if (pos.getY() > maxY.get()) return false;
        if (!mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir() || state.getHardness(mc.world, pos) == -1) return false;
        if (Modules.get().get(Xray.class).isWhitelisted(state.getBlock())) return false;

        long now = System.currentTimeMillis();
        long last = lastPunched.getOrDefault(pos.asLong(), 0);
        if (now - last < 2000) return false;

        return true;
    }

    private boolean hasLineOfSight(BlockPos pos) {
        BlockHitResult hit = mc.world.raycast(new RaycastContext(mc.player.getEyePos(), pos.toCenterPos(), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    private void punch(BlockPos pos, Direction side) {
        lastPunched.put(pos.asLong(), System.currentTimeMillis());

        mc.interactionManager.sendSequencedPacket(mc.world, sequence -> new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, side == null ? Direction.UP : side, sequence));
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, pos, side == null ? Direction.UP : side));
    }
}