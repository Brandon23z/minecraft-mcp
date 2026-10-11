package dev.minecraftmcp.build;

import org.bukkit.World;

/** An inclusive block-aligned box in one world. Corners may be given in any order. */
public record Box(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Box of(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public boolean contains(World w, int x, int y, int z) {
        return w.equals(world)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean contains(Box other) {
        return contains(other.world, other.minX, other.minY, other.minZ)
                && contains(other.world, other.maxX, other.maxY, other.maxZ);
    }

    public boolean isOnEdge(int x, int y, int z) {
        return x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
    }

    public String describe() {
        return "(" + minX + ", " + minY + ", " + minZ + ") to (" + maxX + ", " + maxY + ", " + maxZ + ") in "
                + world.getName() + ", " + sizeX() + "x" + sizeY() + "x" + sizeZ();
    }
}
