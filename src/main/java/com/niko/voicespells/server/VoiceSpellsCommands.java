package com.niko.voicespells.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.niko.voicespells.spells.SpellCaster;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
//? if forge {
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
*///?} else {
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
//?}

import java.util.List;

/**
 * Server-side /voicespells admin commands.
 *
 *  - {@code /voicespells diag} prints the most recent voice-triggered casts on this server,
 *    one line per cast, newest first. Requires operator permission level 2.
 *  - {@code /voicespells rank} prints the current server config snapshot.
 *
 * Registered manually on {@link NeoForge#EVENT_BUS} from {@link com.niko.voicespells.VoiceSpells}'
 * constructor (the @EventBusSubscriber bus enum is deprecated in NeoForge 1.21).
 */
public final class VoiceSpellsCommands {
    private VoiceSpellsCommands() {}

    /** Wire this up from the mod's constructor — see VoiceSpells.java. */
    public static void register() {
//? if forge {
/*        MinecraftForge.EVENT_BUS.addListener(VoiceSpellsCommands::onRegisterCommands);
*///?} else {
        NeoForge.EVENT_BUS.addListener(VoiceSpellsCommands::onRegisterCommands);
//?}
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("voicespells")
            .requires(src -> src.hasPermission(2));

        root.then(Commands.literal("diag").executes(ctx -> {
            CommandSourceStack src = ctx.getSource();
            List<String> recent = SpellCaster.recentLog();
            if (recent.isEmpty()) {
                src.sendSuccess(() -> Component.literal("No voice casts logged this session.")
                    .withStyle(ChatFormatting.GRAY), false);
            } else {
                src.sendSuccess(() -> Component.literal(
                    "Recent voice casts (" + recent.size() + ", newest first):")
                    .withStyle(ChatFormatting.AQUA), false);
                int limit = Math.min(10, recent.size());
                for (int i = 0; i < limit; i++) {
                    String ln = recent.get(i);
                    src.sendSuccess(() -> Component.literal("  " + ln)
                        .withStyle(ChatFormatting.GRAY), false);
                }
            }
            return 1;
        }));

        root.then(Commands.literal("follow").executes(ctx -> {
            CommandSourceStack src = ctx.getSource();
            net.minecraft.server.level.ServerPlayer self = src.getPlayer();
            if (self == null) {
                src.sendFailure(Component.literal("Run this command as a player."));
                return 0;
            }
            boolean nowOn = SpellCaster.toggleSubscriber(self.getUUID());
            src.sendSuccess(() -> Component.literal(
                nowOn ? "Subscribed to voice-cast events (any distance)."
                      : "Unsubscribed from voice-cast events.")
                .withStyle(nowOn ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
            return 1;
        }));

        root.then(Commands.literal("top").executes(ctx -> {
            CommandSourceStack src = ctx.getSource();
            List<java.util.Map.Entry<String, Integer>> top = SpellCaster.topPlayers(10);
            if (top.isEmpty()) {
                src.sendSuccess(() -> Component.literal("No voice-cast totals recorded yet.")
                    .withStyle(ChatFormatting.GRAY), false);
            } else {
                src.sendSuccess(() -> Component.literal("Top voice casters:")
                    .withStyle(ChatFormatting.AQUA), false);
                int i = 1;
                for (var e : top) {
                    final int rank = i++;
                    src.sendSuccess(() -> Component.literal(
                        "  " + rank + ". " + e.getKey() + " — " + e.getValue() + " casts")
                        .withStyle(ChatFormatting.GRAY), false);
                }
            }
            return 1;
        }));

        root.then(Commands.literal("reload").executes(ctx -> {
            // NeoForge's ConfigTracker watches the toml file and auto-reloads on disk change.
            // Print a confirmation so the admin knows the file watcher is active; we re-read
            // the live values defensively to surface anything that might have been edited
            // since the last reload event.
            int max = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.maxCastsPerSecond.get();
            String mode = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.castMode.get().name();
            ctx.getSource().sendSuccess(() -> Component.literal(
                "Server config is auto-reloaded by the file watcher when the toml changes.\n"
                + "  current castMode=" + mode + ", maxCastsPerSecond=" + max)
                .withStyle(ChatFormatting.AQUA), false);
            return 1;
        }));

        root.then(Commands.literal("rank").executes(ctx -> {
            // Tiny convenience — print the current per-process rate-limit setting so admins
            // can confirm their server config is loaded.
            int max = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.maxCastsPerSecond.get();
            String mode = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.castMode.get().name();
            ctx.getSource().sendSuccess(() -> Component.literal(
                "VoiceSpells server config — castMode=" + mode + ", maxCastsPerSecond=" + max)
                .withStyle(ChatFormatting.AQUA), false);
            return 1;
        }));

        dispatcher.register(root);
    }
}
