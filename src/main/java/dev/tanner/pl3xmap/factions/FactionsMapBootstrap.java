package dev.tanner.pl3xmap.factions;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

public final class FactionsMapBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("pl3xmapfactions", "Factions map commands", new ReloadCommand()));
    }

    private static final class ReloadCommand implements BasicCommand {
        @Override
        public void execute(CommandSourceStack stack, String[] args) {
            if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
                stack.getSender().sendMessage(Component.text("Usage: /pl3xmapfactions reload"));
                return;
            }
            try {
                JavaPlugin.getPlugin(FactionsMapPlugin.class).reload();
                stack.getSender().sendMessage(Component.text("Reloaded faction map."));
            } catch (IllegalArgumentException exception) {
                stack.getSender().sendMessage(Component.text("Faction map is not available."));
            }
        }

        @Override
        public Collection<String> suggest(CommandSourceStack stack, String[] args) {
            if (args.length <= 1) {
                return List.of("reload");
            }
            return List.of();
        }

        @Override
        public @Nullable String permission() {
            return "pl3xmapfactions.reload";
        }
    }
}
