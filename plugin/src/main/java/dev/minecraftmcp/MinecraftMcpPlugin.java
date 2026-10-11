package dev.minecraftmcp;

import dev.minecraftmcp.build.BuildQueue;
import dev.minecraftmcp.command.AiCommand;
import dev.minecraftmcp.control.Controller;
import dev.minecraftmcp.mcp.McpHttpServer;
import dev.minecraftmcp.mcp.ToolRegistry;
import dev.minecraftmcp.tools.RenderTools;
import dev.minecraftmcp.tools.ShapeTools;
import dev.minecraftmcp.tools.ToolSupport;
import dev.minecraftmcp.tools.WorldTools;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public final class MinecraftMcpPlugin extends JavaPlugin {

    private static final String INSTRUCTIONS = """
            You are building in a live Minecraft world that a player is watching from inside the game.
            - Start with get_world_state and get_player_context so you know where the player is.
            - Coordinates: +X east, -X west, +Z south, -Z north, +Y up. Read terrain with get_surface or read_region before building.
            - Prefer the shape tools (fill, line, cylinder, sphere, roof, copy_region) over placing blocks one by one.
              Build one detail once, then repeat it with copy_region.
            - Work in steps (foundation, walls, roof, details). After each step, look at it with render_view
              (isometric for the overall look, a side view for walls and windows) and fix what looks wrong.
            - Call get_messages between steps; the player may give feedback in chat.
            - Use preview to show a location before large builds. Large edits wait for the player to approve them in game.
            - If a tool says the player stopped you, stop building and read get_messages. Use undo to revert mistakes.
            - When the player is happy, export_schematic saves the build so they can paste it into their real world.""";

    private BuildQueue queue;
    private McpHttpServer http;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        queue = new BuildQueue(config.getInt("building.blocks-per-tick", 400), config.getInt("building.undo-history", 50));
        Controller controller = new Controller(this, queue);
        getServer().getScheduler().runTaskTimer(this, queue::tick, 1L, 1L);

        ToolRegistry registry = new ToolRegistry(getLogger());
        ToolSupport support = new ToolSupport(this, queue, controller);
        new WorldTools(support, registry).registerAll();
        new ShapeTools(support, registry).registerAll();
        new RenderTools(support, registry).registerAll();

        AiCommand aiCommand = new AiCommand(this, controller, queue);
        PluginCommand command = getCommand("ai");
        if (command != null) {
            command.setExecutor(aiCommand);
            command.setTabCompleter(aiCommand);
        }
        getServer().getPluginManager().registerEvents(new PlayerListener(this, controller), this);

        String bind = config.getString("mcp.bind", "127.0.0.1");
        int port = config.getInt("mcp.port", 25580);
        http = new McpHttpServer(registry, getLogger(), getPluginMeta().getVersion(), INSTRUCTIONS);
        try {
            http.start(bind, port);
            getLogger().info("MCP endpoint ready at http://" + bind + ":" + port + "/mcp");
            getLogger().info("Connect Claude Code with: claude mcp add --transport http minecraft http://"
                    + ("0.0.0.0".equals(bind) ? "127.0.0.1" : bind) + ":" + port + "/mcp");
        } catch (IOException e) {
            getLogger().severe("Could not start the MCP endpoint on " + bind + ":" + port + ": " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (http != null) http.stop();
        if (queue != null) queue.cancelAll();
    }
}
