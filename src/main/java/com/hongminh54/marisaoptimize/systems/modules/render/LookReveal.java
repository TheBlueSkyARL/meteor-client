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
import com.hongminh54.marisaoptimize.utils.world.AntiXrayServerType;
import com.hongminh54.marisaoptimize.utils.world.BlockUtils;
import com.hongminh54.marisaoptimize.utils.world.LookRaycaster;
import com.hongminh54.marisaoptimize.utils.world.XrayMemory;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Reveals hidden ores by punching the first remembered block along the player's
 * look ray, mirroring how raytraced anti-xray plugins decide which blocks the
 * player can see. Unlike the crosshair-based safe punch, this finds hidden ores
 * that render as air or a replacement block, so punches always hit a real ore
 * instead of wasting dig packets on surrounding blocks.
 */
public class LookReveal extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> reach = sgGeneral.add(new IntSetting.Builder()
        .name("reach")
        .description("How far along the look direction hidden ores are searched for.")
        .defaultValue(64)
        .range(8, 128)
        .sliderMax(128)
        .build()
    );

    private final Setting<Integer> maxRevealsPerSecond = sgGeneral.add(new IntSetting.Builder()
        .name("max-reveals-per-second")
        .description("Maximum amount of punch packets per second.")
        .defaultValue(3)
        .range(1, 10)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> pauseWhenMoving = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-when-moving")
        .description("Only punches while standing still. Many anti-cheats flag digging while moving.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> requireXray = sgGeneral.add(new BoolSetting.Builder()
        .name("require-xray")
        .description("Only runs while the Xray module is active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> adaptive = sgGeneral.add(new BoolSetting.Builder()
        .name("adaptive")
        .description("Automatically stops punching on servers that reveal blocks by look direction themselves (detected via re-hide block updates).")
        .defaultValue(true)
        .build()
    );

    private int punchesThisSecond;
    private long secondStart;
    private long nextPunchTime;
    private final Long2LongOpenHashMap lastPunched = new Long2LongOpenHashMap();

    public LookReveal() {
        super(Categories.Render, "look-reveal", "Punches the first hidden ore along your look ray to force the server to reveal it, mirroring how raytraced anti-xray plugins decide what you can see. Punches only land on remembered ores, never on surrounding blocks.");
    }

    @Override
    public void onActivate() {
        punchesThisSecond = 0;
        secondStart = 0;
        nextPunchTime = 0;
        lastPunched.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || mc.getNetworkHandler() == null) return;

        if (requireXray.get() && !Modules.get().get(Xray.class).isActive()) return;
        if (adaptive.get() && AntiXrayServerType.isLookRevealServer()) return;

        if (pauseWhenMoving.get()) {
            Vec3d v = mc.player.getVelocity();
            if (Math.sqrt(v.x * v.x + v.z * v.z) > 0.1) return;
        }

        long now = System.currentTimeMillis();
        if (now - secondStart >= 1000) {
            secondStart = now;
            punchesThisSecond = 0;
        }

        if (punchesThisSecond >= maxRevealsPerSecond.get()) return;
        if (now < nextPunchTime) return;

        Vec3d eye = mc.player.getEyePos();
        BlockPos target = LookRaycaster.firstOpaque(eye, mc.player.getRotationVector(), reach.get(), pos -> {
            if (XrayMemory.isRemembered(pos)) return true;
            BlockState state = mc.world.getBlockState(pos);
            return state.isOpaqueFullCube();
        });

        if (target == null) return;
        if (mc.world.isOutOfHeightLimit(target)) return;
        if (!XrayMemory.isRemembered(target)) return;

        BlockState memoryState = XrayMemory.get(target);
        BlockState worldState = mc.world.getBlockState(target);
        if (worldState == memoryState) return;

        if (eye.distanceTo(target.toCenterPos()) > 4.5) return;
        if (now - lastPunched.getOrDefault(target.asLong(), 0) < 2000) return;

        punch(target);

        punchesThisSecond++;
        nextPunchTime = now + (long) ((1000.0 / maxRevealsPerSecond.get()) * (0.8 + Math.random() * 0.4));
    }

    private void punch(BlockPos pos) {
        lastPunched.put(pos.asLong(), System.currentTimeMillis());

        Direction side = BlockUtils.getDirection(pos);
        mc.interactionManager.sendSequencedPacket(mc.world, sequence -> new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, side, sequence));
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, pos, side));
    }
}