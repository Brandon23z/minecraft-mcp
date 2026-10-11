package dev.minecraftmcp.tools;

import dev.minecraftmcp.build.Box;
import dev.minecraftmcp.mcp.Args;
import dev.minecraftmcp.mcp.Schema;
import dev.minecraftmcp.mcp.ToolException;
import dev.minecraftmcp.mcp.ToolRegistry;
import dev.minecraftmcp.mcp.ToolResult;
import dev.minecraftmcp.render.Renderer;
import dev.minecraftmcp.render.Voxels;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

import java.util.Locale;
import java.util.Map;

/** render_view: lets the AI look at what it built. */
public final class RenderTools {

    private static final long MAX_VOLUME = 4_194_304; // e.g. 128 x 256 x 128
    private static final int MAX_SIDE = 256;

    private final ToolSupport support;
    private final ToolRegistry registry;

    public RenderTools(ToolSupport support, ToolRegistry registry) {
        this.support = support;
        this.registry = registry;
    }

    public void registerAll() {
        registry.registerImage("render_view",
                "Take a picture of a box so you can see how a build looks. Returns a PNG labelled with world coordinates. "
                        + "'isometric' is a 3D view for judging the overall shape, proportions and style (pick the corner "
                        + "with 'from'). 'top' is a map for layout and floor plans. 'north'/'south'/'east'/'west' are flat "
                        + "views of that side, best for checking walls, windows, doors and symmetry. Include a few blocks "
                        + "of margin around the build. Colors are approximate (in-game map colors).",
                Schema.object().box()
                        .stringEnum("view", "Defaults to 'isometric'", false,
                                "isometric", "top", "north", "south", "east", "west")
                        .stringEnum("from", "Isometric only: the corner the camera is at. Defaults to 'se'", false,
                                "se", "sw", "ne", "nw")
                        .world(),
                this::renderView);
    }

    private ToolResult renderView(Args args) throws Exception {
        World world = ToolSupport.world(args);
        Box box = ToolSupport.box(args, world);
        String view = args.string("view", "isometric").toLowerCase(Locale.ROOT);
        String from = args.string("from", "se").toLowerCase(Locale.ROOT);

        if (box.sizeX() > MAX_SIDE || box.sizeY() > MAX_SIDE * 2 || box.sizeZ() > MAX_SIDE) {
            throw new ToolException("Each side can be at most " + MAX_SIDE + " blocks (" + MAX_SIDE * 2 + " tall).");
        }
        if (box.volume() > MAX_VOLUME) {
            throw new ToolException("That box is " + box.volume() + " blocks, the limit is " + MAX_VOLUME
                    + ". Shrink it to the build plus a small margin.");
        }
        int minY = Math.max(box.minY(), world.getMinHeight()), maxY = Math.min(box.maxY(), world.getMaxHeight() - 1);
        if (minY > maxY) throw new ToolException("That box is outside the world's height range.");
        Box clipped = new Box(world, box.minX(), minY, box.minZ(), box.maxX(), maxY, box.maxZ());

        // Copy chunks on the main thread (fast), then read and draw on this thread so the game doesn't stall.
        Map<Long, ChunkSnapshot> chunks = support.sync(() -> Voxels.snapshot(clipped));
        Voxels voxels = Voxels.fromSnapshots(clipped, chunks);

        Renderer.Rendered image = switch (view) {
            case "top" -> Renderer.top(voxels);
            case "north", "south", "east", "west" -> Renderer.side(voxels, view);
            case "isometric" -> {
                if (!java.util.List.of("se", "sw", "ne", "nw").contains(from)) {
                    throw new ToolException("from must be se, sw, ne or nw.");
                }
                yield Renderer.isometric(voxels, from);
            }
            default -> throw new ToolException("view must be isometric, top, north, south, east or west.");
        };
        return new ToolResult(image.description() + " (" + image.width() + "x" + image.height() + " px)", image.png());
    }
}
