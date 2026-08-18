/*
 * This file is part of the Marisa Optimize distribution (https://github.com/TheBlueSkyARL/marisa-optimize).
 * Copyright (c) Meteor Development.
 */

package com.hongminh54.marisaoptimize.utils.world;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

import static com.hongminh54.marisaoptimize.MarisaOptimize.mc;

/**
 * Grid-traversal raycast (Amanatides & Woo) used to find the first block along
 * the player's look direction that stops the view, mirroring how server-side
 * raytraced anti-xray plugins decide which hidden blocks to reveal.
 */
public class LookRaycaster {
    private static final double EPS = 1.0E-7;

    private LookRaycaster() {
    }

    /**
     * Steps along the ray and returns the first cell for which {@code isOpaque}
     * returns true. Returns null if no such cell is found within {@code reach}
     * blocks or if the ray leaves the world.
     */
    public static BlockPos firstOpaque(Vec3d origin, Vec3d direction, double reach, Predicate<BlockPos> isOpaque) {
        if (mc.world == null || direction.lengthSquared() < EPS) return null;

        int x = floor(origin.x);
        int y = floor(origin.y);
        int z = floor(origin.z);

        int stepX = direction.x > 0 ? 1 : -1;
        int stepY = direction.y > 0 ? 1 : -1;
        int stepZ = direction.z > 0 ? 1 : -1;

        double tDeltaX = Math.abs(direction.x) < EPS ? Double.MAX_VALUE : Math.abs(1.0 / direction.x);
        double tDeltaY = Math.abs(direction.y) < EPS ? Double.MAX_VALUE : Math.abs(1.0 / direction.y);
        double tDeltaZ = Math.abs(direction.z) < EPS ? Double.MAX_VALUE : Math.abs(1.0 / direction.z);

        double tMaxX = tDeltaX == Double.MAX_VALUE ? Double.MAX_VALUE : tDeltaX * (direction.x > 0 ? x + 1 - origin.x : origin.x - x);
        double tMaxY = tDeltaY == Double.MAX_VALUE ? Double.MAX_VALUE : tDeltaY * (direction.y > 0 ? y + 1 - origin.y : origin.y - y);
        double tMaxZ = tDeltaZ == Double.MAX_VALUE ? Double.MAX_VALUE : tDeltaZ * (direction.z > 0 ? z + 1 - origin.z : origin.z - z);

        BlockPos.Mutable pos = new BlockPos.Mutable();
        int maxSteps = (int) Math.ceil(reach) + 2;

        for (int i = 0; i < maxSteps; i++) {
            double t;
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            }
            else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            }
            else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }

            if (t > reach) break;
            pos.set(x, y, z);
            if (!mc.world.isInBuildLimit(pos)) continue;
            if (isOpaque.test(pos)) return pos.toImmutable();
        }

        return null;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}