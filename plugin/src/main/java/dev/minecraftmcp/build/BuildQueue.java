package dev.minecraftmcp.build;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Places blocks a few hundred per tick so players can watch the build and stop it partway.
 * Every edit records what it replaced, which is what makes undo possible.
 *
 * {@link #tick()} runs on the main thread. Other methods may be called from any thread.
 */
public final class BuildQueue {

    /** One block to place. A null {@code data} means "skip this position". */
    public record Change(int x, int y, int z, BlockData data) {
    }

    public record Result(int editId, int placed, boolean cancelled) {
    }

    /** Hard limit on journaled blocks across the whole undo history, to bound memory use. */
    private static final long MAX_JOURNAL_BLOCKS = 4_000_000;

    private static final class Edit {
        final int id;
        final String label;
        final World world;
        final Iterator<Change> changes;
        final boolean recordUndo;
        final Map<Long, BlockData> before = new LinkedHashMap<>();
        final CompletableFuture<Result> done = new CompletableFuture<>();
        int placed;

        Edit(int id, String label, World world, Iterator<Change> changes, boolean recordUndo) {
            this.id = id;
            this.label = label;
            this.world = world;
            this.changes = changes;
            this.recordUndo = recordUndo;
        }
    }

    private record UndoEntry(int id, String label, World world, Map<Long, BlockData> before) {
    }

    private final Deque<Edit> queue = new ArrayDeque<>();
    private final Deque<UndoEntry> history = new ArrayDeque<>();
    private final int undoLimit;
    private int blocksPerTick;
    private int nextId = 1;
    private long journalSize;

    public BuildQueue(int blocksPerTick, int undoLimit) {
        this.blocksPerTick = Math.max(1, blocksPerTick);
        this.undoLimit = Math.max(0, undoLimit);
    }

    public synchronized CompletableFuture<Result> submit(World world, String label, Iterator<Change> changes) {
        Edit edit = new Edit(nextId++, label, world, changes, true);
        queue.add(edit);
        return edit.done;
    }

    /** Queues edits that restore the last {@code steps} edits. Returns how many were queued. */
    public synchronized List<CompletableFuture<Result>> undo(int steps) {
        List<CompletableFuture<Result>> futures = new ArrayList<>();
        for (int i = 0; i < steps && !history.isEmpty(); i++) {
            UndoEntry entry = history.pollLast();
            journalSize -= entry.before().size();
            Iterator<Change> restore = entry.before().entrySet().stream()
                    .map(e -> new Change(unpackX(e.getKey()), unpackY(e.getKey()), unpackZ(e.getKey()), e.getValue()))
                    .iterator();
            Edit edit = new Edit(nextId++, "undo of #" + entry.id() + " (" + entry.label() + ")", entry.world(), restore, false);
            queue.add(edit);
            futures.add(edit.done);
        }
        return futures;
    }

    /** Stops all queued and running edits. What was already placed stays and can be undone. */
    public synchronized int cancelAll() {
        int count = queue.size();
        Edit edit;
        while ((edit = queue.pollFirst()) != null) finish(edit, true);
        return count;
    }

    public synchronized void tick() {
        int budget = blocksPerTick;
        while (budget > 0) {
            Edit edit = queue.peekFirst();
            if (edit == null) return;
            if (!edit.changes.hasNext()) {
                queue.pollFirst();
                finish(edit, false);
                continue;
            }
            Change change = edit.changes.next();
            budget--;
            if (change.data() == null) continue;
            if (change.y() < edit.world.getMinHeight() || change.y() >= edit.world.getMaxHeight()) continue;

            Block block = edit.world.getBlockAt(change.x(), change.y(), change.z());
            BlockData old = block.getBlockData();
            if (!old.equals(change.data())) {
                if (edit.recordUndo) edit.before.putIfAbsent(pack(change.x(), change.y(), change.z()), old);
                // No physics, so sand doesn't fall and water doesn't flow halfway through a build.
                block.setBlockData(change.data(), false);
                edit.placed++;
            }
        }
    }

    public synchronized boolean isBusy() {
        return !queue.isEmpty();
    }

    public synchronized int queuedEdits() {
        return queue.size();
    }

    public synchronized int undoableEdits() {
        return history.size();
    }

    public synchronized void setBlocksPerTick(int blocksPerTick) {
        this.blocksPerTick = Math.max(1, blocksPerTick);
    }

    private void finish(Edit edit, boolean cancelled) {
        if (edit.recordUndo && !edit.before.isEmpty() && undoLimit > 0) {
            history.addLast(new UndoEntry(edit.id, edit.label, edit.world, edit.before));
            journalSize += edit.before.size();
            while (history.size() > undoLimit || (journalSize > MAX_JOURNAL_BLOCKS && history.size() > 1)) {
                journalSize -= history.pollFirst().before().size();
            }
        }
        edit.done.complete(new Result(edit.id, edit.placed, cancelled));
    }

    // Positions packed into a long: 26 bits X, 26 bits Z, 12 bits Y (same layout as vanilla BlockPos).
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int unpackX(long p) {
        return (int) (p >> 38);
    }

    private static int unpackY(long p) {
        return (int) (p << 52 >> 52);
    }

    private static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }
}
