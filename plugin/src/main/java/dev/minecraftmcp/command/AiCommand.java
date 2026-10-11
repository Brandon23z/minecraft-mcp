package dev.minecraftmcp.command;

import dev.minecraftmcp.build.Box;
import dev.minecraftmcp.build.BuildQueue;
import dev.minecraftmcp.control.Controller;
import dev.minecraftmcp.control.Outline;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /ai: the player's controls for the AI builder. */
public final class AiCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("stop", "resume", "approve", "deny", "undo", "zone", "status");

    private final Plugin plugin;
    private final Controller controller;
    private final BuildQueue queue;

    public AiCommand(Plugin plugin, Controller controller, BuildQueue queue) {
        this.plugin = plugin;
        this.controller = controller;
        this.queue = queue;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return help(sender);
        String rest = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        String by = sender.getName();

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "stop" -> controller.stop(by, rest);
            case "resume" -> controller.resume(by);
            case "approve" -> {
                if (!controller.approve(by)) reply(sender, "Nothing is waiting for approval.", NamedTextColor.GRAY);
                else Controller.broadcast(Component.text(by + " approved the edit.", NamedTextColor.GREEN));
            }
            case "deny" -> {
                if (!controller.deny(by, rest)) reply(sender, "Nothing is waiting for approval.", NamedTextColor.GRAY);
                else Controller.broadcast(Component.text(by + " denied the edit.", NamedTextColor.RED));
            }
            case "undo" -> undo(sender, rest);
            case "zone" -> zone(sender, args);
            case "status" -> status(sender);
            default -> help(sender);
        }
        return true;
    }

    private void undo(CommandSender sender, String rest) {
        int steps;
        try {
            steps = rest.isEmpty() ? 1 : Math.max(1, Integer.parseInt(rest));
        } catch (NumberFormatException e) {
            reply(sender, "Usage: /ai undo [steps]", NamedTextColor.RED);
            return;
        }
        if (queue.isBusy()) {
            reply(sender, "The AI is still building. Use /ai stop first.", NamedTextColor.YELLOW);
            return;
        }
        int queued = queue.undo(steps).size();
        controller.note(sender.getName() + " undid " + queued + " edit(s) with /ai undo");
        reply(sender, queued == 0 ? "Nothing to undo." : "Undoing " + queued + " edit(s).", NamedTextColor.GREEN);
    }

    private void zone(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("show")) {
            Box zone = controller.zone();
            if (zone == null) {
                reply(sender, "No build zone set, the AI can build anywhere. /ai zone <radius> to set one.", NamedTextColor.GRAY);
            } else {
                Outline.show(plugin, zone, Color.LIME, 10);
                reply(sender, "Build zone: " + zone.describe(), NamedTextColor.GREEN);
            }
            return;
        }
        if (args[1].equalsIgnoreCase("off")) {
            controller.setZone(null);
            controller.note(sender.getName() + " removed the build zone");
            reply(sender, "Build zone removed. The AI can build anywhere.", NamedTextColor.YELLOW);
            return;
        }
        if (!(sender instanceof Player player)) {
            reply(sender, "Only players can set a zone, since it's centered on where you stand.", NamedTextColor.RED);
            return;
        }
        int radius;
        try {
            radius = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            reply(sender, "Usage: /ai zone <radius>|off|show", NamedTextColor.RED);
            return;
        }
        Location l = player.getLocation();
        var world = l.getWorld();
        Box zone = Box.of(world, l.getBlockX() - radius, world.getMinHeight(), l.getBlockZ() - radius,
                l.getBlockX() + radius, world.getMaxHeight() - 1, l.getBlockZ() + radius);
        controller.setZone(zone);
        controller.note(sender.getName() + " set the build zone to " + zone.describe());
        Outline.show(plugin, zone, Color.LIME, 10);
        reply(sender, "Build zone set: " + zone.describe(), NamedTextColor.GREEN);
    }

    private void status(CommandSender sender) {
        String pending = controller.pendingSummary();
        Box zone = controller.zone();
        reply(sender, (controller.isStopped() ? "Stopped" : "Running")
                + " | queued edits: " + queue.queuedEdits()
                + " | undoable: " + queue.undoableEdits()
                + " | zone: " + (zone == null ? "none" : zone.describe())
                + (pending == null ? "" : " | waiting for approval: " + pending), NamedTextColor.AQUA);
    }

    private boolean help(CommandSender sender) {
        reply(sender, "/ai stop [what went wrong]  - halt the AI and tell it why\n"
                + "/ai resume  - let it continue\n"
                + "/ai approve | deny [reason]  - answer a large-edit request\n"
                + "/ai undo [steps]  - revert the AI's last edits\n"
                + "/ai zone <radius>|off|show  - limit where it may build\n"
                + "/ai status\n"
                + "Anything you type in chat is passed to the AI.", NamedTextColor.AQUA);
        return true;
    }

    private static void reply(CommandSender sender, String message, NamedTextColor color) {
        sender.sendMessage(Controller.PREFIX.append(Component.text(message, color)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("zone")) return List.of("16", "32", "64", "off", "show");
        return List.of();
    }
}
