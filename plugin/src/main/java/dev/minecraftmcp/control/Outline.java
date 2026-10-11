package dev.minecraftmcp.control;

import dev.minecraftmcp.build.Box;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Draws a box's edges with particles so players can see where the AI is about to build. */
public final class Outline {

    private static final int MAX_POINTS = 2000;

    private Outline() {
    }

    /** Shows the outline until the returned task is cancelled, or for {@code seconds} if positive. */
    public static BukkitTask show(Plugin plugin, Box box, Color color, int seconds) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.5f);
        double perimeter = 4.0 * (box.sizeX() + box.sizeY() + box.sizeZ());
        double step = Math.max(0.5, perimeter / MAX_POINTS);
        long endTick = seconds > 0 ? seconds * 20L : Long.MAX_VALUE;

        return new org.bukkit.scheduler.BukkitRunnable() {
            long elapsed;

            @Override
            public void run() {
                if (elapsed >= endTick) {
                    cancel();
                    return;
                }
                draw(box, dust, step);
                elapsed += 10;
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private static void draw(Box box, Particle.DustOptions dust, double step) {
        World w = box.world();
        double x0 = box.minX(), y0 = box.minY(), z0 = box.minZ();
        double x1 = box.maxX() + 1, y1 = box.maxY() + 1, z1 = box.maxZ() + 1;
        for (double y : new double[]{y0, y1}) {
            for (double z : new double[]{z0, z1}) line(w, dust, step, x0, y, z, x1, y, z);
            for (double x : new double[]{x0, x1}) line(w, dust, step, x, y, z0, x, y, z1);
        }
        for (double x : new double[]{x0, x1}) {
            for (double z : new double[]{z0, z1}) line(w, dust, step, x, y0, z, x, y1, z);
        }
    }

    private static void line(World w, Particle.DustOptions dust, double step,
                             double ax, double ay, double az, double bx, double by, double bz) {
        double length = Math.max(Math.abs(bx - ax), Math.max(Math.abs(by - ay), Math.abs(bz - az)));
        int points = (int) Math.ceil(length / step);
        for (int i = 0; i <= points; i++) {
            double t = points == 0 ? 0 : (double) i / points;
            w.spawnParticle(Particle.DUST, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t,
                    1, 0, 0, 0, 0, dust, true);
        }
    }
}
