package dev.minecraftmcp.tools;

import dev.minecraftmcp.build.Box;
import dev.minecraftmcp.build.BuildQueue;
import dev.minecraftmcp.build.BuildQueue.Change;
import dev.minecraftmcp.control.Controller;
import dev.minecraftmcp.mcp.Args;
import dev.minecraftmcp.mcp.ToolException;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared plumbing for tools: argument parsing, main-thread access, and the safety gate every
 * world edit passes through (stopped check, build zone, size limit, player approval).
 */
public final class ToolSupport {

    final JavaPlugin plugin;
    final BuildQueue queue;
    final Controller controller;

    public ToolSupport(JavaPlugin plugin, BuildQueue queue, Controller controller) {
        this.plugin = plugin;
        this.queue = queue;
        this.controller = controller;
    }

    /** Runs world access on the main server thread and waits for the result. */
    public <T> T sync(Callable<T> task) throws Exception {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        } catch (TimeoutException e) {
            throw new ToolException("The server took too long to respond. Try a smaller request.");
        }
    }

    /** Checks the edit is allowed, asking a player to approve it if it's large. */
    public void guardEdit(Box bounds, long count, String summary) throws ToolException {
        controller.ensureRunning();
        controller.ensureInZone(bounds);
        long max = plugin.getConfig().getLong("building.max-blocks-per-call", 200_000);
        if (count > max) {
            throw new ToolException("That edit touches " + count + " blocks, the limit is " + max
                    + " per call. Split it into smaller steps.");
        }
        if (count > plugin.getConfig().getLong("building.approval-threshold", 2_000)) {
            controller.requestApproval(summary, bounds,
                    plugin.getConfig().getInt("building.approval-timeout-seconds", 120));
            controller.ensureRunning();
        }
    }

    /** Queues the changes and waits until they're placed or a player stops them. */
    public String runEdit(World world, String label, Iterator<Change> changes) throws ToolException {
        BuildQueue.Result r = await(queue.submit(world, label, changes));
        if (r.cancelled()) {
            throw new ToolException("A player stopped this edit after " + r.placed()
                    + " blocks were changed. Call get_messages to read their feedback before doing anything else.");
        }
        return "Done: " + label + ". Changed " + r.placed() + " blocks (edit #" + r.editId() + ", reversible with undo).";
    }

    /** Validates, gates and places a precomputed list of changes, building from the bottom up. */
    public String applyChanges(World world, String label, String summaryVerb, Collection<Change> changes)
            throws ToolException {
        if (changes.isEmpty()) throw new ToolException("That shape has no blocks. Check the size arguments.");
        Box bounds = boundsOf(world, changes);
        checkHeight(bounds);
        List<Change> ordered = changes.stream()
                .sorted(Comparator.comparingInt(Change::y).thenComparingInt(Change::z).thenComparingInt(Change::x))
                .toList();
        guardEdit(bounds, ordered.size(), summaryVerb + " (" + ordered.size() + " blocks) at " + bounds.describe());
        return runEdit(world, label, ordered.iterator());
    }

    public static Box boundsOf(World world, Collection<Change> changes) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Change c : changes) {
            minX = Math.min(minX, c.x()); minY = Math.min(minY, c.y()); minZ = Math.min(minZ, c.z());
            maxX = Math.max(maxX, c.x()); maxY = Math.max(maxY, c.y()); maxZ = Math.max(maxZ, c.z());
        }
        return new Box(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static BuildQueue.Result await(CompletableFuture<BuildQueue.Result> future) throws ToolException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted while building.");
        } catch (ExecutionException e) {
            throw new ToolException("Build failed: " + e.getCause());
        }
    }

    public static BlockData parseBlock(String id) throws ToolException {
        try {
            BlockData data = Bukkit.createBlockData(id.trim().toLowerCase(Locale.ROOT));
            if (!data.getMaterial().isBlock()) throw new IllegalArgumentException();
            return data;
        } catch (IllegalArgumentException e) {
            throw new ToolException("Unknown block '" + id + "'. Use Minecraft block IDs like 'stone', "
                    + "'oak_planks' or 'oak_stairs[facing=north,half=bottom]'.");
        }
    }

    public static void checkHeight(Box box) throws ToolException {
        World w = box.world();
        if (box.minY() < w.getMinHeight() || box.maxY() >= w.getMaxHeight()) {
            throw new ToolException("Y must be between " + w.getMinHeight() + " and " + (w.getMaxHeight() - 1)
                    + " in " + w.getName() + ".");
        }
    }

    public static World world(Args args) throws ToolException {
        if (!args.has("world")) return Bukkit.getWorlds().getFirst();
        String name = args.string("world");
        World world = Bukkit.getWorld(name);
        if (world == null) {
            throw new ToolException("No world named '" + name + "'. Worlds: "
                    + Bukkit.getWorlds().stream().map(World::getName).toList());
        }
        return world;
    }

    public static Box box(Args args, World world) throws ToolException {
        return Box.of(world, args.integer("x1"), args.integer("y1"), args.integer("z1"),
                args.integer("x2"), args.integer("y2"), args.integer("z2"));
    }
}
