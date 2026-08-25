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
 * All of these require operator permission level 2.
 *
 *  - {@code /voicespells diag} prints the live server config plus the most recent
 *    voice-triggered casts on this server, one line per cast, newest first.
 *  - {@code /voicespells follow} toggles a subscription to voice-cast events at any distance.
 *  - {@code /voicespells top} prints the voice-cast leaderboard.
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
            // Live server settings. This used to be two separate commands — "rank", which
            // printed no rank of any kind, and "reload", which reloaded nothing (the config
            // is reloaded by NeoForge's file watcher; the command only announced that fact).
            // Both printed exactly these two values, and "reload" additionally collided with
            // the client-side /voicespells reload, which does perform a real reload. Folded
            // into diag, which is where an admin looks for server state anyway.
            int max = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.maxCastsPerSecond.get();
            String mode = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.castMode.get().name();
            src.sendSuccess(() -> Component.translatable("voicespells.command.server_config", mode, max)
                .withStyle(ChatFormatting.AQUA), false);
            src.sendSuccess(() -> Component.literal("  ").append(Component.translatable("voicespells.command.server_config_hint"))
                .withStyle(ChatFormatting.DARK_GRAY), false);

            // Who the incantation rule actually applies to.
            //
            // There was no way to see this, and the rule is invisible from inside the game when it
            // is NOT applying to someone - a player just casts normally and the host assumes the
            // setting works. That is exactly how the old "has sent us a cast packet" test went
            // unnoticed. One line per online player, saying whether the mod is present on their
            // connection and therefore whether the rule constrains them.
            String rule = com.niko.voicespells.VoiceSpellsServerConfig.SERVER.incantationOnly.get().name();
            src.sendSuccess(() -> Component.translatable("voicespells.command.incantation_rule", rule)
                .withStyle(ChatFormatting.AQUA), false);
            if (!"OFF".equals(rule)) {
                for (net.minecraft.server.level.ServerPlayer p : src.getServer().getPlayerList().getPlayers()) {
                    boolean hasMod  = SpellCaster.hasVoiceClient(p.getUUID());
                    boolean allowed = SpellCaster.voiceAllowedFor(p);
                    boolean bound   = hasMod && allowed;
                    String who = p.getName().getString();
                    String why = bound ? "voicespells.command.rule_constrained"
                        : !hasMod ? "voicespells.command.rule_exempt_nomod"
                                  : "voicespells.command.rule_exempt_excluded";
                    src.sendSuccess(() -> Component.literal("  " + who + ": ")
                        .append(Component.translatable(why))
                        .withStyle(bound ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
                }
            }

            List<String> recent = SpellCaster.recentLog();
            if (recent.isEmpty()) {
                src.sendSuccess(() -> Component.translatable("voicespells.command.no_casts_logged")
                    .withStyle(ChatFormatting.GRAY), false);
            } else {
                src.sendSuccess(() -> Component.translatable("voicespells.command.recent_casts", recent.size())
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
                src.sendFailure(Component.translatable("voicespells.command.player_only"));
                return 0;
            }
            boolean nowOn = SpellCaster.toggleSubscriber(self.getUUID());
            src.sendSuccess(() -> Component.translatable(nowOn ? "voicespells.command.follow_on" : "voicespells.command.follow_off")
                .withStyle(nowOn ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
            return 1;
        }));

        root.then(Commands.literal("top").executes(ctx -> {
            CommandSourceStack src = ctx.getSource();
            List<java.util.Map.Entry<String, Integer>> top = SpellCaster.topPlayers(10);
            if (top.isEmpty()) {
                src.sendSuccess(() -> Component.translatable("voicespells.command.no_totals")
                    .withStyle(ChatFormatting.GRAY), false);
            } else {
                src.sendSuccess(() -> Component.translatable("voicespells.command.top_casters")
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

        dispatcher.register(root);
    }
}
