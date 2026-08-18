/*
 * This file is part of the Marisa Optimize distribution (https://github.com/TheBlueSkyARL/marisa-optimize).
 * Copyright (c) Meteor Development.
 */

package com.hongminh54.marisaoptimize.systems.modules.render;

import com.hongminh54.marisaoptimize.MixinPlugin;
import com.hongminh54.marisaoptimize.events.game.GameLeftEvent;
import com.hongminh54.marisaoptimize.events.packets.PacketEvent;
import com.hongminh54.marisaoptimize.events.render.RenderBlockEntityEvent;
import com.hongminh54.marisaoptimize.events.world.AmbientOcclusionEvent;
import com.hongminh54.marisaoptimize.events.world.BlockUpdateEvent;
import com.hongminh54.marisaoptimize.events.world.ChunkDataEvent;
import com.hongminh54.marisaoptimize.events.world.ChunkOcclusionEvent;
import com.hongminh54.marisaoptimize.events.world.TickEvent;
import com.hongminh54.marisaoptimize.gui.GuiTheme;
import com.hongminh54.marisaoptimize.gui.widgets.WWidget;
import com.hongminh54.marisaoptimize.systems.modules.Categories;
import com.hongminh54.marisaoptimize.systems.modules.Module;
import com.hongminh54.marisaoptimize.systems.modules.Modules;
import com.hongminh54.marisaoptimize.utils.world.AntiXrayServerType;
import com.hongminh54.marisaoptimize.utils.world.BlockUtils;
import com.hongminh54.marisaoptimize.utils.world.XrayMemory;
import com.hongminh54.marisaoptimize.utils.world.XrayNoiseFilter;
import meteordevelopment.orbit.EventHandler;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

import java.util.List;
import com.hongminh54.marisaoptimize.settings.*;

public class Xray extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public static final List<Block> ORES = List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS);

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("whitelist")
        .description("Which blocks to show x-rayed.")
        .defaultValue(ORES)
        .onChanged(v -> {
            XrayNoiseFilter.setWhitelist(this::isWhitelisted);
            XrayNoiseFilter.clearAll();
            if (isActive()) mc.worldRenderer.reload();
        })
        .build()
    );

    public final Setting<Integer> opacity = sgGeneral.add(new IntSetting.Builder()
        .name("opacity")
        .description("The opacity for all other blocks.")
        .defaultValue(25)
        .range(0, 255)
        .sliderMax(255)
        .onChanged(onChanged -> {
            if (isActive()) mc.worldRenderer.reload();
        })
        .build()
    );

    private final Setting<Boolean> exposedOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("exposed-only")
        .description("Show only exposed ores.")
        .defaultValue(false)
        .onChanged(onChanged -> {
            if (isActive()) mc.worldRenderer.reload();
        })
        .build());

    private final Setting<Integer> noiseOpacity = sgGeneral.add(new IntSetting.Builder()
        .name("noise-opacity")
        .description("Opacity for whitelisted blocks that were only seen in chunk data (suspected anti-xray noise) in chunks where real ores were revealed by block updates. -1 disables the filter.")
        .defaultValue(-1)
        .range(-1, 255)
        .sliderMax(255)
        .onChanged(onChanged -> {
            if (isActive()) mc.worldRenderer.reload();
        })
        .build());

    private final Setting<Boolean> clearOnUnload = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-on-unload")
        .description("Clears remembered blocks when a chunk unloads. Disabled by default so blocks are restored when the chunk is reloaded (servers re-obfuscate chunk data). Memory is capped at 512 chunks.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> autoNoiseFilter = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-noise-filter")
        .description("Analyzes chunk data to detect anti-xray noise: chunks with an unrealistic amount of whitelisted blocks are treated as fully obfuscated and only blocks remembered through block updates are shown. Also captures trusted chunk data into memory.")
        .defaultValue(true)
        .onChanged(onChanged -> {
            if (isActive()) mc.worldRenderer.reload();
        })
        .build());

    private final Setting<Integer> densityThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("density-threshold")
        .description("Maximum amount of whitelisted blocks per chunk to be considered real world generation. Above this the chunk is treated as fully obfuscated.")
        .defaultValue(64)
        .range(8, 512)
        .sliderMax(256)
        .onChanged(onChanged -> {
            XrayNoiseFilter.setDensityThreshold(onChanged);
            if (isActive()) mc.worldRenderer.reload();
        })
        .build());

    private final Setting<Boolean> hideNoise = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-noise")
        .description("Completely hides suspected noise blocks instead of dimming them with noise-opacity.")
        .defaultValue(true)
        .onChanged(onChanged -> {
            if (isActive()) mc.worldRenderer.reload();
        })
        .build());

    private int reloadTimer;
    private boolean reloadQueued;

    public Xray() {
        super(Categories.Render, "xray", "Only renders specified blocks. Good for mining.");
    }

    @Override
    public void onActivate() {
        reloadQueued = false;
        XrayNoiseFilter.setWhitelist(block -> blocks.get().contains(block));
        XrayNoiseFilter.setDensityThreshold(densityThreshold.get());
        XrayMemory.applyAllLoaded();
        mc.worldRenderer.reload();
    }

    @Override
    public void onDeactivate() {
        mc.worldRenderer.reload();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        if (MixinPlugin.isIrisPresent && IrisApi.getInstance().isShaderPackInUse()) return theme.label("Warning: Due to shaders in use, opacity is overridden to 0.");

        return null;
    }

    @EventHandler
    private void onRenderBlockEntity(RenderBlockEntityEvent event) {
        if (isBlocked(event.blockEntityState.blockState.getBlock(), event.blockEntityState.pos)) event.cancel();
    }

    @EventHandler
    private void onChunkOcclusion(ChunkOcclusionEvent event) {
        event.cancel();
    }

    @EventHandler
    private void onAmbientOcclusion(AmbientOcclusionEvent event) {
        event.lightLevel = 1;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        XrayMemory.capture(event.pos, event.newState, block -> blocks.get().contains(block));
        AntiXrayServerType.onBlockUpdate(event.pos, event.newState, block -> blocks.get().contains(block));
        XrayNoiseFilter.invalidate(event.pos);
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (XrayMemory.applyToChunk(event.chunk()) > 0) reloadQueued = true;

        if (autoNoiseFilter.get()) {
            XrayNoiseFilter.invalidateChunk(event.chunk().getPos());
            XrayNoiseFilter.analyze(event.chunk());

            if (!XrayNoiseFilter.isSuspect(event.chunk().getPos()) && XrayMemory.captureChunk(event.chunk(), block -> blocks.get().contains(block)) > 0) {
                reloadQueued = true;
            }
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        XrayMemory.clearAll();
        XrayNoiseFilter.clearAll();
        AntiXrayServerType.reset();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof UnloadChunkS2CPacket packet && clearOnUnload.get()) XrayMemory.clearChunk(packet.pos());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (reloadQueued && ++reloadTimer >= 5) {
            reloadTimer = 0;
            reloadQueued = false;
            mc.worldRenderer.reload();
        }
    }

    public boolean modifyDrawSide(BlockState state, BlockView view, BlockPos pos, Direction facing, boolean returns) {
        if (!returns && !isBlocked(state.getBlock(), pos)) {
            BlockPos adjPos = pos.offset(facing);
            BlockState adjState = view.getBlockState(adjPos);
            return adjState.getCullingFace(facing.getOpposite()) != VoxelShapes.fullCube() || adjState.getBlock() != state.getBlock() || !adjState.isOpaqueFullCube() || isBlocked(adjState.getBlock(), adjPos);
        }

        return returns;
    }

    public boolean isBlocked(Block block, BlockPos blockPos) {
        return !(blocks.get().contains(block) && (!exposedOnly.get() || (blockPos == null || BlockUtils.isExposed(blockPos))));
    }

    public boolean isWhitelisted(Block block) {
        return blocks.get().contains(block);
    }

    public static int getAlpha(BlockState state, BlockPos pos) {
        WallHack wallHack = Modules.get().get(WallHack.class);
        Xray xray = Modules.get().get(Xray.class);

        if (wallHack.isActive() && wallHack.blocks.get().contains(state.getBlock())) {
            if (MixinPlugin.isIrisPresent && IrisApi.getInstance().isShaderPackInUse()) return 0;

            int alpha;

            if (xray.isActive()) alpha = xray.opacity.get();
            else alpha = wallHack.opacity.get();

            return alpha;
        }
        else if (xray.isActive() && !wallHack.isActive()) {
            if (xray.isBlocked(state.getBlock(), pos)) {
                return ((MixinPlugin.isIrisPresent && IrisApi.getInstance().isShaderPackInUse())) ? 0 : xray.opacity.get();
            }

            // Whitelisted block: remembered ores always render, suspected anti-xray noise can be hidden or dimmed
            if (pos != null) {
                if (xray.autoNoiseFilter.get() && XrayNoiseFilter.isLikelyNoise(pos, state)) {
                    return xray.hideNoise.get() ? 0 : xray.noiseOpacity.get();
                }

                if (xray.noiseOpacity.get() != -1 && !XrayMemory.isRemembered(pos) && XrayMemory.chunkHasMemory(pos)) {
                    return xray.noiseOpacity.get();
                }
            }
        }

        return -1;
    }
}