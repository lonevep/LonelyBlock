package com.lonelyBlock.manager;

import com.lonelyBlock.LonelyBlock;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 方块恢复管理器 - 在方块被破坏后定时恢复
 *
 * 恢复时间支持最多两位小数 (单位: 秒)
 *   1.0 秒 = 20 ticks
 *   0.05 秒 = 1 tick (最小精度)
 *
 * 恢复逻辑:
 *   1. 玩家破坏方块时, 捕获方块的 BlockState (含 BlockData)
 *   2. 方块变为 AIR
 *   3. 调度延迟任务, 在指定时间后执行
 *   4. 任务执行时, 检查方块是否仍为 AIR
 *      - 是 AIR: 恢复原 BlockState
 *      - 不是 AIR: 表示玩家在恢复期间放置了其他方块, 跳过恢复 (避免覆盖玩家放置的方块)
 */
public class RestorationManager {

    private final LonelyBlock plugin;

    public RestorationManager(LonelyBlock plugin) {
        this.plugin = plugin;
    }

    /**
     * 调度方块恢复任务
     *
     * @param block   要恢复的方块 (在调用此方法时方块尚未被破坏, 会捕获当前状态)
     * @param seconds 恢复时间 (秒, 支持两位小数)
     */
    public void scheduleRestore(Block block, double seconds) {
        if (block == null || seconds <= 0) {
            return;
        }

        // 在方块被破坏前捕获 BlockState
        final BlockState state;
        try {
            state = block.getState();
        } catch (Throwable e) {
            plugin.getLogger().warning("捕获方块状态失败: " + e.getMessage());
            return;
        }

        if (state == null) {
            return;
        }

        final Location location = block.getLocation();
        // 1 秒 = 20 ticks; 最小 1 tick (0.05 秒)
        long ticks = Math.max(1L, Math.round(seconds * 20.0));

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    Block currentBlock = location.getBlock();
                    if (currentBlock == null) {
                        return;
                    }
                    // 只在方块仍然是 AIR 时恢复 (避免覆盖玩家放置的其他方块)
                    if (currentBlock.getType() == Material.AIR) {
                        // force=true 表示即使方块状态发生变化也强制更新
                        // applyPhysics=false 表示不触发物理更新 (避免连锁反应如水流, 沙子下落等)
                        state.update(true, false);
                    }
                } catch (Throwable e) {
                    if (LonelyBlock.isDebug()) {
                        plugin.getLogger().warning("恢复方块时出错 (" + location + "): " + e.getMessage());
                    }
                }
            }
        }.runTaskLater(plugin, ticks);
    }

    /**
     * 即时恢复方块 (在方块被破坏的同 tick 内恢复)
     * 用于"错误工具挖掘后方块立马复原"的场景
     *
     * 注意: 此方法会捕获当前状态, 调用方应在调用前确保方块尚未被设为 AIR
     *
     * @param block 要捕获并恢复的方块
     */
    public void instantRestore(final Block block) {
        if (block == null) {
            return;
        }
        final BlockState state;
        try {
            state = block.getState();
        } catch (Throwable e) {
            return;
        }
        if (state == null) {
            return;
        }
        // 延迟 1 tick 恢复 (让 setAir 先生效, 避免与 setAir 同 tick 冲突)
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    Block current = block;
                    if (current != null && current.getType() == Material.AIR) {
                        state.update(true, false);
                    }
                } catch (Throwable ignored) {
                }
            }
        }.runTaskLater(plugin, 1L);
    }
}
