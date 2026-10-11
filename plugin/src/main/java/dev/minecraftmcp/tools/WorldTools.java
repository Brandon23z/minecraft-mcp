package dev.minecraftmcp.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minecraftmcp.build.Box;
import dev.minecraftmcp.build.BuildQueue;
import dev.minecraftmcp.build.BuildQueue.Change;
import dev.minecraftmcp.control.Controller;
import dev.minecraftmcp.control.Outline;
import dev.minecraftmcp.mcp.Args;
import dev.minecraftmcp.mcp.Schema;
import dev.minecraftmcp.mcp.ToolException;
import dev.minecraftmcp.mcp.ToolRegistry;
import dev.minecraftmcp.schematic.SchematicWriter;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The MCP tools. Handlers run on HTTP threads and hop to the main thread for world access. */
public final class WorldTools {

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SYMBOLS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#@$%&*+=?!<>~^";
    private static final int MAX_READ_VOLUME = 32_768;
    private static final int MAX_SURFACE_AREA = 4_096;
    private static final int MAX_SET_BLOCKS = 10_000;
    private static final int MAX_EXPORT_VOLUME = 2_000_000;

    private final JavaPlugin plugin;
    private final BuildQueue queue;
    private final Controller controller;
    private final ToolRegistry registry;

    public WorldTools(JavaPlugin plugin, BuildQueue queue, Controller controller, ToolRegistry registry) {
        this.plugin = plugin;
        this.queue = queue;
        this.controller = controller;
        this.registry = registry;
    }

    public void registerAll() {
        registry.register("get_world_state",
                "Overview of the server: worlds, online players with their positions, and the AI's status "
                        + "(stopped, build zone, pending approval, queued edits). Call this first.",
                Schema.object(), args -> getWorldState());

        registry.register("get_player_context",
                "Where a player is standing, which way they face, and the block they are looking at. "
                        + "Use it to resolve requests like 'build here' or 'in front of me'.",
                Schema.object().string("player", "Player name. Optional when only one player is online.", false),
                this::getPlayerContext);

        registry.register("get_messages",
                "Read new chat messages and control events from players (stop, resume, approve, deny) since the last call. "
                        + "Check this between steps of a larger build so you can follow the player's feedback.",
                Schema.object(), args -> getMessages());

        registry.register("read_region",
                "Read the blocks in a box, at most " + MAX_READ_VOLUME + " blocks (e.g. 32x32x32). Returns a palette and "
                        + "one grid per Y layer, rows are Z and columns are X.",
                Schema.object().box().world(), this::readRegion);

        registry.register("get_surface",
                "Height of the top block for each column in an X/Z area (at most " + MAX_SURFACE_AREA
                        + " columns, e.g. 64x64), plus what the top block is. Use it to find flat ground.",
                Schema.object()
                        .integer("x1", "First corner X", true).integer("z1", "First corner Z", true)
                        .integer("x2", "Opposite corner X", true).integer("z2", "Opposite corner Z", true)
                        .world(),
                this::getSurface);

        JsonObject blockItem = Schema.object()
                .integer("x", "X", true).integer("y", "Y", true).integer("z", "Z", true)
                .string("block", "Block ID with optional state, e.g. 'oak_planks' or 'oak_stairs[facing=east]'", true)
                .toJson();
        registry.register("set_blocks",
                "Place individual blocks (up to " + MAX_SET_BLOCKS + " per call). Use for details like doors, stairs, "
                        + "windows and decorations. Use fill for large solid areas.",
                Schema.object().array("blocks", "Blocks to place", true, blockItem).world(),
                this::setBlocks);

        registry.register("fill",
                "Fill a box with one block. mode 'solid' fills everything, 'hollow' makes walls, floor and ceiling "
                        + "with air inside, 'outline' places only the shell and leaves the inside untouched, 'replace' only "
                        + "changes blocks matching 'replace'. Use block 'air' to clear an area.",
                Schema.object().box()
                        .string("block", "Block ID with optional state, e.g. 'stone_bricks'", true)
                        .stringEnum("mode", "Defaults to 'solid'", false, "solid", "hollow", "outline", "replace")
                        .string("replace", "With mode 'replace', the block type to replace, e.g. 'grass_block'", false)
                        .world(),
                this::fill);

        registry.register("preview",
                "Draw a particle outline of a box so the player can confirm a location before you build there.",
                Schema.object().box().integer("seconds", "How long to show it, 1-120. Defaults to 15.", false).world(),
                this::preview);

        registry.register("undo",
                "Undo your most recent edits, restoring the blocks that were there before.",
                Schema.object().integer("steps", "How many edits to undo. Defaults to 1.", false),
                this::undo);

        registry.register("export_schematic",
                "Save a box as a Sponge .schem file that the player can paste into their real world with WorldEdit.",
                Schema.object().box().string("name", "File name without extension, letters, digits, - and _ only", true).world(),
                this::exportSchematic);

        if (plugin.getConfig().getBoolean("tools.allow-run-command", false)) {
            registry.register("run_command",
                    "Run a server command as the console, without the leading slash (e.g. 'time set day', "
                            + "'weather clear', 'summon minecraft:villager 10 64 10'). Returns the command's output.",
                    Schema.object().string("command", "Command without the leading slash", true),
                    this::runCommand);
        }
    }

    // ---- Read-only tools ----

    private String getWorldState() throws Exception {
        return sync(() -> {
            JsonObject state = new JsonObject();
            JsonArray worlds = new JsonArray();
            for (World w : Bukkit.getWorlds()) {
                JsonObject wj = new JsonObject();
                wj.addProperty("name", w.getName());
                wj.addProperty("environment", w.getEnvironment().name().toLowerCase(Locale.ROOT));
                wj.addProperty("minY", w.getMinHeight());
                wj.addProperty("maxY", w.getMaxHeight() - 1);
                wj.addProperty("time", w.getTime());
                worlds.add(wj);
            }
            state.add("worlds", worlds);

            JsonArray players = new JsonArray();
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location l = p.getLocation();
                JsonObject pj = new JsonObject();
                pj.addProperty("name", p.getName());
                pj.addProperty("world", l.getWorld().getName());
                pj.add("position", xyz(l.getBlockX(), l.getBlockY(), l.getBlockZ()));
                pj.addProperty("facing", p.getFacing().name().toLowerCase(Locale.ROOT));
                pj.addProperty("gameMode", p.getGameMode().name().toLowerCase(Locale.ROOT));
                players.add(pj);
            }
            state.add("players", players);

            JsonObject ai = new JsonObject();
            ai.addProperty("stoppedByPlayer", controller.isStopped());
            Box zone = controller.zone();
            ai.addProperty("buildZone", zone == null ? "none (anywhere)" : zone.describe());
            String pending = controller.pendingSummary();
            if (pending != null) ai.addProperty("waitingForApproval", pending);
            ai.addProperty("queuedEdits", queue.queuedEdits());
            ai.addProperty("undoableEdits", queue.undoableEdits());
            state.add("ai", ai);
            return JSON.toJson(state);
        });
    }

    private String getPlayerContext(Args args) throws Exception {
        String name = args.string("player", null);
        return sync(() -> {
            Player p = resolvePlayer(name);
            Location l = p.getLocation();
            JsonObject ctx = new JsonObject();
            ctx.addProperty("player", p.getName());
            ctx.addProperty("world", l.getWorld().getName());
            ctx.add("feet", xyz(l.getBlockX(), l.getBlockY(), l.getBlockZ()));
            ctx.addProperty("facing", p.getFacing().name().toLowerCase(Locale.ROOT));
            ctx.addProperty("yaw", Math.round(l.getYaw()));
            ctx.addProperty("pitch", Math.round(l.getPitch()));
            Block below = l.getBlock().getRelative(BlockFace.DOWN);
            ctx.addProperty("standingOn", below.getBlockData().getAsString());

            Block target = p.getTargetBlockExact(64);
            if (target != null) {
                JsonObject t = xyz(target.getX(), target.getY(), target.getZ());
                t.addProperty("block", target.getBlockData().getAsString());
                BlockFace face = p.getTargetBlockFace(64);
                if (face != null) t.addProperty("face", face.name().toLowerCase(Locale.ROOT));
                ctx.add("lookingAt", t);
            }
            ctx.addProperty("axes", "+X is east, -X west, +Z south, -Z north, +Y up");
            return JSON.toJson(ctx);
        });
    }

    private String getMessages() {
        List<String> lines = controller.drainInbox();
        StringBuilder sb = new StringBuilder();
        if (controller.isStopped()) sb.append("STATUS: stopped by a player. Wait for /ai resume.\n");
        if (lines.isEmpty()) sb.append("No new messages.");
        else lines.forEach(line -> sb.append(line).append('\n'));
        return sb.toString().trim();
    }

    private String readRegion(Args args) throws Exception {
        World world = world(args);
        Box box = box(args, world);
        if (box.volume() > MAX_READ_VOLUME) {
            throw new ToolException("Region is " + box.volume() + " blocks, the limit is " + MAX_READ_VOLUME
                    + ". Read it in smaller pieces, or use get_surface for terrain.");
        }
        return sync(() -> {
            Map<String, Character> palette = new LinkedHashMap<>();
            boolean overflow = false;
            StringBuilder layers = new StringBuilder();
            for (int y = box.minY(); y <= box.maxY(); y++) {
                StringBuilder layer = new StringBuilder();
                boolean anySolid = false;
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    layer.append("z=").append(z).append(' ');
                    for (int x = box.minX(); x <= box.maxX(); x++) {
                        BlockData data = world.getBlockAt(x, y, z).getBlockData();
                        if (data.getMaterial().isAir()) {
                            layer.append('.');
                            continue;
                        }
                        anySolid = true;
                        String key = data.getAsString();
                        Character symbol = palette.get(key);
                        if (symbol == null && palette.size() < SYMBOLS.length()) {
                            symbol = SYMBOLS.charAt(palette.size());
                            palette.put(key, symbol);
                        }
                        if (symbol == null) overflow = true;
                        layer.append(symbol == null ? '?' : symbol);
                    }
                    layer.append('\n');
                }
                layers.append("y=").append(y);
                if (anySolid) layers.append(":\n").append(layer);
                else layers.append(": all air\n");
            }

            StringBuilder out = new StringBuilder();
            out.append("Region ").append(box.describe()).append('\n');
            out.append("Columns run from x=").append(box.minX()).append(" (left) to x=").append(box.maxX())
                    .append(" (right). '.' is air.\n");
            out.append("Palette:\n");
            palette.forEach((state, symbol) -> out.append("  ").append(symbol).append(" = ").append(state).append('\n'));
            if (overflow) out.append("  ? = too many distinct blocks to label, read a smaller region\n");
            out.append(layers);
            return out.toString();
        });
    }

    private String getSurface(Args args) throws Exception {
        World world = world(args);
        int minX = Math.min(args.integer("x1"), args.integer("x2"));
        int maxX = Math.max(args.integer("x1"), args.integer("x2"));
        int minZ = Math.min(args.integer("z1"), args.integer("z2"));
        int maxZ = Math.max(args.integer("z1"), args.integer("z2"));
        long area = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        if (area > MAX_SURFACE_AREA) {
            throw new ToolException("Area is " + area + " columns, the limit is " + MAX_SURFACE_AREA + ".");
        }
        return sync(() -> {
            Map<Material, Character> palette = new LinkedHashMap<>();
            StringBuilder heights = new StringBuilder();
            StringBuilder tops = new StringBuilder();
            int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
            for (int z = minZ; z <= maxZ; z++) {
                heights.append("z=").append(z).append(' ');
                tops.append("z=").append(z).append(' ');
                for (int x = minX; x <= maxX; x++) {
                    Block top = world.getHighestBlockAt(x, z, HeightMap.WORLD_SURFACE);
                    lowest = Math.min(lowest, top.getY());
                    highest = Math.max(highest, top.getY());
                    heights.append(top.getY()).append(x < maxX ? " " : "");
                    Character symbol = palette.get(top.getType());
                    if (symbol == null && palette.size() < SYMBOLS.length()) {
                        symbol = SYMBOLS.charAt(palette.size());
                        palette.put(top.getType(), symbol);
                    }
                    tops.append(symbol == null ? '?' : symbol);
                }
                heights.append('\n');
                tops.append('\n');
            }
            StringBuilder out = new StringBuilder();
            out.append("Surface from (").append(minX).append(", ").append(minZ).append(") to (")
                    .append(maxX).append(", ").append(maxZ).append(") in ").append(world.getName())
                    .append(". Top block Y ranges from ").append(lowest).append(" to ").append(highest).append(".\n");
            out.append("Columns run from x=").append(minX).append(" to x=").append(maxX).append(".\n\n");
            out.append("Top block Y per column:\n").append(heights).append('\n');
            out.append("Top block type per column:\n");
            palette.forEach((m, s) -> out.append("  ").append(s).append(" = ")
                    .append(m.getKey().asString()).append('\n'));
            out.append(tops);
            return out.toString();
        });
    }

    // ---- World-changing tools ----

    private String setBlocks(Args args) throws Exception {
        World world = world(args);
        JsonArray items = args.array("blocks");
        if (items.isEmpty()) throw new ToolException("'blocks' is empty.");
        if (items.size() > MAX_SET_BLOCKS) {
            throw new ToolException("Got " + items.size() + " blocks, the limit is " + MAX_SET_BLOCKS + " per call.");
        }

        Map<String, BlockData> parsed = new HashMap<>();
        List<Change> changes = new ArrayList<>(items.size());
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < items.size(); i++) {
            JsonElement el = items.get(i);
            if (!el.isJsonObject()) throw new ToolException("blocks[" + i + "] must be an object");
            Args item = new Args(el.getAsJsonObject());
            int x = item.integer("x"), y = item.integer("y"), z = item.integer("z");
            String id = item.string("block");
            BlockData data = parsed.get(id);
            if (data == null) {
                data = parseBlock(id);
                parsed.put(id, data);
            }
            changes.add(new Change(x, y, z, data));
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        Box bounds = new Box(world, minX, minY, minZ, maxX, maxY, maxZ);
        checkHeight(bounds);
        String summary = "place " + changes.size() + " blocks around " + bounds.describe();
        guardEdit(bounds, changes.size(), summary);
        return runEdit(world, "set_blocks: " + changes.size() + " blocks", changes.iterator());
    }

    private String fill(Args args) throws Exception {
        World world = world(args);
        Box box = box(args, world);
        checkHeight(box);
        BlockData data = parseBlock(args.string("block"));
        String mode = args.string("mode", "solid").toLowerCase(Locale.ROOT);
        BlockData air = Material.AIR.createBlockData();

        BoxFunction fn;
        long count;
        switch (mode) {
            case "solid" -> {
                fn = (x, y, z) -> data;
                count = box.volume();
            }
            case "hollow" -> {
                fn = (x, y, z) -> box.isOnEdge(x, y, z) ? data : air;
                count = box.volume();
            }
            case "outline" -> {
                fn = (x, y, z) -> box.isOnEdge(x, y, z) ? data : null;
                long inner = (long) Math.max(0, box.sizeX() - 2) * Math.max(0, box.sizeY() - 2) * Math.max(0, box.sizeZ() - 2);
                count = box.volume() - inner;
            }
            case "replace" -> {
                if (!args.has("replace")) throw new ToolException("mode 'replace' needs the 'replace' argument.");
                Material target = parseBlock(args.string("replace")).getMaterial();
                // Runs on the main thread inside BuildQueue.tick(), so reading the world is safe.
                fn = (x, y, z) -> world.getBlockAt(x, y, z).getType() == target ? data : null;
                count = box.volume();
            }
            default -> throw new ToolException("Unknown mode '" + mode + "'. Use solid, hollow, outline or replace.");
        }

        String summary = mode + "-fill " + box.describe() + " with " + data.getMaterial().getKey().getKey();
        guardEdit(box, count, summary);
        return runEdit(world, "fill " + mode + " " + data.getAsString(), iterate(box, fn));
    }

    private String preview(Args args) throws Exception {
        World world = world(args);
        Box box = box(args, world);
        int seconds = Math.clamp(args.integer("seconds", 15), 1, 120);
        sync(() -> Outline.show(plugin, box, Color.AQUA, seconds));
        return "Showing an outline of " + box.describe() + " for " + seconds
                + " seconds. Ask the player to confirm, then call get_messages for their reply.";
    }

    private String undo(Args args) throws Exception {
        controller.ensureRunning();
        int steps = Math.max(1, args.integer("steps", 1));
        if (queue.isBusy()) throw new ToolException("An edit is still in progress. Wait for it to finish first.");
        List<CompletableFuture<BuildQueue.Result>> futures = queue.undo(steps);
        if (futures.isEmpty()) return "Nothing to undo.";
        int restored = 0;
        for (CompletableFuture<BuildQueue.Result> f : futures) {
            BuildQueue.Result r = await(f);
            restored += r.placed();
            if (r.cancelled()) throw new ToolException("A player stopped the undo after restoring " + restored + " blocks.");
        }
        return "Undid " + futures.size() + " edit(s), restoring " + restored + " blocks.";
    }

    private String exportSchematic(Args args) throws Exception {
        World world = world(args);
        Box box = box(args, world);
        String name = args.string("name");
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ToolException("Name may only contain letters, digits, - and _ (max 64 characters).");
        }
        if (box.volume() > MAX_EXPORT_VOLUME) {
            throw new ToolException("Region is " + box.volume() + " blocks, the export limit is " + MAX_EXPORT_VOLUME + ".");
        }
        String[] states = sync(() -> {
            String[] s = new String[(int) box.volume()];
            int i = 0;
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    for (int x = box.minX(); x <= box.maxX(); x++) {
                        s[i++] = world.getBlockAt(x, y, z).getBlockData().getAsString();
                    }
                }
            }
            return s;
        });
        @SuppressWarnings("deprecation")
        int dataVersion = Bukkit.getUnsafe().getDataVersion();
        Path file = plugin.getDataFolder().toPath().resolve("exports").resolve(name + ".schem");
        SchematicWriter.write(file, box, states, dataVersion);
        return "Saved " + box.describe() + " to " + file.toAbsolutePath()
                + ". To use it in a real world: copy it into WorldEdit's schematics folder, then run "
                + "'//schem load " + name + "' and '//paste' (the build's lowest north-west corner goes where you stand).";
    }

    private String runCommand(Args args) throws Exception {
        controller.ensureRunning();
        String command = args.string("command").trim();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty()) throw new ToolException("'command' is empty.");
        String root = command.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        root = root.substring(root.indexOf(':') + 1); // "minecraft:stop" -> "stop"
        // "ai" is blocked so the AI can't approve its own edits or resume after a player stops it.
        if (List.of("ai", "stop", "restart", "reload", "op", "deop", "ban", "ban-ip", "kick", "whitelist", "pardon",
                "plugins", "pl", "version").contains(root)) {
            throw new ToolException("'" + root + "' is reserved for the player.");
        }
        String finalCommand = command;
        return sync(() -> {
            StringBuilder output = new StringBuilder();
            CommandSender sender = Bukkit.createCommandSender(
                    component -> output.append(PlainTextComponentSerializer.plainText().serialize(component)).append('\n'));
            boolean found = Bukkit.dispatchCommand(sender, finalCommand);
            controller.note("AI ran /" + finalCommand);
            if (!found) return "Unknown command: " + finalCommand;
            return output.isEmpty() ? "Command ran with no output." : output.toString().trim();
        });
    }

    // ---- Helpers ----

    @FunctionalInterface
    private interface BoxFunction {
        /** The block for this position, or null to leave it alone. */
        BlockData at(int x, int y, int z);
    }

    /** Lazily walks a box from the bottom layer up, so builds rise from the ground. */
    private static Iterator<Change> iterate(Box box, BoxFunction fn) {
        return new Iterator<>() {
            int x = box.minX(), y = box.minY(), z = box.minZ();

            @Override
            public boolean hasNext() {
                return y <= box.maxY();
            }

            @Override
            public Change next() {
                if (!hasNext()) throw new NoSuchElementException();
                Change change = new Change(x, y, z, fn.at(x, y, z));
                if (++x > box.maxX()) {
                    x = box.minX();
                    if (++z > box.maxZ()) {
                        z = box.minZ();
                        y++;
                    }
                }
                return change;
            }
        };
    }

    private void guardEdit(Box bounds, long count, String summary) throws ToolException {
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

    private String runEdit(World world, String label, Iterator<Change> changes) throws ToolException {
        BuildQueue.Result r = await(queue.submit(world, label, changes));
        if (r.cancelled()) {
            throw new ToolException("A player stopped this edit after " + r.placed()
                    + " blocks were changed. Call get_messages to read their feedback before doing anything else.");
        }
        return "Done: " + label + ". Changed " + r.placed() + " blocks (edit #" + r.editId() + ", reversible with undo).";
    }

    private static BuildQueue.Result await(CompletableFuture<BuildQueue.Result> future) throws ToolException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted while building.");
        } catch (ExecutionException e) {
            throw new ToolException("Build failed: " + e.getCause());
        }
    }

    private static BlockData parseBlock(String id) throws ToolException {
        try {
            BlockData data = Bukkit.createBlockData(id.trim().toLowerCase(Locale.ROOT));
            if (!data.getMaterial().isBlock()) throw new IllegalArgumentException();
            return data;
        } catch (IllegalArgumentException e) {
            throw new ToolException("Unknown block '" + id + "'. Use Minecraft block IDs like 'stone', "
                    + "'oak_planks' or 'oak_stairs[facing=north,half=bottom]'.");
        }
    }

    private static void checkHeight(Box box) throws ToolException {
        World w = box.world();
        if (box.minY() < w.getMinHeight() || box.maxY() >= w.getMaxHeight()) {
            throw new ToolException("Y must be between " + w.getMinHeight() + " and " + (w.getMaxHeight() - 1)
                    + " in " + w.getName() + ".");
        }
    }

    private static World world(Args args) throws ToolException {
        if (!args.has("world")) return Bukkit.getWorlds().getFirst();
        String name = args.string("world");
        World world = Bukkit.getWorld(name);
        if (world == null) {
            throw new ToolException("No world named '" + name + "'. Worlds: "
                    + Bukkit.getWorlds().stream().map(World::getName).toList());
        }
        return world;
    }

    private static Box box(Args args, World world) throws ToolException {
        return Box.of(world, args.integer("x1"), args.integer("y1"), args.integer("z1"),
                args.integer("x2"), args.integer("y2"), args.integer("z2"));
    }

    private static Player resolvePlayer(String name) throws ToolException {
        if (name != null) {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) throw new ToolException("Player '" + name + "' is not online.");
            return p;
        }
        var online = Bukkit.getOnlinePlayers();
        if (online.isEmpty()) throw new ToolException("No players are online.");
        if (online.size() > 1) {
            throw new ToolException("Several players are online, pass 'player': "
                    + online.stream().map(Player::getName).toList());
        }
        return online.iterator().next();
    }

    private static JsonObject xyz(int x, int y, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    /** Runs world access on the main server thread and waits for the result. */
    private <T> T sync(Callable<T> task) throws Exception {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        } catch (TimeoutException e) {
            throw new ToolException("The server took too long to respond. Try a smaller request.");
        }
    }
}
