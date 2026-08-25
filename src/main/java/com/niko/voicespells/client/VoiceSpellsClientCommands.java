package com.niko.voicespells.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.niko.voicespells.VoiceSpellsConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
//? if forge {
/*import net.minecraftforge.client.event.RegisterClientCommandsEvent;
*///?} else {
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
//?}

import java.util.List;

/**
 * Client-only commands.
 *
 * <p>Separate from {@link com.niko.voicespells.server.VoiceSpellsCommands} because these query
 * client-side state that does not exist on a server — the OpenAL capture device list above all.
 * Registered through {@code RegisterClientCommandsEvent}, so they work in single player and on any
 * server, including one that has never heard of this mod.
 */
public final class VoiceSpellsClientCommands {
    private VoiceSpellsClientCommands() {}

    public static void onRegister(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("voicespells");

        root.then(Commands.literal("devices").executes(ctx -> {
            List<String> devices = MicCapture.listDevices();
            String def = MicCapture.defaultDevice();
            String selected = VoiceSpellsConfig.cCaptureDevice;

            ctx.getSource().sendSuccess(() -> Component
                .translatable("voicespells.command.devices_title")
                .withStyle(ChatFormatting.GOLD), false);

            if (devices.isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component
                    .literal("  ")
                    .append(Component.translatable("voicespells.command.devices_none"))
                    .withStyle(ChatFormatting.RED), false);
            } else {
                for (String d : devices) {
                    // Mark which one is actually in use: the configured name if set, otherwise
                    // whatever OpenAL considers the system default.
                    boolean inUse = selected == null || selected.isBlank()
                        ? d.equals(def)
                        : d.equals(selected.trim());
                    ctx.getSource().sendSuccess(() -> Component
                        .literal(inUse ? "  > " : "    ")
                        .append(Component.literal(d)
                            .withStyle(inUse ? ChatFormatting.GREEN : ChatFormatting.GRAY)), false);
                }
            }

            ctx.getSource().sendSuccess(() -> Component
                .literal("  ")
                .append(Component.translatable("voicespells.command.devices_default"))
                .append(Component.literal(": ")).withStyle(ChatFormatting.DARK_GRAY)
                .append((def.isEmpty()
                        ? Component.translatable("voicespells.command.devices_unknown")
                        : Component.literal(def))
                    .withStyle(ChatFormatting.GRAY)), false);
            ctx.getSource().sendSuccess(() -> Component
                .literal("  ")
                .append(Component.translatable("voicespells.command.devices_configured"))
                .append(Component.literal(": ")).withStyle(ChatFormatting.DARK_GRAY)
                .append((selected == null || selected.isBlank()
                        ? Component.translatable("voicespells.command.devices_system_default")
                        : Component.literal(selected))
                    .withStyle(ChatFormatting.GRAY)), false);

            MicCapture cap = VoiceController.captureEngine();
            String state = cap == null
                ? Component.translatable("voicespells.command.devices_not_started").getString()
                : cap.status();
            ctx.getSource().sendSuccess(() -> Component
                .literal("  ")
                .append(Component.translatable("voicespells.command.devices_state"))
                .append(Component.literal(": ")).withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(state).withStyle(ChatFormatting.AQUA)), false);
            ctx.getSource().sendSuccess(() -> Component
                .literal("  ")
                .append(Component.translatable("voicespells.command.devices_hint"))
                .withStyle(ChatFormatting.DARK_GRAY), false);
            return 1;
        }));

        root.then(Commands.literal("grammar").executes(ctx -> {
            java.util.Set<String> owned = VoiceController.ownedSpellIdsView();
            java.util.List<String> active = VoiceController.activeGrammarView();
            int floor = VoiceSpellsConfig.cGrammarFloor;

            ctx.getSource().sendSuccess(() -> Component
                .translatable("voicespells.command.grammar_title", active.size(), floor)
                .withStyle(ChatFormatting.GOLD), false);
            // Only claim there are decoys when the grammar is actually the narrowed one. With no
            // usable owned-spell scan the recognizer listens for every phrase and all of them can
            // cast, and saying "0 spell(s) equipped, the rest are decoys" about a full grammar is
            // both wrong and alarming.
            boolean narrowed = VoiceController.grammarIsNarrowed();
            ctx.getSource().sendSuccess(() -> Component
                .literal("  ")
                .append(narrowed
                    ? Component.translatable("voicespells.command.grammar_decoys", owned.size())
                    : Component.translatable("voicespells.command.grammar_not_narrowed"))
                .withStyle(ChatFormatting.DARK_GRAY), false);

            // Sort so the phrases you can actually cast are listed first and marked — the whole
            // point of reading this list is telling those two groups apart.
            java.util.List<String> castable = new java.util.ArrayList<>();
            java.util.List<String> decoys    = new java.util.ArrayList<>();
            for (String phrase : active) {
                if (VoiceController.phraseIsCastable(phrase)) castable.add(phrase);
                else decoys.add(phrase);
            }
            for (String p : castable) {
                ctx.getSource().sendSuccess(() -> Component.literal("  * " + p)
                    .withStyle(ChatFormatting.GREEN), false);
            }
            for (String p : decoys) {
                ctx.getSource().sendSuccess(() -> Component.literal("    " + p)
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            }
            return 1;
        }));

        root.then(Commands.literal("vocab").executes(ctx -> {
            com.niko.voicespells.spells.SpellIndex.VocabReport r =
                com.niko.voicespells.spells.SpellIndex.vocabularyReport();
            if (r.vocabularyWords() == 0) {
                ctx.getSource().sendSuccess(() -> Component
                    .translatable("voicespells.command.vocab_none")
                    .withStyle(ChatFormatting.YELLOW), false);
                ctx.getSource().sendSuccess(() -> Component
                    .literal("  ")
                    .append(Component.translatable("voicespells.command.vocab_none_hint"))
                    .withStyle(ChatFormatting.DARK_GRAY), false);
                return 1;
            }
            ctx.getSource().sendSuccess(() -> Component
                .translatable("voicespells.command.vocab_title", r.vocabularyWords())
                .withStyle(ChatFormatting.GOLD), false);

            if (r.dead().isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component
                    .literal("  ")
                    .append(Component.translatable("voicespells.command.vocab_all_sayable"))
                    .withStyle(ChatFormatting.GREEN), false);
            } else {
                ctx.getSource().sendSuccess(() -> Component
                    .literal("  ").append(Component.translatable("voicespells.command.vocab_dead", r.dead().size()))
                    .withStyle(ChatFormatting.RED), false);
                for (String d : r.dead()) {
                    ctx.getSource().sendSuccess(() -> Component.literal("    " + d)
                        .withStyle(ChatFormatting.RED), false);
                }
                ctx.getSource().sendSuccess(() -> Component
                    .literal("  ")
                    .append(Component.translatable("voicespells.command.vocab_dead_hint"))
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            }

            // The rescued list is the interesting half on a healthy install: it is the answer to
            // "why does saying the spell's actual name not work, when the spell casts fine?"
            if (!r.rescued().isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component
                    .literal("  ").append(Component.translatable("voicespells.command.vocab_rescued", r.rescued().size()))
                    .withStyle(ChatFormatting.AQUA), false);
                for (String s : r.rescued()) {
                    ctx.getSource().sendSuccess(() -> Component.literal("    " + s)
                        .withStyle(ChatFormatting.GRAY), false);
                }
            }
            return 1;
        }));

        root.then(Commands.literal("test").executes(ctx -> {
            boolean on = VoiceController.toggleTranscription();
            if (on) {
                ctx.getSource().sendSuccess(() -> Component
                    .translatable("voicespells.command.calibration_on")
                    .withStyle(ChatFormatting.GOLD), false);
                ctx.getSource().sendSuccess(() -> Component
                    .translatable("voicespells.command.calibration_hint")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            } else {
                ctx.getSource().sendSuccess(() -> Component
                    .translatable("voicespells.command.calibration_off")
                    .withStyle(ChatFormatting.GREEN), false);
            }
            return 1;
        }));

        root.then(Commands.literal("reload").executes(ctx -> {
            VoiceSpellsConfig.refreshCache();
            VoiceController.onConfigChanged();
            VoiceController.syncCapture();
            ctx.getSource().sendSuccess(() -> Component
                .translatable("voicespells.command.reloaded")
                .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }));

        event.getDispatcher().register(root);
    }
}
