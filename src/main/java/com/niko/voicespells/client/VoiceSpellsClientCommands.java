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

        event.getDispatcher().register(root);
    }
}
