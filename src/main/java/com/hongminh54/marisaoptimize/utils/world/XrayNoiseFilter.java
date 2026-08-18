/*
 * This file is part of the Marisa Optimize distribution (https://github.com/TheBlueSkyARL/marisa-optimize).
 * Copyright (c) Meteor Development.
 */

package com.hongminh54.marisaoptimize.utils.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import static com.hongminh54.marisaoptimize.MarisaOptimize.mc;

/**
 * Detects anti-xray noise in chunk data.
 * <p>
 * Paper's anti-xray engine 2/3 replaces almost every block in the obfuscate list
 * with random blocks from the same list, so obfuscated chunks contain an
 * unrealistic amount of whitelisted blocks. Real world generation stays far
 * below the density threshold. Such chunks are treated as fully obfuscated and
 * only blocks remembered through block updates are rendered.
 */
public class XrayNoiseFilter {
    private record ChunkInfo(int density, boolean suspect) {}

    private static final Long2ObjectOpenHashMap<ChunkInfo> chunks = new Long2ObjectOpenHashMap<>();
    private static Predicate<Block> whitelist;
    private static int densityThreshold = 64;

    private static final Map<Block, int[]> Y_RANGES = new HashMap<>();

    static {
        Y_RANGES.put(Blocks.DIAMOND_ORE, new int[]{-64, 16});
        Y_RANGES.put(Blocks.DEEPSLATE_DIAMOND_ORE, new int[]{-64, 0});
        Y_RANGES.put(Blocks.IRON_ORE, new int[]{-64, 72});
        Y_RANGES.put(Blocks.DEEPSLATE_IRON_ORE, new int[]{-64, 16});
        Y_RANGES.put(Blocks.GOLD_ORE, new int[]{-64, 256});
        Y_RANGES.put(Blocks.DEEPSLATE_GOLD_ORE, new int[]{-64, 0});
        Y_RANGES.put(Blocks.LAPIS_ORE, new int[]{-64, 64});
        Y_RANGES.put(Blocks.DEEPSLATE_LAPIS_ORE, new int[]{-64, 0});
        Y_RANGES.put(Blocks.REDSTONE_ORE, new int[]{-64, 16});
        Y_RANGES.put(Blocks.DEEPSLATE_REDSTONE_ORE, new int[]{-64, 0});
        Y_RANGES.put(Blocks.EMERALD_ORE, new int[]{-64, 320});
        Y_RANGES.put(Blocks.DEEPSLATE_EMERALD_ORE, new int[]{-64, 0});
        Y_RANGES.put(Blocks.COPPER_ORE, new int[]{-64, 96});
        Y_RANGES.put(Blocks.DEEPSLATE_COPPER_ORE, new int[]{-64, 0});
        Y_RANGES.put(Blocks.NETHER_GOLD_ORE, new int[]{0, 117});
        Y_RANGES.put(Blocks.NETHER_QUARTZ_ORE, new int[]{10, 120});
        Y_RANGES.put(Blocks.ANCIENT_DEBRIS, new int[]{0, 22});
    }

    private XrayNoiseFilter() {
    }

    public static void setWhitelist(Predicate<Block> predicate) {
        whitelist = predicate;
    }

    public static void setDensityThreshold(int threshold) {
        densityThreshold = threshold;
        clearAll();
    }

    public static void invalidate(BlockPos pos) {
        chunks.remove(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    public static void invalidateChunk(ChunkPos chunkPos) {
        chunks.remove(chunkPos.toLong());
    }

    public static void clearAll() {
        chunks.clear();
    }

    public static boolean isSuspect(ChunkPos chunkPos) {
        long key = chunkPos.toLong();
        if (!chunks.containsKey(key) && mc.world != null) analyze(mc.world.getChunk(chunkPos.x, chunkPos.z));

        ChunkInfo info = chunks.get(key);
        return info != null && info.suspect();
    }

    /**
     * Returns true if the block is likely anti-xray noise and should be hidden.
     */
    public static boolean isLikelyNoise(BlockPos pos, BlockState state) {
        if (whitelist == null || !whitelist.test(state.getBlock())) return false;
        if (XrayMemory.isRemembered(pos)) return false;

        long key = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        ChunkInfo info = chunks.get(key);

        if (info == null) {
            if (mc.world == null) return false;
            analyze(mc.world.getChunk(pos.getX() >> 4, pos.getZ() >> 4));
            info = chunks.get(key);
            if (info == null) return false;
        }

        if (info.suspect()) return true;

        int[] range = Y_RANGES.get(state.getBlock());
        return range != null && (pos.getY() < range[0] || pos.getY() > range[1]);
    }

    /**
     * Analyzes a chunk: counts whitelisted blocks and classifies it as
     * obfuscated (suspect) when the density is unrealistic.
     */
    public static void analyze(WorldChunk chunk) {
        if (chunk == null || whitelist == null || mc.world == null) return;

        long key = chunk.getPos().toLong();
        if (chunks.containsKey(key)) return;

        int count = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        int bottomY = mc.world.getBottomY();
        int topY = bottomY + mc.world.getDimension().height();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = bottomY; y < topY; y++) {
                    pos.set(startX + x, y, startZ + z);
                    if (whitelist.test(chunk.getBlockState(pos).getBlock())) count++;
                }
            }
        }

        chunks.put(key, new ChunkInfo(count, count > densityThreshold));
    }
}