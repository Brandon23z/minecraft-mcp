package dev.minecraftmcp.render;

import dev.minecraftmcp.build.Box;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.Map;

/**
 * The colors of every block in a box, as ARGB. Alpha 0 is air, 255 is opaque, anything between
 * is see-through (glass, water, ice).
 */
public final class Voxels {

    final int sx, sy, sz;
    final Box box;
    private final int[] argb;

    private Voxels(Box box) {
        this.box = box;
        this.sx = box.sizeX();
        this.sy = box.sizeY();
        this.sz = box.sizeZ();
        this.argb = new int[sx * sy * sz];
    }

    /** Main thread only: copies the chunks covering the box so they can be read on another thread. */
    public static Map<Long, ChunkSnapshot> snapshot(Box box) {
        World world = box.world();
        Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                chunks.put(key(cx, cz), world.getChunkAt(cx, cz).getChunkSnapshot());
            }
        }
        return chunks;
    }

    /** Any thread: reads block colors out of chunk snapshots. */
    public static Voxels fromSnapshots(Box box, Map<Long, ChunkSnapshot> chunks) {
        Voxels v = new Voxels(box);
        Map<BlockData, Integer> colorCache = new HashMap<>();
        for (int x = 0; x < v.sx; x++) {
            int wx = box.minX() + x;
            for (int z = 0; z < v.sz; z++) {
                int wz = box.minZ() + z;
                ChunkSnapshot chunk = chunks.get(key(wx >> 4, wz >> 4));
                for (int y = 0; y < v.sy; y++) {
                    BlockData data = chunk.getBlockData(wx & 15, box.minY() + y, wz & 15);
                    v.argb[v.index(x, y, z)] = colorCache.computeIfAbsent(data, Voxels::colorOf);
                }
            }
        }
        return v;
    }

    /** Local coordinates; returns 0 (air) outside the box. */
    int get(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return 0;
        return argb[index(x, y, z)];
    }

    private int index(int x, int y, int z) {
        return (y * sz + z) * sx + x;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    static int colorOf(BlockData data) {
        Material m = data.getMaterial();
        if (m.isAir()) return 0;
        String key = m.getKey().getKey();
        int rgb = data.getMapColor().asRGB();

        if (key.contains("glass")) {
            if (key.startsWith("tinted")) rgb = 0x3A3340;
            else if (key.equals("glass") || key.equals("glass_pane") || rgb == 0) rgb = 0xBFE6F2;
            return (110 << 24) | rgb;
        }
        if (m == Material.WATER || key.equals("ice") || key.equals("frosted_ice")) {
            return (150 << 24) | (rgb == 0 ? 0x4040FF : rgb);
        }
        if (rgb == 0) {
            // Map color "none": things like torches and buttons. Skip non-solid ones.
            if (!m.isSolid()) return 0;
            rgb = 0x7F7F7F;
        }
        return 0xFF000000 | rgb;
    }
}
