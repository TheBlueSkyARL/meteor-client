/*
 * This file is part of the Marisa Optimize distribution (https://github.com/TheBlueSkyARL/marisa-optimize).
 * Copyright (c) Meteor Development.
 */

package com.hongminh54.marisaoptimize.utils.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Predicate;

import static com.hongminh54.marisaoptimize.MarisaOptimize.mc;

/**
 * Detects servers that re-hide revealed blocks (look-based raytraced anti-xray
 * plugins like Imanity AntiXray). Such servers reveal blocks by look direction
 * themselves, so punching blocks or faking positions is useless and can even
 * make the plugin stop revealing them. Modules can adapt by checking
 * {@link #isLookRevealServer()}.
 *
 * Detection: block updates that replace a remembered ore with a non-whitelisted
 * state. Air replacements within reach are ignored (that is the player mining),
 * air replacements beyond reach are counted (only a re-hide can produce those,
 * such servers always send real data within a small trusted range).
 */
public class AntiXrayServerType {
    private static final int THRESHOLD = 3;
    private static final long WINDOW_MS = 30_000;
    private static final int MAX_TRACKED = 8;
    private static final double TRUSTED_RANGE = 5.0;

    private static boolean lookRevealServer;
    private static int events;
    private static long windowStart;
    private static final Deque<Long> recentPositions = new ArrayDeque<>();

    private AntiXrayServerType() {
    }

    public static void onBlockUpdate(BlockPos pos, BlockState newState, Predicate<Block> isWhitelisted) {
        if (!XrayMemory.isRemembered(pos)) return;
        if (isWhitelisted.test(newState.getBlock())) return;

        if (newState.isAir()) {
            if (mc.player == null) return;
            double dx = pos.getX() + 0.5 - mc.player.getX();
            double dy = pos.getY() + 0.5 - mc.player.getY();
            double dz = pos.getZ() + 0.5 - mc.player.getZ();
            if (dx * dx + dy * dy + dz * dz < TRUSTED_RANGE * TRUSTED_RANGE) return;
        }

        long now = System.currentTimeMillis();
        if (now - windowStart > WINDOW_MS) {
            windowStart = now;
            events = 0;
            recentPositions.clear();
        }

        long key = pos.asLong();
        if (recentPositions.contains(key)) return;
        recentPositions.addLast(key);
        if (recentPositions.size() > MAX_TRACKED) recentPositions.removeFirst();

        if (++events >= THRESHOLD) lookRevealServer = true;
    }

    public static boolean isLookRevealServer() {
        return lookRevealServer;
    }

    public static void reset() {
        lookRevealServer = false;
        events = 0;
        windowStart = 0;
        recentPositions.clear();
    }
}