package dev.minecraftmcp.render;

import dev.minecraftmcp.build.Box;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.IntUnaryOperator;

/**
 * Draws pictures of a region for the AI to look at: a top-down map, flat side elevations, and
 * an isometric 3D view. Colors come from each block's map color. Every view is labelled with
 * world coordinates so the AI can turn what it sees back into block positions.
 */
public final class Renderer {

    public record Rendered(byte[] png, int width, int height, String description) {
    }

    private static final int MAX_PX = 1024;
    private static final int BG = 0x1B1E23;
    private static final int EMPTY_A = 0x23272E, EMPTY_B = 0x1F2328;
    private static final int TEXT = 0xE8E8E8;
    private static final int[] STEPS = {1, 2, 5, 10, 20, 25, 50, 100, 200, 500};

    private Renderer() {
    }

    // ---- Top-down map ----

    public static Rendered top(Voxels v) {
        int cols = v.sx, rows = v.sz;
        int[] colors = new int[cols * rows];
        int[] heights = new int[cols * rows];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int x = col, z = row;
                Hit hit = Hit.trace(v.sy, i -> v.get(x, v.sy - 1 - i, z),
                        i -> 0.55 + 0.45 * (v.sy == 1 ? 1 : (double) (v.sy - 1 - i) / (v.sy - 1)));
                colors[z * cols + x] = hit.rgb;
                heights[z * cols + x] = hit.depth < 0 ? Integer.MIN_VALUE : v.sy - 1 - hit.depth;
            }
        }
        // Hillshade like in-game maps: higher than the block to the north is lighter, lower is darker.
        int[] shaded = colors.clone();
        for (int z = 1; z < rows; z++) {
            for (int x = 0; x < cols; x++) {
                int h = heights[z * cols + x], north = heights[(z - 1) * cols + x];
                if (h == Integer.MIN_VALUE || north == Integer.MIN_VALUE) continue;
                if (h > north) shaded[z * cols + x] = scale(colors[z * cols + x], 1.15);
                else if (h < north) shaded[z * cols + x] = scale(colors[z * cols + x], 0.8);
            }
        }
        Box b = v.box;
        BufferedImage img = grid(cols, rows, shaded, c -> b.minX() + c, r -> b.minZ() + r,
                "TOP VIEW  NORTH IS UP  X ACROSS  Z DOWN");
        String text = "Top-down view of " + b.describe() + ". North is up: X increases to the right, Z increases downward. "
                + "Each cell is one block column showing the highest block between y=" + b.minY() + " and y=" + b.maxY()
                + "; higher blocks are brighter. Dark cells are empty. Labels are world X (top) and Z (left). "
                + "Tip: set y2 just above a floor to see that floor's plan.";
        return new Rendered(png(img), img.getWidth(), img.getHeight(), text);
    }

    // ---- Side elevation ----

    /** @param from the side the camera is on: north, south, east or west. */
    public static Rendered side(Voxels v, String from) {
        Box b = v.box;
        int cols, depth;
        IntUnaryOperator colCoord;
        String axis, left, right;
        switch (from) {
            case "north" -> { cols = v.sx; depth = v.sz; colCoord = c -> b.maxX() - c; axis = "X"; left = "east"; right = "west"; }
            case "east" -> { cols = v.sz; depth = v.sx; colCoord = c -> b.maxZ() - c; axis = "Z"; left = "south"; right = "north"; }
            case "west" -> { cols = v.sz; depth = v.sx; colCoord = c -> b.minZ() + c; axis = "Z"; left = "north"; right = "south"; }
            default -> { cols = v.sx; depth = v.sz; colCoord = c -> b.minX() + c; axis = "X"; left = "west"; right = "east"; }
        }
        int rows = v.sy;
        int[] colors = new int[cols * rows];
        int[] depths = new int[cols * rows];
        for (int r = 0; r < rows; r++) {
            int y = rows - 1 - r;
            for (int c = 0; c < cols; c++) {
                int col = c;
                Hit hit = Hit.trace(depth, d -> sample(v, from, col, y, d),
                        d -> 1.0 - 0.45 * (depth == 1 ? 0 : (double) d / (depth - 1)));
                colors[r * cols + c] = hit.rgb;
                depths[r * cols + c] = hit.depth;
            }
        }
        // Shadow edges: darken a cell that sits further back than its neighbor to the left or above,
        // so recessed windows and doorways stand out.
        int[] shaded = colors.clone();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int d = depths[r * cols + c];
                if (d < 0) continue;
                boolean recessed = (c > 0 && depths[r * cols + c - 1] >= 0 && depths[r * cols + c - 1] < d)
                        || (r > 0 && depths[(r - 1) * cols + c] >= 0 && depths[(r - 1) * cols + c] < d);
                if (recessed) shaded[r * cols + c] = scale(colors[r * cols + c], 0.75);
            }
        }
        BufferedImage img = grid(cols, rows, shaded, colCoord, r -> b.minY() + (rows - 1 - r),
                "VIEW FROM " + from.toUpperCase() + "  " + axis + " ACROSS  Y UP");
        String text = "Side view of " + b.describe() + " seen from the " + from + " (camera on the " + from
                + " side, looking " + opposite(from) + "). Horizontal axis is world " + axis + " (" + left + " on the left, "
                + right + " on the right), vertical is Y. Nearer blocks are brighter, darker edges mark recesses, "
                + "and see-through blocks like glass are tinted over what's behind them. Dark cells are empty.";
        return new Rendered(png(img), img.getWidth(), img.getHeight(), text);
    }

    private static int sample(Voxels v, String from, int col, int y, int d) {
        return switch (from) {
            case "north" -> v.get(v.sx - 1 - col, y, d);
            case "east" -> v.get(v.sx - 1 - d, y, v.sz - 1 - col);
            case "west" -> v.get(d, y, col);
            default -> v.get(col, y, v.sz - 1 - d);
        };
    }

    // ---- Isometric ----

    /** @param from the corner the camera is at: ne, nw, se or sw. */
    public static Rendered isometric(Voxels v, String from) {
        // Work in local (u, v) axes rotated so the camera always sits toward +u, +v.
        int U, V;
        IntBinaryToInt toX, toZ;
        String visible, xDir, zDir;
        switch (from) {
            case "sw" -> { U = v.sz; V = v.sx; toX = (u, w) -> v.sx - 1 - w; toZ = (u, w) -> u;
                visible = "south and west"; xDir = "upper right"; zDir = "lower right"; }
            case "nw" -> { U = v.sx; V = v.sz; toX = (u, w) -> v.sx - 1 - u; toZ = (u, w) -> v.sz - 1 - w;
                visible = "north and west"; xDir = "upper left"; zDir = "upper right"; }
            case "ne" -> { U = v.sz; V = v.sx; toX = (u, w) -> w; toZ = (u, w) -> v.sz - 1 - u;
                visible = "north and east"; xDir = "lower left"; zDir = "upper left"; }
            default -> { U = v.sx; V = v.sz; toX = (u, w) -> u; toZ = (u, w) -> w;
                visible = "south and east"; xDir = "lower right"; zDir = "lower left"; }
        }
        int Y = v.sy;
        double s = Math.max(1, Math.min(24, Math.min((MAX_PX - 48.0) / (U + V), (MAX_PX - 72.0) / ((U + V) / 2.0 + Y))));
        int margin = 24;
        int width = (int) Math.ceil((U + V) * s) + 2 * margin;
        int height = (int) Math.ceil(((U + V) / 2.0 + Y) * s) + 2 * margin + PixelFont.CHAR_H + 8;
        double ox = margin + V * s, oy = margin + PixelFont.CHAR_H + 8 + Y * s;
        Projection p = (pu, pv, py) -> new double[]{ox + (pu - pv) * s, oy + (pu + pv) * s / 2 - py * s};

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(BG));
        g.fillRect(0, 0, width, height);

        // Footprint of the area, so empty space and the build's position are clear.
        g.setColor(new Color(0x3A404A));
        g.draw(poly(p, new double[][]{{0, 0, 0}, {U, 0, 0}, {U, V, 0}, {0, V, 0}}));

        boolean outlines = s >= 5;
        // Painter's order: voxels with a smaller u+v+y are further from the camera.
        for (int d = 0; d <= U + V + Y - 3; d++) {
            for (int y = 0; y < Y; y++) {
                for (int u = 0; u < U; u++) {
                    int w = d - u - y;
                    if (w < 0 || w >= V) continue;
                    int c = v.get(toX.apply(u, w), y, toZ.apply(u, w));
                    if (c == 0) continue;
                    int topN = v.get(toX.apply(u, w), y + 1, toZ.apply(u, w));
                    int uN = u + 1 < U ? v.get(toX.apply(u + 1, w), y, toZ.apply(u + 1, w)) : 0;
                    int vN = w + 1 < V ? v.get(toX.apply(u, w + 1), y, toZ.apply(u, w + 1)) : 0;
                    if (exposed(c, topN)) face(g, p, c, 1.0, outlines,
                            new double[][]{{u, w, y + 1}, {u + 1, w, y + 1}, {u + 1, w + 1, y + 1}, {u, w + 1, y + 1}});
                    if (exposed(c, uN)) face(g, p, c, 0.62, outlines,
                            new double[][]{{u + 1, w, y + 1}, {u + 1, w + 1, y + 1}, {u + 1, w + 1, y}, {u + 1, w, y}});
                    if (exposed(c, vN)) face(g, p, c, 0.8, outlines,
                            new double[][]{{u, w + 1, y + 1}, {u + 1, w + 1, y + 1}, {u + 1, w + 1, y}, {u, w + 1, y}});
                }
            }
        }
        g.dispose();

        // Label the three front corners of the footprint with their world X,Z.
        Box b = v.box;
        String leftLabel = coord(b, toX.apply(0, V - 1), toZ.apply(0, V - 1));
        String rightLabel = coord(b, toX.apply(U - 1, 0), toZ.apply(U - 1, 0));
        String frontLabel = coord(b, toX.apply(U - 1, V - 1), toZ.apply(U - 1, V - 1));
        double[] l = p.at(0, V, 0), r = p.at(U, 0, 0), f = p.at(U, V, 0);
        PixelFont.draw(img, leftLabel, clampX(img, (int) l[0] - PixelFont.width(leftLabel) - 4, leftLabel), (int) l[1] - 4, TEXT);
        PixelFont.draw(img, rightLabel, clampX(img, (int) r[0] + 4, rightLabel), (int) r[1] - 4, TEXT);
        PixelFont.draw(img, frontLabel, clampX(img, (int) f[0] - PixelFont.width(frontLabel) / 2, frontLabel),
                Math.min(height - PixelFont.CHAR_H - 2, (int) f[1] + 6), TEXT);
        PixelFont.draw(img, "VIEW FROM " + from.toUpperCase() + "  CORNERS ARE X,Z", 6, 6, TEXT);

        String text = "Isometric 3D view of " + b.describe() + " from the " + from.toUpperCase()
                + " (camera above that corner). You see the tops of blocks and the " + visible + " faces. "
                + "World +X runs toward the " + xDir + " and +Z toward the " + zDir + ". "
                + "The three front corners of the area's footprint are labelled with world X,Z. "
                + "Use other corners (ne, nw, se, sw) to see the other sides.";
        return new Rendered(png(img), img.getWidth(), img.getHeight(), text);
    }

    private static boolean exposed(int self, int neighbor) {
        if (neighbor == 0) return true;
        return (neighbor >>> 24) < 255 && neighbor != self;
    }

    private static void face(Graphics2D g, Projection p, int argb, double shade, boolean outline, double[][] corners) {
        int alpha = argb >>> 24;
        int rgb = scale(argb & 0xFFFFFF, shade);
        Path2D path = poly(p, corners);
        g.setColor(new Color((alpha << 24) | rgb, true));
        g.fill(path);
        if (outline) {
            g.setColor(new Color((Math.min(255, alpha + 40) << 24) | scale(rgb, 0.72), true));
            g.draw(path);
        }
    }

    private static Path2D poly(Projection p, double[][] pts) {
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < pts.length; i++) {
            double[] q = p.at(pts[i][0], pts[i][1], pts[i][2]);
            if (i == 0) path.moveTo(q[0], q[1]);
            else path.lineTo(q[0], q[1]);
        }
        path.closePath();
        return path;
    }

    private static String coord(Box b, int localX, int localZ) {
        return (b.minX() + localX) + "," + (b.minZ() + localZ);
    }

    private static int clampX(BufferedImage img, int x, String text) {
        return Math.max(2, Math.min(img.getWidth() - PixelFont.width(text) - 2, x));
    }

    @FunctionalInterface
    private interface IntBinaryToInt {
        int apply(int a, int b);
    }

    @FunctionalInterface
    private interface Projection {
        double[] at(double u, double v, double y);
    }

    // ---- Shared: ray compositing ----

    /** The color seen along one line of sight, and how deep the first opaque block was (-1 if none). */
    private record Hit(int rgb, int depth) {
        static Hit trace(int length, IntUnaryOperator voxelAt, java.util.function.IntToDoubleFunction shadeAt) {
            double r = 0, g = 0, b = 0, a = 0;
            int firstSolid = -1;
            for (int i = 0; i < length && a < 0.99; i++) {
                int c = voxelAt.applyAsInt(i);
                if (c == 0) continue;
                double alpha = (c >>> 24) / 255.0;
                double shade = shadeAt.applyAsDouble(i);
                double weight = (1 - a) * alpha;
                r += weight * ((c >> 16) & 0xFF) * shade;
                g += weight * ((c >> 8) & 0xFF) * shade;
                b += weight * (c & 0xFF) * shade;
                a += weight;
                if (alpha >= 1 && firstSolid < 0) firstSolid = i;
            }
            if (a == 0) return new Hit(-1, -1);
            double rest = 1 - a;
            r += rest * ((BG >> 16) & 0xFF);
            g += rest * ((BG >> 8) & 0xFF);
            b += rest * (BG & 0xFF);
            return new Hit((clamp(r) << 16) | (clamp(g) << 8) | clamp(b), firstSolid);
        }
    }

    // ---- Shared: labelled grid image ----

    /** Draws one colored cell per entry (rgb, or -1 for empty) with coordinate labels on the top and left. */
    private static BufferedImage grid(int cols, int rows, int[] colors, IntUnaryOperator colCoord,
                                      IntUnaryOperator rowCoord, String caption) {
        int rowLabelWidth = 0;
        for (int r = 0; r < rows; r++) rowLabelWidth = Math.max(rowLabelWidth, PixelFont.width(String.valueOf(rowCoord.applyAsInt(r))));
        int colLabelWidth = Math.max(PixelFont.width(String.valueOf(colCoord.applyAsInt(0))),
                PixelFont.width(String.valueOf(colCoord.applyAsInt(cols - 1))));
        int left = rowLabelWidth + 10, top = PixelFont.CHAR_H + 10, bottom = PixelFont.CHAR_H + 10;
        int s = Math.max(1, Math.min(32, Math.min((MAX_PX - left - 4) / cols, (MAX_PX - top - bottom) / rows)));
        int width = Math.max(left + cols * s + 4, PixelFont.width(caption) + 12);
        int height = top + rows * s + bottom;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(BG));
        g.fillRect(0, 0, width, height);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int rgb = colors[r * cols + c];
                g.setColor(new Color(rgb >= 0 ? rgb : ((r + c) % 2 == 0 ? EMPTY_A : EMPTY_B)));
                g.fillRect(left + c * s, top + r * s, s, s);
            }
        }
        if (s >= 6) {
            g.setColor(new Color(0, 0, 0, 55));
            for (int c = 0; c <= cols; c++) g.drawLine(left + c * s, top, left + c * s, top + rows * s);
            for (int r = 0; r <= rows; r++) g.drawLine(left, top + r * s, left + cols * s, top + r * s);
        }

        int colStep = step(s, colLabelWidth + 6);
        int rowStep = step(s, PixelFont.CHAR_H + 3);
        g.setColor(new Color(TEXT));
        for (int c = 0; c < cols; c++) {
            int coord = colCoord.applyAsInt(c);
            if (Math.floorMod(coord, colStep) != 0 && cols > 1) continue;
            String label = String.valueOf(coord);
            int cx = left + c * s + s / 2;
            g.drawLine(cx, top - 3, cx, top - 1);
            PixelFont.draw(img, label, Math.max(1, cx - PixelFont.width(label) / 2), 3, TEXT);
        }
        for (int r = 0; r < rows; r++) {
            int coord = rowCoord.applyAsInt(r);
            if (Math.floorMod(coord, rowStep) != 0 && rows > 1) continue;
            String label = String.valueOf(coord);
            int cy = top + r * s + s / 2;
            g.drawLine(left - 3, cy, left - 1, cy);
            PixelFont.draw(img, label, left - 5 - PixelFont.width(label), cy - PixelFont.CHAR_H / 2, TEXT);
        }
        g.dispose();
        PixelFont.draw(img, caption, 6, height - PixelFont.CHAR_H - 4, TEXT);
        return img;
    }

    /** The smallest "nice" label spacing that leaves at least minPixels between labels. */
    private static int step(int cellSize, int minPixels) {
        for (int st : STEPS) if (st * cellSize >= minPixels) return st;
        return STEPS[STEPS.length - 1];
    }

    // ---- Small helpers ----

    private static String opposite(String dir) {
        return switch (dir) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east";
        };
    }

    private static int scale(int rgb, double f) {
        return (clamp(((rgb >> 16) & 0xFF) * f) << 16) | (clamp(((rgb >> 8) & 0xFF) * f) << 8) | clamp((rgb & 0xFF) * f);
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
