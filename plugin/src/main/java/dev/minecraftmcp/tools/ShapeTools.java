package dev.minecraftmcp.tools;

import dev.minecraftmcp.build.Box;
import dev.minecraftmcp.build.BuildQueue.Change;
import dev.minecraftmcp.mcp.Args;
import dev.minecraftmcp.mcp.Schema;
import dev.minecraftmcp.mcp.ToolException;
import dev.minecraftmcp.mcp.ToolRegistry;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Higher-level building tools. The AI describes a shape and the server works out the blocks,
 * which is far more reliable than the AI computing thousands of coordinates itself.
 */
public final class ShapeTools {

    private record Pos(int x, int y, int z) {
        Pos relative(BlockFace face) {
            return new Pos(x + face.getModX(), y + face.getModY(), z + face.getModZ());
        }
    }

    private final ToolSupport support;
    private final ToolRegistry registry;

    public ShapeTools(ToolSupport support, ToolRegistry registry) {
        this.support = support;
        this.registry = registry;
    }

    public void registerAll() {
        registry.register("line",
                "Place a straight line of blocks between two points, in any direction including diagonals. "
                        + "Good for beams, fences, supports and outlines.",
                Schema.object().box().string("block", "Block ID, e.g. 'oak_log[axis=x]'", true).world(),
                this::line);

        registry.register("cylinder",
                "Place a cylinder: towers, pillars, wells, tanks. With axis 'x' or 'z' it lies on its side for tunnels "
                        + "and arches (a half-buried hollow cylinder makes an arch).",
                Schema.object()
                        .integer("x", "Center X of the starting end", true)
                        .integer("y", "Y of the starting end (the base for axis 'y')", true)
                        .integer("z", "Center Z of the starting end", true)
                        .integer("radius", "Radius in blocks, 1-64 (diameter is 2*radius+1)", true)
                        .integer("length", "Length along the axis (height for axis 'y'), 1-256", true)
                        .stringEnum("axis", "Direction the cylinder runs. Defaults to 'y' (upright)", false, "y", "x", "z")
                        .bool("hollow", "Only the curved wall, 1 block thick. Defaults to false", false)
                        .string("block", "Block ID", true)
                        .world(),
                this::cylinder);

        registry.register("sphere",
                "Place a sphere or dome around a center point. part 'top' makes a dome, 'bottom' a bowl.",
                Schema.object()
                        .integer("x", "Center X", true).integer("y", "Center Y", true).integer("z", "Center Z", true)
                        .integer("radius", "Radius in blocks, 1-48", true)
                        .stringEnum("part", "Defaults to 'full'", false, "full", "top", "bottom")
                        .bool("hollow", "Only the 1-block-thick shell. Defaults to false", false)
                        .string("block", "Block ID", true)
                        .world(),
                this::sphere);

        registry.register("roof",
                "Build a sloped roof of stairs over a rectangular footprint, with stairs facing the right way and "
                        + "proper corners. 'gable' has two slopes and triangular ends, 'hip' slopes on all four sides. "
                        + "The footprint is usually the outer walls, and y is one above the top of the walls.",
                Schema.object()
                        .integer("x1", "Footprint corner X", true).integer("z1", "Footprint corner Z", true)
                        .integer("x2", "Opposite footprint corner X", true).integer("z2", "Opposite footprint corner Z", true)
                        .integer("y", "Y of the lowest roof layer", true)
                        .stringEnum("style", "Defaults to 'gable'", false, "gable", "hip")
                        .stringEnum("ridge", "Gable only: direction the ridge runs. Defaults to the longer side", false, "x", "z")
                        .string("stairs", "Stairs block for the slopes, e.g. 'spruce_stairs' or 'deepslate_tile_stairs'", true)
                        .integer("overhang", "How far the roof extends past the footprint, 0-3. Defaults to 1", false)
                        .string("gable_block", "Gable only: block to fill the triangular end walls, e.g. 'spruce_planks'", false)
                        .string("ridge_block", "Block for the very top row. Defaults to the matching slab", false)
                        .world(),
                this::roof);

        registry.register("copy_region",
                "Copy a box to another place, optionally rotated or mirrored, with stairs, logs, doors and other "
                        + "directional blocks turned to match. Use it to repeat a finished part (a window, a wall section, "
                        + "a whole house) instead of rebuilding it.",
                Schema.object().box()
                        .integer("to_x", "Destination X of the copy's lowest north-west corner", true)
                        .integer("to_y", "Destination Y of the copy's bottom layer", true)
                        .integer("to_z", "Destination Z of the copy's lowest north-west corner", true)
                        .stringEnum("rotate", "Clockwise rotation seen from above. Defaults to '0'", false, "0", "90", "180", "270")
                        .stringEnum("mirror", "Flip the copy: 'x' flips east-west, 'z' flips north-south. Applied before rotation. Defaults to 'none'",
                                false, "none", "x", "z")
                        .bool("include_air", "Also copy air, clearing whatever is at the destination. Defaults to false", false)
                        .world(),
                this::copyRegion);
    }

    // ---- line ----

    private String line(Args args) throws Exception {
        World world = ToolSupport.world(args);
        BlockData data = ToolSupport.parseBlock(args.string("block"));
        int x1 = args.integer("x1"), y1 = args.integer("y1"), z1 = args.integer("z1");
        int x2 = args.integer("x2"), y2 = args.integer("y2"), z2 = args.integer("z2");
        int steps = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
        if (steps > 512) throw new ToolException("Lines can be at most 512 blocks long.");

        Map<Pos, BlockData> blocks = new LinkedHashMap<>();
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            blocks.put(new Pos((int) Math.round(x1 + (x2 - x1) * t), (int) Math.round(y1 + (y2 - y1) * t),
                    (int) Math.round(z1 + (z2 - z1) * t)), data);
        }
        return support.applyChanges(world, "line of " + name(data), "draw a line of " + name(data), toChanges(blocks));
    }

    // ---- cylinder ----

    private String cylinder(Args args) throws Exception {
        World world = ToolSupport.world(args);
        BlockData data = ToolSupport.parseBlock(args.string("block"));
        int cx = args.integer("x"), cy = args.integer("y"), cz = args.integer("z");
        int radius = args.integer("radius");
        int length = args.integer("length");
        String axis = args.string("axis", "y").toLowerCase(Locale.ROOT);
        boolean hollow = args.bool("hollow", false);
        if (radius < 1 || radius > 64) throw new ToolException("radius must be 1-64.");
        if (length < 1 || length > 256) throw new ToolException("length must be 1-256.");
        if (!List.of("x", "y", "z").contains(axis)) throw new ToolException("axis must be x, y or z.");

        Map<Pos, BlockData> blocks = new LinkedHashMap<>();
        for (int a = -radius; a <= radius; a++) {
            for (int b = -radius; b <= radius; b++) {
                if (!inCircle(a, b, radius)) continue;
                if (hollow && inCircle(a + 1, b, radius) && inCircle(a - 1, b, radius)
                        && inCircle(a, b + 1, radius) && inCircle(a, b - 1, radius)) continue;
                for (int l = 0; l < length; l++) {
                    Pos p = switch (axis) {
                        case "x" -> new Pos(cx + l, cy + a, cz + b);
                        case "z" -> new Pos(cx + a, cy + b, cz + l);
                        default -> new Pos(cx + a, cy + l, cz + b);
                    };
                    blocks.put(p, data);
                }
            }
        }
        String what = (hollow ? "hollow " : "") + "cylinder of " + name(data) + " (radius " + radius + ", length " + length + ")";
        return support.applyChanges(world, what, "build a " + what, toChanges(blocks));
    }

    private static boolean inCircle(int a, int b, int radius) {
        return a * a + b * b < (radius + 0.5) * (radius + 0.5);
    }

    // ---- sphere ----

    private String sphere(Args args) throws Exception {
        World world = ToolSupport.world(args);
        BlockData data = ToolSupport.parseBlock(args.string("block"));
        int cx = args.integer("x"), cy = args.integer("y"), cz = args.integer("z");
        int radius = args.integer("radius");
        String part = args.string("part", "full").toLowerCase(Locale.ROOT);
        boolean hollow = args.bool("hollow", false);
        if (radius < 1 || radius > 48) throw new ToolException("radius must be 1-48.");
        if (!List.of("full", "top", "bottom").contains(part)) throw new ToolException("part must be full, top or bottom.");

        Map<Pos, BlockData> blocks = new LinkedHashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (part.equals("top") && dy < 0 || part.equals("bottom") && dy > 0) continue;
                for (int dz = -radius; dz <= radius; dz++) {
                    if (!inSphere(dx, dy, dz, radius)) continue;
                    if (hollow && inSphere(dx + 1, dy, dz, radius) && inSphere(dx - 1, dy, dz, radius)
                            && inSphere(dx, dy + 1, dz, radius) && inSphere(dx, dy - 1, dz, radius)
                            && inSphere(dx, dy, dz + 1, radius) && inSphere(dx, dy, dz - 1, radius)) continue;
                    blocks.put(new Pos(cx + dx, cy + dy, cz + dz), data);
                }
            }
        }
        String shape = switch (part) {
            case "top" -> "dome";
            case "bottom" -> "bowl";
            default -> "sphere";
        };
        String what = (hollow ? "hollow " : "") + shape + " of " + name(data) + " (radius " + radius + ")";
        return support.applyChanges(world, what, "build a " + what, toChanges(blocks));
    }

    private static boolean inSphere(int dx, int dy, int dz, int radius) {
        return dx * dx + dy * dy + dz * dz < (radius + 0.5) * (radius + 0.5);
    }

    // ---- roof ----

    private String roof(Args args) throws Exception {
        World world = ToolSupport.world(args);
        int minX = Math.min(args.integer("x1"), args.integer("x2")), maxX = Math.max(args.integer("x1"), args.integer("x2"));
        int minZ = Math.min(args.integer("z1"), args.integer("z2")), maxZ = Math.max(args.integer("z1"), args.integer("z2"));
        int baseY = args.integer("y");
        int overhang = args.integer("overhang", 1);
        if (overhang < 0 || overhang > 3) throw new ToolException("overhang must be 0-3.");
        if (maxX - minX > 128 || maxZ - minZ > 128) throw new ToolException("Roof footprints can be at most 129x129.");
        String style = args.string("style", "gable").toLowerCase(Locale.ROOT);

        BlockData stairsData = ToolSupport.parseBlock(args.string("stairs"));
        if (!(stairsData instanceof Stairs stairs)) {
            throw new ToolException("'" + args.string("stairs") + "' isn't a stairs block. Use one like 'oak_stairs'.");
        }
        BlockData ridgeBlock = args.has("ridge_block") ? ToolSupport.parseBlock(args.string("ridge_block")) : slabFor(stairs);
        BlockData gableBlock = args.has("gable_block") ? ToolSupport.parseBlock(args.string("gable_block")) : null;

        Map<Pos, BlockData> blocks = new LinkedHashMap<>();
        switch (style) {
            case "gable" -> {
                String ridge = args.string("ridge", (maxX - minX) >= (maxZ - minZ) ? "x" : "z").toLowerCase(Locale.ROOT);
                if (!ridge.equals("x") && !ridge.equals("z")) throw new ToolException("ridge must be x or z.");
                gableRoof(blocks, minX, minZ, maxX, maxZ, baseY, overhang, ridge.equals("x"), stairs, ridgeBlock, gableBlock);
            }
            case "hip" -> hipRoof(blocks, minX, minZ, maxX, maxZ, baseY, overhang, stairs, ridgeBlock);
            default -> throw new ToolException("style must be gable or hip.");
        }
        shapeStairs(blocks);
        String what = style + " roof of " + name(stairs);
        return support.applyChanges(world, what, "build a " + what, toChanges(blocks));
    }

    /**
     * Two slopes meeting at a ridge. Works in "across/along" coordinates: the slopes rise across
     * the ridge, and the ridge runs along X (ridgeAlongX) or Z.
     */
    private static void gableRoof(Map<Pos, BlockData> blocks, int minX, int minZ, int maxX, int maxZ, int baseY,
                                  int overhang, boolean ridgeAlongX, Stairs stairs, BlockData ridgeBlock, BlockData gableBlock) {
        int acrossMin = ridgeAlongX ? minZ : minX, acrossMax = ridgeAlongX ? maxZ : maxX;
        int alongMin = (ridgeAlongX ? minX : minZ) - overhang, alongMax = (ridgeAlongX ? maxX : maxZ) + overhang;
        int[] gableEnds = ridgeAlongX ? new int[]{minX, maxX} : new int[]{minZ, maxZ};
        // Stairs ascend toward the side they face, so the low edge faces inward, toward the ridge.
        BlockFace lowSideFacing = ridgeAlongX ? BlockFace.SOUTH : BlockFace.EAST;
        BlockFace highSideFacing = lowSideFacing.getOppositeFace();

        for (int i = 0; ; i++) {
            int lo = acrossMin - overhang + i, hi = acrossMax + overhang - i, y = baseY + i;
            if (lo > hi) break;
            for (int along = alongMin; along <= alongMax; along++) {
                if (lo == hi) {
                    blocks.put(gablePos(ridgeAlongX, along, y, lo), ridgeBlock);
                } else {
                    blocks.put(gablePos(ridgeAlongX, along, y, lo), stair(stairs, lowSideFacing));
                    blocks.put(gablePos(ridgeAlongX, along, y, hi), stair(stairs, highSideFacing));
                }
            }
            if (gableBlock != null) {
                for (int end : gableEnds) {
                    for (int across = Math.max(lo + 1, acrossMin); across <= Math.min(hi - 1, acrossMax); across++) {
                        blocks.put(gablePos(ridgeAlongX, end, y, across), gableBlock);
                    }
                }
            }
            if (lo == hi) break;
        }
    }

    private static Pos gablePos(boolean ridgeAlongX, int along, int y, int across) {
        return ridgeAlongX ? new Pos(along, y, across) : new Pos(across, y, along);
    }

    /** Four slopes; each layer is a ring of stairs facing inward, shrinking until it becomes a ridge. */
    private static void hipRoof(Map<Pos, BlockData> blocks, int minX, int minZ, int maxX, int maxZ, int baseY,
                                int overhang, Stairs stairs, BlockData ridgeBlock) {
        for (int i = 0; ; i++) {
            int xl = minX - overhang + i, xh = maxX + overhang - i;
            int zl = minZ - overhang + i, zh = maxZ + overhang - i;
            int y = baseY + i;
            if (xl > xh || zl > zh) break;
            if (xl == xh || zl == zh) {
                for (int x = xl; x <= xh; x++) {
                    for (int z = zl; z <= zh; z++) blocks.put(new Pos(x, y, z), ridgeBlock);
                }
                break;
            }
            for (int x = xl; x <= xh; x++) {
                blocks.put(new Pos(x, y, zl), stair(stairs, BlockFace.SOUTH));
                blocks.put(new Pos(x, y, zh), stair(stairs, BlockFace.NORTH));
            }
            for (int z = zl + 1; z < zh; z++) {
                blocks.put(new Pos(xl, y, z), stair(stairs, BlockFace.EAST));
                blocks.put(new Pos(xh, y, z), stair(stairs, BlockFace.WEST));
            }
        }
    }

    private static Stairs stair(Stairs base, BlockFace facing) {
        Stairs s = (Stairs) base.clone();
        s.setFacing(facing);
        s.setHalf(Bisected.Half.BOTTOM);
        s.setShape(Stairs.Shape.STRAIGHT);
        return s;
    }

    private static BlockData slabFor(Stairs stairs) {
        String key = stairs.getMaterial().getKey().getKey();
        Material slab = Material.matchMaterial(key.replace("_stairs", "_slab"));
        if (slab != null && slab.isBlock()) {
            BlockData data = slab.createBlockData();
            if (data instanceof Slab s) s.setType(Slab.Type.BOTTOM);
            return data;
        }
        return stair(stairs, BlockFace.NORTH);
    }

    /**
     * Gives corner stairs their inner/outer shape, using vanilla's rule (StairBlock#getStairsShape)
     * against the other stairs in the same shape. Blocks are placed without physics, so the game
     * won't do this for us.
     */
    private static void shapeStairs(Map<Pos, BlockData> blocks) {
        Map<Pos, Stairs.Shape> shapes = new LinkedHashMap<>();
        for (Map.Entry<Pos, BlockData> e : blocks.entrySet()) {
            if (!(e.getValue() instanceof Stairs self)) continue;
            BlockFace facing = self.getFacing();
            Stairs.Shape shape = Stairs.Shape.STRAIGHT;

            if (blocks.get(e.getKey().relative(facing)) instanceof Stairs behind && behind.getHalf() == self.getHalf()
                    && axisOf(behind.getFacing()) != axisOf(facing)
                    && canTakeShape(blocks, e.getKey(), self, behind.getFacing().getOppositeFace())) {
                shape = behind.getFacing() == counterClockwise(facing) ? Stairs.Shape.OUTER_LEFT : Stairs.Shape.OUTER_RIGHT;
            } else if (blocks.get(e.getKey().relative(facing.getOppositeFace())) instanceof Stairs front
                    && front.getHalf() == self.getHalf()
                    && axisOf(front.getFacing()) != axisOf(facing)
                    && canTakeShape(blocks, e.getKey(), self, front.getFacing())) {
                shape = front.getFacing() == counterClockwise(facing) ? Stairs.Shape.INNER_LEFT : Stairs.Shape.INNER_RIGHT;
            }
            shapes.put(e.getKey(), shape);
        }
        shapes.forEach((pos, shape) -> {
            Stairs s = (Stairs) blocks.get(pos).clone();
            s.setShape(shape);
            blocks.put(pos, s);
        });
    }

    private static boolean canTakeShape(Map<Pos, BlockData> blocks, Pos pos, Stairs self, BlockFace side) {
        return !(blocks.get(pos.relative(side)) instanceof Stairs n)
                || n.getFacing() != self.getFacing() || n.getHalf() != self.getHalf();
    }

    private static char axisOf(BlockFace face) {
        return face == BlockFace.EAST || face == BlockFace.WEST ? 'x' : 'z';
    }

    private static BlockFace counterClockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            default -> BlockFace.NORTH;
        };
    }

    // ---- copy_region ----

    private String copyRegion(Args args) throws Exception {
        World world = ToolSupport.world(args);
        Box src = ToolSupport.box(args, world);
        int toX = args.integer("to_x"), toY = args.integer("to_y"), toZ = args.integer("to_z");
        int rotate = Integer.parseInt(args.string("rotate", "0"));
        String mirror = args.string("mirror", "none").toLowerCase(Locale.ROOT);
        boolean includeAir = args.bool("include_air", false);
        if (rotate % 90 != 0 || rotate < 0 || rotate > 270) throw new ToolException("rotate must be 0, 90, 180 or 270.");
        if (!List.of("none", "x", "z").contains(mirror)) throw new ToolException("mirror must be none, x or z.");
        long max = support.plugin.getConfig().getLong("building.max-blocks-per-call", 200_000);
        if (src.volume() > max) throw new ToolException("Source is " + src.volume() + " blocks, the limit is " + max + ".");

        StructureRotation rotation = switch (rotate) {
            case 90 -> StructureRotation.CLOCKWISE_90;
            case 180 -> StructureRotation.CLOCKWISE_180;
            case 270 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
        // Vanilla naming: FRONT_BACK flips the X axis, LEFT_RIGHT flips Z.
        Mirror flip = switch (mirror) {
            case "x" -> Mirror.FRONT_BACK;
            case "z" -> Mirror.LEFT_RIGHT;
            default -> Mirror.NONE;
        };

        int sx = src.sizeX(), sz = src.sizeZ();
        List<Change> changes = support.sync(() -> {
            List<Change> out = new ArrayList<>();
            for (int y = src.minY(); y <= src.maxY(); y++) {
                for (int z = src.minZ(); z <= src.maxZ(); z++) {
                    for (int x = src.minX(); x <= src.maxX(); x++) {
                        BlockData data = world.getBlockAt(x, y, z).getBlockData();
                        if (!includeAir && data.getMaterial().isAir()) continue;
                        data = data.clone();
                        data.mirror(flip);
                        data.rotate(rotation);

                        int dx = x - src.minX(), dz = z - src.minZ();
                        if (flip == Mirror.FRONT_BACK) dx = sx - 1 - dx;
                        if (flip == Mirror.LEFT_RIGHT) dz = sz - 1 - dz;
                        int rx, rz;
                        switch (rotate) {
                            case 90 -> { rx = sz - 1 - dz; rz = dx; }
                            case 180 -> { rx = sx - 1 - dx; rz = sz - 1 - dz; }
                            case 270 -> { rx = dz; rz = sx - 1 - dx; }
                            default -> { rx = dx; rz = dz; }
                        }
                        out.add(new Change(toX + rx, toY + (y - src.minY()), toZ + rz, data));
                    }
                }
            }
            return out;
        });
        String what = "copy of " + src.describe() + (rotate != 0 ? ", rotated " + rotate : "")
                + (flip != Mirror.NONE ? ", mirrored on " + mirror : "");
        return support.applyChanges(world, what, "place a " + what, changes);
    }

    // ---- helpers ----

    private static List<Change> toChanges(Map<Pos, BlockData> blocks) {
        List<Change> changes = new ArrayList<>(blocks.size());
        blocks.forEach((p, data) -> changes.add(new Change(p.x(), p.y(), p.z(), data)));
        return changes;
    }

    private static String name(BlockData data) {
        return data.getMaterial().getKey().getKey();
    }
}
