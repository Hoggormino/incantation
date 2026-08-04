package com.niko.voicespells.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.niko.voicespells.VoiceSpellsConfig;
import com.niko.voicespells.speech.MicCapture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

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

            ctx.getSource().sendSuccess(() -> Component.literal("Capture devices")
                .withStyle(ChatFormatting.GOLD), false);

            if (devices.isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component
                    .literal("  none found — OpenAL reported no capture devices")
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
                .literal("  default: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(def.isEmpty() ? "(unknown)" : def)
                    .withStyle(ChatFormatting.GRAY)), false);
            ctx.getSource().sendSuccess(() -> Component
                .literal("  configured: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(
                        selected == null || selected.isBlank() ? "(system default)" : selected)
                    .withStyle(ChatFormatting.GRAY)), false);

            MicCapture cap = VoiceController.captureEngine();
            String state = cap == null ? "not started" : cap.status();
            ctx.getSource().sendSuccess(() -> Component
                .literal("  state: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(state).withStyle(ChatFormatting.AQUA)), false);
            ctx.getSource().sendSuccess(() -> Component
                .literal("  set captureDevice in voicespells-client.toml to pick one")
                .withStyle(ChatFormatting.DARK_GRAY), false);
            return 1;
        }));

        root.then(Commands.literal("grammar").executes(ctx -> {
            java.util.Set<String> owned = VoiceController.ownedSpellIdsView();
            java.util.List<String> active = VoiceController.activeGrammarView();
            int floor = VoiceSpellsConfig.cGrammarFloor;

            ctx.getSource().sendSuccess(() -> Component
                .literal("Active grammar — " + active.size() + " phrases, floor " + floor)
                .withStyle(ChatFormatting.GOLD), false);
            ctx.getSource().sendSuccess(() -> Component
                .literal("  " + owned.size() + " spell(s) equipped; anything beyond that is a "
                       + "decoy that cannot cast")
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

        root.then(Commands.literal("test").executes(ctx -> {
            boolean on = VoiceController.toggleTranscription();
            if (on) {
                ctx.getSource().sendSuccess(() -> Component
                    .literal("Calibration mode ON — grammar dropped, casting disabled")
                    .withStyle(ChatFormatting.GOLD), false);
                ctx.getSource().sendSuccess(() -> Component
                    .literal("Say a spell name and read what the model actually heard, then bind "
                           + "that wording as an alias. Run again to go back to casting.")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
            } else {
                ctx.getSource().sendSuccess(() -> Component
                    .literal("Calibration mode OFF — grammar restored, casting re-enabled")
                    .withStyle(ChatFormatting.GREEN), false);
            }
            return 1;
        }));

        root.then(Commands.literal("reload").executes(ctx -> {
            VoiceSpellsConfig.refreshCache();
            VoiceController.onConfigChanged();
            VoiceController.syncCapture();
            ctx.getSource().sendSuccess(() -> Component
                .literal("Reloaded config, phrases and grammar")
                .withStyle(ChatFormatting.GREEN), false);
            return 1;
        }));

        event.getDispatcher().register(root);
    }
}
