package dev.minecraftmcp.control;

import dev.minecraftmcp.build.Box;
import dev.minecraftmcp.build.BuildQueue;
import dev.minecraftmcp.mcp.ToolException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The player's side of the conversation: stop/resume, approvals, the build zone, and the
 * inbox of chat messages the AI reads with get_messages.
 */
public final class Controller {

    public static final Component PREFIX = Component.text("[AI] ", NamedTextColor.AQUA);
    private static final int INBOX_LIMIT = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private record Approval(String summary, CompletableFuture<Boolean> decision) {
    }

    private final Plugin plugin;
    private final BuildQueue queue;
    private final Deque<String> inbox = new ArrayDeque<>();

    private volatile boolean stopped;
    private volatile String stopReason = "";
    private volatile Box zone;
    private Approval pending;

    public Controller(Plugin plugin, BuildQueue queue) {
        this.plugin = plugin;
        this.queue = queue;
    }

    // ---- Stop / resume ----

    public void stop(String by, String reason) {
        stopped = true;
        stopReason = reason == null ? "" : reason;
        int cancelled = queue.cancelAll();
        deny(by, "AI was stopped");
        note(by + " stopped the AI" + (stopReason.isEmpty() ? "" : ": " + stopReason));
        broadcast(Component.text(by + " stopped the AI"
                + (cancelled > 0 ? " and cancelled " + cancelled + " queued edit(s)" : "")
                + ". Use /ai resume to let it continue.", NamedTextColor.YELLOW));
    }

    public void resume(String by) {
        stopped = false;
        stopReason = "";
        note(by + " resumed the AI");
        broadcast(Component.text(by + " resumed the AI.", NamedTextColor.GREEN));
    }

    public boolean isStopped() {
        return stopped;
    }

    /** Throws if a player has stopped the AI, so every world-changing tool refuses to run. */
    public void ensureRunning() throws ToolException {
        if (stopped) {
            throw new ToolException("A player stopped you with /ai stop"
                    + (stopReason.isEmpty() ? "" : " and said: \"" + stopReason + "\"")
                    + ". Don't change the world until they run /ai resume. Call get_messages to read their feedback.");
        }
    }

    // ---- Approvals ----

    /**
     * Asks players to approve a large edit and blocks the calling (HTTP) thread until they
     * decide or the timeout passes. Never call this from the main thread.
     */
    public void requestApproval(String summary, Box box, int timeoutSeconds) throws ToolException {
        Approval approval = new Approval(summary, new CompletableFuture<>());
        synchronized (this) {
            if (pending != null) throw new ToolException("Another edit is already waiting for approval.");
            pending = approval;
        }

        Bukkit.getScheduler().runTask(plugin, () -> broadcast(Component.text()
                .append(Component.text("wants to " + summary + ". ", NamedTextColor.WHITE))
                .append(button("[Approve]", NamedTextColor.GREEN, "/ai approve"))
                .append(Component.space())
                .append(button("[Deny]", NamedTextColor.RED, "/ai deny"))
                .build()));
        BukkitTask outline = callSync(() -> Outline.show(plugin, box, Color.YELLOW, 0));

        try {
            boolean approved = approval.decision().get(timeoutSeconds, TimeUnit.SECONDS);
            if (!approved) {
                throw new ToolException("The player denied this edit (" + summary
                        + "). Call get_messages to see if they said why, then adjust the plan.");
            }
        } catch (TimeoutException e) {
            throw new ToolException("No player approved this edit within " + timeoutSeconds
                    + " seconds, so it was cancelled. Try a smaller edit, or ask the player to approve.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted while waiting for approval.");
        } catch (ExecutionException e) {
            throw new ToolException("Approval failed: " + e.getCause());
        } finally {
            synchronized (this) {
                if (pending == approval) pending = null;
            }
            if (outline != null) Bukkit.getScheduler().runTask(plugin, outline::cancel);
        }
    }

    public synchronized boolean approve(String by) {
        if (pending == null) return false;
        note(by + " approved: " + pending.summary());
        pending.decision().complete(true);
        pending = null;
        return true;
    }

    public synchronized boolean deny(String by, String reason) {
        if (pending == null) return false;
        note(by + " denied: " + pending.summary() + (reason == null || reason.isEmpty() ? "" : " (reason: " + reason + ")"));
        pending.decision().complete(false);
        pending = null;
        return true;
    }

    public synchronized String pendingSummary() {
        return pending == null ? null : pending.summary();
    }

    // ---- Build zone ----

    public void setZone(Box zone) {
        this.zone = zone;
    }

    public Box zone() {
        return zone;
    }

    public void ensureInZone(Box box) throws ToolException {
        Box z = zone;
        if (z != null && !z.contains(box)) {
            throw new ToolException("That edit is outside the build zone the player set: " + z.describe()
                    + ". Only build inside it.");
        }
    }

    // ---- Inbox ----

    public void chat(String player, String message) {
        note(player + " said: " + message);
    }

    public synchronized void note(String line) {
        inbox.addLast("[" + LocalTime.now().format(TIME) + "] " + line);
        while (inbox.size() > INBOX_LIMIT) inbox.pollFirst();
    }

    public synchronized List<String> drainInbox() {
        List<String> lines = new ArrayList<>(inbox);
        inbox.clear();
        return lines;
    }

    // ---- Helpers ----

    public static void broadcast(Component message) {
        Bukkit.getServer().sendMessage(PREFIX.append(message));
    }

    private static Component button(String label, NamedTextColor color, String command) {
        return Component.text(label, color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command)));
    }

    private <T> T callSync(java.util.concurrent.Callable<T> task) {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
