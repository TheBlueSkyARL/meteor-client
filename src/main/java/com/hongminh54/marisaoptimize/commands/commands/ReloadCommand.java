/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package com.hongminh54.marisaoptimize.commands.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.hongminh54.marisaoptimize.commands.Command;
import com.hongminh54.marisaoptimize.renderer.Fonts;
import com.hongminh54.marisaoptimize.systems.Systems;
import com.hongminh54.marisaoptimize.systems.friends.Friend;
import com.hongminh54.marisaoptimize.systems.friends.Friends;
import com.hongminh54.marisaoptimize.utils.network.Capes;
import com.hongminh54.marisaoptimize.utils.network.MeteorExecutor;
import net.minecraft.command.CommandSource;

public class ReloadCommand extends Command {
    public ReloadCommand() {
        super("reload", "Reloads many systems.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            warning("Reloading systems, this may take a while.");

            Systems.load();
            Capes.init();
            Fonts.refresh();
            MeteorExecutor.execute(() -> Friends.get().forEach(Friend::updateInfo));

            return SINGLE_SUCCESS;
        });
    }
}
