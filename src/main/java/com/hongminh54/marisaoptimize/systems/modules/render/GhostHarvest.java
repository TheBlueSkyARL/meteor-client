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
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

public class GhostHarvest extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> distance = sgGeneral.add(new IntSetting.Builder()
        .name("distance")
        .description("How far away from the real position fake positions are sent. Must stay inside your render distance.")
        .defaultValue(32)
        .range(16, 64)
        .sliderMax(64)
        .build()
    );

    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
        .name("interval")
        .description("How often a fake position is sent, in seconds.")
        .defaultValue(5)
        .range(1, 30)
        .sliderMax(30)
        .build()
    );

    private final Setting<Integer> restoreDelay = sgGeneral.add(new IntSetting.Builder()
        .name("restore-delay")
        .description("Ticks to wait before sending the real position back.")
        .defaultValue(2)
        .range(1, 10)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> pauseWhenMoving = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-when-moving")
        .description("Only fakes positions while standing still.")
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
        .description("Automatically stops faking positions on servers that reveal blocks by look direction (detected via re-hide block updates). Position faking is useless there.")
        .defaultValue(true)
        .build()
    );

    private int tickCounter;
    private int harvestIndex;
    private boolean awaitingRestore;
    private int restoreTicks;

    public GhostHarvest() {
        super(Categories.Render, "ghost-harvest", "Fakes your position to trick proximity-based anti-xray plugins (Orebfuscator, GhostAntiXray) into sending real chunk data for distant chunks, which is then stored in the xray memory. Does nothing on Paper anti-xray servers. Experimental: movement checks may flag or kick you.");
    }

    @Override
    public void onActivate() {
        warning("Experimental - can trigger anti-cheat movement checks. Use at your own risk.");

        tickCounter = 0;
        harvestIndex = 0;
        awaitingRestore = false;
        restoreTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null || mc.getNetworkHandler() == null) return;

        if (awaitingRestore) {
            if (++restoreTicks >= restoreDelay.get()) {
                mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.isOnGround(), mc.player.horizontalCollision));
                awaitingRestore = false;
            }
            return;
        }

        if (requireXray.get() && !Modules.get().get(Xray.class).isActive()) return;
        if (adaptive.get() && AntiXrayServerType.isLookRevealServer()) return;

        if (pauseWhenMoving.get()) {
            Vec3d v = mc.player.getVelocity();
            if (Math.sqrt(v.x * v.x + v.z * v.z) > 0.1) return;
        }

        if (++tickCounter < interval.get() * 20) return;
        tickCounter = 0;

        harvestIndex++;
        double rad = Math.toRadians(harvestIndex * 45.0);
        double x = mc.player.getX() + Math.sin(rad) * distance.get();
        double z = mc.player.getZ() + Math.cos(rad) * distance.get();
        double y = Math.max(mc.world.getBottomY() + 2, Math.min(mc.world.getBottomY() + mc.world.getDimension().height() - 2, mc.player.getY()));

        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, mc.player.isOnGround(), mc.player.horizontalCollision));
        awaitingRestore = true;
        restoreTicks = 0;
    }
}