package dev.minecraftmcp;

import dev.minecraftmcp.control.Controller;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Forwards chat to the AI's inbox and greets players with the controls. */
final class PlayerListener implements Listener {

    private final JavaPlugin plugin;
    private final Controller controller;

    PlayerListener(JavaPlugin plugin, Controller controller) {
        this.plugin = plugin;
        this.controller = controller;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        controller.chat(event.getPlayer().getName(), PlainTextComponentSerializer.plainText().serialize(event.message()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.getConfig().getBoolean("allow-all-players", false)) {
            player.addAttachment(plugin, "minecraftmcp.control", true);
        }
        if (player.hasPermission("minecraftmcp.control")) {
            player.sendMessage(Controller.PREFIX.append(Component.text(
                    "An AI builder is connected to this world. Chat to talk to it, /ai stop to halt it, /ai for help.",
                    NamedTextColor.GRAY)));
        }
    }
}
