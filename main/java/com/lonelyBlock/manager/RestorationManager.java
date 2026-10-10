package com.lonelyBlock.manager;

import com.lonelyBlock.LonelyBlock;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.scheduler.BukkitTask;

import java.util.PriorityQueue;

public class RestorationManager {

    private final LonelyBlock plugin;
    private final PriorityQueue<PendingRestore> queue = new PriorityQueue<>();
    private BukkitTask tickTask;
    private long tickCounter;

    private static final class PendingRestore implements Comparable<PendingRestore> {
        final Location location;
        final BlockState state;
        final long dueTick;

        PendingRestore(Location location, BlockState state, long dueTick) {
            this.location = location;
            this.state = state;
            this.dueTick = dueTick;
        }

        @Override
        public int compareTo(PendingRestore other) {
            return Long.compare(dueTick, other.dueTick);
        }
    }

    public RestorationManager(LonelyBlock plugin) {
        this.plugin = plugin;
    }

    public void scheduleRestore(Block block, double seconds) {
        if (block == null || seconds <= 0) {
            return;
        }
        BlockState state;
        try {
            state = block.getState();
        } catch (Throwable e) {
            plugin.getLogger().warning("捕获方块状态失败: " + e.getMessage());
            return;
        }
        if (state == null) {
            return;
        }
        long ticks = Math.max(1L, Math.round(seconds * 20.0));
        enqueue(block.getLocation(), state, ticks);
    }

    public void instantRestore(Block block) {
        if (block == null) {
            return;
        }
        BlockState state;
        try {
            state = block.getState();
        } catch (Throwable e) {
            return;
        }
        if (state == null) {
            return;
        }
        enqueue(block.getLocation(), state, 1L);
    }

    private void enqueue(Location location, BlockState state, long delayTicks) {
        queue.add(new PendingRestore(location, state, tickCounter + delayTicks));
        ensureTask();
    }

    private void ensureTask() {
        if (tickTask != null) {
            return;
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 1L, 1L);
    }

    private void tick() {
        tickCounter++;
        if (queue.isEmpty()) {
            cancelTask();
            return;
        }
        while (!queue.isEmpty() && queue.peek().dueTick <= tickCounter) {
            PendingRestore restore = queue.poll();
            performRestore(restore);
        }
        if (queue.isEmpty()) {
            cancelTask();
        }
    }

    private void performRestore(PendingRestore restore) {
        try {
            Block currentBlock = restore.location.getBlock();
            if (currentBlock == null) {
                return;
            }
            if (currentBlock.getType() == Material.AIR) {
                restore.state.update(true, false);
            }
        } catch (Throwable e) {
            if (LonelyBlock.isDebug()) {
                plugin.getLogger().warning("恢复方块时出错 (" + restore.location + "): " + e.getMessage());
            }
        }
    }

    public void shutdown() {
        cancelTask();
        queue.clear();
    }

    private void cancelTask() {
        if (tickTask != null) {
            try {
                tickTask.cancel();
            } catch (Throwable ignored) {
            }
            tickTask = null;
        }
    }
}
